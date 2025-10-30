package com.example.pantera

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    // ⭐ TAG para logsssss
    private val TAG = "Pantera_MainActivity"

    private lateinit var backend: Backend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⭐ LOG: App iniciada
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "🚀 MainActivity onCreate() - App Iniciada")
        Log.d(TAG, "════════════════════════════════════════")

        try {
            backend = Backend(this)
            Log.d(TAG, "✅ Backend inicializado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando Backend: ${e.message}", e)
        }

        enableEdgeToEdge()
        Log.d(TAG, "✅ Edge-to-Edge habilitado")

        try {
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
            Log.d(TAG, "✅ UI inicializada con Jetpack Compose")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error configurando UI: ${e.message}", e)
        }

        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "✅ onCreate() completado exitosamente")
        Log.d(TAG, "════════════════════════════════════════")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "📱 onStart() - Activity visible")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "▶️  onResume() - Activity en primer plano")

        try {
            backend.onAppResumed()
            Log.d(TAG, "✅ Backend.onAppResumed() ejecutado")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en onAppResumed(): ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️  onPause() - Activity pausada")

        try {
            backend.onAppPaused()
            Log.d(TAG, "✅ Backend.onAppPaused() ejecutado")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en onAppPaused(): ${e.message}", e)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "⏹️  onStop() - Activity no visible")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "🛑 onDestroy() - Activity destruida")
        Log.d(TAG, "════════════════════════════════════════")
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "🔄 onRestart() - Activity reiniciada")
    }
}