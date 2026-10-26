package com.example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.example.data.JayhawksDatabase
import com.example.data.createDatabase
import com.example.platform.Device
import com.example.platform.IosDevice
import com.example.ui.JayhawksApp
import com.example.ui.LocalPhone
import com.example.ui.Phone
import com.example.ui.ResultCard
import com.example.ui.theme.MyApplicationTheme
import platform.UIKit.UIViewController

// The iPhone app's way in. Swift calls MainViewController() (as
// MainViewControllerKt.MainViewController(), after this file's name) and shows
// what it returns; everything on screen from there is the shared code, the
// same screens the Android app draws.

internal val iosDevice: Device by lazy { IosDevice() }
private val database: JayhawksDatabase by lazy { createDatabase() }

fun MainViewController(): UIViewController = ComposeUIViewController {
    // One view model for the life of the app, as Android's ViewModel gives.
    val viewModel = remember { JayhawksViewModel(database.dao(), iosDevice, IosUpkeep) }
    CompositionLocalProvider(LocalPhone provides IosPhone) {
        MyApplicationTheme {
            JayhawksApp(viewModel)
        }
    }
}

/**
 * [Phone] on iPhone. Match alerts come as push notifications from the
 * match-night watcher (IosAlerts.kt); the result picture is not drawn here
 * yet, so it does not show.
 */
private object IosPhone : Phone {
    override val device: Device get() = iosDevice

    @Composable
    override fun AlertsCard() = IosAlertsCard()

    override val sharesResultPictures: Boolean get() = false

    override fun shareResult(card: ResultCard) = Unit
}

/** [Upkeep] on iPhone: nothing yet; the home-screen widget comes later. */
private object IosUpkeep : Upkeep {
    override fun appOpened() = Unit

    override suspend fun dataChanged() = Unit
}
