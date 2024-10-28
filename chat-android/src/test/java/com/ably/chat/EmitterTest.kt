package com.ably.chat

import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert
import org.junit.Test

class AsyncEmitterTest {

    @Test
    fun `should be able to emit and listen to the values in the same order`() = runTest {
        val emitter = AsyncEmitter<Int>()
        val receivedValues = mutableListOf<Int>()

        emitter.on { received: Int ->
            delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues.add(received)
        }

        repeat(10) {
            emitter.emit(it)
        }

        assertWaiter { receivedValues.size == 10 }
        Assert.assertTrue(emitter.finishedProcessing)

        Assert.assertEquals((0..9).toList(), receivedValues)
    }

    @Test
    fun `should start listening to events when subscribed and stop when unsubscribed`() = runTest {
        val emitter = AsyncEmitter<String>()
        val receivedValues1 = mutableListOf<String>()
        val receivedValues2 = mutableListOf<String>()

        emitter.emit("1")
        emitter.emit("10")
        Assert.assertTrue(emitter.finishedProcessing) // Since no subscribers, returns true

        val subscription1 = emitter.on { received: String ->
            delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues1.add(received)
        }

        val subscription2 = emitter.on { received: String ->
            delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues2.add(received)
        }

        emitter.emit("2")
        emitter.emit("3")
        emitter.emit("4")
        Assert.assertFalse(emitter.finishedProcessing) // Both subscribers are processing

        subscription1.unsubscribe()

        emitter.emit("5")
        Assert.assertFalse(emitter.finishedProcessing) // second subscriber is processing

        subscription2.unsubscribe()

        emitter.emit("6")

        assertWaiter { receivedValues1.size == 3 }
        Assert.assertEquals(listOf("2", "3", "4"), receivedValues1)

        assertWaiter { receivedValues2.size == 4 }
        Assert.assertEquals(listOf("2", "3", "4", "5"), receivedValues2)

        Assert.assertTrue(emitter.finishedProcessing)
    }

