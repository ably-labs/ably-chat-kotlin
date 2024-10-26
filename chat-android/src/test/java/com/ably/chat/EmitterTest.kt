package com.ably.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class AsyncEmitterTest {

    @Test
    fun `should be able to emit and listen to event`() = runTest {
        val asyncEmitter = AsyncEmitter<String>()
        val receivedValues = mutableListOf<String>()

        asyncEmitter.emit("1")

        val subscription = asyncEmitter.on { received: String ->
            receivedValues.add(received)
        }

        asyncEmitter.emit("2")
        asyncEmitter.emit("3")
        asyncEmitter.emit("4")

        subscription.unsubscribe()

        asyncEmitter.emit("5")
        asyncEmitter.emit("6")

        assertWaiter { receivedValues.size == 3 }.join()

        Assert.assertEquals(listOf("2", "3", "4"), receivedValues)
    }
}
