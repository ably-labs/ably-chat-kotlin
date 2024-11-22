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
     * If a call to `get` is made for a room that is currently being released, then the promise will resolve only when
     * the release operation is complete.
     *
     * If a call to `get` is made, followed by a subsequent call to `release` before the promise resolves, then the
     * promise will reject with an error.
     *
     * @param roomId The ID of the room.
     * @param options The options for the room.
     * @throws {@link ErrorInfo} if a room with the same ID but different options already exists.
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
     * you must call {@link Rooms.get}.
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
    private val logger: Logger,
) : Rooms {

    /**
     * All operations for DefaultRooms should be executed under sequentialScope to avoid concurrency issues.
     * This makes sure all members/properties accessed by one coroutine at a time.
     */
    private val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())

    private val roomIdToRoom: MutableMap<String, DefaultRoom> = mutableMapOf()
    private val roomGetDeferred: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
    private val roomReleaseDeferred: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    override suspend fun get(roomId: String, options: RoomOptions): Room {
        return sequentialScope.async {
            val existingRoom = getReleasedOrExistingRoom(roomId)
            existingRoom?.let {
                if (options != existingRoom.options) { // CHA-RC1f1
                    throw ablyException("room already exists with different options", ErrorCodes.BadRequest)
                }
                return@async existingRoom // CHA-RC1f2
            }
            // CHA-RC1f3
            val newRoom = makeRoom(roomId, options)
            roomIdToRoom[roomId] = newRoom
            return@async newRoom
        }.await()
    }

    override suspend fun release(roomId: String) {
        sequentialScope.launch {
            // CHA-RC1g4 - Previous Room Get in progress, cancel all of them
            roomGetDeferred[roomId]?.let {
                val exception = ablyException(
                    "room released before get operation could complete",
                    ErrorCodes.RoomReleasedBeforeOperationCompleted,
                )
                it.completeExceptionally(exception)
            }

            // CHA-RC1g2, CHA-RC1g3
            val existingRoom = roomIdToRoom[roomId]
            existingRoom?.let {
                if (roomReleaseDeferred.containsKey(roomId)) {
                    roomReleaseDeferred[roomId]?.await()
                } else {
                    val roomReleaseDeferred = CompletableDeferred<Unit>()
                    this@DefaultRooms.roomReleaseDeferred[roomId] = roomReleaseDeferred
                    existingRoom.release() // CHA-RC1g5
                    roomReleaseDeferred.complete(Unit)
                }
            }
            roomReleaseDeferred.remove(roomId)
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
        roomGetDeferred[roomId]?.let {
            it.await()
            return null
        }

        val existingRoom = roomIdToRoom[roomId]
        existingRoom?.let {
            val roomReleaseInProgress = roomReleaseDeferred[roomId]
            roomReleaseInProgress?.let {
                val roomGetDeferred = CompletableDeferred<Unit>()
                this.roomGetDeferred[roomId] = roomGetDeferred
                roomGetDeferred.invokeOnCompletion {
                    it?.let {
                        this.roomGetDeferred.remove(roomId)
                    }
                }
                roomReleaseInProgress.await()
                if (roomGetDeferred.isActive) {
                    roomGetDeferred.complete(Unit)
                } else {
                    roomGetDeferred.await()
                }
                this.roomGetDeferred.remove(roomId)
                return null
            }
            return existingRoom
        }
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
        DefaultRoom(roomId, options, realtimeClient, chatApi, clientId, logger)
}
