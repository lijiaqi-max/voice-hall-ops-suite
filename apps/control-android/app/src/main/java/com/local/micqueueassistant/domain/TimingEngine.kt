package com.local.micqueueassistant.domain

data class ActivePresence(
    val ingkeeName: String,
    val startedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

private data class PendingPresence(
    val firstSeenAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

class TimingEngine(
    private val disappearanceGraceMs: Long = 10_000L,
) {
    private val active = linkedMapOf<String, ActivePresence>()
    private val pending = linkedMapOf<String, PendingPresence>()

    fun acceptSnapshot(
        names: Set<String>,
        atEpochMs: Long,
        pageReadable: Boolean = true,
    ): List<TimingAction> {
        if (!pageReadable) return emptyList()
        val actions = mutableListOf<TimingAction>()
        pending.keys.filter { it !in names }.forEach(pending::remove)
        names.forEach { name ->
            val current = active[name]
            if (current == null) {
                val candidate = pending.remove(name)
                if (candidate == null) {
                    pending[name] = PendingPresence(atEpochMs, atEpochMs)
                } else {
                    active[name] = ActivePresence(
                        ingkeeName = name,
                        startedAtEpochMs = candidate.firstSeenAtEpochMs,
                        lastSeenAtEpochMs = atEpochMs,
                    )
                    actions += TimingAction("start", name, candidate.firstSeenAtEpochMs)
                    actions += TimingAction("seen", name, atEpochMs)
                }
            } else {
                active[name] = current.copy(lastSeenAtEpochMs = atEpochMs)
                actions += TimingAction("seen", name, atEpochMs)
            }
        }
        active.values.toList().forEach { presence ->
            if (presence.ingkeeName !in names &&
                atEpochMs - presence.lastSeenAtEpochMs >= disappearanceGraceMs
            ) {
                active.remove(presence.ingkeeName)
                actions += TimingAction(
                    type = "end",
                    ingkeeName = presence.ingkeeName,
                    atEpochMs = presence.lastSeenAtEpochMs,
                )
            }
        }
        return actions
    }

    fun reset(): List<TimingAction> {
        val actions = active.values.map {
            TimingAction("uncertain", it.ingkeeName, it.lastSeenAtEpochMs)
        }
        active.clear()
        pending.clear()
        return actions
    }

    fun currentNames(): Set<String> = active.keys
}
