package pl.bramkasms.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ServicePhase { STOPPED, STARTING, RUNNING, ERROR }
data class ServiceSnapshot(val phase: ServicePhase = ServicePhase.STOPPED, val address: String? = null, val port: Int = 8080, val error: String? = null)

object GatewayRuntime {
    private val mutable = MutableStateFlow(ServiceSnapshot())
    val state = mutable.asStateFlow()
    fun update(snapshot: ServiceSnapshot) { mutable.value = snapshot }
}
