package com.local.micqueueassistant.automation

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.local.micqueueassistant.domain.SeatObservation
import com.local.micqueueassistant.domain.SeatSnapshotPayload
import java.nio.charset.StandardCharsets
import java.util.UUID

class IngkeeVoiceRoom9860Adapter : IngkeeVoiceRoomAdapter {
    override fun inspect(service: AccessibilityService, officialEnabled: Boolean): AdapterStatus {
        val root = service.rootInActiveWindow
            ?: return AdapterStatus(false, null, null, "unreadable", false, "当前窗口不可读取")
        val packageName = root.packageName?.toString()
            ?: return AdapterStatus(false, null, null, "unsupported", false, "当前应用包名不可读取")
        if (packageName !in setOf(OFFICIAL_PACKAGE, MOCK_PACKAGE)) {
            return AdapterStatus(false, packageName, null, "unsupported", false, "当前不是映客或模拟应用")
        }
        val version = packageVersion(service, packageName)
        if (version != SUPPORTED_VERSION) {
            return AdapterStatus(
                false,
                packageName,
                version,
                "unsupported_version",
                false,
                "仅支持映客 $SUPPORTED_VERSION，当前为 ${version ?: "未知"}",
            )
        }
        val texts = root.walk().map { it.semanticText() }.filter(String::isNotBlank).toList()
        val page = if (
            texts.any { it.contains("语音房") || it.contains("麦位") || it.contains("模拟映客语音房") }
        ) {
            "voice_room"
        } else {
            "unknown"
        }
        val mock = packageName == MOCK_PACKAGE
        val calibrated = mock || officialEnabled
        return AdapterStatus(
            supported = page == "voice_room",
            packageName = packageName,
            versionName = version,
            pageType = page,
            calibrated = calibrated && page == "voice_room",
            reason = when {
                page != "voice_room" -> "未知映客页面，已停止计时"
                mock -> "模拟映客麦位已校准"
                officialEnabled -> "正式映客麦位识别已校准"
                else -> "映客麦位识别待校准"
            },
        )
    }

    override suspend fun captureSeatSnapshot(
        service: AccessibilityService,
        officialEnabled: Boolean,
    ): SeatSnapshotPayload {
        val status = inspect(service, officialEnabled)
        val now = System.currentTimeMillis()
        if (!status.supported || !status.calibrated) {
            return payload(emptyList(), status.pageType, status.reason, now)
        }
        val root = service.rootInActiveWindow
            ?: return payload(emptyList(), "unreadable", "窗口不可读取", now)
        val seats = root.walk().mapNotNull { node ->
            val match = SEAT_PATTERN.find(node.semanticText()) ?: return@mapNotNull null
            SeatObservation(
                seatIndex = match.groupValues[1].toInt(),
                ingkeeName = match.groupValues[2].trim(),
            )
        }.distinctBy(SeatObservation::seatIndex).toList()
        if (seats.isNotEmpty()) return payload(seats, "voice_room", "node", now)

        val ocrSeats = OcrFallback.recognize(service).mapNotNull { line ->
            val match = SEAT_PATTERN.find(line.text) ?: return@mapNotNull null
            SeatObservation(match.groupValues[1].toInt(), match.groupValues[2].trim())
        }.distinctBy(SeatObservation::seatIndex)
        return payload(
            ocrSeats,
            if (ocrSeats.isEmpty()) "unknown" else "voice_room",
            if (ocrSeats.isEmpty()) "节点和 OCR 均无法识别麦位" else "ocr",
            now,
        )
    }

    private fun payload(
        seats: List<SeatObservation>,
        pageStatus: String,
        source: String,
        at: Long,
    ): SeatSnapshotPayload {
        val canonical = seats.sortedBy(SeatObservation::seatIndex)
            .joinToString("|") { "${it.seatIndex}:${it.ingkeeName}" }
        val bucket = at / 1_000L
        return SeatSnapshotPayload(
            eventId = UUID.nameUUIDFromBytes("$canonical|$pageStatus|$bucket".toByteArray(StandardCharsets.UTF_8))
                .toString(),
            capturedAtEpochMs = at,
            seats = seats,
            pageStatus = pageStatus,
            source = source,
        )
    }

    private fun packageVersion(service: AccessibilityService, packageName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                service.packageManager.getPackageInfo(packageName, 0).versionName
            }
        }.getOrNull()

    companion object {
        const val OFFICIAL_PACKAGE = "com.meelive.ingkee"
        const val MOCK_PACKAGE = "com.local.micqueueassistant.mocktarget"
        const val SUPPORTED_VERSION = "9.8.60"
        private val SEAT_PATTERN = Regex("""麦位\s*(\d+)\s*[|｜:：-]*\s*(?:昵称\s*[:：]?)?\s*(\S.{0,38})""")
    }
}
