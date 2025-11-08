package com.example.pantera

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URISyntaxException

class DetectionSocketManager {

    private val TAG = "DetectionSocketManager"

    private var socket: Socket? = null

    // Estado reactivo de detección
    private val _detectionState = MutableStateFlow(DetectionData(0, "Conectando..."))
    val detectionState: StateFlow<DetectionData> = _detectionState.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: DetectionSocketManager? = null

        fun getInstance(): DetectionSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DetectionSocketManager().also { INSTANCE = it }
            }
        }
    }

    fun connect(serverUrl: String, deviceId: String) {
        if (socket?.connected() == true) {
            Log.d(TAG, "⚠️ Ya conectado a Socket.IO")
            return
        }

        try {
            Log.d(TAG, "🔌 Conectando a Socket.IO: $serverUrl")

            val opts = IO.Options().apply {
                transports = arrayOf("websocket")
                reconnection = true
                reconnectionAttempts = Integer.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 10000
            }

            socket = IO.socket(serverUrl, opts)

            setupSocketListeners(deviceId)

            socket?.connect()

        } catch (e: URISyntaxException) {
            Log.e(TAG, "❌ Error en URI de Socket.IO: ${e.message}", e)
            _detectionState.value = DetectionData(0, "Error de conexión")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error conectando Socket.IO: ${e.message}", e)
            _detectionState.value = DetectionData(0, "Error de conexión")
        }
    }

    private fun setupSocketListeners(deviceId: String) {
        socket?.apply {
            // Evento de conexión exitosa
            on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ Conectado a Socket.IO")
                _detectionState.value = DetectionData(0, "Conectado - Esperando detecciones...")
            }

            // Evento de desconexión
            on(Socket.EVENT_DISCONNECT) {
                Log.w(TAG, "⚠️ Desconectado de Socket.IO")
                _detectionState.value = DetectionData(0, "Desconectado")
            }

            // Evento de error de conexión
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args.getOrNull(0)
                Log.e(TAG, "❌ Error de conexión Socket.IO: $error")
                _detectionState.value = DetectionData(0, "Error de conexión")
            }

            // ⭐ EVENTO PRINCIPAL: person-detection ⭐
            on("detection-update") { args ->  // ✅ Broadcast del servidor
                try {
                    val data = args.getOrNull(0) as? JSONObject
                    if (data != null) {
                        val detectedDeviceId = data.optString("deviceId", "")
                        val personCount = data.optInt("personCount", 0)
                        val timestamp = data.optString("timestamp", "")

                        // ✅ Verificar que sea del dispositivo correcto
                        if (detectedDeviceId == deviceId) {
                            Log.d(TAG, "👤 Detección recibida: $personCount persona(s)")

                            val status = when {
                                personCount > 0 -> "✅ Detectado: $personCount ${if (personCount == 1) "persona" else "personas"}"
                                else -> "⚠️ No se detectan personas"
                            }

                            _detectionState.value = DetectionData(personCount, status)
                        } else {
                            Log.d(TAG, "🔕 Detección ignorada (otro dispositivo: $detectedDeviceId)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error procesando person-detection: ${e.message}", e)
                }
            }

            // ⭐ EVENTO DE RECONEXIÓN (como string personalizado)
            on("reconnect") { args ->
                Log.d(TAG, "🔄 Reconectado a Socket.IO")
                _detectionState.value = DetectionData(0, "Reconectado - Esperando detecciones...")
            }
        }
    }

    fun disconnect() {
        try {
            socket?.off() // Remover todos los listeners
            socket?.disconnect()
            socket?.close()
            socket = null
            Log.d(TAG, "✅ Socket.IO desconectado correctamente")
            _detectionState.value = DetectionData(0, "Desconectado")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error desconectando Socket.IO: ${e.message}", e)
        }
    }

    fun isConnected(): Boolean {
        return socket?.connected() == true
    }
}