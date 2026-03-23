package com.example.djicloudlink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.djicloudlink.viewmodel.DroneViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- DATA CLASS FOR THE SUMMARY TABLE ---
data class FlightSummary(
    val date: String,
    val startTime: String,
    val endTime: String,
    val timeInAir: String,
    val maxAltitude: String,
    val maxSpeed: String,
    val aircraft: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "DJICloudLink-Student-Project/1.0"

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFF2E7D32)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    DroneControlUI()
                }
            }
        }
    }
}

@Composable
fun DroneControlUI(viewModel: DroneViewModel = viewModel()) {
    val telemetry by viewModel.telemetry.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val context = LocalContext.current

    // History State Logic
    var showUserMenu by remember { mutableStateOf(false) }
    var isHistoryScreenOpen by remember { mutableStateOf(false) }
    var savedFlightSummaries by remember { mutableStateOf<List<FlightSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        viewModel.connectToServer(context)
    }

    val waypoints = viewModel.waypoints

    if (isHistoryScreenOpen) {
        // --- FULL SCREEN FLIGHT HISTORY ---
        FullFlightHistoryScreen(
            summaries = savedFlightSummaries,
            onBack = { isHistoryScreenOpen = false }
        )
    } else {
        // --- MAIN DASHBOARD UI (Untouched Layout) ---
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- 1. HEADER ROW ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Drone Control", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(if (telemetry.mode_code > 0) Color(0xFF00E676) else Color.Gray, RoundedCornerShape(50)))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (telemetry.mode_code > 0) "SYSTEM ONLINE" else "SYSTEM STANDBY", style = MaterialTheme.typography.labelMedium, color = if (telemetry.mode_code > 0) Color(0xFF00E676) else Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.takePhoto(context) }, modifier = Modifier.background(Color(0xFF00ACC1), RoundedCornerShape(8.dp))) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Photo", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.saveFlightLog(context) }, modifier = Modifier.background(Color(0xFF333333), RoundedCornerShape(8.dp))) {
                        Icon(Icons.Default.Share, contentDescription = "Export", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box {
                        IconButton(onClick = { showUserMenu = true }, modifier = Modifier.background(Color(0xFF333333), RoundedCornerShape(8.dp))) {
                            Icon(Icons.Default.Person, contentDescription = "User", tint = Color.White)
                        }
                        DropdownMenu(expanded = showUserMenu, onDismissRequest = { showUserMenu = false }, modifier = Modifier.background(Color(0xFF1E1E1E))) {
                            DropdownMenuItem(
                                text = { Text("Flight History", color = Color.White) },
                                onClick = {
                                    showUserMenu = false
                                    // Load local CSVs and open history screen
                                    savedFlightSummaries = loadLocalFlightSummaries(context)
                                    isHistoryScreenOpen = true
                                },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null, tint = Color.White) }
                            )
                            DropdownMenuItem(
                                text = { Text("Exit", color = Color(0xFFFF5252)) },
                                onClick = {
                                    showUserMenu = false
                                    viewModel.logout(context)
                                    val intent = Intent(context, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
                                    context.startActivity(intent)
                                },
                                leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color(0xFFFF5252)) }
                            )
                        }
                    }
                }
            }

            // --- 2. DATA GRID ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("ALTITUDE", "${"%.1f".format(telemetry.height)} m", Icons.Default.SwapVert, Color(0xFF29B6F6), Modifier.weight(1f))
                StatCard("BATTERY", "${telemetry.battery_percent}%", Icons.Default.BatteryStd, if (telemetry.battery_percent < 20) Color(0xFFFF5252) else Color(0xFF66BB6A), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("SATELLITES", "${telemetry.gps_number}", Icons.Default.LocationOn, Color(0xFFFFCA28), Modifier.weight(1f))
                StatCard("SPEED", "H: ${"%.1f".format(telemetry.speed)} m/s\nV: ${"%.1f".format(telemetry.verticalSpeed)} m/s", Icons.Default.Speed, Color(0xFFAB47BC), Modifier.weight(1f), valueSize = 14.sp)
            }

            // --- 3. COMMAND CENTER ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.sendTakeoffNew(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Default.FlightTakeoff, null); Spacer(Modifier.width(8.dp)); Text("TAKEOFF", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { viewModel.sendLandNew(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Default.FlightLand, null); Spacer(Modifier.width(8.dp)); Text("LAND", fontWeight = FontWeight.Bold)
                }
            }

            // --- 4. MISSION CONTROLS ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.startMission(context) }, enabled = waypoints.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2962FF)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("START MISSION", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { viewModel.sendRTHNew(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).height(50.dp)) {
                    Icon(Icons.Default.Home, null); Spacer(Modifier.width(8.dp)); Text("RTH", fontWeight = FontWeight.Bold)
                }
            }

            // --- 5. LIVE MAP ---
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(19.0)
                            val eventsReceiver = object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { viewModel.addWaypoint(p.latitude, p.longitude); return true }
                                override fun longPressHelper(p: GeoPoint): Boolean = false
                            }
                            overlays.add(MapEventsOverlay(eventsReceiver))
                        }
                    },
                    update = { mapView ->
                        val dronePoint = GeoPoint(telemetry.latitude, telemetry.longitude)
                        mapView.controller.setCenter(dronePoint)
                        if (mapView.overlays.size > 1) mapView.overlays.subList(1, mapView.overlays.size).clear()

                        val routePoints = ArrayList<GeoPoint>()
                        waypoints.forEachIndexed { index, point ->
                            val wpGeo = GeoPoint(point.first, point.second)
                            routePoints.add(wpGeo)
                            val wpMarker = Marker(mapView).apply { position = wpGeo; title = "WP ${index + 1}"; icon = context.getDrawable(org.osmdroid.library.R.drawable.marker_default) }
                            mapView.overlays.add(wpMarker)
                        }
                        if (routePoints.isNotEmpty()) {
                            val line = Polyline().apply { setPoints(routePoints); outlinePaint.color = android.graphics.Color.GREEN; outlinePaint.strokeWidth = 5f }
                            mapView.overlays.add(line)
                        }
                        val droneMarker = Marker(mapView).apply { position = dronePoint; title = "Drone"; snippet = "Alt: ${telemetry.height}m" }
                        mapView.overlays.add(droneMarker)
                        mapView.invalidate()
                    }
                )
                if (waypoints.isNotEmpty()) {
                    Button(onClick = { viewModel.clearWaypoints() }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).height(36.dp)) {
                        Text("CLEAR", fontSize = 12.sp)
                    }
                }
            }

            // --- 6. SYSTEM LOGS ---
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp)).padding(8.dp)) {
                LazyColumn(reverseLayout = true) {
                    items(logs) { log ->
                        Text(">> $log", color = if (log.contains("ERROR")) Color(0xFFFF5252) else Color(0xFF00E676), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// --- CSV PARSING FUNCTION ---
fun loadLocalFlightSummaries(context: Context): List<FlightSummary> {
    val summaries = mutableListOf<FlightSummary>()
    val dir = context.getExternalFilesDir(null)
    val files = dir?.listFiles { file -> file.extension == "csv" && file.name.startsWith("FlightLog_") }

    val sdfDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    files?.sortedByDescending { it.lastModified() }?.forEach { file ->
        try {
            val lines = file.readLines()
            if (lines.size > 1) {
                var maxAlt = 0.0
                var maxSpeed = 0.0
                var startTimeMs = 0L
                var endTimeMs = 0L

                for (i in 1 until lines.size) {
                    val cols = lines[i].split(",")
                    if (cols.isNotEmpty()) {
                        val ts = cols[0].toLongOrNull() ?: continue
                        val alt = cols.getOrNull(3)?.toDoubleOrNull() ?: 0.0

                        // Safely reads the new speed column (index 7) added in ViewModel
                        val spd = cols.getOrNull(7)?.toDoubleOrNull() ?: 0.0

                        if (startTimeMs == 0L) startTimeMs = ts
                        endTimeMs = ts
                        if (alt > maxAlt) maxAlt = alt
                        if (spd > maxSpeed) maxSpeed = spd
                    }
                }

                val dateStr = sdfDate.format(Date(startTimeMs))
                val startTimeStr = sdfTime.format(Date(startTimeMs))
                val endTimeStr = sdfTime.format(Date(endTimeMs))

                val diffSec = (endTimeMs - startTimeMs) / 1000
                val mins = diffSec / 60
                val secs = diffSec % 60
                val timeInAir = "${mins}m ${secs}s"

                summaries.add(FlightSummary(
                    date = dateStr,
                    startTime = startTimeStr,
                    endTime = endTimeStr,
                    timeInAir = timeInAir,
                    maxAltitude = "${String.format(Locale.US, "%.1f", maxAlt)}m",
                    maxSpeed = if (maxSpeed > 0) "${String.format(Locale.US, "%.1f", maxSpeed)}m/s" else "0.0m/s",
                    aircraft = "Matrice 30"
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return summaries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullFlightHistoryScreen(summaries: List<FlightSummary>, onBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredSummaries = summaries.filter {
        it.date.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0D1B2A))) {
                TopAppBar(
                    title = { Text("Flight History", color = Color.White, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1B2A))
                )
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search flight date...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFE0E0E0),
                        unfocusedContainerColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        },
        containerColor = Color(0xFF0D1B2A)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (summaries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No saved flights found. Use the Share/Save button first.", color = Color.LightGray)
                }
            } else {
                FlightSummaryTable(filteredSummaries)
            }
        }
    }
}

@Composable
fun FlightSummaryTable(summaries: List<FlightSummary>) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // Responsive table width: Stretches in landscape, scrolls cleanly in portrait
    val tableWidth = max(screenWidth - 32.dp, 750.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .horizontalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.width(tableWidth)) {

            // Header Row
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFE0E0E0)).padding(12.dp)) {
                Text("Date", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Start Time", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("End Time", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Time in Air", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Max Altitude", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Max Speed", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Aircraft", Modifier.weight(1.2f), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // Data Rows
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(summaries) { summary ->
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(summary.date, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                        Text(summary.startTime, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                        Text(summary.endTime, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                        Text(summary.timeInAir, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                        Text(summary.maxAltitude, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                        Text(summary.maxSpeed, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                        Text(summary.aircraft, Modifier.weight(1.2f), color = Color.Black, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = Color.LightGray, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, icon: ImageVector, accentColor: Color, modifier: Modifier = Modifier, valueSize: androidx.compose.ui.unit.TextUnit = 20.sp) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = label, color = Color.Gray, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, color = Color.White, fontSize = valueSize, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        }
    }
}