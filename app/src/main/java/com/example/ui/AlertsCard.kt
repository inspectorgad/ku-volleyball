package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.AlertSettings

const val EXPLAIN_ALERTS =
    "A notification as each set ends - who took it and the score in sets - and one at the " +
        "final with the set scores and the top performers, read from the NCAA's live feed. The " +
        "app checks from 15 minutes before first serve, every few minutes; Android can hold " +
        "background checks back to save battery, so an alert can arrive a few minutes after the " +
        "set. For the home-screen widget, long-press your home screen, choose Widgets, and find " +
        "KU Volleyball."

/** The match-alerts switch, at the top of the Matches tab. */
@Composable
fun AlertsCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AlertSettings.enabled(context)) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        on = granted
        AlertSettings.setEnabled(context, granted)
    }
    ExplainedCard(EXPLAIN_ALERTS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Match alerts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (on) "On: each set and the final score" else "Off",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { want ->
                    val needsAsk = want && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needsAsk) {
                        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        on = want
                        AlertSettings.setEnabled(context, want)
                    }
                }
            )
        }
        if (denied) {
            Text(
                "Notifications are blocked for this app. Allow them in Android Settings > Apps > " +
                    "KU Volleyball > Notifications, then turn this on again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
