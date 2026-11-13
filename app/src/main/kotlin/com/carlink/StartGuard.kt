package com.carlink

import kotlinx.coroutines.CancellationException

/**
 * A start cancelled mid-way (a display OFF in the first moments of a wake's start cancels it) left CONNECTING with no
 * session, and a display ON then did nothing until Reset Device (the Mac's 13 Nov review, CL-WAKE-3). Runs [start];
 * if it is cancelled while [stuck] holds, [undo] puts the manager back to DISCONNECTED, and the cancellation goes on.
 */
suspend fun <T> undoCancelledStart(
    stuck: () -> Boolean,
    undo: () -> Unit,
    start: suspend () -> T,
): T =
    try {
        start()
    } catch (e: CancellationException) {
        if (stuck()) undo()
        throw e
    }
