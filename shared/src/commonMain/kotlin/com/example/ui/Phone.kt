package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.platform.Device

/**
 * What the screens need from the phone they run on, beyond what the view
 * model gives them. The app provides one at the top of the screen tree
 * ([LocalPhone]), so the screens themselves never reach for Android (or,
 * later, iPhone) directly: Android's is AndroidPhone.
 */
interface Phone {
    /** Saved settings, files and secrets, for the screens that keep their own (Ask's key and model). */
    val device: Device

    /** Whether this phone offers match alerts, and so shows [AlertsCard]. */
    val hasMatchAlerts: Boolean get() = true

    /** The match-alerts switch at the top of the Matches tab, which needs the phone's permission to notify. */
    @Composable
    fun AlertsCard()

    /** Whether this phone can draw and share a result picture, and so shows the button for it. */
    val sharesResultPictures: Boolean get() = true

    /** Hands a finished match's result picture to the phone's share sheet. */
    fun shareResult(card: ResultCard)
}

val LocalPhone = staticCompositionLocalOf<Phone> { error("No Phone provided: wrap the screens in CompositionLocalProvider(LocalPhone provides ...)") }
