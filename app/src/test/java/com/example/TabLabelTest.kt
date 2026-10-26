package com.example

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.example.ui.AppIcons
import com.example.ui.TabLabel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The six tab names stay whole on a narrow bar. 382dp is the iPhone Duo's
 * front screen (466 points) less its 84-point status column, where "Matches"
 * and "Opponents" used to break mid-word onto a second line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w382dp-h678dp")
class TabLabelTest {

    @get:Rule val rule = createComposeRule()

    private val names = listOf("Roster", "Matches", "Leaders", "Serving", "Opponents", "Big 12")

    @Test
    fun `every tab name fits on one line`() {
        rule.setContent {
            MyApplicationTheme {
                NavigationBar {
                    names.forEachIndexed { i, name ->
                        NavigationBarItem(
                            selected = i == 0,
                            onClick = {},
                            icon = { Icon(AppIcons.List, contentDescription = null) },
                            label = { TabLabel(name) }
                        )
                    }
                }
            }
        }
        for (name in names) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(name, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$name: lines", 1, layout.lineCount)
            assertEquals("$name: cut short", false, layout.hasVisualOverflow)
        }
    }
}
