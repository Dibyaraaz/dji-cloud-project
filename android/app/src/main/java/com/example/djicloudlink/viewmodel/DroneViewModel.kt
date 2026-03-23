package com.example.djicloudlink.viewmodel

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.djicloudlink.model.*
import com.example.djicloudlink.network.DroneNetwork
import com.example.djicloudlink.network.ServerConfig
import com.example.djicloudlink.network.WebSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DroneViewModel : ViewModel() {

    // --- STATE VARIABLES ---
    private val _telemetry = MutableStateFlow(DroneTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("[SYSTEM] Initialized..."))
    val logs = _logs.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices = _devices.asStateFlow()

    // Waypoint Mission Planning
    private val _waypoints = mutableStateListOf<Pair<Double, Double>>()
    val waypoints: List<Pair<Double, Double>> = _waypoints

    // API Flight History (For the old table, kept for compatibility if needed)
    private val _historyList = mutableStateListOf<HistoryPoint>()
    val historyList: List<HistoryPoint> = _historyList

    // --- NEW: FLIGHT RECORDER FIX ---
    // We now store a Pair: the exact time the data arrived + the telemetry data
    private val flightHistory = mutableListOf<Pair<Long, DroneTelemetry>>()

    // --- CONFIGURATION ---
    private val targetSn = "1581F5FHC253Q00EZB6J"  // Your real aircraft SN

    // WebSocket Manager
    private lateinit var webSocketManager: WebSocketManager

    init {
        addLog("[SYSTEM] ViewModel initialized")
        addLog("[INFO] Target drone: $targetSn")
        addLog("[INFO] Backend: ${ServerConfig.BASE_URL}")
    }

    // --- AUTHENTICATION & CONNECTION ---

    fun connectToServer(context: Context) {
        if (!TokenManager.isLoggedIn(context)) {
            addLog("[ERROR] Not logged in. Please login first.")
            return
        }

        val username = TokenManager.getUsername(context)
        addLog("[NET] Connecting as: $username")

        webSocketManager = WebSocketManager(targetSn) { data ->
            _telemetry.value = data
            // Record the exact millisecond the data arrived to fix the "0m 0s" bug
            flightHistory.add(Pair(System.currentTimeMillis(), data))
        }

        webSocketManager.connect()
        _isConnected.value = true
        addLog("[NET] WebSocket connected to ${ServerConfig.WS_URL}")

        fetchDevices(context)
    }

    private fun fetchDevices(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                val response = DroneNetwork.api.getDevices(authHeader)

                if (response.isSuccessful && response.body() != null) {
                    val deviceList = response.body()!!.devices
                    _devices.value = deviceList
                    addLog("[API] Found ${deviceList.size} device(s)")
                } else {
                    addLog("[ERROR] Failed to fetch devices: ${response.code()}")
                }
            } catch (e: Exception) {
                addLog("[ERROR] Network error: ${e.message}")
            }
        }
    }

    // --- WAYPOINT FUNCTIONS ---

    fun addWaypoint(lat: Double, lon: Double) {
        _waypoints.add(Pair(lat, lon))
        addLog("[PLAN] Waypoint Added: $lat, $lon")
    }

    fun clearWaypoints() {
        _waypoints.clear()
        addLog("[PLAN] Mission Cleared")
    }

    fun startMission(context: Context) {
        if (_waypoints.isEmpty()) {
            addLog("[ERROR] No waypoints selected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                val missionPoints = _waypoints.map { listOf(it.first, it.second) }
                addLog("[CMD] Uploading Mission...")

                val params = com.example.djicloudlink.network.MissionParams(waypoints = missionPoints)
                val response = DroneNetwork.api.sendMission(targetSn, params, authHeader)

                if (response.isSuccessful) {
                    addLog("[SUCCESS] Mission sent")
                } else {
                    addLog("[ERROR] Mission upload failed: ${response.code()}")
                }
            } catch (e: Exception) {
                addLog("[FAIL] Network Error: ${e.message}")
            }
        }
    }

    // --- COMMAND FUNCTIONS ---

    fun takePhoto(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                addLog("[CMD] Sending Photo Command...")
                val response = DroneNetwork.api.takePhoto(targetSn, authHeader)

                if (response.isSuccessful && response.body() != null) {
                    addLog("[SUCCESS] 📸 Photo taken")
                } else {
                    addLog("[ERROR] Camera failed: ${response.code()}")
                }
            } catch (e: Exception) {
                addLog("[FAIL] Network Error: ${e.message}")
            }
        }
    }

    fun sendTakeoffNew(context: Context, altitude: Int = 10) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                val params = com.example.djicloudlink.network.TakeoffParams(altitude)
                val response = DroneNetwork.api.sendTakeoff(targetSn, params, authHeader)
                if (response.isSuccessful) addLog("[SUCCESS] Takeoff command sent")
            } catch (e: Exception) {
                addLog("[FAIL] Network Error: ${e.message}")
            }
        }
    }

    fun sendLandNew(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                val response = DroneNetwork.api.sendLand(targetSn, authHeader)
                if (response.isSuccessful) addLog("[SUCCESS] Land command sent")
            } catch (e: Exception) {
                addLog("[FAIL] Network Error: ${e.message}")
            }
        }
    }

    fun sendRTHNew(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                val response = DroneNetwork.api.sendReturnToHome(targetSn, authHeader)
                if (response.isSuccessful) addLog("[SUCCESS] RTH command sent")
            } catch (e: Exception) {
                addLog("[FAIL] Network Error: ${e.message}")
            }
        }
    }

    // --- TELEMETRY FUNCTIONS ---

    fun getTelemetryHistory(context: Context, limit: Int = 100) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authHeader = TokenManager.getAuthHeader(context) ?: return@launch
                val response = DroneNetwork.api.getTelemetryHistory(targetSn, limit, authHeader)

                if (response.isSuccessful && response.body() != null) {
                    val history = response.body()!!
                    withContext(Dispatchers.Main) {
                        _historyList.clear()
                        _historyList.addAll(history.data)
                        addLog("[UI] History Updated: ${history.count} records loaded")
                    }
                }
            } catch (e: Exception) {
                addLog("[ERROR] History Fetch Failed: ${e.message}")
            }
        }
    }

    // --- LOGGING & EXPORT ---

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentList = _logs.value.toMutableList()
        currentList.add(0, "[$timestamp] $message")
        if (currentList.size > 50) currentList.removeAt(currentList.lastIndex)
        _logs.value = currentList
    }

    fun saveFlightLog(context: Context) {
        if (flightHistory.isEmpty()) {
            Toast.makeText(context, "No Data to Save", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "FlightLog_$timestamp.csv"
                val file = File(context.getExternalFilesDir(null), fileName)
                val writer = FileWriter(file)

                // ADDED SPEED TO THE CSV HEADER
                writer.append("Timestamp_ms,Mode,Battery(%),Altitude(m),Lat,Lon,Satellites,Speed(m/s)\n")

                flightHistory.forEach { record ->
                    val timeMs = record.first
                    val data = record.second

                    writer.append("${timeMs},")
                    writer.append("${data.mode_code},")
                    writer.append("${data.battery_percent},")
                    writer.append("${data.height},")
                    writer.append("${data.latitude},")
                    writer.append("${data.longitude},")
                    writer.append("${data.gps_number},")
                    writer.append("${data.speed}\n") // ADDED SPEED DATA HERE
                }

                writer.flush()
                writer.close()

                // Clear the temporary history after saving so the next flight starts fresh
                flightHistory.clear()

                withContext(Dispatchers.Main) {
                    addLog("[IO] Saved: $fileName")
                    Toast.makeText(context, "Flight Saved Successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addLog("[ERROR] Save Failed: ${e.message}") }
            }
        }
    }

    // --- LOGOUT ---

    fun logout(context: Context) {
        TokenManager.clearToken(context)
        if (::webSocketManager.isInitialized) {
            webSocketManager.disconnect()
        }
        _isConnected.value = false
        addLog("[AUTH] Logged out")
    }

    override fun onCleared() {
        super.onCleared()
        if (::webSocketManager.isInitialized) {
            webSocketManager.disconnect()
        }
    }
}