package com.ably.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class FlowEmitterTest {
    @Test
    fun `should be able to emit and listen to event`() = runTest {
        val flowEmitter = FlowEmitter<String>()
        val receivedValues = mutableListOf<String>()

        flowEmitter.emit("1")

        val subscription = flowEmitter.on { received: String ->
            receivedValues.add(received)
        }

        flowEmitter.emit("2")
        flowEmitter.emit("3")
        flowEmitter.emit("4")

        subscription.unsubscribe()

        flowEmitter.emit("5")
        flowEmitter.emit("7")

        assertWaiter { receivedValues.size == 3 }.join()

        // Assertion fails because receivedValues returns empty values
        // Seems collector works independently of emitter and can be late processing values
        Assert.assertEquals(listOf("2", "3", "4"), receivedValues)
    }
}
