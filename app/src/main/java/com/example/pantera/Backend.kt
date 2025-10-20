package com.tudominio.smslocation

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
import java.net.* //Comunicacion de red TCP UDP
import java.text.SimpleDateFormat
import java.util.*

object Constants {
    const val SERVER_IP_1 = "35.173.90.231" //IP Nana
    const val SERVER_IP_2 = "3.130.62.74" //IP Juls
    const val SERVER_IP_3 = "54.84.99.30" // IP samir
    const val SERVER_IP_4 = "98.89.220.57" // IP roger
    const val TCP_PORT = 5000
    const val UDP_PORT = 6001
    const val LOCATION_UPDATE_INTERVAL = 8000L //Intervalos para solicitar actualizaciones de ubicacion
    const val LOCATION_FASTEST_INTERVAL = 8000L
    const val LOCATION_TIMEOUT = 5000L //Tiempo maximo de espera para obtener una ubicacion actual
    const val NETWORK_TIMEOUT = 5000
    const val RETRY_DELAY = 1000L
    const val NOTIFICATION_CHANNEL_ID = "PanteraLocationChannel"
    const val NOTIFICATION_CHANNEL_NAME = "Pantera Location Service"
    const val NOTIFICATION_ID = 1001

    val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, //Obtener una ubicacion mas precisa posible
        Manifest.permission.ACCESS_COARSE_LOCATION //Obtener una ubicacion aproximada del dispositivo
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
    }
}

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val systemTimestamp: Long,
    val deviceId: String, // Identificador único del dispositivo
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

        fun createTestLocation(
            deviceId: String,
            lat: Double = 4.123456,
            lon: Double = -74.123456
        ): LocationData {
            val currentTime = System.currentTimeMillis()
            return LocationData(
                latitude = lat,
                longitude = lon,
                timestamp = currentTime,
                systemTimestamp = currentTime,
                deviceId = deviceId,
                accuracy = 5.0f,
                altitude = 2640.0,
                speed = 0.0f,
                provider = "gps"
            )
        }
    }

    fun toJsonFormat(): String {
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("{")
        jsonBuilder.append("\"lat\":$latitude,")
        jsonBuilder.append("\"lon\":$longitude,")
        jsonBuilder.append("\"time\":$timestamp,")
        jsonBuilder.append("\"deviceId\":\"$deviceId\"") // Añadir deviceId al JSON
        jsonBuilder.append("}")
        return jsonBuilder.toString()
    }

    fun isValid(): Boolean {
        return latitude != 0.0 &&
                longitude != 0.0 &&
                timestamp > 0 &&
                latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0 &&
                isTimestampReasonable()
    }

    private fun isTimestampReasonable(): Boolean {
        val currentTime = System.currentTimeMillis()
        val oneHourAgo = currentTime - (60 * 60 * 1000)
        val oneHourFuture = currentTime + (60 * 60 * 1000)
        return timestamp in oneHourAgo..oneHourFuture
    }

    fun getFormattedCoordinates(): String {
        return "LAT: ${String.format("%.6f", latitude)}, LON: ${String.format("%.6f", longitude)}"
    }

    fun getFormattedCoordinatesWithAccuracy(): String {
        val coords = getFormattedCoordinates()
        return if (accuracy != null) {
            "$coords (±${String.format("%.1f", accuracy)}m)"
        } else {
            coords
        }
    }

    fun distanceTo(other: LocationData): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            this.latitude, this.longitude,
            other.latitude, other.longitude,
            results
        )
        return results[0]
    }

    fun isSignificantlyDifferentFrom(other: LocationData, minDistanceMeters: Float = 5.0f): Boolean {
        return distanceTo(other) > minDistanceMeters
    }

    fun isNewerThan(other: LocationData): Boolean {
        return this.timestamp > other.timestamp
    }

    fun isMoreAccurateThan(other: LocationData): Boolean {
        return when {
            this.accuracy == null && other.accuracy == null -> false
            this.accuracy == null -> false
            other.accuracy == null -> true
            else -> this.accuracy < other.accuracy
        }
    }

    fun getFormattedGpsTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(timestamp))
    }

    fun getFormattedSystemTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(systemTimestamp))
    }

    fun getTimestampDifference(): Long {
        return systemTimestamp - timestamp
    }

    fun withUpdatedSystemTimestamp(): LocationData {
        return copy(systemTimestamp = System.currentTimeMillis())
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

    fun hasAllConnections(): Boolean {
        return server1TCP == ConnectionStatus.CONNECTED &&
                server1UDP == ConnectionStatus.CONNECTED &&
                server2TCP == ConnectionStatus.CONNECTED &&
                server2UDP == ConnectionStatus.CONNECTED &&
                server3TCP == ConnectionStatus.CONNECTED &&
                server3UDP == ConnectionStatus.CONNECTED &&
                server4TCP == ConnectionStatus.CONNECTED &&
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

    fun getSuccessRate(): Float {
        val total = totalSentMessages + totalFailedMessages
        return if (total > 0) {
            (totalSentMessages.toFloat() / total.toFloat()) * 100f
        } else {
            0f
        }
    }

    fun updateServerStatus(
        serverNumber: Int,
        protocol: String,
        status: ConnectionStatus
    ): ServerStatus {
        return when (serverNumber to protocol.uppercase()) {
            1 to "TCP" -> copy(server1TCP = status, lastUpdateTime = System.currentTimeMillis())
            1 to "UDP" -> copy(server1UDP = status, lastUpdateTime = System.currentTimeMillis())
            2 to "TCP" -> copy(server2TCP = status, lastUpdateTime = System.currentTimeMillis())
            2 to "UDP" -> copy(server2UDP = status, lastUpdateTime = System.currentTimeMillis())
            3 to "TCP" -> copy(server3TCP = status, lastUpdateTime = System.currentTimeMillis())
            3 to "UDP" -> copy(server3UDP = status, lastUpdateTime = System.currentTimeMillis())
            4 to "TCP" -> copy(server4TCP = status, lastUpdateTime = System.currentTimeMillis())
            4 to "UDP" -> copy(server4UDP = status, lastUpdateTime = System.currentTimeMillis())
            else -> this
        }
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

    fun resetCounters(): ServerStatus {
        return copy(
            totalSentMessages = 0,
            totalFailedMessages = 0,
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    fun getOverallStatusText(): String {
        val activeConnections = getActiveConnectionsCount()
        return when (activeConnections) {
            0 -> "All servers disconnected"
            4 -> "All servers connected"
            else -> "$activeConnections of 4 connections active"
        }
    }

    fun hasRecentActivity(): Boolean {
        val currentTime = System.currentTimeMillis()
        val tenSecondsMillis = 10 * 1000L
        return (currentTime - lastUpdateTime) < tenSecondsMillis
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
    val isShowingError: Boolean = false,
    val isFirstLaunch: Boolean = true,
    val isDebugging: Boolean = false,
    val sessionStartTime: Long = 0L,
    val totalLocationsSent: Int = 0,
    val sessionDuration: Long = 0L
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

    fun getSessionDurationFormatted(): String {
        if (sessionStartTime == 0L) return "00:00:00"

        val duration = if (isTrackingEnabled) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            sessionDuration
        }

        val hours = duration / 3600000
        val minutes = (duration % 3600000) / 60000
        val seconds = (duration % 60000) / 1000

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun startTracking(): AppState {
        return copy(
            isTrackingEnabled = true,
            isLocationServiceRunning = true,
            sessionStartTime = if (sessionStartTime == 0L) System.currentTimeMillis() else sessionStartTime,
            statusMessage = "Location tracking started",
            errorMessage = "",
            isShowingError = false
        )
    }

    fun stopTracking(): AppState {
        val finalDuration = if (sessionStartTime > 0L) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            sessionDuration
        }

        return copy(
            isTrackingEnabled = false,
            isLocationServiceRunning = false,
            sessionDuration = finalDuration,
            statusMessage = "Location tracking stopped",
            errorMessage = "",
            isShowingError = false
        )
    }

    fun updateLocation(location: LocationData): AppState {
        return copy(
            currentLocation = location,
            lastKnownLocation = currentLocation ?: location,
            isLoadingLocation = false,
            totalLocationsSent = totalLocationsSent + 1
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

    fun clearMessages(): AppState {
        return copy(
            statusMessage = "",
            errorMessage = "",
            isShowingError = false
        )
    }

    fun updateServerStatus(newServerStatus: ServerStatus): AppState {
        return copy(serverStatus = newServerStatus)
    }

    fun getStatusSummary(): String {
        return when {
            !hasAllPermissions() -> "Permissions required"
            isTrackingEnabled -> "Tracking active - ${serverStatus.getActiveConnectionsCount()}/4 servers connected"
            hasValidLocation() -> "Ready to track"
            else -> "Waiting for GPS signal"
        }
    }

    fun isActive(): Boolean {
        return isTrackingEnabled && hasValidLocation() && serverStatus.hasAnyConnection()
    }
}

class Backend(private val context: Context) : ViewModel() {

    companion object {
        private const val TAG = Constants.Logs.TAG_MAIN
    }

    private val deviceId: String by lazy {
        getDeviceId(context)
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

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
            return
        }

        if (!isNetworkAvailable()) {
            updateAppState { it.showErrorMessage(Constants.Messages.NETWORK_ERROR) }
            return
        }

        if (_appState.value.isTrackingEnabled) {
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

            Log.d(TAG, "Location tracking started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting location tracking", e)
            updateAppState { it.showErrorMessage("Error starting tracking: ${e.message}") }
        }
    }

    private fun stopTracking() {
        Log.d(TAG, "Stopping tracking")

        try {
            stopLocationUpdates()
            locationTrackingJob?.cancel()
            locationTrackingJob = null
            updateAppState { it.stopTracking() }

            stopLocationService()

            Log.d(TAG, "Location tracking stopped successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location tracking", e)
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
            addToPendingQueue(locationData)
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

                val successCount = listOf(server1TcpResult, server1UdpResult, server2TcpResult, server2UdpResult, server3TcpResult, server3UdpResult, server4TcpResult, server4UdpResult )
                    .count { it == ServerStatus.ConnectionStatus.CONNECTED }

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
            addToPendingQueue(locationData)
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
                val packet = DatagramPacket(
                    dataBytes,
                    dataBytes.size,
                    address,
                    port
                )

                socket.send(packet)

                Log.d(TAG, "UDP: Successfully sent to $serverIP:$port (${dataBytes.size} bytes)")
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
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            false
        }
    }

    private fun addToPendingQueue(locationData: LocationData) {
        synchronized(pendingLocations) {
            pendingLocations.add(locationData)

            if (pendingLocations.size > 100) {
                pendingLocations.removeAt(0)
                Log.w(TAG, "Pending queue reached max size (100), removed oldest location.")
            }

            Log.d(TAG, "Added location to pending queue. Current queue size: ${pendingLocations.size}")
        }

        if (!isProcessingQueue) {
            processPendingLocations()
        }
    }

    private fun processPendingLocations() {
        networkScope.launch {
            isProcessingQueue = true

            var shouldContinue = true
            while (pendingLocations.isNotEmpty() && isNetworkAvailable() && shouldContinue) {
                val location = synchronized(pendingLocations) {
                    if (pendingLocations.isNotEmpty()) {
                        pendingLocations.removeAt(0)
                    } else {
                        null
                    }
                }

                if (location != null) {
                    Log.d(TAG, "Processing pending location: ${location.getFormattedCoordinates()}")

                    try {
                        val result = sendLocationToAllServers(location)

                        result.fold(
                            onSuccess = {
                                Log.d(TAG, "Pending location sent successfully during retry.")
                            },
                            onFailure = {
                                synchronized(pendingLocations) {
                                    pendingLocations.add(0, location)
                                }
                                shouldContinue = false
                                Log.w(TAG, "Failed to send pending location, will retry later. Location re-queued.")
                            }
                        )

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing pending location: ${e.message}", e)
                        synchronized(pendingLocations) {
                            pendingLocations.add(0, location)
                        }
                        shouldContinue = false
                    }

                    if (shouldContinue) {
                        delay(500)
                    }
                } else {
                    shouldContinue = false
                }
            }

            isProcessingQueue = false

            if (pendingLocations.isNotEmpty()) {
                Log.d(TAG, "Finished processing pending locations. ${pendingLocations.size} locations remain in queue.")
            } else {
                Log.d(TAG, "All pending locations processed successfully. Queue is empty.")
            }
        }
    }
    suspend fun sendTestData(): Result<String> {
        Log.d(TAG, "Sending test data to servers...")

        if (!isNetworkAvailable()) {
            return Result.failure(Exception(Constants.Messages.NETWORK_ERROR))
        }

        return try {
            val testLocation = LocationData.createTestLocation(deviceId)
            val result = sendLocationToAllServers(testLocation)

            result.fold(
                onSuccess = { serverStatus ->
                    val message = "Test data sent to ${serverStatus.getActiveConnectionsCount()}/4 servers"
                    updateAppState { it.showSuccessMessage(message) }
                    Result.success(message)
                },
                onFailure = { error ->
                    val errorMessage = "Test failed: ${error.message}"
                    updateAppState { it.showErrorMessage(errorMessage) }
                    Result.failure(error)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error sending test data", e)
            val errorMessage = "Test error: ${e.message}"
            updateAppState { it.showErrorMessage(errorMessage) }
            Result.failure(e)
        }
    }

    fun clearMessages() {
        updateAppState { it.clearMessages() }
    }

    fun resetStatistics() {
        currentServerStatus = currentServerStatus.resetCounters()
        updateAppState {
            it.copy(
                totalLocationsSent = 0,
                sessionStartTime = if (it.isTrackingEnabled) System.currentTimeMillis() else 0L
            )
        }
        Log.d(TAG, "Statistics reset")
    }

    fun getDiagnosticInfo(): Map<String, String> {
        val appState = _appState.value

        return mapOf(
            "tracking_active" to appState.isTrackingEnabled.toString(),
            "location_permission" to appState.hasLocationPermission.toString(),
            "background_permission" to appState.hasBackgroundLocationPermission.toString(),
            "current_location" to (appState.currentLocation?.getFormattedCoordinates() ?: "None"),
            "session_duration" to appState.getSessionDurationFormatted(),
            "locations_sent" to appState.totalLocationsSent.toString(),
            "location_updates_active" to isRequestingUpdates.toString(),
            "pending_locations" to pendingLocations.size.toString(),
            "network_available" to isNetworkAvailable().toString(),
            "app_version" to "1.0.0"
        )
    }

    fun getCurrentAppState(): AppState = _appState.value

    fun canStartTracking(): Boolean {
        return _appState.value.canStartTracking()
    }

    private fun updateAppState(update: (AppState) -> AppState) {
        _appState.value = update(_appState.value)
    }

    fun getNetworkInfo(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "No Connection"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting network info", e)
            "Unknown"
        }
    }

    suspend fun flushPendingLocations(): Result<String> {
        val pendingCount = pendingLocations.size

        return if (pendingCount > 0) {
            if (isNetworkAvailable()) {
                Result.success("Processing $pendingCount pending locations")
            } else {
                Result.failure(Exception("No network available to flush pending locations"))
            }
        } else {
            Result.success("No pending locations to flush")
        }
    }

    fun onAppResumed() {
        Log.d(TAG, "App resumed")
        checkPermissions()

        viewModelScope.launch {
            if (appState.value.isTrackingEnabled) {
            }
        }
    }

    fun onAppPaused() {
        Log.d(TAG, "App paused")
    }

    fun onNetworkChanged(isConnected: Boolean) {
        Log.d(TAG, "Network changed - Connected: $isConnected")

        if (isConnected && appState.value.isTrackingEnabled) {
            viewModelScope.launch {
                flushPendingLocations()
            }
        }
    }

    fun handleEmergency() {
        viewModelScope.launch {
            Log.w(TAG, "Emergency mode activated")

            val locationResult = getCurrentLocation()

            locationResult.fold(
                onSuccess = { location ->
                    repeat(3) {
                        sendTestData()
                    }
                    Log.w(TAG, "Emergency location sent multiple times")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to get emergency location: ${error.message}")
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()

        Log.d(TAG, "Backend being cleared")

        if (appState.value.isTrackingEnabled) {
            stopTracking()
        }

        locationTrackingJob?.cancel()
        controllerScope.cancel()
        networkScope.cancel()

        stopLocationUpdates()
        _locationUpdates.close()

        Log.d(TAG, "Backend cleared successfully")
    }
}