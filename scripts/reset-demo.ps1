param([switch]$SkipBuild)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apiRoot = Join-Path $root "services\api"
$demoRoot = [IO.Path]::GetFullPath((Join-Path $root "build\demo"))
$java = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\java.exe"
$apiJar = Join-Path $apiRoot "build\libs\voice-hall-ops-api-all.jar"
$apiUrl = "http://127.0.0.1:18080"
$databaseBase = ([IO.Path]::GetFullPath((Join-Path $demoRoot "voicehall"))).Replace("\", "/")

function Decode-JsonString([string]$escaped) {
    return ('"' + $escaped + '"' | ConvertFrom-Json)
}

function Wait-Endpoint {
    param([string]$Url, [int]$TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch {
            Start-Sleep -Milliseconds 400
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Url"
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [hashtable]$Headers = @{}
    )
    $arguments = @{
        Uri = "$apiUrl$Path"
        Method = $Method
        Headers = $Headers
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json; charset=utf-8"
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes(
            ($Body | ConvertTo-Json -Depth 12 -Compress)
        )
    }
    return Invoke-RestMethod @arguments
}

function Send-DeviceEvent {
    param(
        [string]$DeviceId,
        [string]$DeviceSecret,
        [string]$EventId,
        [string]$Type,
        [string]$RoomId,
        [long]$OccurredAt,
        $Payload
    )
    $payloadJson = $Payload | ConvertTo-Json -Depth 12 -Compress
    $event = @{
        eventId = $EventId
        type = $Type
        roomId = $RoomId
        occurredAtEpochMs = $OccurredAt
        payload = $payloadJson
    }
    $body = $event | ConvertTo-Json -Depth 12 -Compress
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
    $hmac = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($DeviceSecret)
    )
    try {
        $signature = ($hmac.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes("$timestamp`n$body")
        ) | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        $hmac.Dispose()
    }
    $ack = Invoke-RestMethod `
        -Uri "$apiUrl/devices/events" `
        -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Headers @{
            "X-Device-Id" = $DeviceId
            "X-Timestamp" = $timestamp
            "X-Signature" = $signature
        } `
        -Body ([Text.Encoding]::UTF8.GetBytes($body))
    if (-not $ack.accepted) {
        throw "Device event was not accepted: $EventId"
    }
}

if (-not $demoRoot.StartsWith(
    [IO.Path]::GetFullPath((Join-Path $root "build")),
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw "Unsafe demo directory: $demoRoot"
}
New-Item -ItemType Directory -Force -Path $demoRoot | Out-Null

if (-not $SkipBuild) {
    Push-Location $apiRoot
    try {
        & .\gradlew.bat buildFatJar --console=plain
        if ($LASTEXITCODE) { throw "API fat JAR build failed" }
    } finally {
        Pop-Location
    }
}
foreach ($path in @($java, $apiJar)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Demo dependency is missing: $path"
    }
}

foreach ($fileName in @("voicehall.mv.db", "voicehall.trace.db")) {
    $target = [IO.Path]::GetFullPath((Join-Path $demoRoot $fileName))
    if (-not $target.StartsWith($demoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe demo database path: $target"
    }
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
}

$savedEnvironment = @{}
foreach ($name in @(
    "PORT", "DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD",
    "BOOTSTRAP_ORG", "BOOTSTRAP_ADMIN_USER", "BOOTSTRAP_ADMIN_PASSWORD",
    "BOOTSTRAP_ADMIN_NAME", "JWT_SECRET", "DEVICE_MASTER_KEY"
)) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$apiProcess = $null
try {
    $env:PORT = "18080"
    $env:DATABASE_URL = "jdbc:h2:file:$databaseBase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
    $env:DATABASE_USER = "sa"
    $env:DATABASE_PASSWORD = ""
    $env:BOOTSTRAP_ORG = Decode-JsonString '\u6bd4\u8d5b\u6f14\u793a\u7ec4\u7ec7'
    $env:BOOTSTRAP_ADMIN_USER = "demo-owner"
    $env:BOOTSTRAP_ADMIN_PASSWORD = "Demo-Owner-12345"
    $env:BOOTSTRAP_ADMIN_NAME = Decode-JsonString '\u6f14\u793a\u8d1f\u8d23\u4eba'
    $env:JWT_SECRET = "demo-only-jwt-secret-replace-in-production"
    $env:DEVICE_MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    $apiArguments = '-jar "{0}"' -f $apiJar
    $apiProcess = Start-Process `
        -FilePath $java `
        -ArgumentList $apiArguments `
        -WorkingDirectory $apiRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $demoRoot "seed-api.out.log") `
        -RedirectStandardError (Join-Path $demoRoot "seed-api.err.log") `
        -PassThru
    Wait-Endpoint "$apiUrl/health"

    $login = Invoke-JsonApi Post "/auth/login" @{
        username = "demo-owner"
        password = "Demo-Owner-12345"
    }
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }

    $roomA = Invoke-JsonApi Post "/rooms" @{
        name = Decode-JsonString '\u661f\u6cb3\u8bed\u97f3\u5385'
        platform = "ingkee"
        externalRoomId = "demo-room-a"
    } $headers
    $roomB = Invoke-JsonApi Post "/rooms" @{
        name = Decode-JsonString '\u6708\u5149\u8bed\u97f3\u5385'
        platform = "ingkee"
        externalRoomId = "demo-room-b"
    } $headers

    $hostAccount = Invoke-JsonApi Post "/accounts" @{
        username = "demo-host"
        displayName = Decode-JsonString '\u4e3b\u6301\u4eba\u6797\u60e0'
        password = "Demo-Host-12345"
        role = "member"
        roomIds = @($roomA.id)
    } $headers
    $memberA = Invoke-JsonApi Post "/accounts" @{
        username = "demo-member-a"
        displayName = Decode-JsonString '\u6210\u5458\u590f\u5929'
        password = "Demo-Member-12345"
        role = "member"
        roomIds = @($roomA.id)
    } $headers
    $memberB = Invoke-JsonApi Post "/accounts" @{
        username = "demo-member-b"
        displayName = Decode-JsonString '\u6210\u5458\u5ff5\u5b89'
        password = "Demo-Member-12345"
        role = "member"
        roomIds = @($roomA.id)
    } $headers

    $midnight = [DateTimeOffset]::new([DateTime]::Today)
    $periodStart = $midnight.ToUnixTimeMilliseconds()
    $periodEnd = $midnight.AddDays(1).ToUnixTimeMilliseconds()
    $shiftStart = $midnight.AddHours(16).ToUnixTimeMilliseconds()
    $shiftEnd = $midnight.AddHours(17).ToUnixTimeMilliseconds()
    $shift = Invoke-JsonApi Post "/shifts" @{
        roomId = $roomA.id
        hostAccountId = $hostAccount.id
        title = Decode-JsonString '\u4e0b\u5348\u9ec4\u91d1\u6863'
        startAtEpochMs = $shiftStart
        endAtEpochMs = $shiftEnd
        hostFixedCents = 8000
        hostHourlyCents = 12000
    } $headers

    $customerA = Invoke-JsonApi Post "/customers" @{
        displayName = Decode-JsonString '\u661f\u8fb0'
        platform = "ingkee"
        externalUserId = "demo-customer-001"
        relationshipStage = "key_maintenance"
        contactEligibility = "manual_confirmed"
    } $headers
    $customerB = Invoke-JsonApi Post "/customers" @{
        displayName = Decode-JsonString '\u6e05\u98ce'
        platform = "ingkee"
        externalUserId = "demo-customer-002"
        relationshipStage = "active"
        contactEligibility = "manual_confirmed"
    } $headers
    Invoke-JsonApi Post "/customers" @{
        displayName = Decode-JsonString '\u5df2\u7981\u6b62\u8054\u7cfb\u7528\u6237'
        platform = "ingkee"
        externalUserId = "demo-customer-blocked"
        relationshipStage = "do_not_contact"
        contactEligibility = "do_not_contact"
    } $headers | Out-Null

    Invoke-JsonApi Post "/tasks" @{
        roomId = $roomA.id
        customerId = $customerA.id
        title = Decode-JsonString '\u91cd\u70b9\u5ba2\u6237\u56de\u8bbf'
        brief = Decode-JsonString '\u611f\u8c22\u8fd1\u671f\u4e92\u52a8\uff0c\u4e86\u89e3\u4f53\u9a8c\u5e76\u8bb0\u5f55\u4e0b\u6b21\u63d0\u9192\u3002'
        priority = 95
        assignedAccountId = $memberA.id
        publish = $true
    } $headers | Out-Null
    Invoke-JsonApi Post "/tasks" @{
        roomId = $roomA.id
        customerId = $customerB.id
        title = Decode-JsonString '\u65e5\u5e38\u4e92\u52a8\u8ddf\u8fdb'
        brief = Decode-JsonString '\u6839\u636e\u5df2\u6709\u4e92\u52a8\u505a\u4e00\u6b21\u6e29\u548c\u56de\u8bbf\uff0c\u4e0d\u63d0\u53ca\u6d88\u8d39\u3002'
        priority = 75
        assignedAccountId = $memberB.id
        publish = $true
    } $headers | Out-Null

    $device = Invoke-JsonApi Post "/devices/register" @{
        roomId = $roomA.id
        name = Decode-JsonString '\u6f14\u793a\u5385\u63a7\u624b\u673a'
    } $headers

    $bindings = @(
        @{ id = "demo-binding-host"; wechat = Decode-JsonString '\u6797\u60e0'; ingkee = Decode-JsonString '\u6797\u60e0' },
        @{ id = "demo-binding-a"; wechat = Decode-JsonString '\u590f\u5929'; ingkee = Decode-JsonString '\u590f\u5929' },
        @{ id = "demo-binding-b"; wechat = Decode-JsonString '\u5ff5\u5b89'; ingkee = Decode-JsonString '\u5ff5\u5b89' }
    )
    $eventTime = $shiftStart
    $eventIndex = 0
    foreach ($binding in $bindings) {
        $eventIndex++
        Send-DeviceEvent $device.deviceId $device.deviceSecret "demo-binding-$eventIndex" `
            "binding_upsert" $roomA.id $eventTime @{
                bindingId = $binding.id
                wechatName = $binding.wechat
                ingkeeName = $binding.ingkee
                state = "approved"
                approvedBy = $env:BOOTSTRAP_ADMIN_NAME
                createdAtEpochMs = $eventTime
                updatedAtEpochMs = $eventTime
            }
    }

    $queueRows = @(
        @{ id = "demo-queue-host"; name = $bindings[0].wechat; role = "host"; position = 1 },
        @{ id = "demo-queue-a"; name = $bindings[1].wechat; role = "participant"; position = 2 },
        @{ id = "demo-queue-b"; name = $bindings[2].wechat; role = "participant"; position = 3 }
    )
    foreach ($queue in $queueRows) {
        Send-DeviceEvent $device.deviceId $device.deviceSecret $queue.id `
            "queue_entry_upsert" $roomA.id $eventTime @{
                entryId = $queue.id
                shiftId = $shift.id
                wechatName = $queue.name
                role = $queue.role
                position = $queue.position
                state = "queued"
                createdBy = "demo-owner"
                createdAtEpochMs = $eventTime
                updatedAtEpochMs = $eventTime
            }
    }

    $hostName = $bindings[0].ingkee
    $memberAName = $bindings[1].ingkee
    $memberBName = $bindings[2].ingkee
    $snapshots = @(
        @{ offset = 300; seats = @(@{ seatIndex = 1; ingkeeName = $hostName }, @{ seatIndex = 2; ingkeeName = $memberAName }) },
        @{ offset = 1500; seats = @(@{ seatIndex = 1; ingkeeName = $hostName }, @{ seatIndex = 2; ingkeeName = $memberAName }) },
        @{ offset = 1800; seats = @(@{ seatIndex = 1; ingkeeName = $hostName }, @{ seatIndex = 3; ingkeeName = $memberBName }) },
        @{ offset = 1811; seats = @(@{ seatIndex = 1; ingkeeName = $hostName }, @{ seatIndex = 3; ingkeeName = $memberBName }) },
        @{ offset = 2640; seats = @(@{ seatIndex = 1; ingkeeName = $hostName }, @{ seatIndex = 3; ingkeeName = $memberBName }) },
        @{ offset = 2700; seats = @() },
        @{ offset = 2711; seats = @() }
    )
    $snapshotIndex = 0
    foreach ($snapshot in $snapshots) {
        $snapshotIndex++
        $capturedAt = $shiftStart + [long]$snapshot.offset * 1000
        Send-DeviceEvent $device.deviceId $device.deviceSecret "demo-snapshot-$snapshotIndex" `
            "seat_snapshot" $roomA.id $capturedAt @{
                eventId = "demo-snapshot-$snapshotIndex"
                capturedAtEpochMs = $capturedAt
                seats = $snapshot.seats
                pageStatus = "voice_room"
                source = "demo"
            }
    }

    $revenueRows = @(
        @{
            roomId = $roomA.id; platform = "ingkee"; transactionId = "DEMO-TX-001"
            customerExternalId = $customerA.externalUserId; customerDisplayName = $customerA.displayName
            giftCategory = "public_gift"; grossCents = 320000
            occurredAtEpochMs = $midnight.AddHours(18).ToUnixTimeMilliseconds()
        },
        @{
            roomId = $roomA.id; platform = "ingkee"; transactionId = "DEMO-TX-002"
            customerExternalId = $customerA.externalUserId; customerDisplayName = $customerA.displayName
            giftCategory = "public_gift"; grossCents = 110000
            occurredAtEpochMs = $midnight.AddHours(19).ToUnixTimeMilliseconds()
        },
        @{
            roomId = $roomA.id; platform = "ingkee"; transactionId = "DEMO-TX-003"
            customerExternalId = $customerB.externalUserId; customerDisplayName = $customerB.displayName
            giftCategory = "public_gift"; grossCents = 82300
            occurredAtEpochMs = $midnight.AddHours(20).ToUnixTimeMilliseconds()
        }
    )
    $preview = Invoke-JsonApi Post "/revenue-imports/preview" @{
        fileName = "demo-official-bill.csv"
        fileSha256 = ("a" * 64)
        expectedTotalCents = 512300
        rows = $revenueRows
    } $headers
    Invoke-JsonApi Post "/revenue-imports/$($preview.importId)/commit" $null $headers | Out-Null

    $rule = Invoke-JsonApi Post "/settlements/rules" @{
        name = Decode-JsonString '\u6f14\u793a\u5385\u6807\u51c6\u5206\u6210'
        roomId = $roomA.id
        role = "all"
        effectiveFromEpochMs = $periodStart
        platformRateBps = 2000
        organizationShareBps = 1000
        memberCommissionBps = 3000
    } $headers
    Invoke-JsonApi Post "/settlements/expenses" @{
        roomId = $roomA.id
        category = "operations"
        amountCents = 16800
        note = Decode-JsonString '\u6d3b\u52a8\u7269\u6599\u4e0e\u65e5\u5e38\u8fd0\u8425'
        occurredAtEpochMs = $midnight.AddHours(15).ToUnixTimeMilliseconds()
    } $headers | Out-Null
    $settlement = Invoke-JsonApi Post "/settlements/close" @{
        roomId = $roomA.id
        periodStartEpochMs = $periodStart
        periodEndEpochMs = $periodEnd
        ruleId = $rule.id
    } $headers

    $summary = @{
        generatedAt = [DateTimeOffset]::Now.ToString("O")
        database = "$databaseBase.mv.db"
        apiPort = 18080
        username = "demo-owner"
        password = "Demo-Owner-12345"
        roomIds = @($roomA.id, $roomB.id)
        settlementId = $settlement.id
    }
    $summary | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $demoRoot "demo-manifest.json") -Encoding utf8

    [pscustomobject]@{
        Status = "ready"
        Database = "$databaseBase.mv.db"
        Login = "demo-owner / Demo-Owner-12345"
        Rooms = 2
        Members = 3
        Tasks = 2
        RevenueCents = $settlement.grossCents
        PlatformCents = $settlement.platformDeductionCents
        OrganizationCents = $settlement.organizationShareCents
        CommissionCents = $settlement.memberCommissionCents
        HostCostCents = $settlement.hostCostCents
        ExpenseCents = $settlement.expenseCents
        NetProfitCents = $settlement.netProfitCents
        ReconciliationCents = $settlement.reconciliationDifferenceCents
        SettlementId = $settlement.id
    } | Format-List
} finally {
    if ($null -ne $apiProcess -and -not $apiProcess.HasExited) {
        Stop-Process -Id $apiProcess.Id -Force -ErrorAction SilentlyContinue
        $apiProcess.WaitForExit(5000) | Out-Null
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
