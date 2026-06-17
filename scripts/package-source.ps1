$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$staging = Join-Path $root "build\source-package"
$artifacts = Join-Path $root "artifacts"
$archive = Join-Path $artifacts "voice-hall-ops-suite-1.0.0-source-and-deploy.zip"

if (-not $staging.StartsWith((Join-Path $root "build"), [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe staging path: $staging"
}
if (Test-Path $staging) {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $staging | Out-Null
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null

foreach ($file in @(".env.example", ".gitignore", "README.md")) {
    Copy-Item -LiteralPath (Join-Path $root $file) -Destination $staging
}
foreach ($directory in @("infra", "docs", "migration", ".github")) {
    Copy-Item -LiteralPath (Join-Path $root $directory) -Destination $staging -Recurse
}

function Copy-Project {
    param(
        [Parameter(Mandatory)] [string] $RelativePath,
        [Parameter(Mandatory)] [string[]] $Files,
        [Parameter(Mandatory)] [AllowEmptyCollection()] [string[]] $Directories
    )
    $source = Join-Path $root $RelativePath
    $destination = Join-Path $staging $RelativePath
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    foreach ($file in $Files) {
        $path = Join-Path $source $file
        if (Test-Path $path) {
            Copy-Item -LiteralPath $path -Destination $destination
        }
    }
    foreach ($directory in $Directories) {
        $path = Join-Path $source $directory
        if (Test-Path $path) {
            Copy-Item -LiteralPath $path -Destination $destination -Recurse
        }
    }
}

Copy-Project "services\api" `
    @("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat", "Dockerfile") `
    @("gradle", "src")

Copy-Project "web-admin" `
    @(
        "index.html", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml",
        "tsconfig.json", "tsconfig.node.json", "vite.config.ts"
    ) `
    @("src")

foreach ($app in @("member-android", "control-android")) {
    Copy-Project "apps\$app" `
        @("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat") `
        @("gradle")
    Copy-Project "apps\$app\app" `
        @("build.gradle.kts", "proguard-rules.pro") `
        @("src", "schemas")
}

Copy-Project "scripts" `
    @(
        "build-all.ps1", "build-finance-samples.mjs", "package-source.ps1",
        "reset-demo.ps1", "smoke-web.ps1", "start-demo.ps1", "stop-demo.ps1"
    ) `
    @()

if (Test-Path $archive) {
    Remove-Item -LiteralPath $archive -Force
}
Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $archive -CompressionLevel Optimal

Get-ChildItem $artifacts -File |
    Where-Object { $_.Extension -in ".apk", ".jar", ".zip", ".xlsx", ".csv" } |
    Get-FileHash -Algorithm SHA256 |
    Sort-Object Path |
    ForEach-Object { "$($_.Hash.ToLower())  $([IO.Path]::GetFileName($_.Path))" } |
    Set-Content (Join-Path $artifacts "SHA256SUMS.txt") -Encoding ascii

Write-Host "Source and deployment archive: $archive"
