package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo

/**
 * Manages the lifecycle of chat rooms.
 */
interface Rooms {
    /**
     * Get the client options used to create the Chat instance.
     */
    val clientOptions: ClientOptions

    /**
     * Gets a room reference by ID. The Rooms class ensures that only one reference
     * exists for each room. A new reference object is created if it doesn't already
     * exist, or if the one used previously was released using release(roomId).
     *
     * Always call `release(roomId)` after the Room object is no longer needed.
     *
     * @param roomId The ID of the room.
     * @param options The options for the room.
     * @throws {@link ErrorInfo} if a room with the same ID but different options already exists.
     * @returns Room A new or existing Room object.
     */
    fun get(roomId: String, options: RoomOptions = RoomOptions()): Room

    /**
     * Release the Room object if it exists. This method only releases the reference
     * to the Room object from the Rooms instance and detaches the room from Ably. It does not unsubscribe to any
     * events.
     *
     * After calling this function, the room object is no-longer usable. If you wish to get the room object again,
     * you must call {@link Rooms.get}.
     *
     * @param roomId The ID of the room.
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
) : Rooms {
    private val roomIdToRoom: MutableMap<String, DefaultRoom> = mutableMapOf()

    override fun get(roomId: String, options: RoomOptions): Room {
        return synchronized(this) {
            val room = roomIdToRoom.getOrPut(roomId) {
                DefaultRoom(
                    roomId = roomId,
                    options = options,
                    realtimeClient = realtimeClient,
                    chatApi = chatApi,
                    logger = clientOptions.logHandler,
                )
            }

            if (room.options != options) {
                throw AblyException.fromErrorInfo(
                    ErrorInfo("Room already exists with different options", HttpStatusCodes.BadRequest, ErrorCodes.BadRequest.errorCode),
                )
            }

            room
        }
    }

    override suspend fun release(roomId: String) {
        val room = roomIdToRoom.remove(roomId)
        room?.release()
    }
}
