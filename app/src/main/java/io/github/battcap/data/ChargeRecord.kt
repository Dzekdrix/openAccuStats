package io.github.battcap.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val confidence: String,
    /** Charge menée jusqu'au bout (statut FULL ou chute du courant à 100 %). Ajouté en v2. */
    @ColumnInfo(defaultValue = "0") val reachedFull: Boolean = false
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

@Database(entities = [ChargeRecord::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chargeRecords(): ChargeRecordDao

    companion object {
        /** v1 -> v2 : ajout de reachedFull. Les mesures existantes sont conservées (valeur 0). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_records ADD COLUMN reachedFull INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "battcap.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
