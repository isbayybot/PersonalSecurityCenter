package com.byu.securitycenter.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val type: String,      // CHECK, WARNING, CONFIG
    val message: String
)

@Dao
interface LogDao {
    @Insert suspend fun insert(e: LogEntry)
    @Query("SELECT * FROM log_entries ORDER BY time DESC LIMIT 500")
    fun observeAll(): Flow<List<LogEntry>>
    @Query("SELECT * FROM log_entries ORDER BY time DESC LIMIT 3")
    fun observeRecent(): Flow<List<LogEntry>>
    @Query("DELETE FROM log_entries") suspend fun clear()
}

@Database(entities = [LogEntry::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "psc.db")
                .build().also { inst = it }
        }
    }
}
