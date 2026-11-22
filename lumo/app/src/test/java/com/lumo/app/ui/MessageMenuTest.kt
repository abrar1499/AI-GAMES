package com.lumo.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.lumo.app.data.Message
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MessageMenuTest {
    @get:Rule val rule = createComposeRule()

    private val msg = Message("m1", "me", "Hello there", System.currentTimeMillis(), pending = false)

    private fun show() = rule.setContent {
        LumoTheme {
            var open by remember { mutableStateOf(false) }
            Bubble(msg, mine = true, tail = true, onMenu = { open = true })
            if (open) MessageMenu(msg, mine = true, onCopy = {}, onEdit = {}, onDelete = {}, onDismiss = { open = false })
        }
    }

    @Test fun tapOpensMenu() {
        show()
        rule.onNodeWithText("Hello there", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Edit").assertExists()
        rule.onNodeWithText("Delete").assertExists()
    }

    @Test fun longPressOpensMenu() {
        show()
        rule.onNodeWithText("Hello there").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Delete").assertExists()
    }
}
