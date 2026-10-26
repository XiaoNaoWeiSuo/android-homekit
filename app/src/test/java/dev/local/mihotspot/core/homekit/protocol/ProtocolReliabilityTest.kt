package dev.local.mihotspot.core.homekit.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class ProtocolReliabilityTest {
    @Test fun everyEncryptedFragmentFitsAttIncludingAuthenticationTag() {
        for (mtu in listOf(23, 64, 185, 517)) {
            val body = ByteArray(1200) { it.toByte() }
            val response = HapResponse(17, 0, body)
            val received = ArrayList<Byte>(); var first = true
            while (!response.isFinal) {
                val fragment = response.nextFragmentForMtu(mtu, true)
                assertTrue(fragment.size + 16 <= mtu - 1)
                received.addAll(fragment.drop(if (first) 5 else 2)); first = false
            }
            assertArrayEquals(body, received.toByteArray())
        }
    }
    @Test fun duplicateWriteReturnsFreshResponseWithoutReexecutingAction() {
        val cache = HapReplayCache()
        val body = byteArrayOf(1)
        cache.remember("peer", 42, 2, 9, body, HapResponse(9, 0, byteArrayOf(3)))
        val first = cache.find("peer", 42, 2, 9, body)!!
        val bytes = first.nextFragmentForMtu(23, false)
        assertArrayEquals(bytes, cache.find("peer", 42, 2, 9, body)!!.nextFragmentForMtu(23, false))
        assertNull(cache.find("other", 42, 2, 9, body))
        assertNull(cache.find("peer", 42, 2, 10, body))
        assertNull(cache.find("peer", 42, 2, 9, byteArrayOf(0)))
    }
    @Test fun reconnectAndExpiryCannotReplayOldSessionOutcome() {
        var now = 0L; val cache = HapReplayCache { now }
        cache.remember("peer", 42, 2, 9, byteArrayOf(1), HapResponse(9, 0))
        now = 10_001L; assertNull(cache.find("peer", 42, 2, 9, byteArrayOf(1)))
        cache.remember("peer", 42, 2, 9, byteArrayOf(1), HapResponse(9, 0))
        cache.clear("peer"); assertNull(cache.find("peer", 42, 2, 9, byteArrayOf(1)))
    }
    @Test fun finishedTransactionDoesNotSuppressAnotherIdenticalButtonPress() {
        val cache = HapReplayCache()
        cache.remember("peer", 42, 2, 9, byteArrayOf(1), HapResponse(9, 0))
        cache.complete("peer", 42, 9)
        assertNull(cache.find("peer", 42, 2, 9, byteArrayOf(1)))
    }
    @Test fun serialQueueKeepsNonceOrderUnderBurst() {
        SerialGattQueue(capacity = 64).use { queue ->
            val completed = CountDownLatch(50); val order = Collections.synchronizedList(ArrayList<Int>())
            repeat(50) { i -> queue.submit({ fail("unexpected expiration") }) { order.add(i); completed.countDown() } }
            assertTrue(completed.await(2, TimeUnit.SECONDS)); assertEquals((0 until 50).toList(), order)
        }
    }
    @Test fun expiredQueuedActionNeverRunsLater() {
        SerialGattQueue(deadlineMs = 100L).use { queue ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val expired = CountDownLatch(1)
            var executed = false
            queue.submit({ fail("first expired") }) { entered.countDown(); release.await() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.submit({ expired.countDown() }) { executed = true }
            Thread.sleep(150L); assertTrue(queue.stalled); release.countDown()
            assertTrue(expired.await(1, TimeUnit.SECONDS)); assertFalse(executed)
        }
    }
    @Test fun overloadIsRejectedAndDoesNotGrowUnbounded() {
        SerialGattQueue(capacity = 1).use { queue ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val rejected = CountDownLatch(1)
            queue.submit({ fail("first expired") }) { entered.countDown(); release.await() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            queue.submit({ fail("second rejected") }) { }
            queue.submit({ rejected.countDown() }) { fail("overloaded action executed") }
            assertTrue(rejected.await(1, TimeUnit.SECONDS)); release.countDown()
        }
    }
}
