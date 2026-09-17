package pl.bramkasms.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bramkasms.data.*
import pl.bramkasms.support.FakeGatewayDao

class GatewayRepositoryTest {
    private lateinit var dao: FakeGatewayDao
    private lateinit var repository: GatewayRepository

    @Before fun setUp() {
        dao = FakeGatewayDao()
        repository = GatewayRepository(dao)
    }

    @Test fun `same idempotency key returns existing message`() = runBlocking {
        val first = repository.enqueue("500100200", "Pierwsza", "A", "same-key", false, MessageSource.API, "erp")
        val second = repository.enqueue("500100200", "Inna treść", "B", "same-key", false, MessageSource.API, "erp")
        assertTrue(first.second)
        assertFalse(second.second)
        assertEquals(first.first.id, second.first.id)
        assertEquals("Pierwsza", second.first.originalContent)
    }

    @Test fun `concurrent identical idempotency requests create one row`() = runBlocking {
        val results = (1..12).map {
            async(Dispatchers.IO) { repository.enqueue("500100200", "Treść", null, "parallel-key", false, MessageSource.API, "erp") }
        }.awaitAll()
        assertEquals(1, results.count { it.second })
        assertEquals(1, results.map { it.first.id }.distinct().size)
        assertEquals(1, dao.messages(100, 0).size)
    }

    @Test fun `manual retry resets cycle counter but keeps total attempts`() = runBlocking {
        val message = repository.enqueue("500100200", "Treść", null, null, false, MessageSource.WEB, null).first
        dao.claim(message.id, 1)
        dao.markFailed(message.id, 2, "błąd")
        assertTrue(repository.retry(message.id))
        val retried = dao.message(message.id)!!
        assertEquals(MessageStatus.QUEUED, retried.status)
        assertEquals(1, retried.attemptCount)
        assertEquals(0, retried.automaticRetryCount)
    }
}
