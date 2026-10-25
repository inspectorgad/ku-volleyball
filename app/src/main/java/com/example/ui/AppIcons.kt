package com.example.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn from Google's Material icon outlines
 * (github.com/google/material-design-icons, Apache 2.0). They used to come
 * from the material-icons libraries, which stopped at version 1.7.3 and are
 * not kept up for iPhone; these few are the same shapes without them. Icon()
 * colours them, as it did the library's.
 */
object AppIcons {
    val Add: ImageVector by lazy { icon("Add", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val ArrowBack: ImageVector by lazy { icon("ArrowBack", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z", autoMirror = true) }
    val Delete: ImageVector by lazy { icon("Delete", "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z") }
    val Edit: ImageVector by lazy { icon("Edit", "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z") }
    val EmojiEvents: ImageVector by lazy { icon("EmojiEvents", "M19,5h-2V3H7v2H5C3.9,5,3,5.9,3,7v1c0,2.55,1.92,4.63,4.39,4.94c0.63,1.5,1.98,2.63,3.61,2.96V19H7v2h10v-2h-4v-3.1 c1.63-0.33,2.98-1.46,3.61-2.96C19.08,12.63,21,10.55,21,8V7C21,5.9,20.1,5,19,5z M5,8V7h2v3.82C5.84,10.4,5,9.3,5,8z M19,8 c0,1.3-0.84,2.4-2,2.82V7h2V8z") }
    val Groups: ImageVector by lazy { icon("Groups", "M12,12.75c1.63,0,3.07,0.39,4.24,0.9c1.08,0.48,1.76,1.56,1.76,2.73L18,18H6l0-1.61c0-1.18,0.68-2.26,1.76-2.73 C8.93,13.14,10.37,12.75,12,12.75z M4,13c1.1,0,2-0.9,2-2c0-1.1-0.9-2-2-2s-2,0.9-2,2C2,12.1,2.9,13,4,13z M5.13,14.1 C4.76,14.04,4.39,14,4,14c-0.99,0-1.93,0.21-2.78,0.58C0.48,14.9,0,15.62,0,16.43V18l4.5,0v-1.61C4.5,15.56,4.73,14.78,5.13,14.1z M20,13c1.1,0,2-0.9,2-2c0-1.1-0.9-2-2-2s-2,0.9-2,2C18,12.1,18.9,13,20,13z M24,16.43c0-0.81-0.48-1.53-1.22-1.85 C21.93,14.21,20.99,14,20,14c-0.39,0-0.76,0.04-1.13,0.1c0.4,0.68,0.63,1.46,0.63,2.29V18l4.5,0V16.43z M12,6c1.66,0,3,1.34,3,3 c0,1.66-1.34,3-3,3s-3-1.34-3-3C9,7.34,10.34,6,12,6z") }
    val Info: ImageVector by lazy { icon("Info", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z") }
    val InfoOutlined: ImageVector by lazy { icon("InfoOutlined", "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z") }
    val Leaderboard: ImageVector by lazy { icon("Leaderboard", "M7.5,21H2V9h5.5V21z M14.75,3h-5.5v18h5.5V3z M22,11h-5.5v10H22V11z") }
    val List: ImageVector by lazy { icon("List", "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z", autoMirror = true) }
    val Shield: ImageVector by lazy { icon("Shield", "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z") }
    val SportsVolleyball: ImageVector by lazy { icon("SportsVolleyball", "M6,4.01C3.58,5.84,2,8.73,2,12c0,1.46,0.32,2.85,0.89,4.11L6,14.31V4.01z M11,11.42V2.05C9.94,2.16,8.93,2.43,8,2.84v10.32L11,11.42z M12,13.15l-8.11,4.68c0.61,0.84,1.34,1.59,2.18,2.2L15,14.89L12,13.15z M13,7.96v3.46l8.11,4.68c0.42-0.93,0.7-1.93,0.82-2.98L13,7.96z M8.07,21.2C9.28,21.71,10.6,22,12,22c3.34,0,6.29-1.65,8.11-4.16L17,16.04L8.07,21.2z M21.92,10.81c-0.55-4.63-4.26-8.3-8.92-8.76v3.6L21.92,10.81z") }
}

/** A 24dp icon filled from SVG path data; [autoMirror] flips it for right-to-left languages. */
private fun icon(name: String, pathData: String, autoMirror: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
