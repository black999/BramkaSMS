package pl.bramkasms.core

private val polish = mapOf('ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z', 'Ą' to 'A', 'Ć' to 'C', 'Ę' to 'E', 'Ł' to 'L', 'Ń' to 'N', 'Ó' to 'O', 'Ś' to 'S', 'Ź' to 'Z', 'Ż' to 'Z')
private const val gsmBasic = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
private const val gsmExtended = "^{}\\[~]|€"

object SmsText {
    fun withoutPolish(value: String) = value.map { polish[it] ?: it }.joinToString("")
    fun parts(value: String): Int {
        if (value.isEmpty()) return 0
        val gsm = value.all { it in gsmBasic || it in gsmExtended }
        val units = if (gsm) value.fold(0) { total, char -> total + if (char in gsmExtended) 2 else 1 } else value.length
        val single = if (gsm) 160 else 70
        val multipart = if (gsm) 153 else 67
        return if (units <= single) 1 else (units + multipart - 1) / multipart
    }
}

object PhoneNumber {
    fun normalize(input: String): String? {
        val clean = input.filterNot(Char::isWhitespace).replace("-", "")
        return when {
            clean.matches(Regex("\\+48[0-9]{9}")) -> clean
            clean.matches(Regex("[0-9]{9}")) -> "+48$clean"
            else -> null
        }
    }
}
