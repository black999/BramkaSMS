package pl.bramkasms.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAccessTest {
    @Test fun `IPv4 CIDR accepts only matching subnet`() {
        assertTrue(NetworkAccess.isAllowed("192.168.1.77", "192.168.1.0/24"))
        assertFalse(NetworkAccess.isAllowed("192.168.2.1", "192.168.1.0/24"))
    }
    @Test fun `blank restriction allows access`() = assertTrue(NetworkAccess.isAllowed("10.0.0.1", ""))
    @Test fun `invalid CIDR denies access when configured`() = assertFalse(NetworkAccess.isAllowed("10.0.0.1", "not-a-cidr"))
}
