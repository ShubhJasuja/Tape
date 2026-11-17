param([Parameter(Mandatory)][string]$BackupFile)
$ErrorActionPreference = 'Stop'
$backup = (Resolve-Path -LiteralPath $BackupFile).Path
$manifest = Get-Content -Raw -LiteralPath ($backup + '.json') | ConvertFrom-Json
if ($manifest.sha256 -ne (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash) { throw 'Backup checksum failed' }
$container = 'tape-restore-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$password = [guid]::NewGuid().ToString('N')
try {
    docker run --rm -d --network none --name $container -e "POSTGRES_PASSWORD=$password" -e POSTGRES_DB=tape postgres:17 | Out-Null
    if ($LASTEXITCODE) { throw 'Restore container did not start' }
    for ($i = 0; $i -lt 60; $i++) {
        docker exec $container pg_isready -q -h 127.0.0.1 -U postgres -d tape
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE) { throw 'Isolated restore database was not ready' }
    docker cp $backup ($container + ':/tmp/tape-restore.dump')
    if ($LASTEXITCODE) { throw 'Restore copy failed' }
    docker exec $container pg_restore --exit-on-error --no-owner --no-acl -U postgres -d tape /tmp/tape-restore.dump
    if ($LASTEXITCODE) { throw 'Restore failed' }
    docker exec $container pg_amcheck --install-missing --heapallindexed -U postgres tape
    if ($LASTEXITCODE) { throw 'Restored heap or index integrity check failed' }
    $counts = docker exec $container psql -X -U postgres -d tape -Atc "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name;"
    if ($LASTEXITCODE) { throw 'Restored table discovery failed' }
    $records = [ordered]@{}
    foreach ($table in $counts) {
        if ($table -notmatch '^[a-z_][a-z0-9_]*$') { throw 'Unexpected table name' }
        $count = docker exec $container psql -X -U postgres -d tape -Atc "SELECT count(*) FROM public.$table;"
        if ($LASTEXITCODE) { throw 'Restored record query failed' }
        $records[$table] = [long]$count
    }
    $verification = [ordered]@{
        verifiedUtc = [DateTime]::UtcNow.ToString('o')
        sha256 = $manifest.sha256
        restoredTo = 'Disposable PostgreSQL 17 container with no network and no published ports'
        heapAndIndexIntegrity = 'passed'
        records = $records
    }
    $verification | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ($backup + '.restore.json') -Encoding UTF8
    $verification | ConvertTo-Json -Depth 5
} finally {
    docker stop $container 2>$null | Out-Null
}
