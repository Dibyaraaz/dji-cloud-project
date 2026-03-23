package com.example.djicloudlink.network

import com.example.djicloudlink.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// Server Configuration
object ServerConfig {
    const val SERVER_IP = "172.20.10.3"  // YOUR MAC IP
    const val PORT = "6789"
    const val BASE_URL = "http://$SERVER_IP:$PORT/"
    const val WS_URL = "ws://$SERVER_IP:$PORT/ws"
}

// API Interface
interface DroneApiService {

    // ========================================
    // AUTHENTICATION (NEW)
    // ========================================
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    // ========================================
    // DEVICES (NEW)
    // ========================================
    @GET("api/devices")
    suspend fun getDevices(@Header("Authorization") token: String): Response<DevicesResponse>

    // ========================================
    // TELEMETRY (NEW)
    // ========================================
    @GET("api/telemetry/latest")
    suspend fun getLatestTelemetry(@Header("Authorization") token: String): Response<LatestTelemetryResponse>

    @GET("api/telemetry/{sn}")
    suspend fun getTelemetry(
        @Path("sn") deviceSn: String,
        @Header("Authorization") token: String
    ): Response<BackendTelemetry>

    @GET("api/telemetry/{sn}/history")
    suspend fun getTelemetryHistory(
        @Path("sn") deviceSn: String,
        @Query("limit") limit: Int = 100,
        @Header("Authorization") token: String
    ): Response<TelemetryHistoryResponse>

    // ========================================
    // COMMANDS (NEW)
    // ========================================
    @POST("api/command/{sn}/takeoff")
    suspend fun sendTakeoff(
        @Path("sn") deviceSn: String,
        @Body params: TakeoffParams,
        @Header("Authorization") token: String
    ): Response<CommandResponse>

    @POST("api/command/{sn}/land")
    suspend fun sendLand(
        @Path("sn") deviceSn: String,
        @Header("Authorization") token: String
    ): Response<CommandResponse>

    @POST("api/command/{sn}/rth")
    suspend fun sendReturnToHome(
        @Path("sn") deviceSn: String,
        @Header("Authorization") token: String
    ): Response<CommandResponse>


    @POST("api/command/{sn}/camera/photo")
    suspend fun takePhoto(
        @Path("sn") deviceSn: String,
        @Header("Authorization") token: String
    ): Response<CommandResponse>


    // <-- NEW: Mission Endpoint added here!
    @POST("api/command/{sn}/mission")
    suspend fun sendMission(
        @Path("sn") deviceSn: String,
        @Body params: MissionParams,
        @Header("Authorization") token: String
    ): Response<CommandResponse>

    // ========================================
    // OLD API (Backward Compatibility)
    // ========================================
    @POST("api/v1/dji/control")
    suspend fun sendCommand(@Body command: DroneCommand): Response<Unit>
}

// Command Parameters for new API
data class TakeoffParams(
    val altitude: Int = 10
)

// <-- NEW: Mission Parameters Data Class
data class MissionParams(
    val waypoints: List<List<Double>>
)

// Singleton Network Instance
object DroneNetwork {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: DroneApiService by lazy {
        Retrofit.Builder()
            .baseUrl(ServerConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DroneApiService::class.java)
    }
}