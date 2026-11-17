package com.carlink

import com.carlink.CarlinkManager.State

/**
 * When a phone's session reaches [State.STREAMING]: its first media frame once the session is keyed
 * ([State.DEVICE_CONNECTED]), or its video key when its media came first. An iOS 15 iPhone sends its empty now-playing
 * frames about 1.7 s before its key (9 Nov bench, 67c038db): as the session's first phone it never reached STREAMING,
 * and as a later one its key moved STREAMING back to DEVICE_CONNECTED, where it stayed while nothing played. The car's
 * own Play waits for STREAMING, and so does night mode's push to CarPlay. So the key never moves a streaming session
 * back, and a phone whose media came first is stamped at its key. [reset] when the phone's session or the session
 * ends. Synchronized; CarlinkManager calls it from its main scope.
 *
 * It also says whether a keyed phone is here at all ([phoneKeyedIn]), which the state alone can't: a phone that leaves
 * leaves the session in DEVICE_CONNECTED, and the next phone's media, before its key, moves that on to STREAMING.
 */
internal class StreamingStamp {
    // This phone's media came before its key, while the session was still connecting.
    private var mediaFirst = false

    // This phone's video key came, and neither its session nor the session has ended since.
    private var keyed = false

    /** A media frame in [state]: [State.STREAMING] to move to, or null to stay. */
    @Synchronized
    fun onMedia(state: State): State? =
        when (state) {
            State.DEVICE_CONNECTED -> State.STREAMING
            State.CONNECTING -> {
                mediaFirst = true
                null
            }
            else -> null
        }

    /** The phone's first video key in [state]: the state to move to, or null to stay. */
    @Synchronized
    fun onKeyed(state: State): State? {
        keyed = true
        if (state == State.STREAMING) return null
        val first = mediaFirst
        mediaFirst = false
        return if (first) State.STREAMING else State.DEVICE_CONNECTED
    }

    /**
     * A keyed phone's session is up in [state]: its key came, it hasn't ended since, and the session is keyed or
     * streaming. What the picture watchdog counts its waits from (CarlinkManager).
     */
    @Synchronized
    fun phoneKeyedIn(state: State): Boolean = keyed && (state == State.DEVICE_CONNECTED || state == State.STREAMING)

    /** The phone's session ended, or the whole session: the next phone starts over. */
    @Synchronized
    fun reset() {
        mediaFirst = false
        keyed = false
    }
}
