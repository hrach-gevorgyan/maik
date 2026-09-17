package com.maik.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maik.app.data.Message
import com.maik.app.ui.chat.Bubble
import com.maik.app.ui.chat.Composer
import com.maik.app.ui.chat.MarkdownText
import com.maik.app.ui.components.ActionSheet
import com.maik.app.ui.components.SheetAction
import com.maik.app.ui.theme.MaikTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens, driven the way a thumb drives them. Unit tests cover the arithmetic;
 * these cover the thing nobody notices breaking until it is in someone's hand.
 */
@RunWith(AndroidJUnit4::class)
class ChatUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun a_reply_can_be_long_pressed_for_its_menu() {
        var pressed = false
        rule.setContent {
            MaikTheme { Bubble(Message("Rome is the capital.", fromUser = false)) { pressed = true } }
        }
        rule.onNodeWithText("Rome is the capital.").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue("long press did not open the menu", pressed)
    }

    @Test
    fun the_menu_runs_the_action_that_was_tapped() {
        var chosen = ""
        rule.setContent {
            MaikTheme {
                ActionSheet(
                    title = "maik's reply",
                    actions = listOf(
                        SheetAction("Copy") { chosen = "copy" },
                        SheetAction("Delete message", destructive = true) { chosen = "delete" }
                    ),
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithText("Delete message").performClick()
        rule.waitForIdle()
        assertEquals("delete", chosen)
    }

    @Test
    fun send_waits_for_the_model_but_typing_never_does() {
        var sent = 0
        var text = "Hello"
        rule.setContent {
            MaikTheme {
                Composer(
                    value = text,
                    onValueChange = { text = it },
                    busy = false,
                    ready = false,
                    onSend = { sent++ },
                    onStop = {}
                )
            }
        }
        rule.onNodeWithText("Hello").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()
        assertEquals("a message was sent before the model was ready", 0, sent)
    }

    @Test
    fun the_send_button_becomes_stop_while_a_reply_runs() {
        rule.setContent {
            MaikTheme {
                Composer(
                    value = "",
                    onValueChange = {},
                    busy = true,
                    ready = true,
                    onSend = {},
                    onStop = {}
                )
            }
        }
        rule.onNodeWithContentDescription("Stop").assertIsDisplayed()
        rule.onNodeWithText("maik is answering…").assertIsDisplayed()
    }

    @Test
    fun code_in_a_reply_offers_its_own_copy_button() {
        rule.setContent {
            MaikTheme {
                MarkdownText(
                    "Here:\n```kotlin\nval a = 1\n```",
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                )
            }
        }
        rule.onNodeWithText("val a = 1").assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
    }
}
