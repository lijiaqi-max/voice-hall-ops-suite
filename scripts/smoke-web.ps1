param([switch]$SkipBuild)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apiRoot = Join-Path $root "services\api"
$webRoot = Join-Path $root "web-admin"
$logRoot = Join-Path $root "build\smoke"
$java = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\java.exe"
$node = (Get-Command node).Source
$apiJar = Join-Path $apiRoot "build\libs\voice-hall-ops-api-all.jar"
$vite = Join-Path $webRoot "node_modules\vite\bin\vite.js"

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

if (-not $SkipBuild) {
    Push-Location $apiRoot
    try {
        & .\gradlew.bat buildFatJar --console=plain
        if ($LASTEXITCODE) { throw "API fat JAR build failed" }
    } finally {
        Pop-Location
    }
}

foreach ($path in @($java, $apiJar, $vite)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Smoke-test dependency is missing: $path"
    }
}

function Wait-Endpoint {
    param(
        [Parameter(Mandatory)] [string] $Url,
        [int] $TimeoutSeconds = 45
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 400
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Url"
}

$savedEnvironment = @{}
foreach ($name in @(
    "PORT", "DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD",
    "BOOTSTRAP_ADMIN_USER", "BOOTSTRAP_ADMIN_PASSWORD", "BOOTSTRAP_ADMIN_NAME",
    "JWT_SECRET", "DEVICE_MASTER_KEY"
)) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$apiProcess = $null
$webProcess = $null
try {
    $env:PORT = "8080"
    $env:DATABASE_URL = "jdbc:h2:mem:voicehall_smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
    $env:DATABASE_USER = "sa"
    $env:DATABASE_PASSWORD = ""
    $env:BOOTSTRAP_ADMIN_USER = "smoke-admin"
    $env:BOOTSTRAP_ADMIN_PASSWORD = "Smoke-Test-12345"
    $env:BOOTSTRAP_ADMIN_NAME = "Smoke Test Admin"
    $env:JWT_SECRET = "smoke-test-jwt-secret-not-for-production"
    $env:DEVICE_MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    $apiArguments = '-jar "{0}"' -f $apiJar
    $webArguments = '"{0}" --host 127.0.0.1 --port 4173' -f $vite

    $apiProcess = Start-Process `
        -FilePath $java `
        -ArgumentList $apiArguments `
        -WorkingDirectory $apiRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logRoot "api.out.log") `
        -RedirectStandardError (Join-Path $logRoot "api.err.log") `
        -PassThru
    Wait-Endpoint "http://127.0.0.1:8080/health"

    $webProcess = Start-Process `
        -FilePath $node `
        -ArgumentList $webArguments `
        -WorkingDirectory $webRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logRoot "web.out.log") `
        -RedirectStandardError (Join-Path $logRoot "web.err.log") `
        -PassThru
    Wait-Endpoint "http://127.0.0.1:4173"

    $index = Invoke-WebRequest "http://127.0.0.1:4173" -UseBasicParsing
    if ($index.Content -notmatch 'id="root"') {
        throw "Vite did not serve the React application shell"
    }

    $login = Invoke-RestMethod `
        -Uri "http://127.0.0.1:4173/api/auth/login" `
        -Method Post `
        -ContentType "application/json" `
        -Body (@{
            username = "smoke-admin"
            password = "Smoke-Test-12345"
        } | ConvertTo-Json)
    if (-not $login.accessToken -or -not $login.refreshToken) {
        throw "Login did not return both access and refresh tokens"
    }

    $refresh = Invoke-RestMethod `
        -Uri "http://127.0.0.1:4173/api/auth/refresh" `
        -Method Post `
        -ContentType "application/json" `
        -Body (@{ refreshToken = $login.refreshToken } | ConvertTo-Json)
    $headers = @{ Authorization = "Bearer $($refresh.accessToken)" }

    $rooms = @(Invoke-RestMethod "http://127.0.0.1:4173/api/rooms" -Headers $headers)
    $micSegments = @(Invoke-RestMethod "http://127.0.0.1:4173/api/mic-segments" -Headers $headers)
    $revenueImports = @(Invoke-RestMethod "http://127.0.0.1:4173/api/revenue-imports" -Headers $headers)
    $settlements = @(Invoke-RestMethod "http://127.0.0.1:4173/api/settlements" -Headers $headers)

    Invoke-RestMethod `
        -Uri "http://127.0.0.1:4173/api/auth/logout" `
        -Method Post `
        -ContentType "application/json" `
        -Body (@{ refreshToken = $refresh.refreshToken } | ConvertTo-Json) | Out-Null

    [pscustomobject]@{
        Status = "passed"
        Frontend = "http://127.0.0.1:4173"
        ApiProxy = "passed"
        RefreshRotation = "passed"
        Rooms = $rooms.Count
        MicSegments = $micSegments.Count
        RevenueImports = $revenueImports.Count
        Settlements = $settlements.Count
        Logs = $logRoot
    } | Format-List
    Write-Host "Smoke test passed: frontend proxy, token rotation, control and finance endpoints."
} finally {
    foreach ($process in @($webProcess, $apiProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $process.WaitForExit(5000) | Out-Null
        }
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
