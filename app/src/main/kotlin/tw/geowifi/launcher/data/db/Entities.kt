package tw.geowifi.launcher.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val logicText: String,
    val scheduleEnabled: Boolean,
    val scheduleDays: String,
    val scheduleRanges: String,
    val targetPackage: String,
    val targetLabel: String,
    val memoryText: String,
    val lastTriggeredAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val ruleName: String,
    val summary: String,
    val detail: String,
    val success: Boolean,
    val createdAt: Long,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun enabled(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): RuleEntity?

    @Insert
    suspend fun insert(entity: RuleEntity): Long

    @Query("UPDATE rules SET name = :name, logicText = :logicText, scheduleEnabled = :scheduleEnabled, scheduleDays = :scheduleDays, scheduleRanges = :scheduleRanges, targetPackage = :targetPackage, targetLabel = :targetLabel, memoryText = :memoryText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateContent(
        id: Long,
        name: String,
        logicText: String,
        scheduleEnabled: Boolean,
        scheduleDays: String,
        scheduleRanges: String,
        targetPackage: String,
        targetLabel: String,
        memoryText: String,
        updatedAt: Long,
    )

    @Query("UPDATE rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE rules SET memoryText = :memoryText, lastTriggeredAt = :lastTriggeredAt WHERE id = :id")
    suspend fun updateRuntime(id: Long, memoryText: String, lastTriggeredAt: Long?)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY createdAt DESC LIMIT 200")
    fun observe(): Flow<List<EventEntity>>

    @Insert
    suspend fun insert(entity: EventEntity)

    @Query("DELETE FROM events")
    suspend fun clear()

    @Query("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY createdAt DESC LIMIT 200)")
    suspend fun trim()
}
