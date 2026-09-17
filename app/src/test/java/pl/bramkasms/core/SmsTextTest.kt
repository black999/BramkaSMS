package pl.bramkasms.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsTextTest {
    @Test fun `normalizes Polish phone numbers`() {
        assertEquals("+48500100200", PhoneNumber.normalize("500 100 200"))
        assertEquals("+48500100200", PhoneNumber.normalize("+48 500-100-200"))
        assertNull(PhoneNumber.normalize("123"))
    }
    @Test fun `removes Polish characters without changing other content`() =
        assertEquals("Zazolc gesla jazn", SmsText.withoutPolish("Zażółć gęślą jaźń"))
    @Test fun `counts GSM and Unicode parts`() {
        assertEquals(1, SmsText.parts("a".repeat(160)))
        assertEquals(2, SmsText.parts("a".repeat(161)))
        assertEquals(1, SmsText.parts("ą".repeat(70)))
        assertEquals(2, SmsText.parts("ą".repeat(71)))
        assertEquals(2, SmsText.parts("^".repeat(81)))
    }
}
