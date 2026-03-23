package com.example.djicloudlink.model

import android.content.Context
import com.google.gson.annotations.SerializedName

// ============================================================
// OLD MODELS (Keep for backward compatibility with ViewModel)
// ============================================================

data class DroneCommand(
    val deviceSn: String = "1581F4BM12345",
    val method: String = "flight_control",
    val params: CommandParams
)

data class CommandParams(
    val action: Int,
    val waypoints: List<List<Double>>? = null
)

data class DroneTelemetry(
    @SerializedName("mode_code") val mode_code: Int = 0,
    @SerializedName("latitude") val latitude: Double = 41.7964587,
    @SerializedName("longitude") val longitude: Double = -6.7684237,
    @SerializedName("height") val height: Double = 0.0,
    @SerializedName("battery_capacity_percent") val battery_percent: Int = 100,
    @SerializedName("rtk_state") val rtk_state: Int = 0,
    @SerializedName("gps_number") val gps_number: Int = 0,

    @SerializedName("speed") val speed: Double = 0.0,
    @SerializedName("verticalSpeed") val verticalSpeed: Double = 0.0
) {
    fun getReadableStatus(): String {
        return when (mode_code) {
            0 -> "STANDBY"
            1, 2 -> "TAKEOFF PREP"
            3 -> "MANUAL FLIGHT"
            4 -> "AUTO TAKEOFF"
            6 -> "HOVER / HOLD"
            10 -> "AUTO LANDING"
            12 -> "LANDING"
            20 -> "MISSION MODE"
            else -> "UNKNOWN ($mode_code)"
        }
    }
}

// ============================================================
// NEW AUTHENTICATION MODELS
// ============================================================

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user") val user: User
)

data class User(
    @SerializedName("id") val id: Int,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("role") val role: String
)

// ============================================================
// NEW BACKEND TELEMETRY MODELS
// ============================================================

data class BackendTelemetry(
    @SerializedName("sn") val sn: String,
    @SerializedName("type") val type: String,
    @SerializedName("telemetry") val telemetry: TelemetryData,
    @SerializedName("metadata") val metadata: DeviceMetadata?,
    @SerializedName("firstSeen") val firstSeen: String,
    @SerializedName("lastUpdate") val lastUpdate: String,
    @SerializedName("online") val online: Boolean
)

data class TelemetryData(
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("altitude") val altitude: Double?,
    @SerializedName("battery") val battery: Int?,
    @SerializedName("speed") val speed: Double?,
    @SerializedName("heading") val heading: Double?,
    @SerializedName("verticalSpeed") val verticalSpeed: Double?,
    @SerializedName("satellites") val satellites: Int?,
    @SerializedName("timestamp") val timestamp: String?
) {
    // Convert to old DroneTelemetry format for compatibility
    fun toDroneTelemetry(): DroneTelemetry {
        return DroneTelemetry(
            latitude = latitude ?: 0.0,
            longitude = longitude ?: 0.0,
            height = altitude ?: 0.0,
            battery_percent = battery ?: 0,
            gps_number = satellites ?: 0,
            // ADDED THESE TWO LINES SO THE SPEED DATA FLOWS THROUGH:
            speed = speed ?: 0.0,
            verticalSpeed = verticalSpeed ?: 0.0
        )
    }
}

data class DeviceMetadata(
    @SerializedName("model") val model: String?,
    @SerializedName("firmware") val firmware: String?
)

// ============================================================
// DEVICES MODELS
// ============================================================

data class DevicesResponse(
    @SerializedName("count") val count: Int,
    @SerializedName("devices") val devices: List<DeviceInfo>
)

data class DeviceInfo(
    @SerializedName("sn") val sn: String,
    @SerializedName("type") val type: String,
    @SerializedName("model") val model: String,
    @SerializedName("firstSeen") val firstSeen: String,
    @SerializedName("lastUpdate") val lastUpdate: String,
    @SerializedName("online") val online: Boolean,
    @SerializedName("hasTelemetry") val hasTelemetry: Boolean
)

// ============================================================
// LATEST TELEMETRY
// ============================================================

data class LatestTelemetryResponse(
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("devices") val devices: Map<String, LatestDeviceTelemetry>
)

data class LatestDeviceTelemetry(
    @SerializedName("sn") val sn: String,
    @SerializedName("type") val type: String,
    @SerializedName("telemetry") val telemetry: TelemetryData,
    @SerializedName("lastUpdate") val lastUpdate: String
)

// ============================================================
// HISTORY MODELS
// ============================================================

data class TelemetryHistoryResponse(
    @SerializedName("deviceSn") val deviceSn: String,
    @SerializedName("count") val count: Int,
    @SerializedName("data") val data: List<HistoryPoint>
)

data class HistoryPoint(
    @SerializedName("id") val id: Long,
    @SerializedName("device_sn") val deviceSn: String,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("latitude") val latitude: String?,
    @SerializedName("longitude") val longitude: String?,
    @SerializedName("altitude") val altitude: String?,
    @SerializedName("battery") val battery: Int?,
    @SerializedName("speed") val speed: String?,
    @SerializedName("heading") val heading: String?
)

// ============================================================
// COMMAND MODELS
// ============================================================

data class CommandResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("commandId") val commandId: Long?,
    @SerializedName("message") val message: String
)

// ============================================================
// WEBSOCKET MODELS
// ============================================================

data class WebSocketMessage(
    @SerializedName("type") val type: String,
    @SerializedName("deviceSn") val deviceSn: String?,
    @SerializedName("data") val data: Any?,
    @SerializedName("timestamp") val timestamp: String
)

// ============================================================
// TOKEN MANAGER
// ============================================================

object TokenManager {
    private const val PREFS_NAME = "dji_auth"
    private const val KEY_TOKEN = "jwt_token"
    private const val KEY_USERNAME = "username"
    private const val KEY_ROLE = "role"
    private const val KEY_USER_ID = "user_id"

    fun saveToken(context: Context, token: String, user: User) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_TOKEN, token)
            putString(KEY_USERNAME, user.username)
            putString(KEY_ROLE, user.role)
            putInt(KEY_USER_ID, user.id)
            apply()
        }
    }

    fun getToken(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
    }

    fun getAuthHeader(context: Context): String? {
        val token = getToken(context)
        return if (token != null) "Bearer $token" else null
    }

    fun getUsername(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, null)
    }

    fun getRole(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, null)
    }

    fun getUserId(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_USER_ID, -1)
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        return getToken(context) != null
    }
}