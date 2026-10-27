package com.framer.sense.feature.camera.vlm

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.core.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameraHistoryDatabaseTest {
    @Test fun appendIsIdempotentPreservesThumbnailPinSelectionAndDoesNotRegressOnLateSave() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CameraHistoryDatabase::class.java).build()
        try {
            val dao = db.historyDao()
            val first = CameraHistorySessionEntity("s", "first", "qwen", "first.jpg", 1, 10, selectedId = "a", usedStyles = "[]", context = "context")
            dao.append(first, CameraHistoryResultEntity("a", "s", 10, "a"))
            dao.pin("s", 50)
            dao.select("s", "a")
            dao.append(first.copy(title = "new", modelName = "seed", thumbnail = "second.jpg", updatedAt = 20),
                CameraHistoryResultEntity("b", "s", 20, "b"))
            dao.append(first.copy(title = "late", updatedAt = 15), CameraHistoryResultEntity("c", "s", 15, "c"))
            dao.append(first.copy(title = "duplicate", updatedAt = 99), CameraHistoryResultEntity("b", "s", 99, "duplicate"))
            val stored = dao.load("s")!!
            assertEquals(3, stored.results.size)
            assertEquals("new", stored.session.title)
            assertEquals("seed", stored.session.modelName)
            assertEquals("first.jpg", stored.session.thumbnail)
            assertEquals(50L, stored.session.pinnedAt)
            assertEquals("a", stored.session.selectedId)
            dao.append(first.copy(id = "other", updatedAt = 30), CameraHistoryResultEntity("d", "other", 30, "d"))
            assertEquals(listOf("s", "other"), dao.observe().first().map { it.id })
            dao.pin("s", null)
            assertEquals(listOf("other", "s"), dao.observe().first().map { it.id })
            dao.delete("s")
            assertNull(dao.load("s"))
            assertEquals(1, dao.observe().first().size)
        } finally { db.close() }
    }
}
