param(
    [Parameter(Mandatory)][string]$SourceContainer,
    [string]$BackupImage = 'tape-backup:local',
    [string]$ApplicationImage = 'tape-beta:local'
)
$ErrorActionPreference = 'Stop'
if ($SourceContainer -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid source container' }
$id = [guid]::NewGuid().ToString('N')
$directory = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('tape-recovery-' + $id)))
$restore = 'tape-recovery-' + $id.Substring(0,12)
New-Item -ItemType Directory -Path $directory -ErrorAction Stop | Out-Null
$identity = Join-Path $directory 'fixture-identity.txt'
$password = [guid]::NewGuid().ToString('N')
$savedPassword = $env:PGPASSWORD
$backupUser = '10001:10001'
if (!$IsWindows) {
    $fixtureUid = & id -u
    $fixtureGid = & id -g
    if ($fixtureUid -notmatch '^\d+$' -or $fixtureGid -notmatch '^\d+$') { throw 'Cannot determine fixture directory owner' }
    $backupUser = $fixtureUid + ':' + $fixtureGid
}
function Checked([string[]]$arguments) {
    $result = & docker @arguments
    if ($LASTEXITCODE) { throw 'Encrypted backup/restore command failed' }
    return $result
}
try {
    # This private identity exists only for this disposable drill. Never use it for real retention.
    docker run --rm --network none --user $backupUser --mount "type=bind,source=$directory,target=/keys" --entrypoint age-keygen $BackupImage -o /keys/fixture-identity.txt 2>$null
    if ($LASTEXITCODE) { throw 'Fixture key generation failed' }
    $recipient = Checked @('run','--rm','--network','none','--mount',"type=bind,source=$identity,target=/identity,readonly",'--entrypoint','age-keygen',$BackupImage,'-y','/identity')
    $user = Checked @('exec',$SourceContainer,'printenv','POSTGRES_USER')
    if ($user -notmatch '^[A-Za-z0-9_]+$') { throw 'Unexpected source database user' }
    $env:PGPASSWORD = Checked @('exec',$SourceContainer,'printenv','POSTGRES_PASSWORD')
    Checked @('run','--rm','--network',('container:'+$SourceContainer),'--read-only','--tmpfs','/tmp','--user',$backupUser,
        '--mount',"type=bind,source=$directory,target=/backups",'-e','PGHOST=127.0.0.1','-e','PGDATABASE=tape','-e',('PGUSER='+$user),'-e','PGPASSWORD',
        '-e',('TAPE_BACKUP_RECIPIENT='+$recipient),'--entrypoint','bash',$BackupImage,'/opt/tape/backup.sh') | Out-Null
    $file = Get-ChildItem -LiteralPath $directory -Filter '*.dump.age' | Select-Object -First 1
    if (!$file) { throw 'Encrypted backup not found' }
    $manifest = Get-Content -Raw -LiteralPath ($file.FullName+'.json') | ConvertFrom-Json
    if ($manifest.file -ne $file.Name -or $manifest.sha256 -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) { throw 'Encrypted checksum mismatch' }
    # Verify ciphertext authentication rather than only its sidecar checksum.
    $tampered = [IO.File]::ReadAllBytes($file.FullName)
    $tampered[$tampered.Length-1] = $tampered[$tampered.Length-1] -bxor 1
    [IO.File]::WriteAllBytes((Join-Path $directory 'tampered.age'),$tampered)
    docker run --rm --network none --read-only --tmpfs /tmp --mount "type=bind,source=$directory,target=/input,readonly" --entrypoint age $BackupImage --decrypt -i /input/fixture-identity.txt -o /tmp/rejected.dump /input/tampered.age *> $null
    if ($LASTEXITCODE -eq 0) { throw 'Modified ciphertext was accepted' }
    Checked @('run','--rm','-d','--name',$restore,'--network','none','--tmpfs','/tmp',
        '--mount',"type=bind,source=$directory,target=/input,readonly",'-e','POSTGRES_DB=tape','-e',('POSTGRES_PASSWORD='+$password),$BackupImage) | Out-Null
    for ($i=0;$i -lt 60;$i++) {
        docker exec $restore pg_isready -q -h 127.0.0.1 -U postgres -d tape
        if (!$LASTEXITCODE) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE) { throw 'Isolated recovery server not ready' }
    Checked @('exec',$restore,'age','--decrypt','-i','/input/fixture-identity.txt','-o','/tmp/restored.dump',('/input/'+$file.Name)) | Out-Null
    $plain = Checked @('exec',$restore,'sha256sum','/tmp/restored.dump')
    if ($plain.Split(' ')[0] -ne $manifest.plainSha256) { throw 'Decrypted archive differs from the original dump' }
    Checked @('exec',$restore,'pg_restore','--exit-on-error','--no-owner','--no-acl','-U','postgres','-d','tape','/tmp/restored.dump') | Out-Null
    Checked @('exec',$restore,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d','tape','-c','CREATE ROLE tape_runtime NOLOGIN') | Out-Null
    $tables = Checked @('exec',$restore,'psql','-X','-U','postgres','-d','tape','-Atc',"SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name")
    foreach ($table in $tables) {
        if ($table -notmatch '^[a-z_][a-z0-9_]*$') { throw 'Unexpected restored table' }
        $sql = "SELECT count(*) FROM public.$table"
        $before = Checked @('exec',$SourceContainer,'psql','-X','-U',$user,'-d','tape','-Atc',$sql)
        $after = Checked @('exec',$restore,'psql','-X','-U','postgres','-d','tape','-Atc',$sql)
        if ($before -ne $after) { throw "Restored row count differs for $table. Use a quiescent source for this comparison." }
    }
    # Maintenance starts no server or workers and reconciles the restored financial journals.
    Checked @('run','--rm','--network',('container:'+$restore),'-e','TAPE_MAINTENANCE_URL=jdbc:postgresql://127.0.0.1:5432/tape',
        '-e','TAPE_MAINTENANCE_USER=postgres','-e',('TAPE_MAINTENANCE_PASSWORD='+$password),'--entrypoint','java',$ApplicationImage,
        '-Dloader.main=in.practice.tape.DatabaseMaintenance','-cp','app.jar','org.springframework.boot.loader.launch.PropertiesLauncher','init','/opt/tape') | Out-Null
    Checked @('exec',$restore,'pg_amcheck','--install-missing','--heapallindexed','-U','postgres','tape') | Out-Null
    [ordered]@{ encryption='age'; copiedCiphertextChecksum='passed'; decryptedArchiveChecksum='passed'; tamperRejected=$true;
        publicTableCounts='matched quiescent source'; heapAndIndexIntegrity='passed'; financialReconciliation='passed';
        network='isolated restore, no published ports'; offHostTransfer='not attempted'; retentionKey='disposable fixture only' } | ConvertTo-Json
} finally {
    $env:PGPASSWORD = $savedPassword
    docker stop $restore 2>$null | Out-Null
    # Remove only the unique directory created by this drill, including its disposable private key.
    $expected = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('tape-recovery-' + $id)))
    $resolved = (Resolve-Path -LiteralPath $directory).Path
    if ($resolved -ne $expected -or (Get-Item -LiteralPath $directory).LinkType) { throw 'Unsafe fixture cleanup target' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
