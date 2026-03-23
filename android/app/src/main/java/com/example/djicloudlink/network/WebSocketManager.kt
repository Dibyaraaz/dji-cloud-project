package com.example.djicloudlink.network

import android.util.Log
import com.example.djicloudlink.model.DroneTelemetry
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*

/**
 * WebSocket Manager - Filtered to only listen to the actual Drone's Serial Number
 */
class WebSocketManager(
    private val targetSn: String, // <-- NEW: We pass the Drone SN here
    private val onTelemetryReceived: (DroneTelemetry) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    private var isConnected = false

    fun connect() {
        val request = Request.Builder()
            .url(ServerConfig.WS_URL)
            .build()

        Log.d("DJI_WS", "Connecting to ${ServerConfig.WS_URL}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("DJI_WS", "✅ Connected successfully")
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)

                    when {
                        // Format 1: Backend broadcast message
                        json.has("type") && json.get("type").asString == "telemetry" -> {
                            val deviceSn = json.get("deviceSn")?.asString

                            // <-- NEW: Strict filter! Only update UI if it's the M-30 Enterprise
                            if (deviceSn == targetSn) {
                                val data = json.getAsJsonObject("data")
                                if (data != null) {
                                    val telemetry = parseTelemetryData(data)
                                    Log.d("DJI_WS", "✅ Drone Telemetry: Battery ${telemetry.battery_percent}%, Alt: ${telemetry.height}")
                                    onTelemetryReceived(telemetry)
                                }
                            } else {
                                // Ignore the Remote Controller's telemetry so it doesn't overwrite the UI
                                Log.d("DJI_WS", "Skipping telemetry for SN: $deviceSn")
                            }
                        }

                        // Format 2: Direct DroneTelemetry object (for backward compatibility)
                        json.has("battery_capacity_percent") || json.has("latitude") -> {
                            val telemetry = gson.fromJson(text, DroneTelemetry::class.java)
                            Log.d("DJI_WS", "✅ Direct telemetry: Battery ${telemetry.battery_percent}%")
                            onTelemetryReceived(telemetry)
                        }

                        else -> {
                            Log.w("DJI_WS", "⚠️ Unknown message format")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DJI_WS", "❌ Parse error: ${e.message}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("DJI_WS", "❌ Connection failed: ${t.message}", t)
                isConnected = false
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("DJI_WS", "Connection closing: $code - $reason")
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("DJI_WS", "Connection closed: $code - $reason")
                isConnected = false
            }
        })
    }

    /**
     * Parse backend telemetry data to DroneTelemetry format
     */
    private fun parseTelemetryData(data: JsonObject): DroneTelemetry {
        return DroneTelemetry(
            mode_code = data.get("mode_code")?.asInt ?: 0,
            latitude = data.get("latitude")?.asDouble ?: 0.0,
            longitude = data.get("longitude")?.asDouble ?: 0.0,
            height = data.get("altitude")?.asDouble ?: data.get("height")?.asDouble ?: 0.0,
            battery_percent = data.get("battery")?.asInt ?: data.get("battery_percent")?.asInt ?: 100,
            rtk_state = data.get("rtk_state")?.asInt ?: 0,
            gps_number = data.get("satellites")?.asInt ?: data.get("gps_number")?.asInt ?: 0,

            // --> NEW: Parse the speeds
            speed = data.get("speed")?.asDouble ?: 0.0,
            verticalSpeed = data.get("verticalSpeed")?.asDouble ?: 0.0
        )
    }

    fun disconnect() {
        webSocket?.close(1000, "App closed")
        webSocket = null
        isConnected = false
        Log.d("DJI_WS", "Disconnected")
    }

    fun isConnected(): Boolean = isConnected
}