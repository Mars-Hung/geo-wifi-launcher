package tw.geowifi.launcher.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RuleEntity::class, EventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun events(): EventDao
}
