package com.local.micqueueassistant.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: AppDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun duplicateCollectorEventIsIdempotent() = runBlocking {
        val first = CollectorEventEntity("row-1", "event-1", "seat_snapshot", "{}")
        val duplicate = CollectorEventEntity("row-2", "event-1", "seat_snapshot", "{}")
        assertNotEquals(-1L, dao.insertCollectorEvent(first))
        assertEquals(-1L, dao.insertCollectorEvent(duplicate))
        assertEquals(listOf("event-1"), dao.pendingCollectorEvents().map { it.eventId })
    }

    @Test
    fun approvedBindingBackfillsUnmatchedSegments() = runBlocking {
        dao.saveSegment(
            MicSegmentEntity(
                id = "segment-1",
                shiftId = "shift-1",
                bindingId = null,
                wechatName = null,
                ingkeeName = "映客小夏",
                role = "participant",
                queuePosition = null,
                startedAtEpochMs = 1_000,
                lastSeenAtEpochMs = 2_000,
                endedAtEpochMs = null,
                durationSeconds = 1,
            ),
        )
        dao.backfillBinding(
            ingkeeName = "映客小夏",
            bindingId = "binding-1",
            wechatName = "夏天",
            role = "host",
            queuePosition = 3,
        )

        val segment = requireNotNull(dao.activeSegmentFor("映客小夏"))
        assertEquals("binding-1", segment.bindingId)
        assertEquals("夏天", segment.wechatName)
        assertEquals("host", segment.role)
        assertEquals(3, segment.queuePosition)
    }
}
