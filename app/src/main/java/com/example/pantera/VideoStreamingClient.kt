package com.example.pantera

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import java.net.URISyntaxException

class VideoStreamingClient(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    private var socket: Socket? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null  // ⭐ CAMBIO: Nullable

    companion object {
        private const val TAG = "VideoStreamingClient"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val VIDEO_RESOLUTION_WIDTH = 640
        private const val VIDEO_RESOLUTION_HEIGHT = 480
        private const val VIDEO_FPS = 30
    }

    // Observador para eventos de PeerConnection
    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(iceCandidate: IceCandidate) {
            Log.d(TAG, "onIceCandidate: $iceCandidate")
            val json = JSONObject()
            try {
                json.put("type", "ice_candidate")
                json.put("candidate", iceCandidate.sdp)
                json.put("sdpMid", iceCandidate.sdpMid)
                json.put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                socket?.emit("ice_candidate", json)
            } catch (e: JSONException) {
                Log.e(TAG, "Error al crear JSON para IceCandidate", e)
            }
        }

        override fun onAddStream(mediaStream: MediaStream) { Log.d(TAG, "onAddStream") }
        override fun onRemoveStream(mediaStream: MediaStream) { Log.d(TAG, "onRemoveStream") }
        override fun onDataChannel(dataChannel: DataChannel) { Log.d(TAG, "onDataChannel") }
        override fun onRenegotiationNeeded() { Log.d(TAG, "onRenegotiationNeeded") }
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.d(TAG, "onIceConnectionChange: $newState")
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Log.d(TAG, "onIceGatheringChange: $newState")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Log.d(TAG, "onSignalingChange: $newState")
        }
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    }

    // Observador para eventos de SDP (Offer/Answer)
    private inner class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            Log.d(TAG, "SdpObserver onCreateSuccess")
            peerConnection?.setLocalDescription(this, sessionDescription)
            val json = JSONObject()
            try {
                json.put("type", sessionDescription.type.canonicalForm())
                json.put("sdp", sessionDescription.description)
                val eventName = if (sessionDescription.type == SessionDescription.Type.ANSWER) "answer" else "offer"
                socket?.emit(eventName, json)
            } catch (e: JSONException) {
                Log.e(TAG, "Error al crear JSON para SDP", e)
            }
        }
        override fun onSetSuccess() { Log.d(TAG, "SdpObserver onSetSuccess") }
        override fun onCreateFailure(s: String) { Log.e(TAG, "SdpObserver onCreateFailure: $s") }
        override fun onSetFailure(s: String) { Log.e(TAG, "SdpObserver onSetFailure: $s") }
    }


    fun startStreaming(serverUrl: String, deviceId: String) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "🎥 Iniciando streaming de video")
            Log.d(TAG, "   Server: $serverUrl")
            Log.d(TAG, "   Device ID: $deviceId")
            Log.d(TAG, "════════════════════════════════════════")

            val opts = IO.Options()
            opts.query = "deviceId=$deviceId"
            opts.transports = arrayOf("websocket")

            socket = IO.socket(serverUrl, opts)

            setupSocketListeners()
            socket?.connect()

            Log.d(TAG, "✅ Socket WebSocket configurado correctamente")

        } catch (e: URISyntaxException) {
            Log.e(TAG, "❌ Error de URI: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error iniciando streaming: ${e.message}", e)
        }
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "✅ Socket conectado exitosamente")
        }?.on(Socket.EVENT_DISCONNECT) {
            Log.d(TAG, "⚠️ Socket desconectado")
            stopStreaming()
        }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "❌ Error de conexión: ${args.getOrNull(0)}")
        }?.on("offer") { args ->
            Log.d(TAG, "📩 Oferta SDP recibida del servidor")
            try {
                val sdpJson = args[0] as JSONObject
                val sdpDescription = sdpJson.getString("sdp")
                val sdpType = SessionDescription.Type.fromCanonicalForm(sdpJson.getString("type").lowercase())

                initPeerConnection()

                peerConnection?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(sdpType, sdpDescription))
                createAnswer()
            } catch (e: JSONException) {
                Log.e(TAG, "❌ Error al parsear oferta SDP", e)
            }
        }?.on("ice_candidate") { args ->
            Log.d(TAG, "🧊 Candidato ICE recibido")
            try {
                val json = args[0] as JSONObject
                val candidate = IceCandidate(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
                Log.d(TAG, "✅ Candidato ICE añadido")
            } catch (e: JSONException) {
                Log.e(TAG, "❌ Error al parsear candidato ICE", e)
            }
        }
    }

    private fun initPeerConnection() {
        if (peerConnection != null) {
            Log.d(TAG, "⚠️ PeerConnection ya existe, reutilizando")
            return
        }

        Log.d(TAG, "🔧 Inicializando PeerConnection...")

        try {
            // Configuración de ICE Servers (STUN)
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            // Crear PeerConnection
            peerConnection = peerConnectionFactory.createPeerConnection(iceServers, peerConnectionObserver)
            Log.d(TAG, "✅ PeerConnection creada")

            // Inicializar helper de textura y capturador de video
            surfaceTextureHelper = SurfaceTextureHelper.create("VideoCapturerThread", eglBaseContext)
            Log.d(TAG, "✅ SurfaceTextureHelper creado")

            videoCapturer = createCameraCapturer()

            if (videoCapturer == null) {
                Log.e(TAG, "❌ No se pudo crear el capturador de video")
                return
            }
            Log.d(TAG, "✅ CameraVideoCapturer creado")

            // Crear fuente de video y track
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
            videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
            Log.d(TAG, "✅ VideoSource y VideoTrack creados")

            // Inicializar y arrancar capturador
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            videoCapturer?.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS)
            Log.d(TAG, "✅ Captura de cámara iniciada: ${VIDEO_RESOLUTION_WIDTH}x${VIDEO_RESOLUTION_HEIGHT} @ ${VIDEO_FPS}fps")

            // Añadir track de video al PeerConnection
            peerConnection?.addTrack(videoTrack)
            Log.d(TAG, "✅ Track de video añadido a PeerConnection")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando PeerConnection: ${e.message}", e)
        }
    }

    private fun createAnswer() {
        Log.d(TAG, "📝 Creando respuesta SDP...")
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))

        peerConnection?.createAnswer(SimpleSdpObserver(), constraints)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        Log.d(TAG, "🎥 Buscando cámaras disponibles...")
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        Log.d(TAG, "📱 Cámaras encontradas: ${deviceNames.size}")

        // Intentar encontrar la cámara trasera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                Log.d(TAG, "✅ Usando cámara trasera: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // Si no, intentar encontrar la cámara frontal
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "✅ Usando cámara frontal: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // Si no, usar la primera cámara disponible
        if (deviceNames.isNotEmpty()) {
            Log.d(TAG, "✅ Usando primera cámara disponible: ${deviceNames[0]}")
            return enumerator.createCapturer(deviceNames[0], null)
        }

        Log.e(TAG, "❌ No se encontraron cámaras disponibles")
        return null
    }

    fun stopStreaming() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "🛑 Deteniendo streaming de video...")
        Log.d(TAG, "════════════════════════════════════════")

        try {
            // ⭐ VERIFICACIÓN SEGURA ⭐
            if (videoCapturer != null) {
                try {
                    videoCapturer?.stopCapture()
                    Log.d(TAG, "✅ Captura de cámara detenida")
                } catch (e: InterruptedException) {
                    Log.e(TAG, "⚠️ Error al detener captura: ${e.message}", e)
                }
                videoCapturer?.dispose()
                videoCapturer = null
                Log.d(TAG, "✅ VideoCapturer liberado")
            }

            // ⭐ VERIFICACIÓN SEGURA ⭐
            surfaceTextureHelper?.let {
                it.dispose()
                surfaceTextureHelper = null
                Log.d(TAG, "✅ SurfaceTextureHelper liberado")
            }

            // ⭐ VERIFICACIÓN SEGURA ⭐
            videoTrack?.let {
                it.dispose()
                videoTrack = null
                Log.d(TAG, "✅ VideoTrack liberado")
            }

            // ⭐ VERIFICACIÓN SEGURA ⭐
            videoSource?.let {
                it.dispose()
                videoSource = null
                Log.d(TAG, "✅ VideoSource liberado")
            }

            // ⭐ VERIFICACIÓN SEGURA ⭐
            peerConnection?.let {
                it.close()
                it.dispose()
                peerConnection = null
                Log.d(TAG, "✅ PeerConnection cerrada y liberada")
            }

            // ⭐ VERIFICACIÓN SEGURA ⭐
            socket?.let {
                it.disconnect()
                it.off()
                socket = null
                Log.d(TAG, "✅ Socket desconectado y liberado")
            }

            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "✅ Streaming completamente detenido")
            Log.d(TAG, "════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deteniendo streaming: ${e.message}", e)
        }
    }
}