    @Test
    fun `should be able to handle sequential emits and listen them in same order by multiple subscribers`() = runTest {
        val emitter = AsyncEmitter<Int>()
        val emittedValues = mutableListOf<Int>()
        val receivedValues1 = mutableListOf<Int>()
        val receivedValues2 = mutableListOf<Int>()
        val receivedValues3 = mutableListOf<Int>()

        emitter.on { received ->
            delay((10..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues1.add(received)
        }

        emitter.on { received ->
            delay((20..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues2.add(received)
        }

        emitter.on { received ->
            delay((30..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues3.add(received)
        }

        // emit 100 events from same thread
        repeat(100) {
            emitter.emit(it)
            emittedValues.add(it)
        }

        Assert.assertFalse(emitter.finishedProcessing) // Processing events

        assertWaiter { emittedValues.size == 100 }
        assertWaiter { receivedValues1.size == 100 }
        assertWaiter { receivedValues2.size == 100 }
        assertWaiter { receivedValues3.size == 100 }

        Assert.assertEquals(emittedValues, receivedValues1)
        Assert.assertEquals(emittedValues, receivedValues2)
        Assert.assertEquals(emittedValues, receivedValues3)

        Assert.assertTrue(emitter.finishedProcessing) // Finished processing
    }

    @Test
    fun `should be able to handle concurrent emits and all subscribers should receive them in the same order`() = runTest {
        val emitter = AsyncEmitter<Int>()
        val emitted = LinkedBlockingQueue<Int>()
        val receivedValues1 = mutableListOf<Int>()
        val receivedValues2 = mutableListOf<Int>()
        val receivedValues3 = mutableListOf<Int>()

        emitter.on { received ->
            receivedValues1.add(received)
        }

        emitter.on { received ->
            receivedValues2.add(received)
        }

        emitter.on { received ->
            receivedValues3.add(received)
        }

        // Concurrently emit 100000 events from multiple threads
        withContext(Dispatchers.IO) {
            repeat(100000) {
                launch {
                    emitter.emit(it)
                    emitted.add(it)
                }
            }
        }

        Assert.assertFalse(emitter.finishedProcessing)

        assertWaiter { emitted.size == 100000 }
        assertWaiter { receivedValues1.size == 100000 }
        assertWaiter { receivedValues2.size == 100000 }
        assertWaiter { receivedValues3.size == 100000 }

        // Due to concurrent emits, emit order is not guaranteed
        // i.e. assertEquals(emittedValues, receivedValues1) will fail
        // But order of received messages will be same across all subscribers
        Assert.assertEquals(receivedValues1, receivedValues2)
        Assert.assertEquals(receivedValues1, receivedValues3)

        Assert.assertTrue(emitter.finishedProcessing)
    }

    @Test
    fun `should be able to handle concurrent emits and all async subscribers should receive them in the same order`() = runTest {
        val emitter = AsyncEmitter<Int>()
        val emitted = LinkedBlockingQueue<Int>()
        val receivedValues1 = mutableListOf<Int>()
        val receivedValues2 = mutableListOf<Int>()
        val receivedValues3 = mutableListOf<Int>()

        emitter.on { received ->
            delay((30..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues1.add(received)
        }

        emitter.on { received ->
            delay((30..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues2.add(received)
        }

        emitter.on { received ->
            delay((30..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues3.add(received)
        }

        // Concurrently emit 100 events from multiple threads
        withContext(Dispatchers.IO) {
            repeat(100) {
                launch {
                    emitter.emit(it)
                    emitted.add(it)
                }
            }
        }

        Assert.assertFalse(emitter.finishedProcessing)

        assertWaiter { emitted.size == 100 }
        assertWaiter { receivedValues1.size == 100 }
        assertWaiter { receivedValues2.size == 100 }
        assertWaiter { receivedValues3.size == 100 }

        // Due to concurrent emits, emit order is not guaranteed
        // i.e. assertEquals(emittedValues, receivedValues1) will fail
        // But order of received messages will be same across all subscribers
        Assert.assertEquals(receivedValues1, receivedValues2)
        Assert.assertEquals(receivedValues1, receivedValues3)

        Assert.assertTrue(emitter.finishedProcessing)
    }

    @Test
    fun `shouldn't register same subscriber block twice`() = runTest {
        val emitter = AsyncEmitter<Int>()
        val receivedValues = mutableListOf<Int>()

        val block: suspend CoroutineScope.(Int) -> Unit = {
            delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues.add(it)
        }

        emitter.on(block)
        emitter.on(block)
        emitter.on(block)

        Assert.assertEquals(1, emitter.subscribersCount)

        emitter.emit(1)

        assertWaiter { receivedValues.size == 1 }
        Assert.assertTrue(emitter.finishedProcessing)

        Assert.assertEquals(1, receivedValues[0])
    }

    @Test
    fun `Ignore subscriber errors while processing events`() = runTest {
        val emitter = AsyncEmitter<Int>()
        val emittedValues = mutableListOf<Int>()
        val receivedValues1 = mutableListOf<Int>()
        val receivedValues2 = mutableListOf<Int>()
        val receivedValues3 = mutableListOf<Int>()

        emitter.on { received ->
            if (received % 2 == 0) {
                throw Exception("Can't process integers divisible by 2")
            }
            delay((20..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues1.add(received)
        }

        emitter.on { received ->
            if (received % 5 == 0) {
                throw Exception("Can't process integers divisible by 5")
            }
            delay((20..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues2.add(received)
        }

        emitter.on { received ->
            if (received % 7 == 0) {
                throw Exception("Can't process integers divisible by 7")
            }
            delay((30..100).random().toDuration(DurationUnit.MILLISECONDS))
            receivedValues3.add(received)
        }

        // emit 100 events from same thread
        repeat(100) {
            emitter.emit(it)
            emittedValues.add(it)
        }

        Assert.assertFalse(emitter.finishedProcessing) // Processing events

        val expectedReceivedValues1 = (0..99).toList().filter { it % 2 != 0 }
        val expectedReceivedValues2 = (0..99).toList().filter { it % 5 != 0 }
        val expectedReceivedValues3 = (0..99).toList().filter { it % 7 != 0 }

        assertWaiter { emittedValues.size == 100 }
        assertWaiter { receivedValues1.size == expectedReceivedValues1.size }
        assertWaiter { receivedValues2.size == expectedReceivedValues2.size }
        assertWaiter { receivedValues3.size == expectedReceivedValues3.size }

        Assert.assertEquals(expectedReceivedValues1, receivedValues1)
        Assert.assertEquals(expectedReceivedValues2, receivedValues2)
        Assert.assertEquals(expectedReceivedValues3, receivedValues3)

        Assert.assertTrue(emitter.finishedProcessing) // Finished processing
    }
}
