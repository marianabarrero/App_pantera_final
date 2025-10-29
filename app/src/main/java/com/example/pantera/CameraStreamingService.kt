package com.tudominio.smslocation

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraStreaming(private val context: Context) {

    companion object {
        private const val TAG = "CameraStreaming"
        private const val FRAME_RATE = 15 // Frames por segundo
        private const val JPEG_QUALITY = 70 // Calidad del JPEG (0-100)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isStreaming = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val streamingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var streamingJob: Job? = null

    fun startStreaming(
        lifecycleOwner: LifecycleOwner,
        serverIP: String,
        port: Int,
        deviceId: String,
        onError: (String) -> Unit
    ) {
        if (isStreaming) {
            Log.d(TAG, "Streaming already active")
            return
        }

        isStreaming = true
        Log.d(TAG, "Starting camera streaming to $serverIP:$port")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, serverIP, port, deviceId, onError)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera", e)
                onError("Error al iniciar cámara: ${e.message}")
                isStreaming = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        serverIP: String,
        port: Int,
        deviceId: String,
        onError: (String) -> Unit
    ) {
        val cameraProvider = cameraProvider ?: return

        // Configurar análisis de imagen para capturar frames
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (isStreaming) {
                        processAndSendFrame(imageProxy, serverIP, port, deviceId)
                    }
                    imageProxy.close()
                }
            }

        // Seleccionar cámara trasera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Desvincular casos de uso previos
            cameraProvider.unbindAll()

            // Vincular casos de uso a la cámara
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )

            Log.d(TAG, "Camera bound successfully, streaming started")

        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            onError("Error al conectar cámara: ${e.message}")
            isStreaming = false
        }
    }

    private fun processAndSendFrame(
        imageProxy: ImageProxy,
        serverIP: String,
        port: Int,
        deviceId: String
    ) {
        streamingScope.launch {
            try {
                // Convertir imagen a bytes (formato simplificado)
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                // Crear JSON con datos del frame
                val frameData = buildString {
                    append("{")
                    append("\"type\":\"video_frame\",")
                    append("\"deviceId\":\"$deviceId\",")
                    append("\"timestamp\":${System.currentTimeMillis()},")
                    append("\"width\":${imageProxy.width},")
                    append("\"height\":${imageProxy.height},")
                    append("\"format\":\"YUV_420_888\",")
                    append("\"dataSize\":${bytes.size}")
                    append("}")
                }

                // Enviar frame al servidor
                sendFrameToServer(serverIP, port, frameData, bytes)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            }
        }
    }

    private suspend fun sendFrameToServer(
        serverIP: String,
        port: Int,
        metadata: String,
        frameData: ByteArray
    ) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var writer: PrintWriter? = null

        try {
            socket = Socket().apply {
                soTimeout = 3000
                tcpNoDelay = true
            }

            socket.connect(
                java.net.InetSocketAddress(serverIP, port + 1), // Puerto diferente para video
                3000
            )

            // Enviar metadata primero
            writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(metadata)
            writer.flush()

            // Nota: Para un streaming real, necesitarías enviar los bytes del frame
            // Esto requiere un protocolo más sofisticado en el servidor

            Log.d(TAG, "Frame sent to $serverIP:${port + 1}")

        } catch (e: Exception) {
            Log.w(TAG, "Error sending frame: ${e.message}")
        } finally {
            try {
                writer?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }

    fun stopStreaming() {
        if (!isStreaming) {
            return
        }

        Log.d(TAG, "Stopping camera streaming")
        isStreaming = false
        streamingJob?.cancel()

        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()

        Log.d(TAG, "Camera streaming stopped")
    }

    fun isActive(): Boolean = isStreaming
}