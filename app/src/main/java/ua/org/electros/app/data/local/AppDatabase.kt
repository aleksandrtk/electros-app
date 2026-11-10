package ua.org.electros.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity

@Database(
    entities = [ChargeSessionEntity::class, ChargeSliceEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chargeDao(): ChargeDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "electros_charge.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}

