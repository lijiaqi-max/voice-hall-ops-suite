$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$processFile = Join-Path $root "build\demo\processes.json"

if (-not (Test-Path -LiteralPath $processFile)) {
    Write-Host "Demo process file does not exist; nothing to stop."
    return
}

$manifest = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
$stopped = @()
foreach ($entry in @($manifest.web, $manifest.api)) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($null -eq $process) { continue }
    $expectedStart = [DateTime]::Parse($entry.startedAt).ToUniversalTime()
    $actualStart = $process.StartTime.ToUniversalTime()
    $sameStart = [Math]::Abs(($actualStart - $expectedStart).TotalSeconds) -lt 2
    if ($process.ProcessName -ne $entry.name -or -not $sameStart) {
        throw "PID $($entry.id) no longer matches the recorded demo process."
    }
    Stop-Process -Id $process.Id -Force
    $stopped += "$($process.ProcessName):$($process.Id)"
}
Remove-Item -LiteralPath $processFile -Force
Write-Host "Stopped demo processes: $($stopped -join ', ')"
