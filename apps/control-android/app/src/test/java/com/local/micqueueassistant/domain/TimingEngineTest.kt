package com.local.micqueueassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingEngineTest {
    @Test
    fun requiresTwoConsecutiveSnapshotsBeforeStarting() {
        val engine = TimingEngine(10_000)
        assertTrue(engine.acceptSnapshot(setOf("夏天"), 1_000).isEmpty())

        val actions = engine.acceptSnapshot(setOf("夏天"), 2_000)
        assertEquals(listOf("start", "seen"), actions.map(TimingAction::type))
        assertEquals(1_000, actions.first().atEpochMs)
        assertEquals(setOf("夏天"), engine.currentNames())
    }

    @Test
    fun shortDisappearanceMergesAndLongDisappearanceEndsAtLastSeen() {
        val engine = TimingEngine(10_000)
        engine.acceptSnapshot(setOf("夏天"), 1_000)
        engine.acceptSnapshot(setOf("夏天"), 2_000)

        assertTrue(engine.acceptSnapshot(emptySet(), 11_999).isEmpty())
        assertEquals("seen", engine.acceptSnapshot(setOf("夏天"), 12_000).single().type)
        assertTrue(engine.acceptSnapshot(emptySet(), 21_999).isEmpty())

        val end = engine.acceptSnapshot(emptySet(), 22_000).single()
        assertEquals("end", end.type)
        assertEquals(12_000, end.atEpochMs)
    }

    @Test
    fun unreadableResetMarksActiveSegmentUncertain() {
        val engine = TimingEngine()
        engine.acceptSnapshot(setOf("夏天"), 1_000)
        engine.acceptSnapshot(setOf("夏天"), 2_000)

        val action = engine.reset().single()
        assertEquals("uncertain", action.type)
        assertEquals(2_000, action.atEpochMs)
        assertTrue(engine.currentNames().isEmpty())
    }
}
