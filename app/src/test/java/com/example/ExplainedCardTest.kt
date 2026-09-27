package com.example

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.ui.ExplainedCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExplainedCardTest {

    @get:Rule val rule = createComposeRule()

    private val note = "What these numbers mean."

    private fun show() = rule.setContent {
        MyApplicationTheme {
            Surface {
                ExplainedCard(note) { Text("Record 8-3") }
            }
        }
    }

    @Test
    fun `the explanation is hidden until the card is held, and holding again hides it`() {
        show()
        rule.onNodeWithText("Record 8-3").assertExists()
        rule.onNodeWithText(note).assertDoesNotExist()

        rule.onNodeWithText("Record 8-3").performTouchInput { longClick() }
        rule.onNodeWithText(note).assertExists()

        rule.onNodeWithText("Record 8-3").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(note).assertDoesNotExist()
    }

    @Test
    fun `the info button opens it too`() {
        show()
        rule.onNodeWithContentDescription("What this card means").performClick()
        rule.onNodeWithText(note).assertExists()
        rule.onNodeWithContentDescription("Hide explanation").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(note).assertDoesNotExist()
    }
}
