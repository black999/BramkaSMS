package pl.bramkasms.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageStatus { QUEUED, SENDING, SENT, DELIVERED, FAILED, UNKNOWN, CANCELLED }
enum class MessageSource { WEB, API }

@Entity(tableName = "messages", indices = [Index("externalId"), Index(value = ["idempotencyKey"], unique = true), Index("status")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val externalId: String?,
    val idempotencyKey: String?,
    val recipient: String,
    val originalContent: String,
    val sentContent: String,
    val source: MessageSource,
    val apiKeyName: String?,
    val status: MessageStatus,
    val parts: Int,
    val attemptCount: Int = 0,
    val automaticRetryCount: Int = 0,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null
)

@Entity(tableName = "message_attempts", indices = [Index("messageId")])
data class MessageAttemptEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val attemptNumber: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val result: String,
    val error: String? = null
)

@Entity(tableName = "api_keys", indices = [Index(value = ["hash"], unique = true)])
data class ApiKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prefix: String,
    val hash: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastUsedAt: Long? = null
)

@Entity(tableName = "admin_users", indices = [Index(value = ["username"], unique = true)])
data class AdminUserEntity(@PrimaryKey val id: String, val username: String, val passwordHash: String, val createdAt: Long)

@Entity(tableName = "sessions", indices = [Index(value = ["tokenHash"], unique = true)])
data class SessionEntity(@PrimaryKey val id: String, val userId: String, val tokenHash: String, val csrfToken: String, val expiresAt: Long)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val port: Int = 8080,
    val delayMs: Long = 3_000,
    val maxRetries: Int = 3,
    val removePolishByDefault: Boolean = true,
    val queuePaused: Boolean = false,
    val allowedSubnetPrefix: String = "",
    val sendingTimeoutMs: Long = 60_000,
    val startAfterBoot: Boolean = false,
    val lastServiceError: String? = null
)

@Entity(tableName = "audit_log", indices = [Index("createdAt")])
data class AuditLogEntity(@PrimaryKey val id: String, val event: String, val details: String, val remoteAddress: String? = null, val createdAt: Long)
