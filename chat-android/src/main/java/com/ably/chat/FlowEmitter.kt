package com.ably.chat

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

interface Emitter<V> {
    fun emit(value: V)
    fun on(block: suspend CoroutineScope.(V) -> Unit): Subscription
    fun offAll()
}

interface EventEmitter<K, V> {
    fun emit(event: K, value: V)
    fun on(event: K, block: suspend CoroutineScope.(V) -> Unit): Subscription
    fun offAll()
}

open class FlowEmitter<V>(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : Emitter<V> {

    private val coroutineScope = scope

    // Same as channel with unlimited size with no replay
    private val mutableFlow = MutableSharedFlow<V>(extraBufferCapacity = Int.MAX_VALUE)

    override fun emit(value: V) {
        mutableFlow.tryEmit(value)
    }

    override fun on(block: suspend CoroutineScope.(V) -> Unit): Subscription {
        val keepCollecting = AtomicBoolean(true)
        coroutineScope.launch {
            mutableFlow.takeWhile { keepCollecting.get() }.collect {
                kotlin.runCatching { block(this, it) }
            }
        }
        return Subscription {
            keepCollecting.set(false)
        }
    }

    override fun offAll() {
        coroutineScope.cancel("Cancelled all collectors")
    }
}

open class FlowEventEmitter<K, V>(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : EventEmitter<K, V> {

    protected var coroutineScope = scope

    // Same as channel with unlimited size
    private val pairedMutableFlow = MutableSharedFlow<Pair<K, V>>(extraBufferCapacity = Int.MAX_VALUE)

    override fun emit(event: K, value: V) {
        pairedMutableFlow.tryEmit(Pair(event, value))
    }

    override fun on(event: K, block: suspend CoroutineScope.(V) -> Unit): Subscription {
        val keepCollecting = AtomicBoolean(true)
        coroutineScope.launch {
            pairedMutableFlow.takeWhile { keepCollecting.get() }.collect {
                if (it.first == event) {
                    kotlin.runCatching { block(this, it.second) }
                }
            }
        }
        return Subscription {
            keepCollecting.set(false)
        }
    }

    override fun offAll() {
        coroutineScope.cancel("Cancelled all collectors")
    }
}
