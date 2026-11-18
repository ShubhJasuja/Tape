param([int]$HttpsPort=18443, [int]$HttpPort=18088, [switch]$EncryptedRecovery)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = 'tape-beta-verify-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$artifacts = Join-Path $repository ('target/' + $project)
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$envFile = Join-Path $artifacts 'fixture.env'
$configuration = [ordered]@{
    TAPE_POSTGRES_PASSWORD=[guid]::NewGuid().ToString('N')
    TAPE_ACCOUNTS_PASSWORD=[guid]::NewGuid().ToString('N')
    TAPE_PUBLIC_ORIGIN="https://localhost:$HttpsPort"
    TAPE_BETA_HTTP_PORT=$HttpPort
    TAPE_BETA_HTTPS_PORT=$HttpsPort
    TAPE_MAIL_FROM='fixture@example.test'
    TAPE_IDENTITY_MAIL_KEY=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    TAPE_SMTP_USER='local-fixture-not-a-provider-login'
    TAPE_SMTP_PASSWORD=[guid]::NewGuid().ToString('N')
    TAPE_OPS_TOKEN=[guid]::NewGuid().ToString('N')
    TAPE_REGISTRATION_MODE='CLOSED'
    TAPE_BETA_HOST='localhost'
}
$configuration.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value } | Set-Content -LiteralPath $envFile -Encoding UTF8
$arguments = @('--env-file',$envFile,'-p',$project,'-f',(Join-Path $repository 'deploy/compose.beta.yaml'))
function Compose([string[]]$operation) {
    & docker compose @arguments @operation
    if ($LASTEXITCODE) { throw 'Disposable deployment command failed' }
}
$curl = if (Get-Command curl.exe -ErrorAction SilentlyContinue) { 'curl.exe' } else { 'curl' }
$nullSink = if ($IsWindows) { 'NUL' } else { '/dev/null' }
$origin = "https://localhost:$HttpsPort"
try {
    Compose @('config','--quiet')
    $resolved = docker compose @arguments --profile maintenance config --format json
    if ($LASTEXITCODE) { throw 'Configuration resolution failed' }
    $resolved | node (Join-Path $PSScriptRoot 'check-beta-config.mjs')
    if ($LASTEXITCODE) { throw 'Deployment preflight rejected configuration' }
    $resolved = $null
    Compose @('up','-d','postgres')
    Compose @('--profile','maintenance','run','--rm','migrate')
    Compose @('up','-d','--no-build','app','proxy')
    $app = docker compose @arguments ps -q app
    for ($i=0; $i -lt 90; $i++) {
        $health = docker inspect --format '{{.State.Health.Status}}' $app
        if ($health -eq 'healthy') { break }
        Start-Sleep -Seconds 1
    }
    if ($health -ne 'healthy') { throw 'Private beta did not become ready' }
    $proxy = docker compose @arguments ps -q proxy
    $certificate = Join-Path $artifacts 'fixture-ca.crt'
    docker cp ($proxy+':/data/caddy/pki/authorities/local/root.crt') $certificate
    if ($LASTEXITCODE) { throw 'Local certificate authority export failed' }
    $tlsOptions = @('--cacert',$certificate)
    # This throwaway private CA has no CRL service. Keep chain/hostname checks enabled on Schannel.
    if ($IsWindows) { $tlsOptions += '--ssl-revoke-best-effort' }
    $headers = Join-Path $artifacts 'headers.txt'
    $response = Join-Path $artifacts 'csrf.json'
    & $curl --silent --show-error --fail @tlsOptions --max-time 10 -D $headers -o $response "$origin/api/v1/auth/csrf"
    if ($LASTEXITCODE) { throw 'Actual Caddy HTTPS proxy failed' }
    if ((Get-Content -Raw -LiteralPath $headers) -notmatch '(?im)^Set-Cookie:.*Secure;.*HttpOnly|(?im)^Set-Cookie:.*HttpOnly;.*Secure') { throw 'Secure HttpOnly cookie missing' }
    $status = & $curl --silent @tlsOptions --max-time 10 -o $nullSink -w '%{http_code}' "$origin/actuator/health/readiness"
    if ($status -ne '404') { throw 'Operations URL exposed through proxy' }
    $redirect = & $curl --silent --max-time 10 -I "http://localhost:$HttpPort/api/v1/auth/csrf"
    if (!(($redirect -join "`n").Contains("$origin/api/v1/auth/csrf"))) { throw 'Incorrect HTTPS redirect' }
    $cookies = Join-Path $artifacts 'cookies.txt'
    & $curl --silent @tlsOptions --max-time 10 -c $cookies -o $response "$origin/api/v1/auth/csrf"
    if ($LASTEXITCODE) { throw 'CSRF session request failed' }
    $csrf = Get-Content -Raw -LiteralPath $response | ConvertFrom-Json
    $body = Join-Path $artifacts 'registration.json'
    @{email=([guid]::NewGuid().ToString('N')+'@example.test');password=[guid]::NewGuid().ToString('N')} | ConvertTo-Json | Set-Content -LiteralPath $body -Encoding UTF8
    $status = & $curl --silent @tlsOptions --max-time 10 -b $cookies -H ($csrf.headerName+': '+$csrf.token) -H 'Content-Type: application/json' --data-binary ('@'+$body) -o $nullSink -w '%{http_code}' "$origin/api/v1/auth/register"
    if ($status -ne '202') { throw 'Closed registration response failed' }
    Compose @('restart','app')
    for ($i=0; $i -lt 90; $i++) {
        $health = docker inspect --format '{{.State.Health.Status}}' $app
        if ($health -eq 'healthy') { break }
        Start-Sleep -Seconds 1
    }
    if ($health -ne 'healthy') { throw 'Application restart did not recover readiness' }
    $status = & $curl --silent @tlsOptions --max-time 10 -b $cookies -H ($csrf.headerName+': '+$csrf.token) -H 'Content-Type: application/json' --data-binary ('@'+$body) -o $nullSink -w '%{http_code}' "$origin/api/v1/auth/register"
    if ($status -ne '202') { throw 'JDBC-backed CSRF session was not preserved across container restart' }
    $counts = docker compose @arguments exec -T postgres psql -X -U tape -d tape -Atc 'SELECT max(version::int) FROM flyway_schema_history WHERE success; SELECT count(*) FROM local_credentials; SELECT count(*) FROM identity_mail;'
    if ($LASTEXITCODE -or ($counts -join ',') -ne '12,0,0') { throw 'Migration/access gate validation failed' }
    if ($EncryptedRecovery) {
        # Fund one deliberately local recovery fixture through the isolated schema owner, not a public API.
        $sql = "DO `$`$ DECLARE u uuid=gen_random_uuid(); a uuid=gen_random_uuid(); BEGIN INSERT INTO users(id,identity_issuer,identity_subject) VALUES(u,'backup-fixture',u::text); INSERT INTO trading_accounts(id,user_id,mode) VALUES(a,u,'DEMO'); INSERT INTO wallets(account_id,balance_paise) VALUES(a,100000000); INSERT INTO cash_ledger_entries(id,account_id,kind,amount_paise) VALUES(gen_random_uuid(),a,'OPENING_BALANCE',100000000); END `$`$;"
        $sql | docker compose @arguments exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tape -d tape
        if ($LASTEXITCODE) { throw 'Recovery fixture failed' }
        # Stop application writers gracefully before comparing every source and restored table count.
        Compose @('stop','app')
        $postgres = docker compose @arguments ps -q postgres
        & (Join-Path $PSScriptRoot 'Test-EncryptedBackup.ps1') -SourceContainer $postgres
    }
    $result = [ordered]@{ image='tape-beta:local'; profiles='accounts,beta,brevo'; schema=12; https='actual local Caddy passed'; readiness='healthy'; cookies='Secure HttpOnly'; operationsProxy='blocked'; redirect='passed'; registration='closed, no identity or mail provisioned'; restart='readiness and JDBC-backed CSRF session recovered'; externalEmail='not attempted'; publicIngress='none' }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifacts 'result.json') -Encoding UTF8
    $result | ConvertTo-Json
} finally {
    docker compose @arguments logs --no-color *> (Join-Path $artifacts 'containers.log')
    # Only this newly generated test project owns these disposable containers and volumes.
    if ($project -notmatch '^tape-beta-verify-[a-f0-9]{12}$') { throw 'Unsafe cleanup target' }
    docker compose @arguments down --volumes 2>&1 | Out-Null
    Remove-Item -LiteralPath $envFile -ErrorAction SilentlyContinue
}
