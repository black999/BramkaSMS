package pl.bramkasms.support

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.bramkasms.data.*
import java.util.concurrent.ConcurrentHashMap

class FakeGatewayDao : GatewayDao {
    private val messageRows = ConcurrentHashMap<String, MessageEntity>()
    private val keys = ConcurrentHashMap<String, ApiKeyEntity>()
    private val users = ConcurrentHashMap<String, AdminUserEntity>()
    private val sessions = ConcurrentHashMap<String, SessionEntity>()
    private val attempts = mutableListOf<MessageAttemptEntity>()
    private val audits = mutableListOf<AuditLogEntity>()
    private val queued = MutableStateFlow(0)
    private val settingsState = MutableStateFlow<SettingsEntity?>(null)

    private fun refresh() { queued.value = messageRows.values.count { it.status == MessageStatus.QUEUED } }
    override suspend fun insertMessage(message: MessageEntity) { messageRows[message.id] = message; refresh() }
    override suspend fun message(id: String) = messageRows[id]
    override suspend fun byIdempotencyKey(key: String) = messageRows.values.firstOrNull { it.idempotencyKey == key }
    override suspend fun messages(limit: Int, offset: Int) = messageRows.values.sortedByDescending { it.createdAt }.drop(offset).take(limit)
    override suspend fun firstByStatus(status: MessageStatus) = messageRows.values.filter { it.status == status }.minByOrNull { it.createdAt }
    override suspend fun setStatus(id: String, status: MessageStatus, now: Long, error: String?): Int = update(id) { it.copy(status = status, updatedAt = now, error = error) }
    override suspend fun claim(id: String, now: Long): Int = if (messageRows[id]?.status != MessageStatus.QUEUED) 0 else update(id) { it.copy(status = MessageStatus.SENDING, attemptCount = it.attemptCount + 1, automaticRetryCount = it.automaticRetryCount + 1, updatedAt = now) }
    override suspend fun markSent(id: String, now: Long) { update(id) { it.copy(status = MessageStatus.SENT, sentAt = now, updatedAt = now, error = null) } }
    override suspend fun markFailed(id: String, now: Long, error: String) { update(id) { it.copy(status = MessageStatus.FAILED, updatedAt = now, error = error) } }
    override suspend fun recoverSending(now: Long, reason: String): Int {
        val ids = messageRows.values.filter { it.status == MessageStatus.SENDING }.map { it.id }
        ids.forEach { update(it) { row -> row.copy(status = MessageStatus.UNKNOWN, updatedAt = now, error = reason) } }
        return ids.size
    }
    override suspend fun manualRetry(id: String, now: Long, reason: String?): Int = if (messageRows[id]?.status !in setOf(MessageStatus.FAILED, MessageStatus.UNKNOWN)) 0 else update(id) { it.copy(status = MessageStatus.QUEUED, automaticRetryCount = 0, updatedAt = now, error = reason) }
    override suspend fun resolveUnknownAsSent(id: String, now: Long, reason: String): Int = if (messageRows[id]?.status != MessageStatus.UNKNOWN) 0 else update(id) { it.copy(status = MessageStatus.SENT, sentAt = now, updatedAt = now, error = reason) }
    override fun queuedCount(): Flow<Int> = queued
    override suspend fun queuedCountNow() = queued.value
    override suspend fun count(status: MessageStatus) = messageRows.values.count { it.status == status }
    override suspend fun insertAttempt(attempt: MessageAttemptEntity) { synchronized(attempts) { attempts += attempt } }
    override suspend fun insertAudit(event: AuditLogEntity) { synchronized(audits) { audits += event } }
    override suspend fun activeApiKeys() = keys.values.filter { it.enabled }
    override suspend fun activeApiKeyByHash(hash: String) = keys.values.firstOrNull { it.enabled && it.hash == hash }
    override suspend fun apiKeys() = keys.values.sortedByDescending { it.createdAt }
    override suspend fun insertApiKey(key: ApiKeyEntity) { keys[key.id] = key }
    override suspend fun touchApiKey(id: String, now: Long) { keys.computeIfPresent(id) { _, v -> v.copy(lastUsedAt = now) } }
    override suspend fun setApiKeyEnabled(id: String, enabled: Boolean): Int = if (!keys.containsKey(id)) 0 else { keys.computeIfPresent(id) { _, v -> v.copy(enabled = enabled) }; 1 }
    override suspend fun deleteApiKey(id: String) = if (keys.remove(id) != null) 1 else 0
    override suspend fun user(username: String) = users.values.firstOrNull { it.username == username }
    override suspend fun userCount() = users.size
    override suspend fun insertUser(user: AdminUserEntity) { users[user.id] = user }
    override suspend fun insertSession(session: SessionEntity) { sessions[session.id] = session }
    override suspend fun session(hash: String, now: Long) = sessions.values.firstOrNull { it.tokenHash == hash && it.expiresAt > now }
    override suspend fun deleteSession(hash: String) { sessions.entries.removeIf { it.value.tokenHash == hash } }
    override suspend fun deleteExpiredSessions(now: Long) { sessions.entries.removeIf { it.value.expiresAt <= now } }
    override suspend fun sessionById(id: String) = sessions[id]
    override fun settingsFlow(): Flow<SettingsEntity?> = settingsState
    override suspend fun settings() = settingsState.value
    override suspend fun saveSettings(settings: SettingsEntity) { settingsState.value = settings }
    override suspend fun auditLog(limit: Int) = synchronized(audits) { audits.sortedByDescending { it.createdAt }.take(limit) }

    private fun update(id: String, transform: (MessageEntity) -> MessageEntity): Int {
        var changed = false
        messageRows.computeIfPresent(id) { _, value -> changed = true; transform(value) }
        refresh()
        return if (changed) 1 else 0
    }
}
