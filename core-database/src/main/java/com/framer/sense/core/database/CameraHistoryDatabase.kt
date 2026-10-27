package com.framer.sense.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "camera_history_sessions")
data class CameraHistorySessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelName: String,
    val thumbnail: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinnedAt: Long? = null,
    val selectedId: String?,
    val usedStyles: String,
    val context: String
)

@Entity(tableName = "camera_history_results",
    foreignKeys = [ForeignKey(entity = CameraHistorySessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class CameraHistoryResultEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val completedAt: Long,
    val payload: String
)

data class CameraHistoryWithResults(
    @Embedded val session: CameraHistorySessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId") val results: List<CameraHistoryResultEntity>
)

/** 列表不加载扫描快照或每张完整提示词。 */
data class CameraHistorySummaryRow(val id: String, val title: String, val modelName: String, val thumbnail: String,
    val createdAt: Long, val updatedAt: Long, val pinnedAt: Long?)

@Dao
abstract class CameraHistoryDao {
    @Query("SELECT id, title, modelName, thumbnail, createdAt, updatedAt, pinnedAt FROM camera_history_sessions ORDER BY pinnedAt IS NULL, pinnedAt DESC, updatedAt DESC, id")
    abstract fun observe(): Flow<List<CameraHistorySummaryRow>>

    @Transaction
    @Query("SELECT * FROM camera_history_sessions WHERE id = :id")
    abstract suspend fun load(id: String): CameraHistoryWithResults?

    @Query("SELECT * FROM camera_history_sessions WHERE id = :id")
    abstract suspend fun session(id: String): CameraHistorySessionEntity?
    @Query("SELECT usedStyles FROM camera_history_sessions WHERE id = :id")
    abstract suspend fun usedStyles(id: String): String?

    @Upsert protected abstract suspend fun upsert(session: CameraHistorySessionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insert(result: CameraHistoryResultEntity): Long
    @Query("SELECT EXISTS(SELECT 1 FROM camera_history_results WHERE id = :id)")
    protected abstract suspend fun containsResult(id: String): Boolean

    /** 首图不变，置顶不丢；重复回调不改变标题或最近时间。 */
    @Transaction
    open suspend fun append(session: CameraHistorySessionEntity, result: CameraHistoryResultEntity) {
        if (containsResult(result.id)) return
        val old = this.session(session.id)
        if (old == null) upsert(session)
        if (insert(result) == -1L) return
        if (old != null && result.completedAt >= old.updatedAt) upsert(session.copy(createdAt = old.createdAt,
            thumbnail = old.thumbnail, pinnedAt = old.pinnedAt, selectedId = old.selectedId ?: session.selectedId))
    }

    @Query("UPDATE camera_history_sessions SET pinnedAt = :at WHERE id = :id")
    abstract suspend fun pin(id: String, at: Long?)

    @Query("UPDATE camera_history_sessions SET selectedId = :candidateId WHERE id = :id")
    abstract suspend fun select(id: String, candidateId: String)

    @Query("UPDATE camera_history_sessions SET usedStyles = :styles WHERE id = :id")
    abstract suspend fun styles(id: String, styles: String)

    @Query("DELETE FROM camera_history_sessions WHERE id = :id")
    abstract suspend fun delete(id: String)
}

/** 与模板 MyModel 数据库隔离，历史图片和数据库可独立排除云备份。 */
@Database(entities = [CameraHistorySessionEntity::class, CameraHistoryResultEntity::class], version = 1, exportSchema = true)
abstract class CameraHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): CameraHistoryDao
}
