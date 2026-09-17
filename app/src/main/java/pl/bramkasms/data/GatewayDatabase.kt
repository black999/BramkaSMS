package pl.bramkasms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromStatus(value: MessageStatus) = value.name
    @TypeConverter fun toStatus(value: String) = MessageStatus.valueOf(value)
    @TypeConverter fun fromSource(value: MessageSource) = value.name
    @TypeConverter fun toSource(value: String) = MessageSource.valueOf(value)
}

@Database(entities = [MessageEntity::class, MessageAttemptEntity::class, ApiKeyEntity::class, AdminUserEntity::class, SessionEntity::class, SettingsEntity::class, AuditLogEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun dao(): GatewayDao
    companion object {
        @Volatile private var instance: GatewayDatabase? = null
        fun get(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GatewayDatabase::class.java, "bramka-sms.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN automaticRetryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN sendingTimeoutMs INTEGER NOT NULL DEFAULT 60000")
                db.execSQL("ALTER TABLE settings ADD COLUMN startAfterBoot INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN lastServiceError TEXT")
                // Starych hashy BCrypt nie można przekształcić do SHA-256 bez pełnego klucza.
                db.execSQL("DELETE FROM api_keys")
            }
        }
    }
}
