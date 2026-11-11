package com.carlink.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the vehicle is being driven, as [DriveStateMonitor] last said, for the screens: the pane holds
 * the lyrics back while it moves when Limit features while driving is on (owner, 10-29). False until the
 * monitor says otherwise, and always off automotive. [parked] is the positive state ([DriveStateMonitor.parked]):
 * the Fuel card's full layout waits for it, and anything unknown counts as moving.
 */
object DrivingState {
    private val state = MutableStateFlow(false)
    private val parkedState = MutableStateFlow(false)

    val moving: StateFlow<Boolean> = state.asStateFlow()

    /** Known to be parked: false while moving, before the first reading, and with no car service. */
    val parked: StateFlow<Boolean> = parkedState.asStateFlow()

    fun set(driving: Boolean) {
        state.value = driving
    }

    fun setParked(now: Boolean) {
        parkedState.value = now
    }
}
