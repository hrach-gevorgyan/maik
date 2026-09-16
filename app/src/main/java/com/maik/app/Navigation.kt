package com.maik.app

/** Where Back goes from [screen], given where the user came from. */
fun backTarget(screen: Screen, returnTo: Screen, currentId: String?): Screen = when (screen) {
    Screen.Setup, Screen.Settings -> if (returnTo == Screen.Chat && currentId != null) Screen.Chat else Screen.List
    else -> Screen.List
}
