package pl.bramkasms.core

import android.database.sqlite.SQLiteConstraintException
import pl.bramkasms.data.*
import java.util.UUID

class ValidationException(message: String) : IllegalArgumentException(message)
class DuplicateExternalIdException : IllegalStateException("externalId już istnieje")

class GatewayRepository(private val dao: GatewayDao) {
    suspend fun enqueue(
        recipientInput: String,
        content: String,
        externalId: String?,
        idempotencyKey: String?,
        removePolish: Boolean,
        source: MessageSource,
        apiKeyName: String?
    ): Pair<MessageEntity, Boolean> {
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null) dao.byIdempotencyKey(key)?.let { return it to false }
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
            if (key != null) dao.byIdempotencyKey(key)?.let { return it to false }
            throw e
        }
        audit("MESSAGE_QUEUED", "id=${message.id}; source=$source")
        return message to true
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
        if (message.status != MessageStatus.FAILED) throw IllegalStateException("Ponowić można tylko wiadomość błędną")
        dao.setStatus(id, MessageStatus.QUEUED, System.currentTimeMillis())
        audit("MESSAGE_REQUEUED", "id=$id")
        return true
    }

    suspend fun audit(event: String, details: String, remote: String? = null) =
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(), event, details.take(500), remote, System.currentTimeMillis()))
}
