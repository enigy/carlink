package com.carlink.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a system USB permission dialog Carlink3 asked for may be on screen: from the moment the
 * bridge (or, with no bridge, this app) requests permission until it is granted, denied or the
 * wait ends.
 *
 * MainActivity holds its cluster steps while this is true. Launching the hidden CarAppActivity, or
 * reordering MainActivity to the front, over the dialog paused it and left the whole screen
 * ignoring touches ("no touched window on display 0", emulator 2026-10-20). That happens when the
 * adapter attaches early in boot and the platform's automatic grant never reaches the bridge, the
 * same setup as the GM head unit.
 */
object UsbPermissionPrompt {
    private val state = MutableStateFlow(false)

    val open: StateFlow<Boolean> = state.asStateFlow()

    val isOpen: Boolean get() = state.value

    fun set(open: Boolean) {
        state.value = open
    }
}
