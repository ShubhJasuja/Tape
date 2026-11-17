param(
    [Parameter(Mandatory)][string]$DestinationDirectory,
    [string]$Container = 'tape-postgres-1',
    [string]$Database = 'tape',
    [string]$DatabaseUser = 'tape'
)
$ErrorActionPreference = 'Stop'
if ($Container -notmatch '^[A-Za-z0-9_.-]+$' -or $Database -notmatch '^[A-Za-z0-9_]+$' -or $DatabaseUser -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Invalid database identifier'
}
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\', '/')
$destination = [IO.Path]::GetFullPath($DestinationDirectory).TrimEnd('\', '/')
if ($destination.Equals($repository, [StringComparison]::OrdinalIgnoreCase) -or
    $destination.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backups must be outside the repository'
}
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$id = [guid]::NewGuid().ToString('N')
$temporary = "/tmp/tape-backup-$id.dump"
$backup = Join-Path $destination "tape-$stamp-$id.dump"
try {
    docker exec $Container pg_dump --format=custom --no-password "--username=$DatabaseUser" "--dbname=$Database" "--file=$temporary"
    if ($LASTEXITCODE) { throw 'Consistent PostgreSQL dump failed' }
    docker cp ($Container + ':' + $temporary) $backup
    if ($LASTEXITCODE) { throw 'Backup copy failed' }
    $sha = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
    $inside = docker exec $Container sha256sum $temporary
    if ($LASTEXITCODE -or $inside.Split(' ')[0] -ne $sha) { throw 'Backup checksum mismatch' }
    $manifest = [ordered]@{
        file = [IO.Path]::GetFileName($backup)
        sha256 = $sha
        createdUtc = [DateTime]::UtcNow.ToString('o')
        database = $Database
        method = 'pg_dump custom format, consistent online snapshot'
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath ($backup + '.json') -Encoding UTF8
    Write-Output "Backup created at $backup"
    Write-Output "SHA-256 matched the container dump. Manifest saved at $backup.json"
} finally {
    docker exec $Container rm -f -- $temporary | Out-Null
}
