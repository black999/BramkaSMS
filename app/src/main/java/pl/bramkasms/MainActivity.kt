package pl.bramkasms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.bramkasms.data.AdminUserEntity
import pl.bramkasms.data.SettingsEntity
import pl.bramkasms.security.Security
import pl.bramkasms.service.GatewayService
import pl.bramkasms.service.GatewayRuntime
import pl.bramkasms.service.ServicePhase
import pl.bramkasms.service.SmsSender
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SmsGatewayApp
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006C4C), secondary = Color(0xFF4D6359))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F7F5)) { GatewayScreen(app) }
            }
        }
    }
}

@Composable private fun GatewayScreen(app: SmsGatewayApp) {
    val scope = rememberCoroutineScope()
    var hasAdmin by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val service by GatewayRuntime.state.collectAsStateWithLifecycle()
    val settings by app.database.dao().settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    val queued by app.database.dao().queuedCount().collectAsStateWithLifecycle(initialValue = 0)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.SEND_SMS] == true) GatewayService.start(app) else error = "Uprawnienie do wysyłania SMS jest wymagane."
    }
    LaunchedEffect(Unit) { hasAdmin = withContext(Dispatchers.IO) { app.database.dao().userCount() > 0 } }
    if (hasAdmin == false) {
        SetupAdmin(onCreate = { username, password ->
            scope.launch(Dispatchers.IO) {
                if (username.length < 3 || password.length < 10) { error = "Login: min. 3 znaki, hasło: min. 10 znaków"; return@launch }
                app.database.dao().insertUser(AdminUserEntity(UUID.randomUUID().toString(), username.trim(), Security.passwordHash(password), System.currentTimeMillis()))
                app.repository.audit("ADMIN_CREATED", "username=${username.trim()}")
                hasAdmin = true
            }
        }, error = error)
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val displayPort = if (service.phase == ServicePhase.STOPPED) settings?.port ?: service.port else service.port
        Text("Bramka SMS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val stateLabel = when (service.phase) { ServicePhase.RUNNING -> "USŁUGA DZIAŁA"; ServicePhase.STARTING -> "URUCHAMIANIE"; ServicePhase.ERROR -> "BŁĄD URUCHOMIENIA"; ServicePhase.STOPPED -> "USŁUGA ZATRZYMANA" }
        Text(stateLabel, color = if (service.phase == ServicePhase.RUNNING) Color(0xFF087F5B) else Color(0xFFB42318), fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Info("Adres panelu", "http://${service.address ?: GatewayService.localAddress(app) ?: "brak-Wi-Fi"}:$displayPort")
                Info("Karta SIM", SmsSender(app).readinessError() ?: "Gotowa")
                Info("W kolejce", queued.toString())
                (service.error ?: settings?.lastServiceError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val requested = buildList { add(Manifest.permission.SEND_SMS); add(Manifest.permission.READ_PHONE_STATE); if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
                permissions.launch(requested.toTypedArray())
            }, enabled = service.phase == ServicePhase.STOPPED || service.phase == ServicePhase.ERROR) { Text("Uruchom") }
            OutlinedButton(onClick = { GatewayService.stop(app) }, enabled = service.phase == ServicePhase.RUNNING || service.phase == ServicePhase.STARTING) { Text("Zatrzymaj") }
        }
        OutlinedButton(onClick = { app.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Ustawienia baterii") }
        Text("Panel działa wyłącznie w sieci lokalnej. Ustaw rezerwację DHCP dla telefonu.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SetupAdmin(onCreate: (String, String) -> Unit, error: String?) {
    var username by remember { mutableStateOf("admin") }; var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Pierwsze uruchomienie", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Utwórz lokalne konto administratora.", modifier = Modifier.padding(vertical = 12.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Nazwa użytkownika") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Hasło (min. 10 znaków)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Button({ onCreate(username, password) }, Modifier.padding(top = 16.dp).fillMaxWidth()) { Text("Utwórz konto") }
    }
}
@Composable private fun Info(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label); Text(value, fontWeight = FontWeight.SemiBold) }
