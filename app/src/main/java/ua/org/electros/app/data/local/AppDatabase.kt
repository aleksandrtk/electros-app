package ua.org.electros.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity

@Database(
    entities = [ChargeSessionEntity::class, ChargeSliceEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chargeDao(): ChargeDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_sessions ADD COLUMN targetDurationMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE charge_sessions ADD COLUMN accumulatedChargingMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE charge_sessions ADD COLUMN lastTickAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "electros_charge.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

