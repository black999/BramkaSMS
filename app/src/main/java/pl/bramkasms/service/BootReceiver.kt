package pl.bramkasms.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.bramkasms.SmsGatewayApp
import pl.bramkasms.data.SettingsEntity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val app = context.applicationContext as SmsGatewayApp
            try {
                val settings = app.database.dao().settings() ?: SettingsEntity()
                if (settings.startAfterBoot) GatewayService.start(context)
                app.repository.audit("BOOT_COMPLETED", "autostart=${settings.startAfterBoot}")
            } catch (t: Throwable) {
                val error = (t.message ?: t.javaClass.simpleName).take(500)
                val settings = app.database.dao().settings() ?: SettingsEntity()
                app.database.dao().saveSettings(settings.copy(lastServiceError = "Autostart: $error"))
                app.repository.audit("BOOT_START_FAILED", "error=$error")
                GatewayRuntime.update(ServiceSnapshot(ServicePhase.ERROR, port = settings.port, error = "Autostart: $error"))
            } finally { pending.finish() }
        }
    }
}
