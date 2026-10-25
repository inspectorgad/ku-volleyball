package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.ExplainedCard
import kotlinx.coroutines.flow.MutableStateFlow
// UIKit declares the remote-notification calls in a category, which Kotlin
// sees as extension functions, hence the whole package.
import platform.UIKit.*
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// Match alerts on iPhone. Unlike Android's, they are not worked out on the
// phone: the match-night watcher on GitHub reads the live feed and sends them
// through Apple's push service (scripts/apns.mjs). The phone's part is to ask
// for permission, get its alert code (APNs device token) from Apple, and show
// it so it can be saved once as the GitHub secret APNS_DEVICE_TOKENS.
//
// The Swift app delegate passes Apple's answers in through pushTokenReceived
// and pushRegistrationFailed, which Swift sees as IosAlertsKt.

const val EXPLAIN_IPHONE_ALERTS =
    "A notification as each set ends - who took it and the score in sets - and one at the " +
        "final with the set scores and the top performers, the same alerts the Android app " +
        "sends. On iPhone they come from the match-night watcher on GitHub, which follows the " +
        "NCAA's live feed every few minutes from first serve. Turn alerts on, then save this " +
        "iPhone's alert code once in GitHub: the repository's Settings > Secrets and variables > " +
        "Actions > New repository secret, named APNS_DEVICE_TOKENS. GitHub can start the watcher " +
        "a few minutes late, so an alert can trail its set by a few minutes."

private const val GROUP = "match_alerts"

private object AlertsState {
    val enabled = MutableStateFlow(iosDevice.settings(GROUP).boolean("enabled"))
    val token = MutableStateFlow(iosDevice.settings(GROUP).string("device_token"))
    val problem = MutableStateFlow<String?>(null)
}

/** Called by the app delegate with the alert code Apple gave this iPhone. */
fun pushTokenReceived(token: String) {
    iosDevice.settings(GROUP).edit { putString("device_token", token) }
    AlertsState.token.value = token
    AlertsState.problem.value = null
}

/** Called by the app delegate when Apple would not give this iPhone an alert code. */
fun pushRegistrationFailed(reason: String) {
    AlertsState.problem.value = "Apple did not give this iPhone an alert code: $reason"
}

/** Called at launch: an app with alerts on asks Apple for its code each time, as Apple recommends. */
fun registerForAlertsIfOn() {
    if (AlertsState.enabled.value) UIApplication.sharedApplication.registerForRemoteNotifications()
}

private fun setEnabled(on: Boolean) {
    iosDevice.settings(GROUP).edit { putBoolean("enabled", on) }
    AlertsState.enabled.value = on
}

private fun turnOn() {
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
        UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
    ) { granted, _ ->
        dispatch_async(dispatch_get_main_queue()) {
            if (granted) {
                setEnabled(true)
                AlertsState.problem.value = null
                UIApplication.sharedApplication.registerForRemoteNotifications()
            } else {
                setEnabled(false)
                AlertsState.problem.value = "Notifications are off for this app. Allow them in the iPhone's " +
                    "Settings > Notifications > KU Volleyball, then turn this on again."
            }
        }
    }
}

private fun turnOff() {
    setEnabled(false)
    // Apple then refuses the watcher's alerts for this code, so they stop.
    UIApplication.sharedApplication.unregisterForRemoteNotifications()
}

/** The match-alerts switch at the top of the Matches tab, on iPhone. */
@Composable
internal fun IosAlertsCard() {
    val enabled by AlertsState.enabled.collectAsState()
    val token by AlertsState.token.collectAsState()
    val problem by AlertsState.problem.collectAsState()
    var copied by remember { mutableStateOf(false) }
    ExplainedCard(EXPLAIN_IPHONE_ALERTS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Match alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (enabled) "On: each set and the final score" else "Off",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(checked = enabled, onCheckedChange = { want -> if (want) turnOn() else turnOff() })
        }
        val code = token
        if (enabled && code != null) {
            Text(
                "This iPhone's alert code, to save once in GitHub as APNS_DEVICE_TOKENS (see the info button):",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            TextButton(onClick = {
                UIPasteboard.generalPasteboard.string = code
                copied = true
            }) {
                Text(if (copied) "Copied" else "Copy code")
            }
        }
        problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
