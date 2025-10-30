package com.example.pantera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    private lateinit var backend: Backend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        backend = Backend(this)

        enableEdgeToEdge()

        setContent {
            SMSLocationAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(backend = backend)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        backend.onAppResumed()
    }

    override fun onPause() {
        super.onPause()
        backend.onAppPaused()
    }
}