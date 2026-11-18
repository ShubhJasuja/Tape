param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [string]$Jar = 'target/tape-1.0.0.jar',
    [string]$Container = 'tape-postgres-1',
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$scratch = 'tape-upgrade-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$scratchPassword = [guid]::NewGuid().ToString('N')
$saved = @{}
foreach ($name in @('TAPE_MAINTENANCE_URL','TAPE_MAINTENANCE_USER','TAPE_MAINTENANCE_PASSWORD')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Maintenance([string]$command, [string]$file) {
    java -Xmx256m '-Duser.timezone=UTC' '-Dloader.main=in.practice.tape.DatabaseMaintenance' -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher $command $file
    if ($LASTEXITCODE) { throw "Database maintenance $command failed. Do not start Tape; inspect the verified backup and recovery runbook." }
}
function Sql([string]$target, [string]$statement) {
    $statement | docker exec -i $target psql -X -q -v ON_ERROR_STOP=1 -U tape -d tape
    if ($LASTEXITCODE) { throw 'Database verification command failed' }
}
try {
    # This operator-only local procedure never stops an application or resets a password.
    $mapped = docker port $Container 5432/tcp
    if ($LASTEXITCODE -or $mapped -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Source must publish PostgreSQL on loopback only' }
    $sourceUrl = 'jdbc:postgresql://127.0.0.1:' + $Matches[1] + '/tape'
    $sourcePassword = docker exec $Container printenv POSTGRES_PASSWORD
    if ($LASTEXITCODE -or !$sourcePassword) { throw 'Local database owner credentials unavailable' }
    $env:TAPE_MAINTENANCE_URL = $sourceUrl
    $env:TAPE_MAINTENANCE_USER = 'tape'
    $env:TAPE_MAINTENANCE_PASSWORD = $sourcePassword
    & (Join-Path $PSScriptRoot 'Backup-Postgres.ps1') -DestinationDirectory $BackupDirectory -Container $Container
    $backup = Get-ChildItem -LiteralPath $BackupDirectory -Filter '*.dump' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (!$backup) { throw 'Backup not found' }
    $fingerprint = $backup.FullName + '.financial.json'
    Maintenance snapshot $fingerprint
    & (Join-Path $PSScriptRoot 'Test-PostgresBackup.ps1') -BackupFile $backup.FullName
    $manifest = Get-Content -Raw -LiteralPath ($backup.FullName + '.json') | ConvertFrom-Json
    if ($manifest.sha256 -ne (Get-FileHash -LiteralPath $backup.FullName -Algorithm SHA256).Hash) { throw 'Backup hash changed' }
    docker run --rm -d --name $scratch -p 127.0.0.1::5432 -e POSTGRES_USER=tape -e POSTGRES_DB=tape -e "POSTGRES_PASSWORD=$scratchPassword" postgres:17 | Out-Null
    if ($LASTEXITCODE) { throw 'Rehearsal container failed' }
    for ($i=0; $i -lt 60; $i++) {
        docker exec $scratch pg_isready -q -h 127.0.0.1 -U tape -d tape
        if (!$LASTEXITCODE) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE) { throw 'Rehearsal database not ready' }
    docker cp $backup.FullName ($scratch + ':/tmp/upgrade.dump')
    if ($LASTEXITCODE) { throw 'Rehearsal backup copy failed' }
    docker exec $scratch pg_restore --exit-on-error --no-owner --no-acl -U tape -d tape /tmp/upgrade.dump
    if ($LASTEXITCODE) { throw 'Rehearsal restore failed' }
    $mapped = docker port $scratch 5432/tcp
    if ($mapped -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Rehearsal loopback port unavailable' }
    $env:TAPE_MAINTENANCE_URL = 'jdbc:postgresql://127.0.0.1:' + $Matches[1] + '/tape'
    $env:TAPE_MAINTENANCE_PASSWORD = $scratchPassword
    Maintenance migrate $fingerprint
    Sql $scratch 'CREATE ROLE tape_runtime NOLOGIN;'
    Maintenance grants $repository
    Maintenance verify $fingerprint
    docker exec $scratch pg_amcheck --install-missing --heapallindexed -U tape tape
    if ($LASTEXITCODE) { throw 'Upgraded rehearsal integrity check failed' }
    $report = [ordered]@{ backup=$backup.FullName; sha256=$manifest.sha256; fingerprint=$fingerprint; rehearsal='passed'; targetVersion=12; original='unchanged'; verifiedUtc=[DateTime]::UtcNow.ToString('o') }
    # The source is checked again immediately before Flyway. Any changed records or open connections abort.
    if ($Apply) {
        $env:TAPE_MAINTENANCE_URL = $sourceUrl
        $env:TAPE_MAINTENANCE_PASSWORD = $sourcePassword
        Maintenance migrate $fingerprint
        Maintenance grants $repository
        Maintenance verify $fingerprint
        docker exec $Container pg_amcheck --install-missing --heapallindexed -U tape tape
        if ($LASTEXITCODE) { throw 'Upgraded source integrity check failed; keep Tape stopped' }
        $report.original = 'upgraded and verified V12'
    }
    $report.verifiedUtc = [DateTime]::UtcNow.ToString('o')
    $report | ConvertTo-Json | Set-Content -LiteralPath ($backup.FullName + '.upgrade.json') -Encoding UTF8
    $report | ConvertTo-Json
} finally {
    docker stop $scratch 2>$null | Out-Null
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
