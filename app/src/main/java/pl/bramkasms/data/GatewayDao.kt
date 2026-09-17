package pl.bramkasms.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMessage(message: MessageEntity)
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE idempotencyKey = :key LIMIT 1") suspend fun byIdempotencyKey(key: String): MessageEntity?
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit OFFSET :offset") suspend fun messages(limit: Int, offset: Int): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE status = :status ORDER BY createdAt ASC LIMIT 1") suspend fun firstByStatus(status: MessageStatus): MessageEntity?
    @Query("UPDATE messages SET status = :status, updatedAt = :now, error = :error WHERE id = :id") suspend fun setStatus(id: String, status: MessageStatus, now: Long, error: String? = null): Int
    @Query("UPDATE messages SET status = 'SENDING', attemptCount = attemptCount + 1, updatedAt = :now WHERE id = :id AND status = 'QUEUED'") suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE messages SET status = 'SENT', sentAt = :now, updatedAt = :now, error = NULL WHERE id = :id") suspend fun markSent(id: String, now: Long)
    @Query("UPDATE messages SET status = 'FAILED', updatedAt = :now, error = :error WHERE id = :id") suspend fun markFailed(id: String, now: Long, error: String)
    @Query("UPDATE messages SET status = 'QUEUED', updatedAt = :now, error = :reason WHERE status = 'SENDING'") suspend fun recoverSending(now: Long, reason: String): Int
    @Query("SELECT COUNT(*) FROM messages WHERE status = 'QUEUED'") fun queuedCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM messages WHERE status = 'QUEUED'") suspend fun queuedCountNow(): Int
    @Query("SELECT COUNT(*) FROM messages WHERE status = :status") suspend fun count(status: MessageStatus): Int

    @Insert suspend fun insertAttempt(attempt: MessageAttemptEntity)
    @Insert suspend fun insertAudit(event: AuditLogEntity)

    @Query("SELECT * FROM api_keys WHERE enabled = 1") suspend fun activeApiKeys(): List<ApiKeyEntity>
    @Query("SELECT * FROM api_keys ORDER BY createdAt DESC") suspend fun apiKeys(): List<ApiKeyEntity>
    @Insert suspend fun insertApiKey(key: ApiKeyEntity)
    @Query("UPDATE api_keys SET lastUsedAt = :now WHERE id = :id") suspend fun touchApiKey(id: String, now: Long)
    @Query("UPDATE api_keys SET enabled = :enabled WHERE id = :id") suspend fun setApiKeyEnabled(id: String, enabled: Boolean): Int
    @Query("DELETE FROM api_keys WHERE id = :id") suspend fun deleteApiKey(id: String): Int

    @Query("SELECT * FROM admin_users WHERE username = :username LIMIT 1") suspend fun user(username: String): AdminUserEntity?
    @Query("SELECT COUNT(*) FROM admin_users") suspend fun userCount(): Int
    @Insert suspend fun insertUser(user: AdminUserEntity)

    @Insert suspend fun insertSession(session: SessionEntity)
    @Query("SELECT * FROM sessions WHERE tokenHash = :hash AND expiresAt > :now LIMIT 1") suspend fun session(hash: String, now: Long): SessionEntity?
    @Query("DELETE FROM sessions WHERE tokenHash = :hash") suspend fun deleteSession(hash: String)
    @Query("DELETE FROM sessions WHERE expiresAt <= :now") suspend fun deleteExpiredSessions(now: Long)
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1") suspend fun sessionById(id: String): SessionEntity?

    @Query("SELECT * FROM settings WHERE id = 1") fun settingsFlow(): Flow<SettingsEntity?>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(settings: SettingsEntity)

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT :limit") suspend fun auditLog(limit: Int): List<AuditLogEntity>
}
