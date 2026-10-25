package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.JayhawksDatabase
import com.example.data.get
import com.example.platform.AndroidDevice
import com.example.ui.JayhawksApp
import com.example.ui.LocalPhone
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val phone = AndroidPhone(applicationContext)
        setContent {
            // Kept across rotation like any ViewModel; built here because it
            // takes the database, storage and upkeep rather than finding them.
            val viewModel = viewModel {
                JayhawksViewModel(
                    JayhawksDatabase.get(applicationContext).dao(),
                    AndroidDevice(applicationContext),
                    AndroidUpkeep(applicationContext)
                )
            }
            CompositionLocalProvider(LocalPhone provides phone) {
                MyApplicationTheme {
                    JayhawksApp(viewModel)
                }
            }
        }
    }
}
