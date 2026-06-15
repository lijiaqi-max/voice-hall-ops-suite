param(
    [switch]$Reset,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apiRoot = Join-Path $root "services\api"
$webRoot = Join-Path $root "web-admin"
$demoRoot = Join-Path $root "build\demo"
$processFile = Join-Path $demoRoot "processes.json"
$databaseBase = ([IO.Path]::GetFullPath((Join-Path $demoRoot "voicehall"))).Replace("\", "/")
$apiJar = Join-Path $apiRoot "build\libs\voice-hall-ops-api-all.jar"
$java = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\java.exe"
$node = (Get-Command node).Source
$vite = Join-Path $webRoot "node_modules\vite\bin\vite.js"

function Wait-Endpoint {
    param([string]$Url, [int]$TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Milliseconds 400
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Url"
}

if ($Reset) {
    & (Join-Path $PSScriptRoot "reset-demo.ps1") -SkipBuild:$SkipBuild
}
if (-not (Test-Path -LiteralPath "$databaseBase.mv.db")) {
    throw "Demo database is missing. Run .\scripts\reset-demo.ps1 first."
}
foreach ($path in @($java, $apiJar, $vite)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Demo dependency is missing: $path"
    }
}
$listeners = Get-NetTCPConnection -LocalPort 8080,4173 -State Listen -ErrorAction SilentlyContinue
if ($listeners) {
    throw "Port 8080 or 4173 is already in use. Stop the existing service first."
}

New-Item -ItemType Directory -Force -Path $demoRoot | Out-Null
$savedEnvironment = @{}
foreach ($name in @(
    "PORT", "DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD",
    "JWT_SECRET", "DEVICE_MASTER_KEY"
)) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$apiProcess = $null
$webProcess = $null
try {
    $env:PORT = "8080"
    $env:DATABASE_URL = "jdbc:h2:file:$databaseBase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
    $env:DATABASE_USER = "sa"
    $env:DATABASE_PASSWORD = ""
    $env:JWT_SECRET = "demo-only-jwt-secret-replace-in-production"
    $env:DEVICE_MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    $apiProcess = Start-Process `
        -FilePath $java `
        -ArgumentList ('-jar "{0}"' -f $apiJar) `
        -WorkingDirectory $apiRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $demoRoot "api.out.log") `
        -RedirectStandardError (Join-Path $demoRoot "api.err.log") `
        -PassThru
    Wait-Endpoint "http://127.0.0.1:8080/health"

    $webProcess = Start-Process `
        -FilePath $node `
        -ArgumentList ('"{0}" --host 127.0.0.1 --port 4173' -f $vite) `
        -WorkingDirectory $webRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $demoRoot "web.out.log") `
        -RedirectStandardError (Join-Path $demoRoot "web.err.log") `
        -PassThru
    Wait-Endpoint "http://127.0.0.1:4173"
    Wait-Endpoint "http://127.0.0.1:4173/api/health"

    @{
        api = @{
            id = $apiProcess.Id
            name = $apiProcess.ProcessName
            startedAt = $apiProcess.StartTime.ToUniversalTime().ToString("O")
        }
        web = @{
            id = $webProcess.Id
            name = $webProcess.ProcessName
            startedAt = $webProcess.StartTime.ToUniversalTime().ToString("O")
        }
    } | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath $processFile -Encoding ascii

    [pscustomobject]@{
        Status = "running"
        AdminUrl = "http://127.0.0.1:4173"
        ApiHealth = "http://127.0.0.1:4173/api/health"
        Login = "demo-owner / Demo-Owner-12345"
        ProcessFile = $processFile
        StopCommand = ".\scripts\stop-demo.ps1"
    } | Format-List
    Write-Host "Demo is running at http://127.0.0.1:4173"
} catch {
    foreach ($process in @($webProcess, $apiProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    throw
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
