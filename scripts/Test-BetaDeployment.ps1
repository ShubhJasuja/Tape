param()
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = 'tape-beta-verify-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$artifacts = Join-Path $repository ('target/' + $project)
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$envFile = Join-Path $artifacts 'fixture.env'
$configuration = [ordered]@{
    TAPE_POSTGRES_PASSWORD=[guid]::NewGuid().ToString('N')
    TAPE_ACCOUNTS_PASSWORD=[guid]::NewGuid().ToString('N')
    TAPE_PUBLIC_ORIGIN='https://localhost:8443'
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
try {
    Compose @('config','--quiet')
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
    $headers = Join-Path $artifacts 'headers.txt'
    $response = Join-Path $artifacts 'csrf.json'
    & $curl --silent --show-error --fail --insecure --max-time 10 -D $headers -o $response https://localhost:8443/api/v1/auth/csrf
    if ($LASTEXITCODE) { throw 'Actual Caddy HTTPS proxy failed' }
    if ((Get-Content -Raw -LiteralPath $headers) -notmatch '(?im)^Set-Cookie:.*Secure;.*HttpOnly|(?im)^Set-Cookie:.*HttpOnly;.*Secure') { throw 'Secure HttpOnly cookie missing' }
    $status = & $curl --silent --insecure --max-time 10 -o NUL -w '%{http_code}' https://localhost:8443/actuator/health/readiness
    if ($status -ne '404') { throw 'Operations URL exposed through proxy' }
    $redirect = & $curl --silent --max-time 10 -I http://localhost:8088/api/v1/auth/csrf
    if (($redirect -join "`n") -notmatch 'https://localhost:8443/api/v1/auth/csrf') { throw 'Incorrect HTTPS redirect' }
    $cookies = Join-Path $artifacts 'cookies.txt'
    & $curl --silent --insecure --max-time 10 -c $cookies -o $response https://localhost:8443/api/v1/auth/csrf
    if ($LASTEXITCODE) { throw 'CSRF session request failed' }
    $csrf = Get-Content -Raw -LiteralPath $response | ConvertFrom-Json
    $body = Join-Path $artifacts 'registration.json'
    @{email=([guid]::NewGuid().ToString('N')+'@example.test');password=[guid]::NewGuid().ToString('N')} | ConvertTo-Json | Set-Content -LiteralPath $body -Encoding UTF8
    $status = & $curl --silent --insecure --max-time 10 -b $cookies -H ($csrf.headerName+': '+$csrf.token) -H 'Content-Type: application/json' --data-binary ('@'+$body) -o NUL -w '%{http_code}' https://localhost:8443/api/v1/auth/register
    if ($status -ne '202') { throw 'Closed registration response failed' }
    $counts = docker compose @arguments exec -T postgres psql -X -U tape -d tape -Atc 'SELECT max(version::int) FROM flyway_schema_history WHERE success; SELECT count(*) FROM local_credentials; SELECT count(*) FROM identity_mail;'
    if ($LASTEXITCODE -or ($counts -join ',') -ne '12,0,0') { throw 'Migration/access gate validation failed' }
    $result = [ordered]@{ image='tape-beta:local'; profiles='accounts,beta,brevo'; schema=12; https='actual local Caddy passed'; readiness='healthy'; cookies='Secure HttpOnly'; operationsProxy='blocked'; redirect='passed'; registration='closed, no identity or mail provisioned'; externalEmail='not attempted'; publicIngress='none' }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifacts 'result.json') -Encoding UTF8
    $result | ConvertTo-Json
} finally {
    docker compose @arguments logs --no-color *> (Join-Path $artifacts 'containers.log')
    # Only this newly generated test project owns these disposable containers and volumes.
    if ($project -notmatch '^tape-beta-verify-[a-f0-9]{12}$') { throw 'Unsafe cleanup target' }
    docker compose @arguments down --volumes 2>&1 | Out-Null
    Remove-Item -LiteralPath $envFile -ErrorAction SilentlyContinue
}
