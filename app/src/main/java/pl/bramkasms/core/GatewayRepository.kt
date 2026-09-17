package pl.bramkasms.core

import android.database.sqlite.SQLiteConstraintException
import pl.bramkasms.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ValidationException(message: String) : IllegalArgumentException(message)
class DuplicateExternalIdException : IllegalStateException("externalId już istnieje")

class GatewayRepository(private val dao: GatewayDao) {
    private val enqueueMutex = Mutex()
    suspend fun enqueue(
        recipientInput: String,
        content: String,
        externalId: String?,
        idempotencyKey: String?,
        removePolish: Boolean,
        source: MessageSource,
        apiKeyName: String?
    ): Pair<MessageEntity, Boolean> = enqueueMutex.withLock {
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null) dao.byIdempotencyKey(key)?.let { return@withLock it to false }
        val recipient = PhoneNumber.normalize(recipientInput) ?: throw ValidationException("Nieprawidłowy numer telefonu")
        val original = content.trim()
        if (original.isEmpty()) throw ValidationException("Treść nie może być pusta")
        if (original.length > 1_000) throw ValidationException("Treść nie może przekraczać 1000 znaków")
        val sent = if (removePolish) SmsText.withoutPolish(original) else original
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = UUID.randomUUID().toString(), externalId = externalId?.trim()?.takeIf(String::isNotEmpty),
            idempotencyKey = key, recipient = recipient, originalContent = original, sentContent = sent,
            source = source, apiKeyName = apiKeyName, status = MessageStatus.QUEUED,
            parts = SmsText.parts(sent), createdAt = now, updatedAt = now
        )
        try {
            dao.insertMessage(message)
        } catch (e: SQLiteConstraintException) {
            if (key != null) dao.byIdempotencyKey(key)?.let { return@withLock it to false }
            throw e
        }
        audit("MESSAGE_QUEUED", "id=${message.id}; source=$source")
        message to true
    }

    suspend fun cancel(id: String): Boolean {
        val message = dao.message(id) ?: return false
        if (message.status != MessageStatus.QUEUED) throw IllegalStateException("Anulować można tylko wiadomość oczekującą")
        dao.setStatus(id, MessageStatus.CANCELLED, System.currentTimeMillis())
        audit("MESSAGE_CANCELLED", "id=$id")
        return true
    }

    suspend fun retry(id: String): Boolean {
        val message = dao.message(id) ?: return false
        if (message.status !in setOf(MessageStatus.FAILED, MessageStatus.UNKNOWN)) throw IllegalStateException("Ponowić można tylko wiadomość błędną lub o nieznanym wyniku")
        dao.manualRetry(id, System.currentTimeMillis(), if (message.status == MessageStatus.UNKNOWN) "Ręczne ponowienie po nieznanym wyniku" else null)
        audit("MESSAGE_REQUEUED", "id=$id")
        return true
    }

    suspend fun resolveUnknownAsSent(id: String): Boolean {
        val message = dao.message(id) ?: return false
        if (message.status != MessageStatus.UNKNOWN) throw IllegalStateException("Tylko wiadomość o nieznanym wyniku można oznaczyć jako wysłaną")
        dao.resolveUnknownAsSent(id, System.currentTimeMillis(), "Ręcznie oznaczono jako wysłaną")
        audit("MESSAGE_RESOLVED_AS_SENT", "id=$id")
        return true
    }

    suspend fun audit(event: String, details: String, remote: String? = null) =
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(), event, details.take(500), remote, System.currentTimeMillis()))
}
