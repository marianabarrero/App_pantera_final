package com.example.pantera // 👈 ASEGÚRATE DE QUE ESTO SEA CORRECTO

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.io.PrintWriter
import java.net.*
import java.text.SimpleDateFormat
import java.util.*

object Constants {
    // IPs de los servidores
    const val SERVER_IP_1 = "35.173.90.231" //IP Nana
    const val SERVER_IP_2 = "3.130.62.74"   //IP Juls
    const val SERVER_IP_3 = "54.84.99.30"   //IP samir
    const val SERVER_IP_4 = "98.89.220.57"  //IP roger

    // Puertos
    const val TCP_PORT = 5000
    const val UDP_PORT = 6001
    const val WEBRTC_PORT = 8443

    const val LOCATION_UPDATE_INTERVAL = 8000L
    const val LOCATION_FASTEST_INTERVAL = 8000L
    const val LOCATION_TIMEOUT = 5000L
    const val NETWORK_TIMEOUT = 5000
    const val RETRY_DELAY = 1000L
    const val NOTIFICATION_CHANNEL_ID = "PanteraLocationChannel"
    const val NOTIFICATION_CHANNEL_NAME = "Pantera Location Service"
    const val NOTIFICATION_ID = 1001

    val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val BACKGROUND_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    object Messages {
        const val TRACKING_STARTED = "Location tracking started"
        const val TRACKING_STOPPED = "Location tracking stopped"
        const val LOCATION_PERMISSION_REQUIRED = "Location permissions required"
        const val BACKGROUND_PERMISSION_REQUIRED = "Background location permission required"
        const val GPS_NOT_AVAILABLE = "GPS not available or signal lost"
        const val NETWORK_ERROR = "Network connection error"
        const val SERVER_CONNECTION_ERROR = "Server connection error"
        const val LOCATION_SENT_SUCCESS = "Location sent successfully"
        const val VIDEO_STREAMING_STARTED = "Video streaming started"
        const val VIDEO_STREAMING_STOPPED = "Video streaming stopped"
        const val VIDEO_STREAMING_ERROR = "Video streaming error"
    }

    object ServiceActions {
        const val START_TRACKING = "START_TRACKING"
        const val STOP_TRACKING = "STOP_TRACKING"
        const val UPDATE_LOCATION = "UPDATE_LOCATION"
    }

    object Logs {
        const val TAG_MAIN = "Pantera_Main"
        const val TAG_LOCATION = "Pantera_Location"
        const val TAG_NETWORK = "Pantera_Network"
        const val TAG_SERVICE = "Pantera_Service"
        const val TAG_CONTROLLER = "Pantera_Controller"
        const val TAG_VIDEO = "Pantera_Video"
    }

    // ⭐ AGREGAR ESTA FUNCIÓN ⭐
    fun getVideoServerUrls(): List<String> {
        return listOf(
            "wss://$SERVER_IP_1:$WEBRTC_PORT",
            "wss://$SERVER_IP_2:$WEBRTC_PORT",
            "wss://$SERVER_IP_3:$WEBRTC_PORT",
            "wss://$SERVER_IP_4:$WEBRTC_PORT"
        )
    }
}
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val systemTimestamp: Long,
    val deviceId: String,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val speed: Float? = null,
    val provider: String? = null
) {
    companion object {
        fun fromAndroidLocation(location: Location, deviceId: String): LocationData {
            val gpsTimestamp = if (location.time > 0) location.time else System.currentTimeMillis()
            val systemTimestamp = System.currentTimeMillis()

            return LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = gpsTimestamp,
                systemTimestamp = systemTimestamp,
                deviceId = deviceId,
                accuracy = if (location.hasAccuracy()) location.accuracy else null,
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed else null,
                provider = location.provider
            )
        }

        fun empty(deviceId: String): LocationData {
            return LocationData(
                latitude = 0.0,
                longitude = 0.0,
                timestamp = 0L,
                systemTimestamp = 0L,
                deviceId = deviceId
            )
        }
    }

    fun toJsonFormat(): String {
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("{")
        jsonBuilder.append("\"lat\":$latitude,")
        jsonBuilder.append("\"lon\":$longitude,")
        jsonBuilder.append("\"time\":$timestamp,")
        jsonBuilder.append("\"deviceId\":\"$deviceId\"")
        jsonBuilder.append("}")
        return jsonBuilder.toString()
    }

    fun isValid(): Boolean {
        return latitude != 0.0 &&
                longitude != 0.0 &&
                timestamp > 0 &&
                latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0
    }

    fun getFormattedCoordinates(): String {
        return "LAT: ${String.format("%.6f", latitude)}, LON: ${String.format("%.6f", longitude)}"
    }
}

