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

    // ⭐ CAMBIO: Ahora usamos un mapa para múltiples conexiones ⭐
    private val peerConnections: MutableMap<String, PeerConnection> = mutableMapOf()

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var deviceId: String = ""

    companion object {
        private const val TAG = "VideoStreamingClient"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val VIDEO_RESOLUTION_WIDTH = 640
        private const val VIDEO_RESOLUTION_HEIGHT = 480
        private const val VIDEO_FPS = 30
    }

    // ⭐ CAMBIO: Crear un observador único para cada viewer ⭐
    private fun createPeerConnectionObserver(viewerId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(iceCandidate: IceCandidate) {
            Log.d(TAG, "🧊 onIceCandidate para viewer $viewerId: ${iceCandidate.sdpMid}")

            val json = JSONObject()
            try {
                json.put("target", viewerId)
                json.put("candidate", JSONObject().apply {
                    put("sdpMid", iceCandidate.sdpMid)
                    put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                    put("candidate", iceCandidate.sdp)
                })
                socket?.emit("ice-candidate", json)
                Log.d(TAG, "📤 ICE candidate enviado al viewer $viewerId")
            } catch (e: JSONException) {
                Log.e(TAG, "Error al crear JSON para IceCandidate", e)
            }
        }

        override fun onAddStream(mediaStream: MediaStream) {
            Log.d(TAG, "onAddStream - Viewer: $viewerId")
        }
        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.d(TAG, "onRemoveStream - Viewer: $viewerId")
        }
        override fun onDataChannel(dataChannel: DataChannel) {
            Log.d(TAG, "onDataChannel - Viewer: $viewerId")
        }
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "onRenegotiationNeeded - Viewer: $viewerId")
        }
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.d(TAG, "❄️ onIceConnectionChange para $viewerId: $newState")

            // ⭐ NUEVO: Limpiar conexión si falla o se desconecta ⭐
            if (newState == PeerConnection.IceConnectionState.FAILED ||
                newState == PeerConnection.IceConnectionState.CLOSED) {
                Log.w(TAG, "⚠️ Limpiando conexión fallida para viewer: $viewerId")
                peerConnections[viewerId]?.close()
                peerConnections.remove(viewerId)
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Log.d(TAG, "🧊 onIceGatheringChange para $viewerId: $newState")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Log.d(TAG, "📡 onSignalingChange para $viewerId: $newState")
        }
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    }

    // ⭐ CAMBIO: Crear un SdpObserver único para cada viewer ⭐
    private inner class ViewerSdpObserver(private val viewerId: String) : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            Log.d(TAG, "✅ SDP Created para viewer $viewerId: ${sessionDescription.type}")

            peerConnections[viewerId]?.setLocalDescription(this, sessionDescription)

            val json = JSONObject()
            try {
                json.put("target", viewerId)
                json.put("sdp", JSONObject().apply {
                    put("type", sessionDescription.type.canonicalForm())
                    put("sdp", sessionDescription.description)
                })
                socket?.emit("offer", json)
                Log.d(TAG, "📤 Offer enviado al viewer $viewerId")
            } catch (e: JSONException) {
                Log.e(TAG, "Error al crear JSON para SDP", e)
            }
        }

        override fun onSetSuccess() {
            Log.d(TAG, "✅ SdpObserver onSetSuccess para viewer $viewerId")
        }

        override fun onCreateFailure(s: String) {
            Log.e(TAG, "❌ SdpObserver onCreateFailure para viewer $viewerId: $s")
        }

        override fun onSetFailure(s: String) {
            Log.e(TAG, "❌ SdpObserver onSetFailure para viewer $viewerId: $s")
        }
    }

    fun startStreaming(serverUrl: String, deviceId: String) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "🎥 Iniciando streaming de video")
            Log.d(TAG, "   Server: $serverUrl")
            Log.d(TAG, "   Device ID: $deviceId")
            Log.d(TAG, "════════════════════════════════════════")

            this.deviceId = deviceId

            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1000
                forceNew = true
            }

            socket = IO.socket(serverUrl, opts)
            setupSocketListeners()
            socket?.connect()

            Log.d(TAG, "✅ Socket configurado correctamente")

        } catch (e: URISyntaxException) {
            Log.e(TAG, "❌ Error de URI: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error iniciando streaming: ${e.message}", e)
        }
    }

    private fun setupSocketListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "✅ Socket conectado exitosamente")

            // ⭐ REGISTRARSE COMO BROADCASTER ⭐
            val registerData = JSONObject().apply {
                put("deviceId", deviceId)
            }
            socket?.emit("register-broadcaster", registerData)
            Log.d(TAG, "📡 Registrado como broadcaster: $deviceId")

        }?.on(Socket.EVENT_DISCONNECT) {
            Log.d(TAG, "⚠️ Socket desconectado")

        }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "❌ Error de conexión: ${args.getOrNull(0)}")

        }?.on("viewer-joined") { args ->
            // ⭐ CAMBIO: Ya no cerramos conexiones anteriores ⭐
            Log.d(TAG, "👀 Viewer se unió!")
            try {
                val data = args[0] as JSONObject
                val viewerId = data.getString("socketId")

                Log.d(TAG, "📱 Creando PeerConnection para viewer: $viewerId")
                Log.d(TAG, "📊 Total de conexiones activas: ${peerConnections.size}")

                createPeerConnectionForViewer(viewerId)

            } catch (e: JSONException) {
                Log.e(TAG, "❌ Error al parsear viewer-joined", e)
            }

        }?.on("answer") { args ->
            // ⭐ CAMBIO: Identificar a qué viewer pertenece esta respuesta ⭐
            Log.d(TAG, "📩 Answer SDP recibido")
            try {
                val data = args[0] as JSONObject
                val senderId = data.optString("sender") // El backend debe incluir esto
                val sdpData = data.getJSONObject("sdp")
                val sdpDescription = sdpData.getString("sdp")

                val answer = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    sdpDescription
                )

                // Buscar la conexión correcta
                val peerConnection = if (senderId.isNotEmpty()) {
                    peerConnections[senderId]
                } else {
                    // Fallback: usar la última conexión creada
                    peerConnections.values.lastOrNull()
                }

                peerConnection?.setRemoteDescription(ViewerSdpObserver(senderId), answer)
                Log.d(TAG, "✅ Remote description (answer) establecida para viewer $senderId")

            } catch (e: JSONException) {
                Log.e(TAG, "❌ Error al parsear answer SDP", e)
            }

        }?.on("ice-candidate") { args ->
            Log.d(TAG, "🧊 Candidato ICE recibido")
            try {
                val data = args[0] as JSONObject
                val senderId = data.optString("sender") // El backend debe incluir esto
                val candidateData = data.getJSONObject("candidate")

                val candidate = IceCandidate(
                    candidateData.getString("sdpMid"),
                    candidateData.getInt("sdpMLineIndex"),
                    candidateData.getString("candidate")
                )

                // Buscar la conexión correcta
                val peerConnection = if (senderId.isNotEmpty()) {
                    peerConnections[senderId]
                } else {
                    // Fallback: añadir a todas las conexiones activas
                    peerConnections.values.forEach { it.addIceCandidate(candidate) }
                    null
                }

                peerConnection?.addIceCandidate(candidate)
                Log.d(TAG, "✅ Candidato ICE añadido para viewer $senderId")

            } catch (e: JSONException) {
                Log.e(TAG, "❌ Error al parsear candidato ICE", e)
            }
        }?.on("viewer-disconnected") { args ->
            // ⭐ NUEVO: Limpiar cuando un viewer se desconecta ⭐
            try {
                val data = args[0] as JSONObject
                val viewerId = data.getString("viewerId")

                Log.d(TAG, "👋 Viewer desconectado: $viewerId")
                peerConnections[viewerId]?.close()
                peerConnections.remove(viewerId)
                Log.d(TAG, "✅ Conexión cerrada. Conexiones restantes: ${peerConnections.size}")

            } catch (e: JSONException) {
                Log.e(TAG, "❌ Error al parsear viewer-disconnected", e)
            }
        }
    }

    // ⭐ NUEVO: Método para crear una conexión para un viewer específico ⭐
    private fun createPeerConnectionForViewer(viewerId: String) {
        // Si ya existe una conexión para este viewer, cerrarla primero
        if (peerConnections.containsKey(viewerId)) {
            Log.w(TAG, "⚠️ Ya existe conexión para $viewerId, cerrando la anterior")
            peerConnections[viewerId]?.close()
            peerConnections.remove(viewerId)
        }

        Log.d(TAG, "🔧 Inicializando PeerConnection para viewer: $viewerId")

        try {
            // Configuración de ICE Servers (STUN)
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            }

            // ⭐ Crear PeerConnection con observer único para este viewer ⭐
            val peerConnection = peerConnectionFactory.createPeerConnection(
                rtcConfig,
                createPeerConnectionObserver(viewerId)
            )

            if (peerConnection == null) {
                Log.e(TAG, "❌ No se pudo crear PeerConnection para $viewerId")
                return
            }

            peerConnections[viewerId] = peerConnection
            Log.d(TAG, "✅ PeerConnection creada para viewer $viewerId")

            // ⭐ IMPORTANTE: Inicializar recursos de video solo una vez ⭐
            initializeVideoResourcesIfNeeded()

            // Añadir track de video a esta conexión
            val streamId = "stream_${deviceId}_$viewerId"
            peerConnection.addTrack(videoTrack, listOf(streamId))
            Log.d(TAG, "✅ Track de video añadido a PeerConnection para $viewerId")

            // ⭐ Crear OFFER para este viewer específico ⭐
            createOfferForViewer(viewerId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando PeerConnection para $viewerId: ${e.message}", e)
        }
    }

    // ⭐ NUEVO: Inicializar recursos de video solo una vez ⭐
    private fun initializeVideoResourcesIfNeeded() {
        // Inicializar helper de textura y capturador de video
        if (surfaceTextureHelper == null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("VideoCapturerThread", eglBaseContext)
            Log.d(TAG, "✅ SurfaceTextureHelper creado")
        }

        if (videoCapturer == null) {
            videoCapturer = createCameraCapturer()

            if (videoCapturer == null) {
                Log.e(TAG, "❌ No se pudo crear el capturador de video")
                return
            }
            Log.d(TAG, "✅ CameraVideoCapturer creado")
        }

        // Crear fuente de video y track
        if (videoSource == null) {
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
            videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
            Log.d(TAG, "✅ VideoSource y VideoTrack creados")

            // Inicializar y arrancar capturador
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            videoCapturer?.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS)
            Log.d(TAG, "✅ Captura de cámara iniciada: ${VIDEO_RESOLUTION_WIDTH}x${VIDEO_RESOLUTION_HEIGHT} @ ${VIDEO_FPS}fps")
        }
    }

    // ⭐ NUEVO: Crear offer para un viewer específico ⭐
    private fun createOfferForViewer(viewerId: String) {
        Log.d(TAG, "📝 Creando OFFER SDP para viewer: $viewerId")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnections[viewerId]?.createOffer(ViewerSdpObserver(viewerId), constraints)
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
            // ⭐ CAMBIO: Cerrar todas las PeerConnections ⭐
            peerConnections.forEach { (viewerId, pc) ->
                Log.d(TAG, "🔌 Cerrando conexión para viewer: $viewerId")
                pc.close()
                pc.dispose()
            }
            peerConnections.clear()
            Log.d(TAG, "✅ Todas las PeerConnections cerradas")

            videoCapturer?.let {
                try {
                    it.stopCapture()
                    Log.d(TAG, "✅ Captura de cámara detenida")
                } catch (e: InterruptedException) {
                    Log.e(TAG, "⚠️ Error al detener captura: ${e.message}", e)
                }
                it.dispose()
                videoCapturer = null
                Log.d(TAG, "✅ VideoCapturer liberado")
            }

            surfaceTextureHelper?.let {
                it.dispose()
                surfaceTextureHelper = null
                Log.d(TAG, "✅ SurfaceTextureHelper liberado")
            }

            videoTrack?.let {
                it.dispose()
                videoTrack = null
                Log.d(TAG, "✅ VideoTrack liberado")
            }

            videoSource?.let {
                it.dispose()
                videoSource = null
                Log.d(TAG, "✅ VideoSource liberado")
            }

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