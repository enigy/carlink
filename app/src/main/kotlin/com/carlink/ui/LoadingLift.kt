package com.carlink.ui

import com.carlink.CarlinkManager

/**
 * When the loading screen starts to lift and touch reaches CarPlay (MainScreen): a session is up and the decoder has drawn
 * the phone's first frame ([com.carlink.av.PictureGate]). Never STREAMING alone (the phone's first media can come before
 * its first frame, and the fade then began over a blank surface), and never a frame alone (it may be the last session's,
 * still on the surface under the screen while the next one connects).
 */
object LoadingLift {
    fun lifted(
        state: CarlinkManager.State,
        pictureDrawn: Boolean,
    ): Boolean = pictureDrawn && (state == CarlinkManager.State.DEVICE_CONNECTED || state == CarlinkManager.State.STREAMING)
}
