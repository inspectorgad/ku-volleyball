package com.example

import android.content.Context
import androidx.compose.runtime.Composable
import com.example.data.KuWidget
import com.example.data.MatchAlertWorker
import com.example.platform.AndroidDevice
import com.example.platform.Device
import com.example.ui.MatchAlertsCard
import com.example.ui.Phone
import com.example.ui.ResultCard
import com.example.ui.shareResultCard

/** [Phone] on Android: alerts through notifications and WorkManager, sharing through the share sheet. */
class AndroidPhone(context: Context) : Phone {
    private val context = context.applicationContext

    override val device: Device = AndroidDevice(this.context)

    @Composable
    override fun AlertsCard() = MatchAlertsCard()

    override fun shareResult(card: ResultCard) = shareResultCard(context, card)
}

/** [Upkeep] on Android: the home-screen widget and the WorkManager background check. */
class AndroidUpkeep(context: Context) : Upkeep {
    private val context = context.applicationContext

    override fun appOpened() = MatchAlertWorker.reschedule(context)

    override suspend fun dataChanged() {
        runCatching { KuWidget.updateAll(context) }
    }
}
