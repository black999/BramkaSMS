package pl.bramkasms.service

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

sealed interface SendOutcome {
    data object Success : SendOutcome
    data class Failure(val message: String) : SendOutcome
    data class Unknown(val message: String) : SendOutcome
}

interface SmsTransport {
    fun readinessError(): String?
    suspend fun send(recipient: String, content: String, timeoutMs: Long): SendOutcome
}

internal suspend fun awaitPartResults(
    expectedParts: Int,
    timeoutMs: Long,
    start: ((Int) -> Unit) -> Unit,
    cleanup: () -> Unit
): SendOutcome {
    val cleaned = AtomicBoolean(false)
    fun cleanupOnce() { if (cleaned.compareAndSet(false, true)) cleanup() }
    val result = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(expectedParts)
            val errors = mutableListOf<String>()
            continuation.invokeOnCancellation { cleanupOnce() }
            try {
                start { resultCode ->
                    if (resultCode != android.app.Activity.RESULT_OK) synchronized(errors) { errors += smsResultMessage(resultCode) }
                    if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                        cleanupOnce()
                        val snapshot = synchronized(errors) { errors.toList() }
                        continuation.resume(if (snapshot.isEmpty()) SendOutcome.Success else SendOutcome.Failure(snapshot.distinct().joinToString("; ")))
                    }
                }
            } catch (t: Throwable) {
                cleanupOnce()
                if (continuation.isActive) continuation.resume(SendOutcome.Failure(t.message ?: t.javaClass.simpleName))
            }
        }
    }
    return result ?: SendOutcome.Unknown("Przekroczono limit ${timeoutMs / 1000} s oczekiwania na wynik wysyłki")
}

internal fun smsResultMessage(code: Int): String = when (code) {
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Ogólny błąd modemu lub sieci"
    SmsManager.RESULT_ERROR_RADIO_OFF -> "Moduł radiowy jest wyłączony"
    SmsManager.RESULT_ERROR_NULL_PDU -> "Operator zwrócił pusty PDU"
    SmsManager.RESULT_ERROR_NO_SERVICE -> "Brak usługi sieci komórkowej"
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Systemowy limit wysyłania SMS został przekroczony"
    SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "Numer zablokowany przez listę FDN karty SIM"
    SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "Wysyłanie na krótki numer jest niedozwolone"
    SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Wysyłanie na ten krótki numer jest trwale zablokowane"
    else -> "Błąd wysyłania SmsManager (kod $code)"
}

class SmsSender(private val context: Context) : SmsTransport {
    override fun readinessError(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "Brak uprawnienia SEND_SMS"
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) return "Urządzenie nie obsługuje wiadomości SMS"
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony.simState != TelephonyManager.SIM_STATE_READY) return "Karta SIM jest niedostępna"
        return null
    }

    override suspend fun send(recipient: String, content: String, timeoutMs: Long): SendOutcome {
        readinessError()?.let { return SendOutcome.Failure(it) }
        val manager = context.getSystemService(SmsManager::class.java)
        val parts = manager.divideMessage(content)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        var callback: ((Int) -> Unit)? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                callback?.invoke(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return awaitPartResults(parts.size, timeoutMs, start = { onResult ->
            callback = onResult
            val intents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                val intent = Intent(action).setPackage(context.packageName).putExtra("part", index)
                intents += PendingIntent.getBroadcast(context, action.hashCode() + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            manager.sendMultipartTextMessage(recipient, null, parts, intents, null)
        }, cleanup = {
            callback = null
            runCatching { context.unregisterReceiver(receiver) }
        })
        }
}
