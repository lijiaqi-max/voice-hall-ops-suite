package com.local.micqueueassistant.domain

import kotlinx.serialization.Serializable

enum class DeviceRole(val value: String) {
    UNSELECTED("unselected"),
    ROBOT("robot"),
    COLLECTOR("collector"),
}

enum class ShiftState(val value: String) {
    DRAFT("draft"),
    OPEN("open"),
    CLOSED("closed"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
}

enum class QueueRole(val value: String) {
    PARTICIPANT("participant"),
    HOST("host"),
}

enum class SegmentState(val value: String) {
    ACTIVE("active"),
    CLOSED("closed"),
    UNCERTAIN("uncertain"),
    CORRECTED("corrected"),
}

@Serializable
data class SeatObservation(
    val seatIndex: Int,
    val ingkeeName: String,
)

@Serializable
data class SeatSnapshotPayload(
    val eventId: String,
    val capturedAtEpochMs: Long,
    val seats: List<SeatObservation>,
    val pageStatus: String,
    val source: String,
)

@Serializable
data class WireEnvelope(
    val type: String,
    val deviceId: String,
    val pairingCode: String? = null,
    val eventId: String? = null,
    val payload: String = "",
    val sentAtEpochMs: Long = System.currentTimeMillis(),
)

data class IncomingGroupMessage(
    val groupTitle: String,
    val senderWechatName: String,
    val text: String,
    val observedAtEpochMs: Long,
)

sealed interface GroupCommand {
    data class OpenShift(
        val startAtEpochMs: Long,
        val endAtEpochMs: Long,
        val capacity: Int,
        val cutoffAtEpochMs: Long,
    ) : GroupCommand

    data class Join(val shiftLabel: String?) : GroupCommand
    data object Cancel : GroupCommand
    data class InsertHost(val wechatName: String, val position: Int) : GroupCommand
    data object CloseShift : GroupCommand
    data object ShowQueue : GroupCommand
    data class Bind(val ingkeeName: String) : GroupCommand
    data class Total(val targetWechatName: String?) : GroupCommand
    data class Export(val periodType: String, val periodKey: String) : GroupCommand
    data object Help : GroupCommand
}

data class CommandParseResult(
    val command: GroupCommand?,
    val error: String? = null,
)

data class TimingAction(
    val type: String,
    val ingkeeName: String,
    val atEpochMs: Long,
)

data class MemberSummary(
    val wechatName: String,
    val ordinarySeconds: Long,
    val hostSeconds: Long,
    val segmentCount: Int,
    val shiftCount: Int,
)
