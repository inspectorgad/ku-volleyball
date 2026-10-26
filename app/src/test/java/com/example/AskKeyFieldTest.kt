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
 * The Anthropic API key stays in this app: its box on the Ask screen is marked
 * as holding no autofill data, so it can neither start an autofill session nor
 * feed one (and saving the key closes any session another box opened, unsaved).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AskKeyFieldTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `the API key box can neither start nor feed an autofill session`() {
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
