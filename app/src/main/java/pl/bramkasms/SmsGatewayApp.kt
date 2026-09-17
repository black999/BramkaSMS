package pl.bramkasms

import android.app.Application
import pl.bramkasms.core.GatewayRepository
import pl.bramkasms.data.GatewayDatabase

class SmsGatewayApp : Application() {
    val database by lazy { GatewayDatabase.get(this) }
    val repository by lazy { GatewayRepository(database.dao()) }
}
