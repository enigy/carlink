package com.carlink

import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.LayoutConfig
import com.carlink.util.DisplayProfile

/**
 * Keeps the adapter session through a rebuild of [MainActivity] for a configuration change.
 *
 * Android recreates the activity for a change its manifest doesn't declare, and for an overlay or assets change
 * no app can declare. On the 10 Nov truck drive (and the 7 Nov one) such a rebuild came 2.8 s after the phone
 * connected, at the head unit's wake; `onDestroy` released the [CarlinkManager] unconditionally, the session
 * ended, and the box tore its wireless stack down. Now the manager lives here: a rebuild hands it to the new
 * activity ([MainActivity] adopts it), and it is released only when the activity goes for good — [onCleared],
 * since ComponentActivity clears this store at ON_DESTROY unless a configuration change is under way.
 *
 * The manager is built on the application context, so nothing in it is tied to the activity that built it.
 */
class SessionKeeper : ViewModel() {
    /** The live manager, or null between a release and the next build. */
    var manager: CarlinkManager? = null

    /** What [manager] was built for, so the adopting activity knows the geometry the box was pushed. */
    var profile: DisplayProfile? = null
    var layout: LayoutConfig? = null
    var displayMode: DisplayMode? = null

    /** The outgoing activity's configuration, for the incoming one's log of what changed ([ConfigChanges]). */
    var lastConfig: Configuration? = null

    /** Release [manager], once, whichever of [onCleared] and [MainActivity.onDestroy] comes first. */
    fun release() {
        val m = manager ?: return
        manager = null
        m.release()
    }

    override fun onCleared() = release()
}
