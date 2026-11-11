package io.github.dzekdrix.openaccustats.data

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
import io.github.dzekdrix.openaccustats.battery.CapacityAverage
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
    @ColumnInfo(defaultValue = "0") val reachedFull: Boolean = false,
    /** Début de la charge (branchement ou lancement du suivi). Ajouté en v3 : null pour les charges plus anciennes. */
    val startedAtEpochMs: Long? = null,
    /** Cycle de décharge plutôt que de charge. Ajouté en v4 : false pour les charges existantes. */
    @ColumnInfo(defaultValue = "0") val isDischarge: Boolean = false
)

// Propriétés calculées en extensions : Room ne les confond pas avec des colonnes.
// En valeur absolue : pour une décharge, startLevel > endLevel.
val ChargeRecord.deltaSocPct get() = kotlin.math.abs(endLevel - startLevel)

/**
 * Charge électrique réellement entrée (ou sortie, pour une décharge) pendant ce cycle (mAh).
 * bestMah est la capacité totale extrapolée à 100 % : on la ramène à la variation de niveau
 * mesurée, ce qui redonne exactement la charge mesurée (bestMah = charge mesurée ÷ Δniveau).
 */
val ChargeRecord.chargeInMah get() = bestMah * deltaSocPct / 100.0

/** Charge trop courte pour entrer dans la moyenne (affichée différemment). */
val ChargeRecord.isPartial get() = CapacityAverage.isPartial(deltaSocPct)

@Dao
interface ChargeRecordDao {
    @Query("SELECT * FROM charge_records ORDER BY endedAtEpochMs DESC")
    fun observeAll(): Flow<List<ChargeRecord>>

    @Insert
    suspend fun insert(record: ChargeRecord): Long

    @Delete
    suspend fun delete(record: ChargeRecord)
}

@Database(entities = [ChargeRecord::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chargeRecords(): ChargeRecordDao

    companion object {
        /** v1 -> v2 : ajout de reachedFull. Les mesures existantes sont conservées (valeur 0). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_records ADD COLUMN reachedFull INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3 : ajout de la date de début de charge (inconnue pour les charges existantes). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_records ADD COLUMN startedAtEpochMs INTEGER")
            }
        }

        /** v3 -> v4 : ajout de isDischarge. Les mesures existantes sont des charges (valeur 0). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_records ADD COLUMN isDischarge INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "openaccustats.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
