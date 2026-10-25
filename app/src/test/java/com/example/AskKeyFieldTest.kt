package com.example

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AskScreen
import com.example.ui.LocalPhone
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Anthropic API key stays in this app: its box on the Ask screen is kept
 * out of autofill, so the phone's password manager neither offers passwords
 * for it nor offers to save the key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AskKeyFieldTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `the API key box is not offered to the password manager`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent {
            CompositionLocalProvider(LocalPhone provides AndroidPhone(context)) {
                MyApplicationTheme { AskScreen(onBack = {}) }
            }
        }
        val box = rule.onNodeWithText("sk-ant-…").fetchSemanticsNode()
        assertEquals(ContentDataType.None, box.config.getOrNull(SemanticsProperties.ContentDataType))
    }
}
