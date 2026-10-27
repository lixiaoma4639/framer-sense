package com.framer.sense.core.data

import com.framer.sense.core.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class CameraHistoryEntry(val id: String, val title: String, val modelName: String, val thumbnail: String,
    val createdAt: Long, val updatedAt: Long, val pinnedAt: Long?)
data class CameraHistoryRecord(val entry: CameraHistoryEntry, val selectedId: String?, val usedStyles: String,
    val context: String, val results: List<CameraHistoryResult>)
data class CameraHistoryResult(val id: String, val completedAt: Long, val payload: String)

interface CameraHistoryRepository {
    val entries: Flow<List<CameraHistoryEntry>>
    suspend fun load(id: String): CameraHistoryRecord?
    suspend fun usedStyles(id: String): String?
    suspend fun append(record: CameraHistoryRecord, result: CameraHistoryResult)
    suspend fun pin(id: String, at: Long?)
    suspend fun select(id: String, candidateId: String)
    suspend fun styles(id: String, styles: String)
    suspend fun delete(id: String)
}

class LocalCameraHistoryRepository @Inject constructor(private val dao: CameraHistoryDao) : CameraHistoryRepository {
    override suspend fun usedStyles(id: String) = dao.usedStyles(id)
    private fun CameraHistorySessionEntity.entry() = CameraHistoryEntry(id, title, modelName, thumbnail, createdAt, updatedAt, pinnedAt)
    override val entries = dao.observe().map { rows -> rows.map {
        CameraHistoryEntry(it.id, it.title, it.modelName, it.thumbnail, it.createdAt, it.updatedAt, it.pinnedAt)
    } }
    override suspend fun load(id: String) = dao.load(id)?.let {
        CameraHistoryRecord(it.session.entry(), it.session.selectedId, it.session.usedStyles, it.session.context,
            it.results.sortedWith(compareBy<CameraHistoryResultEntity> { row -> row.completedAt }.thenBy { row -> row.id })
                .map { row -> CameraHistoryResult(row.id, row.completedAt, row.payload) })
    }
    override suspend fun append(record: CameraHistoryRecord, result: CameraHistoryResult) {
        val e = record.entry
        dao.append(CameraHistorySessionEntity(e.id, e.title, e.modelName, e.thumbnail, e.createdAt, e.updatedAt,
            e.pinnedAt, record.selectedId, record.usedStyles, record.context),
            CameraHistoryResultEntity(result.id, e.id, result.completedAt, result.payload))
    }
    override suspend fun pin(id: String, at: Long?) = dao.pin(id, at)
    override suspend fun select(id: String, candidateId: String) = dao.select(id, candidateId)
    override suspend fun styles(id: String, styles: String) = dao.styles(id, styles)
    override suspend fun delete(id: String) = dao.delete(id)
}
