package com.maik.app

import android.app.Application
import com.maik.app.engine.LocalEngine
import kotlinx.coroutines.launch

/**
 * The process itself.
 *
 * maik keeps a multi-gigabyte model loaded between screens, which is the whole reason
 * the second question is answered instantly. This is where that decision is given a
 * limit: when Android says memory is short, the model goes back rather than waiting
 * for the process to be killed with the conversation inside it.
 */
class MaikApp : Application() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_BACKGROUND and above mean maik is no longer on screen and the
        // system is choosing what to kill. Anything lighter happens while the user is
        // still reading a reply, where dropping the model would be felt immediately.
        if (level < TRIM_MEMORY_BACKGROUND) return
        LocalEngine.scope.launch { LocalEngine.releaseForMemory() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LocalEngine.scope.launch { LocalEngine.releaseForMemory() }
    }
}
