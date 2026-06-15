param([switch]$SkipTests)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
$env:JAVA_HOME = $javaHome
$artifacts = Join-Path $root "artifacts"
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null

Push-Location (Join-Path $root "services\api")
try {
    $apiTasks = if ($SkipTests) { @("buildFatJar") } else { @("test", "buildFatJar") }
    & .\gradlew.bat @apiTasks --console=plain
    if ($LASTEXITCODE) { throw "API build failed" }
} finally { Pop-Location }

Push-Location (Join-Path $root "web-admin")
try {
    pnpm install --frozen-lockfile
    if (-not $SkipTests) { pnpm test }
    pnpm build
    if ($LASTEXITCODE) { throw "web build failed" }
} finally { Pop-Location }

foreach ($app in @("member-android", "control-android")) {
    Push-Location (Join-Path $root "apps\$app")
    try {
        $tasks = if ($SkipTests) { @(":app:assembleRelease") } else {
            @(":app:testDebugUnitTest", ":app:assembleRelease")
        }
        & .\gradlew.bat @tasks --console=plain
        if ($LASTEXITCODE) { throw "$app build failed" }
    } finally { Pop-Location }
}

Copy-Item "$root\services\api\build\libs\voice-hall-ops-api-all.jar" `
  "$artifacts\voice-hall-ops-api-1.0.0.jar" -Force
Copy-Item "$root\apps\member-android\app\build\outputs\apk\release\app-release.apk" `
  "$artifacts\voice-hall-member-2.0.0.apk" -Force
Copy-Item "$root\apps\control-android\app\build\outputs\apk\release\app-release.apk" `
  "$artifacts\voice-hall-control-1.0.0.apk" -Force

if (Test-Path "$artifacts\voice-hall-admin-1.0.0.zip") {
    Remove-Item "$artifacts\voice-hall-admin-1.0.0.zip" -Force
}
Compress-Archive -Path "$root\web-admin\dist\*" `
  -DestinationPath "$artifacts\voice-hall-admin-1.0.0.zip"
Get-ChildItem $artifacts -File |
  Where-Object { $_.Extension -in ".apk", ".jar", ".zip", ".xlsx", ".csv" } |
  Get-FileHash -Algorithm SHA256 |
  Sort-Object Path |
  ForEach-Object { "$($_.Hash.ToLower())  $([IO.Path]::GetFileName($_.Path))" } |
  Set-Content "$artifacts\SHA256SUMS.txt" -Encoding ascii

Write-Host "Release artifacts: $artifacts"