data class ServerStatus(
    val server1TCP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server1UDP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server2TCP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server2UDP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server3TCP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server3UDP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server4TCP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val server4UDP: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val lastUpdateTime: Long = 0L,
    val lastSuccessfulSend: Long = 0L,
    val totalSentMessages: Int = 0,
    val totalFailedMessages: Int = 0
) {
    enum class ConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        ERROR,
        TIMEOUT
    }

    fun hasAnyConnection(): Boolean {
        return server1TCP == ConnectionStatus.CONNECTED ||
                server1UDP == ConnectionStatus.CONNECTED ||
                server2TCP == ConnectionStatus.CONNECTED ||
                server2UDP == ConnectionStatus.CONNECTED ||
                server3TCP == ConnectionStatus.CONNECTED ||
                server3UDP == ConnectionStatus.CONNECTED ||
                server4TCP == ConnectionStatus.CONNECTED ||
                server4UDP == ConnectionStatus.CONNECTED
    }

    fun getActiveConnectionsCount(): Int {
        var count = 0
        if (server1TCP == ConnectionStatus.CONNECTED) count++
        if (server1UDP == ConnectionStatus.CONNECTED) count++
        if (server2TCP == ConnectionStatus.CONNECTED) count++
        if (server2UDP == ConnectionStatus.CONNECTED) count++
        if (server3TCP == ConnectionStatus.CONNECTED) count++
        if (server3UDP == ConnectionStatus.CONNECTED) count++
        if (server4TCP == ConnectionStatus.CONNECTED) count++
        if (server4UDP == ConnectionStatus.CONNECTED) count++
        return count
    }

    fun incrementSuccessfulSend(): ServerStatus {
        return copy(
            totalSentMessages = totalSentMessages + 1,
            lastSuccessfulSend = System.currentTimeMillis(),
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    fun incrementFailedSend(): ServerStatus {
        return copy(
            totalFailedMessages = totalFailedMessages + 1,
            lastUpdateTime = System.currentTimeMillis()
        )
    }
}

data class AppState(
    val isTrackingEnabled: Boolean = false,
    val isLocationServiceRunning: Boolean = false,
    val currentLocation: LocationData? = null,
    val lastKnownLocation: LocationData? = null,
    val isLoadingLocation: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBackgroundLocationPermission: Boolean = false,
    val permissionsRequested: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus(),
    val statusMessage: String = "",
    val errorMessage: String = "",
    val isShowingError: Boolean = false
) {
    fun canStartTracking(): Boolean {
        return hasLocationPermission &&
                hasBackgroundLocationPermission &&
                !isTrackingEnabled &&
                !isLocationServiceRunning
    }

    fun hasAllPermissions(): Boolean {
        return hasLocationPermission && hasBackgroundLocationPermission
    }

    fun hasValidLocation(): Boolean {
        return currentLocation?.isValid() == true
    }

    fun startTracking(): AppState {
        return copy(
            isTrackingEnabled = true,
            isLocationServiceRunning = true,
            statusMessage = "Location tracking started",
            errorMessage = "",
            isShowingError = false
        )
    }

    fun stopTracking(): AppState {
        return copy(
            isTrackingEnabled = false,
            isLocationServiceRunning = false,
            statusMessage = "Location tracking stopped",
            errorMessage = "",
            isShowingError = false
        )
    }

    fun updateLocation(location: LocationData): AppState {
        return copy(
            currentLocation = location,
            lastKnownLocation = currentLocation ?: location,
            isLoadingLocation = false
        )
    }

    fun updatePermissions(
        locationPermission: Boolean,
        backgroundPermission: Boolean
    ): AppState {
        return copy(
            hasLocationPermission = locationPermission,
            hasBackgroundLocationPermission = backgroundPermission,
            permissionsRequested = true
        )
    }

    fun showSuccessMessage(message: String): AppState {
        return copy(
            statusMessage = message,
            errorMessage = "",
            isShowingError = false
        )
    }

    fun showErrorMessage(message: String): AppState {
        return copy(
            errorMessage = message,
            statusMessage = "",
            isShowingError = true
        )
    }

    fun updateServerStatus(newServerStatus: ServerStatus): AppState {
        return copy(serverStatus = newServerStatus)
    }
}

class Backend(private val context: Context) : ViewModel() {

    companion object {
        private const val TAG = Constants.Logs.TAG_MAIN
        private const val TAG_VIDEO = Constants.Logs.TAG_VIDEO
    }

    private val deviceId: String by lazy {
        getDeviceId(context)
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    // ⭐ Variable para video streaming ⭐


    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _locationUpdates = Channel<LocationData>(Channel.UNLIMITED)
    val locationUpdates: Flow<LocationData> = _locationUpdates.receiveAsFlow()

    private var locationCallback: LocationCallback? = null
    private var isRequestingUpdates = false
    private var locationTrackingJob: Job? = null
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentServerStatus = ServerStatus()
    private val pendingLocations = mutableListOf<LocationData>()
    private var isProcessingQueue = false

    init {
        Log.d(TAG, "Backend initialized")
        checkPermissions()
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun checkPermissions() {
        val hasLocation = hasLocationPermissions()
        val hasBackground = hasBackgroundLocationPermission()

        updateAppState { currentState ->
            currentState.updatePermissions(hasLocation, hasBackground)
        }

        Log.d(TAG, "Permissions - Location: $hasLocation, Background: $hasBackground")
    }

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun onPermissionsGranted() {
        checkPermissions()

        val currentState = appState.value
        if (currentState.hasAllPermissions()) {
            Log.d(TAG, "All permissions granted")

            viewModelScope.launch {
                getCurrentLocation()
            }
        }
    }

    fun toggleTracking() {
        viewModelScope.launch {
            val currentState = appState.value

            if (currentState.isTrackingEnabled) {
                stopTracking()
            } else {
                startTracking()
            }
        }
    }

    private suspend fun startTracking() {
        Log.d(TAG, "Starting tracking")

        if (!_appState.value.hasAllPermissions()) {
            Log.w(TAG, "No se tienen todos los permisos")
            return
        }

        if (!isNetworkAvailable()) {
            updateAppState { it.showErrorMessage(Constants.Messages.NETWORK_ERROR) }
            return
        }

        if (_appState.value.isTrackingEnabled) {
            Log.w(TAG, "Tracking ya está activo")
            return
        }

        try {
            val locationStarted = startLocationUpdates()

            if (!locationStarted) {
                updateAppState { it.showErrorMessage("Failed to start location updates") }
                return
            }

            updateAppState { it.startTracking() }
            startLocationProcessingJob()
            startLocationService()

            Log.d(TAG, "✅ Location tracking iniciado exitosamente")

            // ⭐ INICIAR VIDEO STREAMING ⭐
            try {
                Log.d(TAG_VIDEO, "Iniciando video streaming...")

                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG_VIDEO, "❌ No hay permiso de cámara")
                    updateAppState {
                        it.showErrorMessage("Permiso de cámara requerido")
                    }
                } else {
                    val serverUrl = Constants.getVideoServerUrls().firstOrNull()

                    if (serverUrl == null) {
                        Log.e(TAG_VIDEO, "❌ No se encontró URL de servidor WebRTC")
                        updateAppState { it.showErrorMessage("No WebRTC server URL") }
                        return
                    }

                    Log.d(TAG_VIDEO, "Usando servidor WebRTC: $serverUrl")

                    // Iniciar el CameraStreamingService
                    val intent = Intent(context, CameraStreamingService::class.java).apply {
                        action = CameraStreamingService.ACTION_START_STREAMING
                        putExtra(CameraStreamingService.EXTRA_SERVER_URL, serverUrl)
                        putExtra(CameraStreamingService.EXTRA_DEVICE_ID, deviceId)
                    }
                    context.startService(intent) // Usar startService, no startForegroundService desde aquí

                    Log.d(TAG_VIDEO, "✅ Solicitud de inicio de CameraStreamingService enviada")
                    updateAppState {
                        it.showSuccessMessage("Tracking y video iniciados")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG_VIDEO, "❌ Error iniciando video: ${e.message}", e)
                updateAppState {
                    it.showErrorMessage("Video falló, GPS continúa")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting tracking", e)
            updateAppState { it.showErrorMessage("Error: ${e.message}") }
        }
    }

    private fun stopTracking() {
        Log.d(TAG, "Stopping tracking")

        try {
            stopLocationUpdates()
            locationTrackingJob?.cancel()
            locationTrackingJob = null

            Log.d(TAG, "✅ Location updates detenidos")

            // ⭐ DETENER VIDEO STREAMING (a través del servicio) ⭐
            try {
                Log.d(TAG_VIDEO, "Deteniendo CameraStreamingService...")
                val intent = Intent(context, CameraStreamingService::class.java).apply {
                    action = CameraStreamingService.ACTION_STOP_STREAMING
                }
                context.startService(intent)
                Log.d(TAG_VIDEO, "✅ Solicitud de detención de CameraStreamingService enviada")

            } catch (e: Exception) {
                Log.e(TAG_VIDEO, "❌ Error deteniendo video: ${e.message}", e)
            }

            updateAppState {
                it.stopTracking().showSuccessMessage("Tracking y video detenidos")
            }

            stopLocationService() // Detiene el servicio de GPS

            Log.d(TAG, "✅ Tracking completamente detenido")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping tracking", e)
            updateAppState {
                it.showErrorMessage("Error: ${e.message}")
            }
        }
    }

    private fun startLocationService() {
        val intent = Intent(context, LocationService::class.java).apply {
            action = Constants.ServiceActions.START_TRACKING
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Location service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location service", e)
        }
    }

    private fun stopLocationService() {
        val intent = Intent(context, LocationService::class.java).apply {
            action = Constants.ServiceActions.STOP_TRACKING
        }

        try {
            context.startService(intent)
            Log.d(TAG, "Location service stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location service", e)
        }
    }

    suspend fun getCurrentLocation(): Result<LocationData> {
        if (!_appState.value.hasLocationPermission) {
            return Result.failure(Exception(Constants.Messages.LOCATION_PERMISSION_REQUIRED))
        }

        updateAppState { it.copy(isLoadingLocation = true) }

        return try {
            val cancellationTokenSource = CancellationTokenSource()
            val location = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()

            if (location != null) {
                val locationData = LocationData.fromAndroidLocation(location, deviceId)
                if (locationData.isValid()) {
                    updateAppState {
                        it.copy(
                            currentLocation = locationData,
                            isLoadingLocation = false
                        )
                    }

                    Log.d(TAG, "Current location obtained: ${locationData.getFormattedCoordinates()}")
                    Result.success(locationData)
                } else {
                    updateAppState {
                        it.copy(isLoadingLocation = false)
                            .showErrorMessage("Invalid location data")
                    }
                    Result.failure(Exception("Invalid location data"))
                }
            } else {
                updateAppState {
                    it.copy(isLoadingLocation = false)
                        .showErrorMessage(Constants.Messages.GPS_NOT_AVAILABLE)
                }
                Result.failure(Exception("No location available"))
            }

        } catch (e: Exception) {
            updateAppState {
                it.copy(isLoadingLocation = false)
                    .showErrorMessage("Error getting location: ${e.message}")
            }

            Log.e(TAG, "Error getting current location", e)
            Result.failure(e)
        }
    }

    private suspend fun startLocationUpdates(): Boolean {
        if (!hasLocationPermissions()) {
            Log.w(TAG, "Cannot start location updates - permissions not granted.")
            return false
        }

        if (isRequestingUpdates) {
            Log.d(TAG, "Location updates are already started.")
            return true
        }

        return try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                Constants.LOCATION_UPDATE_INTERVAL
            ).apply {
                setMinUpdateIntervalMillis(Constants.LOCATION_FASTEST_INTERVAL)
                setMaxUpdateDelayMillis(Constants.LOCATION_UPDATE_INTERVAL * 2)
                setWaitForAccurateLocation(false)
            }.build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        val locationData = LocationData.fromAndroidLocation(location, deviceId)

                        if (locationData.isValid()) {
                            Log.d(TAG, "New location update received: ${locationData.getFormattedCoordinates()}")
                            _locationUpdates.trySend(locationData)
                        } else {
                            Log.w(TAG, "Invalid location data received, discarding.")
                        }
                    }
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    Log.d(TAG, "Location availability changed: ${availability.isLocationAvailable}")
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            isRequestingUpdates = true
            Log.d(TAG, "Location updates started successfully.")
            true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting location updates", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
            false
        }
    }

    private fun stopLocationUpdates() {
        if (!isRequestingUpdates) {
            Log.d(TAG, "Location updates are not active, no need to stop.")
            return
        }

        try {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                Log.d(TAG, "Location updates stopped successfully.")
            }

            locationCallback = null
            isRequestingUpdates = false

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    private fun startLocationProcessingJob() {
        locationTrackingJob = controllerScope.launch {
            locationUpdates.collect { locationData ->
                processNewLocation(locationData)
            }
        }
    }

    private suspend fun processNewLocation(locationData: LocationData) {
        Log.d(TAG, "Processing new location: ${locationData.getFormattedCoordinates()}")

        updateAppState { it.updateLocation(locationData) }

        val sendResult = sendLocationToAllServers(locationData)

        sendResult.fold(
            onSuccess = { serverStatus ->
                Log.d(TAG, "Location sent successfully - ${serverStatus.getActiveConnectionsCount()} connections")
                currentServerStatus = serverStatus
            },
            onFailure = { error ->
                Log.w(TAG, "Failed to send location: ${error.message}")

                if (!currentServerStatus.hasAnyConnection()) {
                    updateAppState {
                        it.showErrorMessage("Connection lost to all servers")
                    }
                }
            }
        )
    }

    private suspend fun sendLocationToAllServers(locationData: LocationData): Result<ServerStatus> {
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No network connection available")
            return Result.failure(Exception("No network available"))
        }

        val jsonData = locationData.toJsonFormat()
        Log.d(TAG, "Attempting to send location data as JSON: $jsonData")

        return try {
            coroutineScope {
                val server1TcpDeferred = async {
                    sendToServer(Constants.SERVER_IP_1, Constants.TCP_PORT, jsonData, "TCP")
                }
                val server1UdpDeferred = async {
                    sendToServer(Constants.SERVER_IP_1, Constants.UDP_PORT, jsonData, "UDP")
                }
                val server2TcpDeferred = async {
                    sendToServer(Constants.SERVER_IP_2, Constants.TCP_PORT, jsonData, "TCP")
                }
                val server2UdpDeferred = async {
                    sendToServer(Constants.SERVER_IP_2, Constants.UDP_PORT, jsonData, "UDP")
                }
                val server3TcpDeferred = async {
                    sendToServer(Constants.SERVER_IP_3, Constants.TCP_PORT, jsonData, "TCP")
                }
                val server3UdpDeferred = async {
                    sendToServer(Constants.SERVER_IP_3, Constants.UDP_PORT, jsonData, "UDP")
                }
                val server4TcpDeferred = async {
                    sendToServer(Constants.SERVER_IP_4, Constants.TCP_PORT, jsonData, "TCP")
                }
                val server4UdpDeferred = async {
                    sendToServer(Constants.SERVER_IP_4, Constants.UDP_PORT, jsonData, "UDP")
                }

                val server1TcpResult = server1TcpDeferred.await()
                val server1UdpResult = server1UdpDeferred.await()
                val server2TcpResult = server2TcpDeferred.await()
                val server2UdpResult = server2UdpDeferred.await()
                val server3TcpResult = server3TcpDeferred.await()
                val server3UdpResult = server3UdpDeferred.await()
                val server4TcpResult = server4TcpDeferred.await()
                val server4UdpResult = server4UdpDeferred.await()

                val newStatus = currentServerStatus.copy(
                    server1TCP = server1TcpResult,
                    server1UDP = server1UdpResult,
                    server2TCP = server2TcpResult,
                    server2UDP = server2UdpResult,
                    server3TCP = server3TcpResult,
                    server3UDP = server3UdpResult,
                    server4TCP = server4TcpResult,
                    server4UDP = server4UdpResult,
                    lastUpdateTime = System.currentTimeMillis()
                )

                val successCount = listOf(
                    server1TcpResult, server1UdpResult, server2TcpResult, server2UdpResult,
                    server3TcpResult, server3UdpResult, server4TcpResult, server4UdpResult
                ).count { it == ServerStatus.ConnectionStatus.CONNECTED }

                val finalStatus = if (successCount > 0) {
                    newStatus.incrementSuccessfulSend()
                } else {
                    newStatus.incrementFailedSend()
                }

                currentServerStatus = finalStatus
                Result.success(finalStatus)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending location: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun sendToServer(
        serverIP: String,
        port: Int,
        data: String,
        protocol: String
    ): ServerStatus.ConnectionStatus {
        return try {
            val result = when (protocol.uppercase()) {
                "TCP" -> sendTcpData(serverIP, port, data)
                "UDP" -> sendUdpData(serverIP, port, data)
                else -> {
                    Log.e(TAG, "Unknown protocol specified: $protocol")
                    ServerStatus.ConnectionStatus.ERROR
                }
            }

            Log.d(TAG, "$protocol to $serverIP:$port - Result: $result")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Error sending $protocol to $serverIP:$port", e)
            ServerStatus.ConnectionStatus.ERROR
        }
    }

    private suspend fun sendTcpData(serverIP: String, port: Int, data: String): ServerStatus.ConnectionStatus =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            var writer: PrintWriter? = null

            try {
                socket = Socket().apply {
                    soTimeout = Constants.NETWORK_TIMEOUT
                    tcpNoDelay = true
                }

                socket.connect(
                    InetSocketAddress(serverIP, port),
                    Constants.NETWORK_TIMEOUT
                )

                if (!socket.isConnected) {
                    return@withContext ServerStatus.ConnectionStatus.ERROR
                }

                writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(data)
                writer.flush()

                if (writer.checkError()) {
                    return@withContext ServerStatus.ConnectionStatus.ERROR
                }

                Log.d(TAG, "TCP: Successfully sent JSON to $serverIP:$port")
                ServerStatus.ConnectionStatus.CONNECTED

            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "TCP: Socket timeout for $serverIP:$port", e)
                ServerStatus.ConnectionStatus.TIMEOUT
            } catch (e: Exception) {
                Log.e(TAG, "TCP: Error for $serverIP:$port", e)
                ServerStatus.ConnectionStatus.ERROR
            } finally {
                try {
                    writer?.close()
                    socket?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "TCP: Error closing resources", e)
                }
            }
        }

    private suspend fun sendUdpData(serverIP: String, port: Int, data: String): ServerStatus.ConnectionStatus =
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null

            try {
                val dataBytes = data.toByteArray(Charsets.UTF_8)
                if (dataBytes.size > 1024) {
                    Log.w(TAG, "UDP: Data size (${dataBytes.size}) exceeds max packet size (1024)")
                    return@withContext ServerStatus.ConnectionStatus.ERROR
                }

                socket = DatagramSocket().apply {
                    soTimeout = Constants.NETWORK_TIMEOUT
                }

                val address = InetAddress.getByName(serverIP)
                val packet = DatagramPacket(dataBytes, dataBytes.size, address, port)

                socket.send(packet)

                Log.d(TAG, "UDP: Successfully sent JSON to $serverIP:$port")
                ServerStatus.ConnectionStatus.CONNECTED

            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "UDP: Socket timeout for $serverIP:$port", e)
                ServerStatus.ConnectionStatus.TIMEOUT
            } catch (e: Exception) {
                Log.e(TAG, "UDP: Error for $serverIP:$port", e)
                ServerStatus.ConnectionStatus.ERROR
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "UDP: Error closing socket", e)
                }
            }
        }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }

    fun canStartTracking(): Boolean {
        return _appState.value.canStartTracking()
    }

    fun onAppResumed() {
        Log.d(TAG, "App resumed")
        checkPermissions()
    }

    fun onAppPaused() {
        Log.d(TAG, "App paused")
    }

    private fun updateAppState(transform: (AppState) -> AppState) {
        _appState.update(transform)
    }

    // ⭐ FUNCIONES PARA VIDEO (YA NO SE USAN AQUÍ) ⭐
    // fun getVideoStreamingStatus(): String { ... }
    // fun isVideoStreamingActive(): Boolean { ... }

    override fun onCleared() {
        super.onCleared()
        try {
            // Ya no manejamos el video client aquí
            Log.d(TAG, "Backend cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando recursos: ${e.message}")
        }
    }
}