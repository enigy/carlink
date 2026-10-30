package com.carlink.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the vehicle is being driven, as [DriveStateMonitor] last said, for the screens: the pane holds
 * the lyrics back while it moves when Limit features while driving is on (owner, 10-29). False until the
 * monitor says otherwise, and always off automotive.
 */
object DrivingState {
    private val state = MutableStateFlow(false)

    val moving: StateFlow<Boolean> = state.asStateFlow()

    fun set(driving: Boolean) {
        state.value = driving
    }
}
