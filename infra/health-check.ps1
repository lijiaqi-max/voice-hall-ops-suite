param([string]$BaseUrl = "https://ops.example.com")
$ErrorActionPreference = "Stop"
$health = Invoke-RestMethod "$($BaseUrl.TrimEnd('/'))/api/health"
if ($health.status -ne "ok") { throw "API health check failed" }
[pscustomobject]@{
    Status = $health.status
    Service = $health.service
    Version = $health.version
    CheckedAt = Get-Date
}

