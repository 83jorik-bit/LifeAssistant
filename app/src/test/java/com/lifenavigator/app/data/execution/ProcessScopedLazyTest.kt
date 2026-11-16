package com.lifenavigator.app.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessScopedLazyTest {

    @Test
    fun createsOnlyOneInstanceWhenAccessedConcurrently() {
        val provider = ProcessScopedLazy<Any>()
        val factoryCalls = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        try {
            val futures = (1..24).map {
                pool.submit<Any> {
                    start.await()
                    provider.get {
                        Thread.sleep(10)
                        factoryCalls.incrementAndGet()
                        Any()
                    }
                }
            }

            start.countDown()
            val instances = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, factoryCalls.get())
            instances.forEach { assertSame(instances.first(), it) }
        } finally {
            pool.shutdownNow()
        }
    }
}
