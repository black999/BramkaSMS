package pl.bramkasms.server

import android.content.Context
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import pl.bramkasms.core.*
import pl.bramkasms.data.*
import pl.bramkasms.security.Security
import pl.bramkasms.network.CidrBlock
import pl.bramkasms.network.NetworkAccess
import pl.bramkasms.service.SmsSender
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GatewayServer(private val context: Context, private val dao: GatewayDao, private val repository: GatewayRepository) {
    private var engine: EmbeddedServer<*, *>? = null
    private val loginAttempts = ConcurrentHashMap<String, MutableList<Long>>()

    suspend fun start(port: Int) {
        if (engine != null) return
        val created = embeddedServer(CIO, host = "0.0.0.0", port = port) { module() }
        try {
            created.startSuspend(wait = false)
            created.engine.resolvedConnectors()
            engine = created
        } catch (t: Throwable) {
            runCatching { created.stop(0, 1_000) }
            throw t
        }
    }
    fun stop() { engine?.stop(1_000, 3_000); engine = null }

    private fun Application.module() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false; encodeDefaults = true }) }
        install(StatusPages) {
            exception<ValidationException> { call, cause -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Błędne dane")) }
            exception<ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nieprawidłowe dane JSON")) }
            exception<Throwable> { call, cause ->
                repository.audit("HTTP_ERROR", "type=${cause.javaClass.simpleName}", call.request.local.remoteAddress)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Wewnętrzny błąd usługi"))
            }
        }
        intercept(ApplicationCallPipeline.Plugins) {
            val configured = (dao.settings() ?: SettingsEntity()).allowedSubnetPrefix
            if (!NetworkAccess.isAllowed(call.request.local.remoteAddress, configured)) {
                repository.audit("NETWORK_ACCESS_DENIED", "remote=${call.request.local.remoteAddress}")
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Adres spoza dozwolonej podsieci"))
                finish()
            }
        }
        routing {
            get("/") { call.respondText(asset("web/index.html"), ContentType.Text.Html) }
            get("/app.css") { call.respondText(asset("web/app.css"), ContentType.Text.CSS) }
            get("/app.js") { call.respondText(asset("web/app.js"), ContentType.Application.JavaScript) }
            route("/api/v1") {
                get("/health") { call.respond(HealthResponse("ok")) }
                post("/auth/login") { login(call) }
                post("/auth/logout") {
                    call.request.cookies["bramka_session"]?.let { dao.deleteSession(Security.sha256(it)) }
                    call.response.cookies.append(Cookie("bramka_session", "", maxAge = 0, httpOnly = true, extensions = mapOf("SameSite" to "Strict")))
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/status") {
                    if (principal(call) == null) return@get unauthorized(call)
                    val settings = dao.settings() ?: SettingsEntity()
                    call.respond(StatusResponse("RUNNING", SmsSender(context).readinessError() ?: "READY", dao.queuedCountNow(), dao.count(MessageStatus.SENT), dao.count(MessageStatus.FAILED), settings.queuePaused, settings.port))
                }
                post("/messages") {
                    val actor = principal(call) ?: return@post unauthorized(call)
                    if (!actor.allowedForWrite(call)) return@post
                    val request = call.receive<MessageRequest>()
                    val removePolish = request.removePolishCharacters ?: (dao.settings() ?: SettingsEntity()).removePolishByDefault
                    val (message, created) = repository.enqueue(request.recipient, request.content, request.externalId, call.request.headers["Idempotency-Key"], removePolish, actor.source, actor.apiKeyName)
                    call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, message.response())
                }
                get("/messages/{id}") {
                    if (principal(call) == null) return@get unauthorized(call)
                    val message = dao.message(call.parameters["id"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Nie znaleziono wiadomości"))
                    call.respond(message.response())
                }
                get("/messages") {
                    if (principal(call) == null) return@get unauthorized(call)
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    call.respond(MessagesResponse(dao.messages(limit, offset).map { it.response() }, limit, offset))
                }
                delete("/messages/{id}") {
                    val actor = principal(call) ?: return@delete unauthorized(call)
                    if (!actor.allowedForWrite(call)) return@delete
                    val id = call.parameters["id"].orEmpty()
                    try {
                        if (!repository.cancel(id)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Nie znaleziono wiadomości")) else call.respond(HttpStatusCode.NoContent)
                    } catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message.orEmpty())) }
                }
                post("/messages/{id}/retry") {
                    val actor = principal(call) ?: return@post unauthorized(call)
                    if (!actor.allowedForWrite(call)) return@post
                    val id = call.parameters["id"].orEmpty()
                    try {
                        if (!repository.retry(id)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Nie znaleziono wiadomości")) else call.respond(dao.message(id)!!.response())
                    } catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message.orEmpty())) }
                }
                post("/messages/{id}/mark-sent") {
                    val actor = principal(call) ?: return@post unauthorized(call)
                    if (!actor.allowedForWrite(call)) return@post
                    val id = call.parameters["id"].orEmpty()
                    try {
                        if (!repository.resolveUnknownAsSent(id)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Nie znaleziono wiadomości")) else call.respond(dao.message(id)!!.response())
                    } catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message.orEmpty())) }
                }
                post("/queue/{action}") {
                    val actor = principal(call) ?: return@post unauthorized(call)
                    if (!actor.allowedForWrite(call)) return@post
                    val paused = when (call.parameters["action"]) { "pause" -> true; "resume" -> false; else -> return@post call.respond(HttpStatusCode.NotFound) }
                    val current = dao.settings() ?: SettingsEntity()
                    dao.saveSettings(current.copy(queuePaused = paused))
                    repository.audit(if (paused) "QUEUE_PAUSED" else "QUEUE_RESUMED", "source=${actor.source}")
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/settings") {
                    if (panelPrincipal(call) == null) return@get unauthorized(call)
                    call.respond((dao.settings() ?: SettingsEntity()).response())
                }
                put("/settings") {
                    if (panelPrincipal(call) == null || !csrfValid(call)) return@put forbidden(call)
                    val req = call.receive<SettingsRequest>()
                    if (req.port !in 1024..65535 || req.delayMs !in 0..3_600_000 || req.maxRetries !in 0..20 || req.sendingTimeoutMs !in 5_000..300_000 || (req.allowedSubnetCidr.isNotBlank() && req.allowedSubnetCidr.split(',', ';').any { CidrBlock.parse(it) == null }))
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nieprawidłowe ustawienia"))
                    val old = dao.settings() ?: SettingsEntity()
                    val updated = old.copy(port = req.port, delayMs = req.delayMs, maxRetries = req.maxRetries, removePolishByDefault = req.removePolishByDefault, allowedSubnetPrefix = req.allowedSubnetCidr.trim(), sendingTimeoutMs = req.sendingTimeoutMs, startAfterBoot = req.startAfterBoot)
                    dao.saveSettings(updated); repository.audit("SETTINGS_CHANGED", "port=${updated.port}; delayMs=${updated.delayMs}; maxRetries=${updated.maxRetries}")
                    call.respond(updated.response(restartRequired = old.port != updated.port))
                }
                get("/api-keys") {
                    if (panelPrincipal(call) == null) return@get unauthorized(call)
                    call.respond(dao.apiKeys().map { it.response() })
                }
                post("/api-keys") {
                    if (panelPrincipal(call) == null || !csrfValid(call)) return@post forbidden(call)
                    val req = call.receive<ApiKeyRequest>()
                    if (req.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nazwa jest wymagana"))
                    val raw = "bms_${Security.token()}"
                    val entity = ApiKeyEntity(UUID.randomUUID().toString(), req.name.trim(), raw.take(12), Security.sha256(raw), true, System.currentTimeMillis())
                    dao.insertApiKey(entity)
                    repository.audit("API_KEY_CREATED", "id=${entity.id}; name=${entity.name}")
                    call.respond(HttpStatusCode.Created, ApiKeyCreatedResponse(entity.id, entity.name, raw))
                }
                delete("/api-keys/{id}") {
                    if (panelPrincipal(call) == null || !csrfValid(call)) return@delete forbidden(call)
                    val id = call.parameters["id"].orEmpty()
                    if (dao.deleteApiKey(id) == 0) call.respond(HttpStatusCode.NotFound, ErrorResponse("Nie znaleziono klucza")) else {
                        repository.audit("API_KEY_DELETED", "id=$id"); call.respond(HttpStatusCode.NoContent)
                    }
                }
                post("/api-keys/{id}/{action}") {
                    if (panelPrincipal(call) == null || !csrfValid(call)) return@post forbidden(call)
                    val id = call.parameters["id"].orEmpty()
                    val enabled = when (call.parameters["action"]) { "enable" -> true; "disable" -> false; else -> return@post call.respond(HttpStatusCode.NotFound) }
                    if (dao.setApiKeyEnabled(id, enabled) == 0) call.respond(HttpStatusCode.NotFound, ErrorResponse("Nie znaleziono klucza")) else {
                        repository.audit(if (enabled) "API_KEY_ENABLED" else "API_KEY_DISABLED", "id=$id"); call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }

    private data class Actor(val source: MessageSource, val apiKeyName: String? = null, val session: SessionEntity? = null) {
        suspend fun allowedForWrite(call: ApplicationCall): Boolean {
            if (source == MessageSource.WEB && call.request.headers["X-CSRF-Token"] != session?.csrfToken) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nieprawidłowy token CSRF")); return false
            }
            return true
        }
    }

    private suspend fun principal(call: ApplicationCall): Actor? {
        panelPrincipal(call)?.let { return Actor(MessageSource.WEB, session = it) }
        val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() } ?: return null
        val key = dao.activeApiKeyByHash(Security.sha256(bearer)) ?: return null
        dao.touchApiKey(key.id, System.currentTimeMillis())
        return Actor(MessageSource.API, key.name)
    }
    private suspend fun panelPrincipal(call: ApplicationCall): SessionEntity? {
        val raw = call.request.cookies["bramka_session"] ?: return null
        return dao.session(Security.sha256(raw), System.currentTimeMillis())
    }
    private suspend fun csrfValid(call: ApplicationCall) = call.request.headers["X-CSRF-Token"] == panelPrincipal(call)?.csrfToken
    private suspend fun login(call: ApplicationCall) {
        val remote = call.request.local.remoteAddress
        val now = System.currentTimeMillis()
        val attempts = loginAttempts.getOrPut(remote) { mutableListOf() }
        val throttled = synchronized(attempts) {
            attempts.removeAll { it < now - 15 * 60_000 }
            attempts.size >= 5
        }
        if (throttled) return call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Zbyt wiele prób. Spróbuj później."))
        val req = call.receive<LoginRequest>()
        val user = dao.user(req.username)
        if (user == null || !Security.passwordMatches(req.password, user.passwordHash)) {
            synchronized(attempts) { attempts += now }
            repository.audit("LOGIN_FAILED", "username=${req.username.take(50)}", remote)
            return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Nieprawidłowy login lub hasło"))
        }
        attempts.clear(); dao.deleteExpiredSessions(now)
        val raw = Security.token(); val csrf = Security.token(24); val expiry = now + 8 * 60 * 60_000
        dao.insertSession(SessionEntity(UUID.randomUUID().toString(), user.id, Security.sha256(raw), csrf, expiry))
        call.response.cookies.append(Cookie("bramka_session", raw, path = "/", httpOnly = true, maxAge = 8 * 60 * 60, extensions = mapOf("SameSite" to "Strict")))
        repository.audit("LOGIN_SUCCESS", "username=${user.username}", remote)
        call.respond(LoginResponse(csrf, Instant.ofEpochMilli(expiry).toString()))
    }
    private suspend fun unauthorized(call: ApplicationCall) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Wymagane uwierzytelnienie")) }
    private suspend fun forbidden(call: ApplicationCall) { call.respond(HttpStatusCode.Forbidden, ErrorResponse("Brak dostępu")) }
    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
}
