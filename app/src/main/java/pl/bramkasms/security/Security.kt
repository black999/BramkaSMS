package pl.bramkasms.security

import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Security {
    private val random = SecureRandom()
    fun passwordHash(value: String): String = BCrypt.hashpw(value, BCrypt.gensalt(12))
    fun passwordMatches(value: String, hash: String): Boolean = runCatching { BCrypt.checkpw(value, hash) }.getOrDefault(false)
    fun token(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
