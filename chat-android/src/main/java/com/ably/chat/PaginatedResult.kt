package com.ably.chat

import com.google.gson.JsonElement
import io.ably.lib.types.AblyException
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.ably.lib.types.ErrorInfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Represents the result of a paginated query.
 */
interface PaginatedResult<T> {

    /**
     * The items returned by the query.
     */
    val items: List<T>

    /**
     * Fetches the next page of items.
     */
    suspend fun next(): PaginatedResult<T>

    /**
     * Whether there are more items to query.
     *
     * @returns `true` if there are more items to query, `false` otherwise.
     */
    fun hasNext(): Boolean
}

fun <T> AsyncHttpPaginatedResponse?.toPaginatedResult(transform: (JsonElement) -> T): PaginatedResult<T> =
    this?.let { AsyncPaginatedResultWrapper(it, transform) } ?: EmptyPaginatedResult()

private class EmptyPaginatedResult<T> : PaginatedResult<T> {
    override val items: List<T>
        get() = emptyList()

    override suspend fun next(): PaginatedResult<T> = this

    override fun hasNext(): Boolean = false
}

private class AsyncPaginatedResultWrapper<T>(
    val asyncPaginatedResult: AsyncHttpPaginatedResponse,
    val transform: (JsonElement) -> T,
) : PaginatedResult<T> {
    override val items: List<T> = asyncPaginatedResult.items()?.map(transform) ?: emptyList()

    override suspend fun next(): PaginatedResult<T> = suspendCancellableCoroutine { continuation ->
        asyncPaginatedResult.next(object : AsyncHttpPaginatedResponse.Callback {
            override fun onResponse(response: AsyncHttpPaginatedResponse?) {
                continuation.resume(response.toPaginatedResult(transform))
            }

            override fun onError(reason: ErrorInfo?) {
                continuation.resumeWithException(AblyException.fromErrorInfo(reason))
            }
        })
    }

    override fun hasNext(): Boolean = asyncPaginatedResult.hasNext()
}
