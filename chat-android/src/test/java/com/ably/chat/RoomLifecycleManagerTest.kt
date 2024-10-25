package com.ably.chat

import io.ably.lib.types.AblyException
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class RoomLifecycleManagerTest {
    @Test
    fun `(CHA-RL1a) Attach return when channel in already in attached state`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Attached)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(status, listOf()))
        val result = kotlin.runCatching { roomLifecycle.attach() }
        Assert.assertTrue(result.isSuccess)
    }

    @Test
    fun `(CHA-RL1b) Attach return when channel in releasing state`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Releasing)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(status, listOf()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.attach()
            }
        }
        Assert.assertEquals("unable to attach room; room is releasing", exception.errorInfo.message)
        Assert.assertEquals(102_102, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)
    }

    @Test
    fun `(CHA-RL1c) Attach return when channel in released state`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(status, listOf()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.attach()
            }
        }
        Assert.assertEquals("unable to attach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(102_103, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)
    }
}
