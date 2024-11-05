package com.ably.helpers

import com.ably.chat.AtomicCoroutineScope
import com.ably.chat.RoomLifecycleManager

fun RoomLifecycleManager.atomicCoroutineScope(): AtomicCoroutineScope {
    val loadedClass = RoomLifecycleManager::class
    val valueField = loadedClass.java.getDeclaredField("atomicCoroutineScope")
    valueField.isAccessible = true
    return valueField.get(this) as AtomicCoroutineScope
}
