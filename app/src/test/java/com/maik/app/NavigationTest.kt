package com.maik.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Back from a download or settings used to drop you on the chat list, out of your chat. */
class NavigationTest {

    @Test
    fun `back from setup returns to the chat it was opened from`() {
        assertEquals(Screen.Chat, backTarget(Screen.Setup, returnTo = Screen.Chat, currentId = "c"))
    }

    @Test
    fun `back from setup with no chat goes to the list`() {
        assertEquals(Screen.List, backTarget(Screen.Setup, returnTo = Screen.List, currentId = null))
        assertEquals(Screen.List, backTarget(Screen.Setup, returnTo = Screen.Chat, currentId = null))
    }

    @Test
    fun `back from settings returns to the chat`() {
        assertEquals(Screen.Chat, backTarget(Screen.Settings, Screen.Chat, "c"))
    }

    @Test
    fun `back from a chat goes to the list`() {
        assertEquals(Screen.List, backTarget(Screen.Chat, Screen.Chat, "c"))
    }
}
