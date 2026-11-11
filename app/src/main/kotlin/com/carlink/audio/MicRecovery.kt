package com.carlink.audio

/**
 * What to do when the mic's capture dies mid-use (a fatal AudioRecord error). On a GM build reported 11 Nov, the car
 * takes its call input back about a second into every call and refuses to open it again (AudioFlinger status -38), and
 * Carlink used to leave the mic off: callers heard nothing for the rest of the call. Plain Kotlin, so it is tested
 * without a car.
 */
object MicRecovery {
    enum class Action {
        /** The box's gate is closed, or the capture already died once since it opened: leave the mic off. */
        NOTHING,

        /** It died on a named input (the car call path, or one the driver picked): this call goes on the system's choice. */
        SYSTEM_CHOICE,

        /** It died on the system's choice: open it again, once. */
        RESTART,
    }

    /** Restarts of the system's choice allowed while the gate stays open. */
    const val MAX_RESTARTS = 1

    /**
     * After a capture died: [gateOn] whether the box still wants the mic, [onNamedInput] whether it was recording from a
     * named input rather than the system's choice, [restarts] how often it was restarted since the gate opened.
     */
    fun after(
        gateOn: Boolean,
        onNamedInput: Boolean,
        restarts: Int,
    ): Action =
        when {
            !gateOn -> Action.NOTHING
            onNamedInput -> Action.SYSTEM_CHOICE
            restarts < MAX_RESTARTS -> Action.RESTART
            else -> Action.NOTHING
        }
}
