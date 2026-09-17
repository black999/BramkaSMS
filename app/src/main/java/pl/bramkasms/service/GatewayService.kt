package pl.bramkasms.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import pl.bramkasms.MainActivity
import pl.bramkasms.SmsGatewayApp
import pl.bramkasms.data.SettingsEntity
import pl.bramkasms.network.NetworkAccess
import pl.bramkasms.server.GatewayServer

class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var queue: QueueProcessor
    private lateinit var server: GatewayServer

    override fun onCreate() {
        super.onCreate()
        val app = application as SmsGatewayApp
        val dao = app.database.dao()
        createChannel()
        GatewayRuntime.update(ServiceSnapshot(ServicePhase.STARTING))
        startForeground(NOTIFICATION_ID, notification("Bramka SMS uruchamia się", 0, 8080))
        scope.launch(Dispatchers.IO) {
            val settings = dao.settings() ?: SettingsEntity().also { dao.saveSettings(it) }
            GatewayRuntime.update(ServiceSnapshot(ServicePhase.STARTING, localAddress(this@GatewayService), settings.port))
            try {
                server = GatewayServer(this@GatewayService, dao, app.repository).also { it.start(settings.port) }
                queue = QueueProcessor(dao, app.repository, SmsSender(this@GatewayService)).also { it.start(scope) }
                dao.saveSettings(settings.copy(lastServiceError = null))
                GatewayRuntime.update(ServiceSnapshot(ServicePhase.RUNNING, localAddress(this@GatewayService), settings.port))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Bramka SMS działa", dao.queuedCountNow(), settings.port))
                app.repository.audit("SERVICE_STARTED", "port=${settings.port}")
            } catch (t: Throwable) {
                val error = (t.message ?: t.javaClass.simpleName).take(500)
                dao.saveSettings(settings.copy(lastServiceError = error))
                app.repository.audit("SERVICE_START_FAILED", "port=${settings.port}; error=$error")
                GatewayRuntime.update(ServiceSnapshot(ServicePhase.ERROR, localAddress(this@GatewayService), settings.port, error))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Błąd bramki SMS", 0, settings.port, error))
            }
        }
        scope.launch {
            dao.queuedCount().collectLatest { count ->
                val port = dao.settings()?.port ?: 8080
                if (GatewayRuntime.state.value.phase == ServicePhase.RUNNING)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Bramka SMS działa", count, port))
            }
        }
    }

    override fun onDestroy() {
        if (::queue.isInitialized) queue.stop()
        if (::server.isInitialized) server.stop()
        GatewayRuntime.update(ServiceSnapshot(ServicePhase.STOPPED))
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Bramka SMS", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(title: String, queued: Int, port: Int, error: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val address = localAddress(this)?.let { "$it:$port" } ?: "brak adresu Wi‑Fi"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(error ?: "Adres: $address • W kolejce: $queued")
            .setOngoing(true).setContentIntent(open).build()
    }

    companion object {
        const val CHANNEL = "gateway_service"
        const val NOTIFICATION_ID = 1001
        fun start(context: Context) = context.startForegroundService(Intent(context, GatewayService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, GatewayService::class.java)); GatewayRuntime.update(ServiceSnapshot(ServicePhase.STOPPED)) }
        fun localAddress(context: Context): String? = NetworkAccess.wifiIpv4Address(context)
    }
}
