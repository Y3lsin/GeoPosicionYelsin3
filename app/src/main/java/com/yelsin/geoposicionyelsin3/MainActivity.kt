package com.yelsin.geoposicionyelsin3

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlin.math.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableDoubleStateOf

// =================== CONFIG ===================
private const val EC2_BASE_URL = "http://3.81.72.37"  // <--- CAMBIA A TU IP
private const val DEVICE_ID = "celular1"

// =================== ACTIVITY =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        setContent { AppRoot() }
    }
}

// ================ PERMISOS HELPERS ===============
private fun hasLocationPermission(ctx: Context): Boolean {
    val f = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val c = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return f && c
}
private fun needsActRec(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
private fun needsPostNotifications(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

// =================== UI ROOT ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val ctx = LocalContext.current

    var hasFine by remember { mutableStateOf(false) }
    var hasCoarse by remember { mutableStateOf(false) }
    var hasActRec by remember { mutableStateOf(false) }
    var hasPostNoti by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        hasFine = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        hasCoarse = res[Manifest.permission.ACCESS_COARSE_LOCATION] == true || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        hasActRec = if (needsActRec()) (res[Manifest.permission.ACTIVITY_RECOGNITION] == true || granted(Manifest.permission.ACTIVITY_RECOGNITION)) else true
        hasPostNoti = if (needsPostNotifications()) (res[Manifest.permission.POST_NOTIFICATIONS] == true || granted(Manifest.permission.POST_NOTIFICATIONS)) else true
    }

    LaunchedEffect(Unit) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        hasFine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        hasCoarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        hasActRec = if (needsActRec()) granted(Manifest.permission.ACTIVITY_RECOGNITION) else true
        hasPostNoti = if (needsPostNotifications()) granted(Manifest.permission.POST_NOTIFICATIONS) else true
        val perms = mutableListOf<String>()
        if (!hasFine) perms += Manifest.permission.ACCESS_FINE_LOCATION
        if (!hasCoarse) perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needsActRec() && !hasActRec) perms += Manifest.permission.ACTIVITY_RECOGNITION
        if (needsPostNotifications() && !hasPostNoti) perms += Manifest.permission.POST_NOTIFICATIONS
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
    }

    val allGranted = hasFine && hasCoarse && hasActRec && hasPostNoti

    Scaffold(topBar = { TopAppBar(title = { Text("GeoPosicionYelsin3") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!allGranted) {
                Text("Concede permisos de ubicación/actividad/notificaciones para continuar.", Modifier.padding(16.dp))
            } else {
                MapWithSensorsAndSearch()
            }
        }
    }
}

// ================= MAP + SENSORES + RUTAS =================
data class Place(val name: String, val lat: Double, val lon: Double, val display: String)

