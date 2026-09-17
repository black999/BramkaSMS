package pl.bramkasms.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTimeoutTest {
    @Test fun `multipart timeout cleans receiver and returns unknown`() = runTest {
        var cleaned = false
        val result = awaitPartResults(expectedParts = 3, timeoutMs = 50, start = { _ -> }, cleanup = { cleaned = true })
        assertTrue(result is SendOutcome.Unknown)
        assertTrue(cleaned)
    }

    @Test fun `all multipart callbacks are required`() = runTest {
        val result = awaitPartResults(expectedParts = 2, timeoutMs = 1_000, start = { callback ->
            callback(android.app.Activity.RESULT_OK)
            callback(android.app.Activity.RESULT_OK)
        }, cleanup = {})
        assertEquals(SendOutcome.Success, result)
    }
}
