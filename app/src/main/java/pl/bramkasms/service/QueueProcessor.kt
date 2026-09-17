package pl.bramkasms.service

import kotlinx.coroutines.*
import pl.bramkasms.core.GatewayRepository
import pl.bramkasms.data.*
import java.util.UUID

class QueueProcessor(private val dao: GatewayDao, private val repository: GatewayRepository, private val sender: SmsSender) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val recovered = dao.recoverSending(System.currentTimeMillis(), "Odzyskano po przerwanym działaniu; stan wysyłki wymagał ponowienia")
            if (recovered > 0) repository.audit("QUEUE_RECOVERED", "messages=$recovered")
            while (isActive) {
                val settings = dao.settings() ?: SettingsEntity().also { dao.saveSettings(it) }
                val message = if (!settings.queuePaused) dao.firstByStatus(MessageStatus.QUEUED) else null
                if (message == null) { delay(1_000); continue }
                val readiness = sender.readinessError()
                if (readiness != null) { delay(5_000); continue }
                if (dao.claim(message.id, System.currentTimeMillis()) != 1) continue
                val attemptNo = message.attemptCount + 1
                val attemptId = UUID.randomUUID().toString()
                val started = System.currentTimeMillis()
                repository.audit("SMS_SEND_STARTED", "id=${message.id}; attempt=$attemptNo")
                val result = sender.send(message.recipient, message.sentContent)
                val finished = System.currentTimeMillis()
                if (result.isSuccess) {
                    dao.markSent(message.id, finished)
                    dao.insertAttempt(MessageAttemptEntity(attemptId, message.id, attemptNo, started, finished, "SENT"))
                    repository.audit("SMS_SENT", "id=${message.id}; attempt=$attemptNo")
                } else {
                    val error = result.exceptionOrNull()?.message?.take(300) ?: "Nieznany błąd"
                    dao.insertAttempt(MessageAttemptEntity(attemptId, message.id, attemptNo, started, finished, "FAILED", error))
                    if (attemptNo < settings.maxRetries) dao.setStatus(message.id, MessageStatus.QUEUED, finished, error)
                    else dao.markFailed(message.id, finished, error)
                    repository.audit("SMS_SEND_FAILED", "id=${message.id}; attempt=$attemptNo; error=$error")
                }
                delay(settings.delayMs.coerceAtLeast(0))
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
