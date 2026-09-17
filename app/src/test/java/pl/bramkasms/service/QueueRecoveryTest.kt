package pl.bramkasms.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.bramkasms.core.GatewayRepository
import pl.bramkasms.data.*
import pl.bramkasms.support.FakeGatewayDao
import java.util.UUID

class QueueRecoveryTest {
    @Test fun `restart changes sending to unknown without resending`() = runBlocking {
        val dao = FakeGatewayDao()
        val id = UUID.randomUUID().toString()
        dao.insertMessage(MessageEntity(id, null, null, "+48500100200", "test", "test", MessageSource.API, "erp", MessageStatus.SENDING, 1, createdAt = 1, updatedAt = 1))
        var sendCalls = 0
        val transport = object : SmsTransport {
            override fun readinessError(): String? = null
            override suspend fun send(recipient: String, content: String, timeoutMs: Long): SendOutcome { sendCalls++; return SendOutcome.Success }
        }
        val processor = QueueProcessor(dao, GatewayRepository(dao), transport)
        assertEquals(1, processor.recoverInterrupted())
        assertEquals(MessageStatus.UNKNOWN, dao.message(id)?.status)
        assertEquals(0, sendCalls)
    }
}
