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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
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

    // ✅ Compose-friendly launcher (evita el crash de LifecycleOwner)
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

        hasFine = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        hasCoarse = res[Manifest.permission.ACCESS_COARSE_LOCATION] == true || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        hasActRec = if (needsActRec())
            (res[Manifest.permission.ACTIVITY_RECOGNITION] == true || granted(Manifest.permission.ACTIVITY_RECOGNITION))
        else true
        hasPostNoti = if (needsPostNotifications())
            (res[Manifest.permission.POST_NOTIFICATIONS] == true || granted(Manifest.permission.POST_NOTIFICATIONS))
        else true
    }

    // Chequeo inicial + solicitud
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
                Text(
                    "Concede permisos de ubicación/actividad/notificaciones para continuar.",
                    Modifier.padding(16.dp)
                )
            } else {
                MapWithSensors()
            }
        }
    }
}

// ============== MAPA + SENSORES + GPS ===========
@Composable
@SuppressLint("MissingPermission")
fun MapWithSensors() {
    val ctx = LocalContext.current

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            controller.setZoom(17.0)
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            setMultiTouchControls(true)
        }
    }

    var current by remember { mutableStateOf<GeoPoint?>(null) }
    var steps by remember { mutableFloatStateOf(0f) }
    var isMoving by remember { mutableStateOf(false) }
    var gpsAcc by remember { mutableFloatStateOf(0f) }
    var totalDist by remember { mutableDoubleStateOf(0.0) }

    val path = remember { Polyline().apply { outlinePaint.strokeWidth = 6f } }
    val marker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }

    // Sensores
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

    // GPS
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

    val scope = rememberCoroutineScope()
    val http = remember { OkHttpClient() }
    var lastSentTs by remember { mutableStateOf(0L) }
    var lastGpsLoc by remember { mutableStateOf<Location?>(null) }
    var lastStepCountSent by remember { mutableFloatStateOf(0f) }

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
        if (!hasLocationPermission(ctx)) {
            return@DisposableEffect onDispose { }
        }

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

                if (!mapView.overlays.contains(path)) mapView.overlays.add(path)
                if (!mapView.overlays.contains(marker)) mapView.overlays.add(marker)
                marker.position = point
                path.addPoint(point)
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

    // Interpolación por pasos cuando la precisión cae
    LaunchedEffect(steps) {
        val gp = current
        val last = lastGpsLoc
        if (gp != null && last != null) {
            if (gpsAcc > 20f && isMoving && steps - lastStepCountSent >= 1f) {
                val stepLen = 0.78
                val dist = (steps - lastStepCountSent) * stepLen
                if (last.hasBearing()) {
                    val proj = project(gp, last.bearing.toDouble(), dist)
                    current = proj
                    path.addPoint(proj)
                    marker.position = proj
                    totalDist += dist / 1000.0
                    mapView.controller.setCenter(proj)
                    mapView.invalidate()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Steps: ${steps.toInt()}")
            Text("Mov: ${if (isMoving) "Sí" else "No"}")
            Text("Acc: ${"%.1f".format(gpsAcc)} m")
            Text("Dist: ${"%.2f".format(totalDist)} km")
        }
        Button(
            onClick = {
                current?.let {
                    sendToServer(it, gpsAcc, steps)
                    lastSentTs = System.currentTimeMillis()
                    lastStepCountSent = steps
                }
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Text("Enviar ahora") }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
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
    }
}

// ============== GEO HELPERS =====================
private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat/2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1-a))
    return R * c
}
private fun project(from: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
    val R = 6371000.0
    val br = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(from.latitude)
    val lon1 = Math.toRadians(from.longitude)
    val dr = meters / R
    val lat2 = asin( sin(lat1)*cos(dr) + cos(lat1)*sin(dr)*cos(br) )
    val lon2 = lon1 + atan2( sin(br)*sin(dr)*cos(lat1), cos(dr)-sin(lat1)*sin(lat2) )
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}
