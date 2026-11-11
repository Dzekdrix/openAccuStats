package io.github.battcap.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Une charge terminée, conservée pour calculer une moyenne sur plusieurs mesures. */
@Entity(tableName = "charge_records")
data class ChargeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val endedAtEpochMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val bestMah: Double,
    val coulombMah: Double?,
    val chargeCounterMah: Double?,
    val energyWh: Double?,
    val durationS: Double,
    val maxTempC: Float,
    val plugged: Int,
    val confidence: String
)

@Dao
interface ChargeRecordDao {
    @Query("SELECT * FROM charge_records ORDER BY endedAtEpochMs DESC")
    fun observeAll(): Flow<List<ChargeRecord>>

    @Insert
    suspend fun insert(record: ChargeRecord): Long

    @Delete
    suspend fun delete(record: ChargeRecord)
}

@Database(entities = [ChargeRecord::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chargeRecords(): ChargeRecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "battcap.db")
                .build().also { instance = it }
        }
    }
}
