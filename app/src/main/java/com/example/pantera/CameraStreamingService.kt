package com.example.pantera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.*

class CameraStreamingService : Service() {

    private var videoStreamingClient: VideoStreamingClient? = null
    private var eglBase: EglBase? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "CameraStreamingChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_STREAMING = "ACTION_START_STREAMING"
        const val ACTION_STOP_STREAMING = "ACTION_STOP_STREAMING"
        const val EXTRA_SERVER_URL = "EXTRA_SERVER_URL"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 1. Inicializar EglBase (necesario para WebRTC)
        eglBase = EglBase.create()

        // 2. Inicializar PeerConnectionFactory
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
    }

    private fun createNotification(): Notification {
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Servicio de Streaming")
            .setContentText("Transmitiendo video...")
            .setSmallIcon(R.mipmap.ic_launcher) // Asegúrate de tener este ícono
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return notificationBuilder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Canal de Servicio de Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("CameraStreamingService", "Acción recibida: $action")

        if (action == ACTION_START_STREAMING) {
            val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
            val eglContext = eglBase?.eglBaseContext

            if (serverUrl != null && deviceId != null && eglContext != null) {
                if (videoStreamingClient == null) {
                    Log.d("CameraStreamingService", "Iniciando nuevo VideoStreamingClient")
                    // 3. Crear el PeerConnectionFactory
                    val factory = createPeerConnectionFactory()

                    // 4. Crear e iniciar el cliente de streaming
                    videoStreamingClient = VideoStreamingClient(
                        context = this,
                        eglBaseContext = eglContext,
                        peerConnectionFactory = factory
                    )
                }
                videoStreamingClient?.startStreaming(serverUrl, deviceId)
            } else {
                Log.e("CameraStreamingService", "Faltan datos para iniciar el stream o EGL no está listo.")
            }
        } else if (action == ACTION_STOP_STREAMING) {
            Log.d("CameraStreamingService", "Deteniendo VideoStreamingClient")
            videoStreamingClient?.stopStreaming()
            videoStreamingClient = null
            stopForeground(true)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    override fun onDestroy() {
        Log.d("CameraStreamingService", "Servicio destruido")
        videoStreamingClient?.stopStreaming()
        eglBase?.release()
        PeerConnectionFactory.shutdownInternalTracer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}