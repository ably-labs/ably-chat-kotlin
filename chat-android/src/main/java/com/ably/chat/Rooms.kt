package com.ably.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle of chat rooms.
 */
interface Rooms {
    /**
     * Get the client options used to create the Chat instance.
     * @returns ClientOptions
     */
    val clientOptions: ClientOptions

    /**
     * Gets a room reference by ID. The Rooms class ensures that only one reference
     * exists for each room. A new reference object is created if it doesn't already
     * exist, or if the one used previously was released using release(roomId).
     *
     * Always call `release(roomId)` after the Room object is no longer needed.
     *
     * If a call to `get` is made for a room that is currently being released, then it will resolve only when
     * the release operation is complete.
     *
     * If a call to `get` is made, followed by a subsequent call to `release` before it resolves, then `get` will
     * throw an exception
     *
     * @param roomId The ID of the room.
     * @param options The options for the room.
     * @throws [io.ably.lib.types.ErrorInfo] if a room with the same ID but different options already exists.
     * @returns Room A new or existing Room object.
     * Spec: CHA-RC1f
     */
    suspend fun get(roomId: String, options: RoomOptions = RoomOptions()): Room

    /**
     * Release the Room object if it exists. This method only releases the reference
     * to the Room object from the Rooms instance and detaches the room from Ably. It does not unsubscribe to any
     * events.
     *
     * After calling this function, the room object is no-longer usable. If you wish to get the room object again,
     * you must call [Rooms.get].
     *
     * Calling this function will abort any in-progress `get` calls for the same room.
     *
     * @param roomId The ID of the room.
     * Spec: CHA-RC1g, CHA-RC1g1
     */
    suspend fun release(roomId: String)
}

/**
 * Manages the chat rooms.
 */
internal class DefaultRooms(
    private val realtimeClient: RealtimeClient,
    private val chatApi: ChatApi,
    override val clientOptions: ClientOptions,
    private val clientId: String,
    logger: Logger,
) : Rooms {
    private val logger = logger.withContext(tag = "Rooms")

    /**
     * All operations for DefaultRooms should be executed under sequentialScope to avoid concurrency issues.
     * This makes sure all members/properties accessed by one coroutine at a time.
     */
    private val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())

    private val roomIdToRoom: MutableMap<String, DefaultRoom> = mutableMapOf()
    private val roomGetDeferredMap: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
    private val roomReleaseDeferredMap: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    override suspend fun get(roomId: String, options: RoomOptions): Room {
        logger.trace("get(); $roomId; $options")
        return sequentialScope.async {
            val existingRoom = getReleasedOrExistingRoom(roomId)
            existingRoom?.let {
                if (options != existingRoom.options) { // CHA-RC1f1
                    throw ablyException("room already exists with different options", ErrorCode.BadRequest)
                }
                logger.debug("get(); returning existing room with roomId: $roomId")
                return@async existingRoom // CHA-RC1f2
            }
            // CHA-RC1f3
            val newRoom = makeRoom(roomId, options)
            roomIdToRoom[roomId] = newRoom
            logger.debug("get(); returning new room with roomId: $roomId")
            return@async newRoom
        }.await()
    }

    override suspend fun release(roomId: String) {
        logger.trace("release(); $roomId")
        sequentialScope.launch {
            // CHA-RC1g4 - Previous Room Get in progress, cancel all of them
            roomGetDeferredMap[roomId]?.let {
                logger.debug("release(); cancelling existing rooms.get() for roomId: $roomId")
                val exception = ablyException(
                    "room released before get operation could complete",
                    ErrorCode.RoomReleasedBeforeOperationCompleted,
                )
                it.completeExceptionally(exception)
                it.join() // Doesn't throw exception, only waits till job is complete.
                roomGetDeferredMap.remove(roomId)
                logger.warn("release(); cancelled existing rooms.get() for roomId: $roomId")
            }

            // CHA-RC1g2, CHA-RC1g3
            val existingRoom = roomIdToRoom[roomId]
            existingRoom?.let {
                logger.debug("release(); releasing roomId: $roomId")
                if (roomReleaseDeferredMap.containsKey(roomId)) {
                    roomReleaseDeferredMap[roomId]?.await()
                } else {
                    val roomReleaseDeferred = CompletableDeferred<Unit>()
                    roomReleaseDeferredMap[roomId] = roomReleaseDeferred
                    existingRoom.release() // CHA-RC1g5
                    roomReleaseDeferred.complete(Unit)
                }
                logger.debug("release(); released roomId: $roomId")
            }
            roomReleaseDeferredMap.remove(roomId)
            roomIdToRoom.remove(roomId)
        }.join()
    }

    /**
     * @returns null for released room or non-null existing active room (not in releasing/released state)
     * Spec: CHA-RC1f4, CHA-RC1f5, CHA-RC1f6, CHA-RC1g4
     */
    @Suppress("ReturnCount")
    private suspend fun getReleasedOrExistingRoom(roomId: String): Room? {
        // Previous Room Get in progress, because room release in progress
        // So await on same deferred and return null
        roomGetDeferredMap[roomId]?.let {
            logger.debug("getReleasedOrExistingRoom(); awaiting on previous rooms.get() for roomId: $roomId")
            it.await()
            return null
        }

        val existingRoom = roomIdToRoom[roomId]
        existingRoom?.let {
            logger.debug("getReleasedOrExistingRoom(); existing room found, roomId: $roomId")
            val roomReleaseInProgress = roomReleaseDeferredMap[roomId]
            roomReleaseInProgress?.let {
                logger.debug("getReleasedOrExistingRoom(); waiting for roomId: $roomId to be released")
                val roomGetDeferred = CompletableDeferred<Unit>()
                roomGetDeferredMap[roomId] = roomGetDeferred
                roomReleaseInProgress.await()
                if (roomGetDeferred.isActive) {
                    roomGetDeferred.complete(Unit)
                } else {
                    roomGetDeferred.await()
                }
                roomGetDeferredMap.remove(roomId)
                logger.debug("getReleasedOrExistingRoom(); waiting complete, roomId: $roomId is released")
                return null
            }
            return existingRoom
        }
        logger.debug("getReleasedOrExistingRoom(); no existing room found, roomId: $roomId")
        return null
    }

    /**
     * makes a new room object
     *
     * @param roomId The ID of the room.
     * @param options The options for the room.
     *
     * @returns DefaultRoom A new room object.
     * Spec: CHA-RC1f3
     */
    private fun makeRoom(roomId: String, options: RoomOptions): DefaultRoom =
        DefaultRoom(roomId, options.copy(), realtimeClient, chatApi, clientId, logger)
}
