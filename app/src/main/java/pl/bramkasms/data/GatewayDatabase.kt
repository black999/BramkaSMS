package pl.bramkasms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromStatus(value: MessageStatus) = value.name
    @TypeConverter fun toStatus(value: String) = MessageStatus.valueOf(value)
    @TypeConverter fun fromSource(value: MessageSource) = value.name
    @TypeConverter fun toSource(value: String) = MessageSource.valueOf(value)
}

@Database(entities = [MessageEntity::class, MessageAttemptEntity::class, ApiKeyEntity::class, AdminUserEntity::class, SessionEntity::class, SettingsEntity::class, AuditLogEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun dao(): GatewayDao
    companion object {
        @Volatile private var instance: GatewayDatabase? = null
        fun get(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GatewayDatabase::class.java, "bramka-sms.db").build().also { instance = it }
        }
    }
}
