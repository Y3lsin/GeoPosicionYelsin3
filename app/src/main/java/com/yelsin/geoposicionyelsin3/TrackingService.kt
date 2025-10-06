package com.yelsin.geoposicionyelsin3

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

private const val CHANNEL_ID = "geo_channel"
private const val NOTIF_ID = 1001

private const val EC2_BASE_URL = "http://3.81.72.37"   // <-- CAMBIA A TU IP
private const val DEVICE_ID = "celular1"

class TrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private val http by lazy { OkHttpClient() }
    private var lastSentTs = 0L
    private var lastStepCountSent = 0f

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        // startForeground necesita mostrar una notificación inmediata
        startForeground(NOTIF_ID, buildNotif("Iniciando seguimiento…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.0f)
            .build()

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
            updateNotif("Seguimiento activo")
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { fused.removeLocationUpdates(cb) } catch (_: SecurityException) {}
        updateNotif("Seguimiento detenido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean {
        val f = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val c = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return f && c
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private val cb = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            val loc = res.lastLocation ?: return
            val p = GeoPoint(loc.latitude, loc.longitude)
            val now = System.currentTimeMillis()
            if (now - lastSentTs > 4000) {
                sendToServer(p, loc.accuracy, lastStepCountSent)
                lastSentTs = now
            }
            updateNotif("Lat: %.5f  Lon: %.5f  ±%.1fm".format(p.latitude, p.longitude, loc.accuracy))
        }
    }

    private fun sendToServer(loc: GeoPoint, acc: Float, stepsNow: Float) {
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
        kotlin.runCatching { http.newCall(req).execute().use { } }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Geo Tracking",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotif(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GeoPosicionYelsin3")
            .setContentText(text)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        if (!canPostNotifications()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }
}
