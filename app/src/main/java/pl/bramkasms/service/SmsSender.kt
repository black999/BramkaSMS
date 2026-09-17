package pl.bramkasms.service

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class SmsSender(private val context: Context) {
    fun readinessError(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "Brak uprawnienia SEND_SMS"
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return "Brak połączenia Wi‑Fi"
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony.simState != TelephonyManager.SIM_STATE_READY) return "Karta SIM jest niedostępna"
        return null
    }

    suspend fun send(recipient: String, content: String): Result<Unit> = suspendCancellableCoroutine { continuation ->
        readinessError()?.let { continuation.resume(Result.failure(IllegalStateException(it))); return@suspendCancellableCoroutine }
        val manager = context.getSystemService(SmsManager::class.java)
        val parts = manager.divideMessage(content)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val remaining = AtomicInteger(parts.size)
        val failures = AtomicInteger(0)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (resultCode != android.app.Activity.RESULT_OK) failures.incrementAndGet()
                if (remaining.decrementAndGet() == 0) {
                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) continuation.resume(
                        if (failures.get() == 0) Result.success(Unit)
                        else Result.failure(IllegalStateException("Nie wysłano ${failures.get()} części SMS"))
                    )
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        try {
            val intents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                val intent = Intent(action).setPackage(context.packageName).putExtra("part", index)
                intents += PendingIntent.getBroadcast(context, action.hashCode() + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            manager.sendMultipartTextMessage(recipient, null, parts, intents, null)
        } catch (t: Throwable) {
            runCatching { context.unregisterReceiver(receiver) }
            if (continuation.isActive) continuation.resume(Result.failure(t))
        }
    }
}