@Composable
@SuppressLint("MissingPermission")
fun MapWithSensorsAndSearch() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember { OkHttpClient() }

    // ---------- Mapa ----------
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            controller.setZoom(17.0)
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            setMultiTouchControls(true)
        }
    }

    // Overlays/markers
    val trackLine = remember { Polyline().apply { outlinePaint.strokeWidth = 6f } }
    val youMarker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Tú" } }
    val destMarker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Destino" } }
    val routeLine = remember { Polyline().apply { outlinePaint.strokeWidth = 8f } }

    // ---------- Estados ----------
    var current by remember { mutableStateOf<GeoPoint?>(null) }
    var gpsAcc by remember { mutableFloatStateOf(0f) }
    var totalDist by remember { mutableDoubleStateOf(0.0) }
    var steps by remember { mutableFloatStateOf(0f) }
    var isMoving by remember { mutableStateOf(false) }
    var lastGpsLoc by remember { mutableStateOf<Location?>(null) }
    var lastStepCountSent by remember { mutableFloatStateOf(0f) }
    var lastSentTs by remember { mutableStateOf(0L) }

    // Búsqueda / geocoding
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf(listOf<Place>()) }
    var isSearching by remember { mutableStateOf(false) }
    var reverseInfo by remember { mutableStateOf<String?>(null) }

    // Navegación
    var navMode by remember { mutableStateOf("driving") } // "driving" o "foot"
    var stepsList by remember { mutableStateOf(listOf<String>()) }

    // ---------- Sensores ----------
    val sensorManager = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val stepCounter = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    val stepDetector = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }
    val accBuffer = remember { ArrayDeque<FloatArray>() }
    val windowSize = 20
    val movingThreshold = 0.2f

    DisposableEffect(Unit) {
        var baseSteps = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (accBuffer.size >= windowSize) accBuffer.removeFirst()
                        accBuffer.addLast(floatArrayOf(event.values[0], event.values[1], event.values[2]))
                        if (accBuffer.size == windowSize) {
                            val mags = accBuffer.map { sqrt(it[0]*it[0] + it[1]*it[1] + it[2]*it[2]) }
                            val mean = mags.average().toFloat()
                            val varz = mags.fold(0f) { a, v -> a + (v - mean).pow(2) } / mags.size
                            isMoving = varz > movingThreshold
                        }
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        if (baseSteps < 0f) baseSteps = event.values[0]
                        steps = max(0f, event.values[0] - baseSteps)
                    }
                    Sensor.TYPE_STEP_DETECTOR -> steps += 1f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try { accSensor?.also { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } } catch (_: SecurityException) {}
        try { stepCounter?.also { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } } catch (_: SecurityException) {}
        try { stepDetector?.also { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } } catch (_: SecurityException) {}
        onDispose { try { sensorManager.unregisterListener(listener) } catch (_: SecurityException) {} }
    }

    // ---------- GPS ----------
    val fused = remember { LocationServices.getFusedLocationProviderClient(ctx) }
    val locationReqMoving = remember {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(700)
            .setMinUpdateDistanceMeters(1.5f)
            .build()
    }
    val locationReqStill = remember {
        LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(3000)
            .setMinUpdateDistanceMeters(0f)
            .build()
    }

    // Envío a EC2 (igual que antes)
    fun sendToServer(loc: GeoPoint, acc: Float, stepsNow: Float) {
        scope.launch(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("device_id", DEVICE_ID)
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", acc)
                put("steps", stepsNow)
                put("ts", System.currentTimeMillis())
            }.toString()
            val req = Request.Builder()
                .url("$EC2_BASE_URL/api/ingest")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            try { http.newCall(req).execute().use { } } catch (_: Exception) {}
        }
    }

    // Suscripción a GPS
    DisposableEffect(isMoving) {
        if (!hasLocationPermission(ctx)) return@DisposableEffect onDispose { }
        val cb = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                gpsAcc = loc.accuracy
                val point = GeoPoint(loc.latitude, loc.longitude)

                current?.let { prev ->
                    val d = haversine(prev.latitude, prev.longitude, point.latitude, point.longitude)
                    if (d.isFinite() && d < 0.2) totalDist += d
                }
                current = point
                lastGpsLoc = loc

                if (!mapView.overlays.contains(trackLine)) mapView.overlays.add(trackLine)
                if (!mapView.overlays.contains(youMarker)) mapView.overlays.add(youMarker)
                youMarker.position = point
                trackLine.addPoint(point)
                mapView.controller.animateTo(point)

                val now = System.currentTimeMillis()
                if (now - lastSentTs > 4000 || (steps - lastStepCountSent) >= 5f) {
                    sendToServer(point, gpsAcc, steps)
                    lastSentTs = now
                    lastStepCountSent = steps
                }
                mapView.invalidate()
            }
        }
        val req = if (isMoving) locationReqMoving else locationReqStill
        try { fused.requestLocationUpdates(req, cb, Looper.getMainLooper()) } catch (_: SecurityException) {}
        onDispose { try { fused.removeLocationUpdates(cb) } catch (_: SecurityException) {} }
    }

    // ---------- BÚSQUEDA: Nominatim ----------
    fun searchPlaces(q: String) {
        if (q.isBlank()) { results = emptyList(); return }
        isSearching = true
        scope.launch(Dispatchers.IO) {
            val url = "https://nominatim.openstreetmap.org/search?format=json&q=${q.trim()}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GeoPosicionYelsin3/1.0 (Android)")
                .build()
            val list = runCatching {
                http.newCall(req).execute().use { r ->
                    val arr = JSONArray(r.body?.string() ?: "[]")
                    (0 until minOf(arr.length(), 10)).map { i ->
                        val o = arr.getJSONObject(i)
                        Place(
                            name = o.optString("display_name"),
                            lat = o.getString("lat").toDouble(),
                            lon = o.getString("lon").toDouble(),
                            display = o.optString("type", "")
                        )
                    }
                }
            }.getOrElse { emptyList() }
            results = list
            isSearching = false
        }
    }

    // Reverse geocoding (coordenadas -> dirección)
    fun reverseGeocode(lat: Double, lon: Double) {
        scope.launch(Dispatchers.IO) {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GeoPosicionYelsin3/1.0 (Android)")
                .build()
            val txt = runCatching {
                http.newCall(req).execute().use { r ->
                    JSONObject(r.body?.string() ?: "{}").optString("display_name", "")
                }
            }.getOrElse { "" }
            reverseInfo = if (txt.isNotBlank()) txt else null
        }
    }

    // ---------- RUTA: OSRM ----------
    fun requestRoute(from: GeoPoint, to: GeoPoint, mode: String = navMode) {
        scope.launch(Dispatchers.IO) {
            val url =
                "https://router.project-osrm.org/route/v1/$mode/${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                        "?overview=full&geometries=geojson&steps=true"
            val req = Request.Builder().url(url).build()
            val resp: Response = http.newCall(req).execute()
            resp.use {
                val body = it.body?.string() ?: return@use
                val root = JSONObject(body)
                val routes = root.optJSONArray("routes") ?: return@use
                if (routes.length() == 0) return@use
                val route = routes.getJSONObject(0)
                val geom = route.getJSONObject("geometry")
                val coords = geom.getJSONArray("coordinates")
                val pts = mutableListOf<GeoPoint>()
                for (i in 0 until coords.length()) {
                    val p = coords.getJSONArray(i)
                    val lon = p.getDouble(0)
                    val lat = p.getDouble(1)
                    pts += GeoPoint(lat, lon)
                }

                // steps
                val navSteps = mutableListOf<String>()
                val legs = route.optJSONArray("legs")
                if (legs != null && legs.length() > 0) {
                    val sarr = legs.getJSONObject(0).optJSONArray("steps")
                    if (sarr != null) {
                        for (i in 0 until sarr.length()) {
                            val st = sarr.getJSONObject(i)
                            val name = st.optString("name", "")
                            val m = st.optJSONObject("maneuver")?.optString("type") ?: ""
                            val dist = st.optDouble("distance", 0.0)
                            navSteps += "${"%.0f".format(dist)} m · $m ${if (name.isNotBlank()) "→ $name" else ""}"
                        }
                    }
                }

                // pintar en UI
                routeLine.setPoints(pts)
                stepsList = navSteps
                if (!mapView.overlays.contains(routeLine)) mapView.overlays.add(routeLine)
                mapView.invalidate()
            }
        }
    }

    // ---------- Eventos del mapa (toque/long press) ----------
    DisposableEffect(Unit) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                // mostrar coordenadas y reverse
                reverseGeocode(p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                // fijar destino y pedir ruta desde tu posición actual
                if (!mapView.overlays.contains(destMarker)) mapView.overlays.add(destMarker)
                destMarker.position = p
                current?.let { requestRoute(it, p, navMode) }
                mapView.controller.animateTo(p)
                mapView.invalidate()
                return true
            }
        }
        val overlay = MapEventsOverlay(receiver)
        mapView.overlays.add(overlay)
        onDispose { mapView.overlays.remove(overlay) }
    }

    // ---------- UI ----------
    Column(Modifier.fillMaxSize()) {
        // Barra de búsqueda
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Buscar lugares/direcciones") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { searchPlaces(query.text) },
                enabled = query.text.isNotBlank() && !isSearching
            ) { Text(if (isSearching) "Buscando..." else "Buscar") }
        }

        // Resultados clicables
        if (results.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                items(results) { pl ->
                    Text(
                        pl.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // ir al resultado, marcar destino y pedir ruta desde la posición actual
                                val p = GeoPoint(pl.lat, pl.lon)
                                if (!mapView.overlays.contains(destMarker)) mapView.overlays.add(destMarker)
                                destMarker.position = p
                                mapView.controller.setCenter(p)
                                mapView.controller.setZoom(17.0)
                                current?.let { requestRoute(it, p, navMode) }
                                results = emptyList() // ocultar lista
                            }
                            .padding(vertical = 6.dp)
                    )
                    Divider()
                }
            }
        }

        // Mapa
        AndroidView(factory = { mapView }, modifier = Modifier.weight(1f))

        // Info + controles
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            reverseInfo?.let { Text("Dirección: $it") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Acc: ${"%.1f".format(gpsAcc)} m")
                Text("Dist: ${"%.2f".format(totalDist)} km")
                Text("Steps: ${steps.toInt()}")
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                // cambiar modo de ruta
                Button(
                    onClick = { navMode = if (navMode == "driving") "foot" else "driving" },
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                ) { Text("Modo: ${if (navMode == "driving") "Auto" else "A pie"}") }

                // enviar punto actual a EC2
                Button(
                    onClick = {
                        current?.let {
                            sendToServer(it, gpsAcc, steps)
                            lastSentTs = System.currentTimeMillis(); lastStepCountSent = steps
                        }
                    },
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                ) { Text("Enviar ahora") }
            }

            // Servicio en segundo plano
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(
                    onClick = {
                        val itn = Intent(ctx, TrackingService::class.java)
                        startForegroundService(ctx, itn)
                    },
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                ) { Text("Iniciar fondo") }

                Button(
                    onClick = { ctx.stopService(Intent(ctx, TrackingService::class.java)) },
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                ) { Text("Parar fondo") }
            }

            // Pasos de navegación (si hay)
            if (stepsList.isNotEmpty()) {
                Text("Indicaciones:", modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(stepsList) { s -> Text("• $s") }
                }
            }
        }
    }
}

// ================= Helpers geo/num =================
private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat/2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1-a))
    return R * c
}
