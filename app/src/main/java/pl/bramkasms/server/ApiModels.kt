package pl.bramkasms.server

import kotlinx.serialization.Serializable
import pl.bramkasms.data.*
import java.time.Instant

@Serializable data class MessageRequest(val recipient: String, val content: String, val externalId: String? = null, val removePolishCharacters: Boolean? = null)
@Serializable data class MessageResponse(val id: String, val externalId: String?, val recipient: String, val content: String, val sentContent: String, val source: String, val status: String, val parts: Int, val error: String?, val createdAt: String, val updatedAt: String)
@Serializable data class MessagesResponse(val items: List<MessageResponse>, val limit: Int, val offset: Int)
@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class LoginResponse(val csrfToken: String, val expiresAt: String)
@Serializable data class ErrorResponse(val error: String)
@Serializable data class HealthResponse(val status: String)
@Serializable data class StatusResponse(val service: String, val sim: String, val queued: Int, val sent: Int, val failed: Int, val queuePaused: Boolean, val port: Int)
@Serializable data class ApiKeyRequest(val name: String)
@Serializable data class ApiKeyCreatedResponse(val id: String, val name: String, val key: String)
@Serializable data class ApiKeyResponse(val id: String, val name: String, val prefix: String, val enabled: Boolean, val createdAt: String, val lastUsedAt: String?)
@Serializable data class SettingsRequest(val port: Int, val delayMs: Long, val maxRetries: Int, val removePolishByDefault: Boolean, val allowedSubnetCidr: String = "", val sendingTimeoutMs: Long = 60_000, val startAfterBoot: Boolean = false)
@Serializable data class SettingsResponse(val port: Int, val delayMs: Long, val maxRetries: Int, val removePolishByDefault: Boolean, val queuePaused: Boolean, val allowedSubnetCidr: String, val sendingTimeoutMs: Long, val startAfterBoot: Boolean, val restartRequired: Boolean = false)

fun MessageEntity.response() = MessageResponse(id, externalId, recipient, originalContent, sentContent, source.name, status.name, parts, error, Instant.ofEpochMilli(createdAt).toString(), Instant.ofEpochMilli(updatedAt).toString())
fun ApiKeyEntity.response() = ApiKeyResponse(id, name, prefix, enabled, Instant.ofEpochMilli(createdAt).toString(), lastUsedAt?.let { Instant.ofEpochMilli(it).toString() })
fun SettingsEntity.response(restartRequired: Boolean = false) = SettingsResponse(port, delayMs, maxRetries, removePolishByDefault, queuePaused, allowedSubnetPrefix, sendingTimeoutMs, startAfterBoot, restartRequired)
