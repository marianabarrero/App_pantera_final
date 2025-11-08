package com.example.pantera

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CameraPreviewActivity : ComponentActivity() {

    private val TAG = "CameraPreviewActivity"
    private lateinit var socketManager: DetectionSocketManager
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "🎥 CameraPreviewActivity iniciada - MODO VIEWER")

        deviceId = getAndroidDeviceId()
        socketManager = DetectionSocketManager.getInstance()

        val serverUrl = Constants.getVideoServerUrls().firstOrNull() ?: "https://panteratracker.tech"
        socketManager.connect(serverUrl, deviceId)
        Log.d(TAG, "🔌 Socket.IO conectando para deviceId: $deviceId")
        Log.d(TAG, "📹 CameraStreamingService continúa transmitiendo a Raspberry Pi")

        setContent {
            SMSLocationAppTheme {
                val detectionState by socketManager.detectionState.collectAsState()
                DetectionViewerScreen(
                    detectionState = detectionState,
                    deviceId = deviceId,
                    onBack = { finish() }
                )
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    @Composable
    fun DetectionViewerScreen(
        detectionState: DetectionData,
        deviceId: String,
        onBack: () -> Unit
    ) {
        // Animación pulsante para cuando detecta personas
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (detectionState.personCount > 0) 1.05f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = Color(0xFF0F172A) // Dark blue
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Indicador de cámara activa
                Box(
                    modifier = Modifier.size(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Círculo pulsante de fondo
                    if (detectionState.personCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(140.dp * scale)
                                .background(
                                    color = Color(0xFF4CAF50).copy(alpha = 0.2f),
                                    shape = CircleShape
                                )
                        )
                    }

                    // Icono de cámara
                    Card(
                        modifier = Modifier.size(120.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (detectionState.personCount > 0)
                                Color(0xFF4CAF50)
                            else
                                Color(0xFF1E293B)
                        ),
                        shape = CircleShape,
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Camera",
                                modifier = Modifier.size(60.dp),
                                tint = Color.White
                            )
                        }
                    }

                    // Indicador "LIVE"
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-8).dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Red
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "🔴 LIVE",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Título
                Text(
                    text = "Detección YOLO en Tiempo Real",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Procesado por Raspberry Pi",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(48.dp))

                // Card de conteo de personas
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (detectionState.personCount > 0)
                            Color(0xFF4CAF50)
                        else
                            Color(0xFF1E293B)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "👥",
                            fontSize = 48.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "${detectionState.personCount}",
                            fontSize = 96.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (detectionState.personCount == 1)
                                "Persona Detectada"
                            else
                                "Personas Detectadas",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.95f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Card de estado
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B).copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Estado de Conexión",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = detectionState.status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Indicador de conexión
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = if (detectionState.status.contains("Conectado", ignoreCase = true))
                                        Color(0xFF4CAF50)
                                    else
                                        Color(0xFFFF5252),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Info del device
                Text(
                    text = "Device ID: ${deviceId.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Nota informativa
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E3A8A).copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ℹ️",
                            fontSize = 24.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = "La cámara está transmitiendo a Raspberry Pi para análisis con YOLOv8",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF93C5FD),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Botón de regreso
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            socketManager.disconnect()
            Log.d(TAG, "✅ Socket.IO desconectado - CameraStreamingService sigue activo")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}")
        }
    }
}

data class DetectionData(
    val personCount: Int,
    val status: String
)