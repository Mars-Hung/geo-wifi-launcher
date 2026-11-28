package tw.geowifi.launcher.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tw.geowifi.launcher.core.rule.Clause
import tw.geowifi.launcher.core.rule.LogicCodec
import tw.geowifi.launcher.core.rule.MemoryCodec
import tw.geowifi.launcher.core.rule.RuleMemory
import tw.geowifi.launcher.core.rule.RuleSpec
import tw.geowifi.launcher.core.schedule.ScheduleCodec
import tw.geowifi.launcher.core.schedule.WeekSchedule
import tw.geowifi.launcher.data.db.AppDatabase
import tw.geowifi.launcher.data.db.EventEntity
import tw.geowifi.launcher.data.db.RuleEntity

data class RuleRecord(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val clauses: List<Clause>,
    val schedule: WeekSchedule?,
    val cooldownMillis: Long,
    val targetPackage: String,
    val targetLabel: String,
    val memory: RuleMemory,
    val lastTriggeredAt: Long?,
) {
    fun toSpec(): RuleSpec = RuleSpec(id, enabled, clauses, schedule, cooldownMillis)
}

data class RuleDraft(
    val id: Long?,
    val name: String,
    val clauses: List<Clause>,
    val schedule: WeekSchedule?,
    val cooldownMillis: Long,
    val targetPackage: String,
    val targetLabel: String,
)

data class EventRecord(
    val id: Long,
    val ruleName: String,
    val summary: String,
    val detail: String,
    val success: Boolean,
    val createdAt: Long,
)

class RuleRepository(private val database: AppDatabase) {
    fun observeRules(): Flow<List<RuleRecord>> {
        return database.rules().observeAll().map { rows -> rows.map { it.toRecord() } }
    }

    fun observeEvents(): Flow<List<EventRecord>> {
        return database.events().observe().map { rows ->
            rows.map { EventRecord(it.id, it.ruleName, it.summary, it.detail, it.success, it.createdAt) }
        }
    }

    suspend fun enabled(): List<RuleRecord> = database.rules().enabled().map { it.toRecord() }

    suspend fun get(id: Long): RuleRecord? = database.rules().get(id)?.toRecord()

    suspend fun save(draft: RuleDraft) {
        val now = System.currentTimeMillis()
        val logic = LogicCodec.encode(draft.clauses, draft.cooldownMillis)
        val days = ScheduleCodec.encodeDays(draft.schedule?.days ?: emptySet())
        val ranges = ScheduleCodec.encodeRanges(draft.schedule?.ranges ?: emptyList())
        val scheduleEnabled = draft.schedule != null
        val existing = draft.id?.let { database.rules().get(it) }
        if (existing == null) {
            database.rules().insert(
                RuleEntity(
                    name = draft.name.trim(),
                    enabled = true,
                    logicText = logic,
                    scheduleEnabled = scheduleEnabled,
                    scheduleDays = days,
                    scheduleRanges = ranges,
                    targetPackage = draft.targetPackage,
                    targetLabel = draft.targetLabel,
                    memoryText = MemoryCodec.encode(RuleMemory()),
                    lastTriggeredAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return
        }
        val previous = LogicCodec.decode(existing.logicText)
        val sameLogic = previous.clauses == draft.clauses &&
            existing.scheduleEnabled == scheduleEnabled &&
            existing.scheduleDays == days &&
            existing.scheduleRanges == ranges
        database.rules().updateContent(
            id = existing.id,
            name = draft.name.trim(),
            logicText = logic,
            scheduleEnabled = scheduleEnabled,
            scheduleDays = days,
            scheduleRanges = ranges,
            targetPackage = draft.targetPackage,
            targetLabel = draft.targetLabel,
            memoryText = if (sameLogic) existing.memoryText else MemoryCodec.encode(RuleMemory()),
            updatedAt = now,
        )
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        database.rules().setEnabled(id, enabled, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) {
        database.rules().delete(id)
    }

    suspend fun saveMemory(id: Long, memory: RuleMemory, lastTriggeredAt: Long?) {
        database.rules().updateRuntime(id, MemoryCodec.encode(memory), lastTriggeredAt)
    }

    suspend fun log(ruleId: Long, ruleName: String, summary: String, detail: String, success: Boolean) {
        database.events().insert(
            EventEntity(
                ruleId = ruleId,
                ruleName = ruleName,
                summary = summary,
                detail = detail,
                success = success,
                createdAt = System.currentTimeMillis(),
            ),
        )
        database.events().trim()
    }

    suspend fun clearEvents() {
        database.events().clear()
    }
}

private fun RuleEntity.toRecord(): RuleRecord {
    val schedule = if (!scheduleEnabled) {
        null
    } else {
        WeekSchedule(ScheduleCodec.decodeDays(scheduleDays), ScheduleCodec.decodeRanges(scheduleRanges))
    }
    val memory = try {
        MemoryCodec.decode(memoryText)
    } catch (_: RuntimeException) {
        RuleMemory()
    }
    val stored = LogicCodec.decode(logicText)
    return RuleRecord(
        id = id,
        name = name,
        enabled = enabled,
        clauses = stored.clauses,
        schedule = schedule,
        cooldownMillis = stored.cooldownMillis,
        targetPackage = targetPackage,
        targetLabel = targetLabel,
        memory = memory,
        lastTriggeredAt = lastTriggeredAt,
    )
}
