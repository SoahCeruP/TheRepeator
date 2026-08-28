package com.therepeator.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TheRepeatorRequest::class, BrowserHistoryItem::class, IntruderResult::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun requestDao(): TheRepeatorRequestDao
    abstract fun intruderResultDao(): IntruderResultDao
    abstract fun browserHistoryDao(): BrowserHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "therepeator_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
