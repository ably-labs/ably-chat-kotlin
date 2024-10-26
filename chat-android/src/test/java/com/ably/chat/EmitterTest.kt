package com.ably.chat

import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert
import org.junit.Test

class AsyncEmitterTest {

    @Test
    fun `should be able to emit and listen to event`() = runTest {
        val asyncEmitter = AsyncEmitter<String>()
        val receivedValues = mutableListOf<String>()

        asyncEmitter.emit("1")

        val subscription = asyncEmitter.on { received: String ->
            delay((2000..3000).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues.add(received)
        }

        asyncEmitter.emit("2")
        asyncEmitter.emit("3")
        asyncEmitter.emit("4")

        subscription.unsubscribe()

        asyncEmitter.emit("5")
        asyncEmitter.emit("6")

        assertWaiter { receivedValues.size == 3 }.join()
        Assert.assertEquals(3, receivedValues.size)

        Assert.assertEquals(listOf("2", "3", "4"), receivedValues)
    }

    @Test
    fun `should be able to handle concurrent emits and listen to them in the same order`() = runTest {
        val asyncEmitter = AsyncEmitter<Int>()
        val emitted = LinkedBlockingQueue<Int>()
        val receivedValues1 = mutableListOf<Int>()
        val receivedValues2 = mutableListOf<Int>()
        val receivedValues3 = mutableListOf<Int>()


        asyncEmitter.on { received ->
            receivedValues1.add(received)
        }

        asyncEmitter.on { received ->
            receivedValues2.add(received)
        }

        asyncEmitter.on { received ->
            receivedValues3.add(received)
        }

        // Concurrently emit 100000 events from multiple threads
        withContext(Dispatchers.IO) {
            repeat(100000) {
                launch {
                    asyncEmitter.emit(it)
                    emitted.add(it)
                }
            }
        }

        assertWaiter { emitted.size == 100000 }.join()
        assertWaiter { receivedValues1.size == 100000 }.join()
        assertWaiter { receivedValues2.size == 100000 }.join()

        Assert.assertEquals(receivedValues1, receivedValues2)
        Assert.assertEquals(receivedValues2, receivedValues3)

        Assert.assertEquals(100000, emitted.size)
        Assert.assertEquals(100000, receivedValues1.size)
        Assert.assertEquals(100000, receivedValues2.size)
        Assert.assertEquals(100000, receivedValues3.size)
    }
}
