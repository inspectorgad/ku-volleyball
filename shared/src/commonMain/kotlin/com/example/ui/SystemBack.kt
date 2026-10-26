package com.example.ui

import androidx.compose.runtime.Composable

/**
 * Runs [onBack] when the phone's own back control is used while [enabled]:
 * Android's back button or gesture. The iPhone has no such control (its
 * screens go back through their on-screen arrows), so there it does nothing.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
