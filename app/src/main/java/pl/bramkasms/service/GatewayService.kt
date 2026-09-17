package pl.bramkasms.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import pl.bramkasms.MainActivity
import pl.bramkasms.R
import pl.bramkasms.SmsGatewayApp
import pl.bramkasms.data.SettingsEntity
import pl.bramkasms.server.GatewayServer
import java.net.Inet4Address
import java.net.NetworkInterface

class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var queue: QueueProcessor
    private lateinit var server: GatewayServer

    override fun onCreate() {
        super.onCreate()
        val app = application as SmsGatewayApp
        val dao = app.database.dao()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(0, 8080))
        scope.launch(Dispatchers.IO) {
            val settings = dao.settings() ?: SettingsEntity().also { dao.saveSettings(it) }
            server = GatewayServer(this@GatewayService, dao, app.repository).also { it.start(settings.port) }
            app.repository.audit("SERVICE_STARTED", "port=${settings.port}")
        }
        queue = QueueProcessor(dao, app.repository, SmsSender(this)).also { it.start(scope) }
        scope.launch {
            dao.queuedCount().collectLatest { count ->
                val port = dao.settings()?.port ?: 8080
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(count, port))
            }
        }
    }

    override fun onDestroy() {
        queue.stop()
        if (::server.isInitialized) server.stop()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Bramka SMS", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(queued: Int, port: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val address = localAddress()?.let { "$it:$port" } ?: "brak Wi‑Fi"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Bramka SMS działa")
            .setContentText("Adres: $address • W kolejce: $queued")
            .setOngoing(true).setContentIntent(open).build()
    }

    companion object {
        const val CHANNEL = "gateway_service"
        const val NOTIFICATION_ID = 1001
        fun start(context: Context) = context.startForegroundService(Intent(context, GatewayService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, GatewayService::class.java))
        fun localAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
}
