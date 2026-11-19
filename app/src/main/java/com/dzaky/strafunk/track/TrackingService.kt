package com.dzaky.strafunk.track

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dzaky.strafunk.MainActivity
import com.dzaky.strafunk.R
import com.dzaky.strafunk.data.Fmt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Snapshot status rekaman untuk UI. */
data class TrackSnap(
    val active: Boolean = false,
    val running: Boolean = false,
    val type: String = "Lari",
    val dist: Double = 0.0,
    val steps: Long = 0,
    val elapsedMs: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val acc: Float = -1f,
    val fix: Boolean = false,
    val est: Boolean = false,
    val lastGood: Long = 0,
    val spd: Double = 0.0,
    val distAt: Long = 0,
    val splits: List<Int> = emptyList(),
    val pathVer: Int = 0,
    val lat: Double = Double.NaN,
    val lng: Double = Double.NaN,
    val provider: String = ""
)

/** Hasil akhir saat rekaman dihentikan. */
data class TrackResult(val snap: TrackSnap, val path: List<DoubleArray>, val startWall: Long)

object Track {
    val snap = MutableStateFlow(TrackSnap())
    val splitEvents = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 8)
    internal val path = ArrayList<DoubleArray>()
    fun pathCopy(): List<DoubleArray> = synchronized(path) { ArrayList(path) }
}

/**
 * Mesin tracking: GPS (difilter Kalman) + sensor langkah, berjalan sebagai foreground service
 * sehingga tetap merekam saat layar mati. Menampilkan notifikasi live bergaya Strafunk.
 */
class TrackingService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val A_START = "sf.START"; const val A_PAUSE = "sf.PAUSE"; const val A_RESUME = "sf.RESUME"; const val A_STOP = "sf.STOP"
        const val NID = 7301
        const val CH_TRACK = "sf_track"; const val CH_KM = "sf_km_v1"

        fun start(ctx: Context, type: String, stride: Int, weight: Int) {
            val i = Intent(ctx, TrackingService::class.java).setAction(A_START)
                .putExtra("type", type).putExtra("stride", stride).putExtra("weight", weight)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun send(ctx: Context, action: String) {
            try { ctx.startService(Intent(ctx, TrackingService::class.java).setAction(action)) } catch (_: Exception) { }
        }
        /** Hentikan & kembalikan hasil akhir. */
        fun stop(ctx: Context): TrackResult {
            val r = TrackResult(Track.snap.value.copy(elapsedMs = elapsedMs()), Track.pathCopy(), startWall)
            send(ctx, A_STOP)
            return r
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH_TRACK, "Rekaman aktif", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); description = "Jarak, waktu, dan pace saat merekam"
            })
            nm.createNotificationChannel(NotificationChannel(CH_KM, "Kilometer & battle", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pop-up tiap kilometer"
                setSound(Uri.parse("android.resource://" + ctx.packageName + "/raw/sf_chime"),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true); vibrationPattern = longArrayOf(0, 60, 80, 60)
            })
        }

        // ---- state ----
        @Volatile var active = false; private set
        private var running = false
        private var type = "Lari"
        private var dist = 0.0
        private var acc = -1f
        private var gotFix = false
        private var est = false
        private var steps = 0L
        private var stride = 72.0
        private var startRt = 0L; private var pausedTotal = 0L; private var pausedAt = 0L
        private var startWall = 0L
        private var lastGood = 0L; private var lastT = 0L; private var distAt = 0L
        private var lastLat = Double.NaN; private var lastLng = 0.0; private var lastStep = -1.0
        private var kmNext = 1000
        private val splits = ArrayList<Int>()
        private var kLat = 0.0; private var kLng = 0.0; private var kVar = -1.0; private var kT = 0L; private var spdS = 0.0
        private var pathVer = 0
        private var curLat = Double.NaN; private var curLng = Double.NaN; private var curProv = ""

        fun elapsedMs(): Long {
            if (startRt == 0L) return 0
            val now = SystemClock.elapsedRealtime()
            return now - startRt - pausedTotal - (if (running) 0 else now - pausedAt)
        }

        private fun publish() {
            Track.snap.value = TrackSnap(active, running, type, dist, steps, elapsedMs(), System.currentTimeMillis(), acc, gotFix, est,
                lastGood, spdS, distAt, ArrayList(splits), pathVer, curLat, curLng, curProv)
        }
    }

    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private val h = Handler(Looper.getMainLooper())
    private var tickN = 0
    private var useFused = false
    private var lastPtTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_START -> begin(intent)
            A_PAUSE -> setRunning(false)
            A_RESUME -> setRunning(true)
            A_STOP -> { end(); stopSelf() }
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin(it: Intent) {
        if (active) { notifyUpdate(); return }
        type = it.getStringExtra("type") ?: "Lari"
        stride = it.getIntExtra("stride", 72).toDouble()
        dist = 0.0; steps = 0; acc = -1f; gotFix = false; est = false
        synchronized(Track.path) { Track.path.clear() }
        splits.clear(); lastLat = Double.NaN; lastStep = -1.0; kmNext = 1000; lastGood = 0; kVar = -1.0; spdS = 0.0; distAt = 0; pathVer = 0
        curLat = Double.NaN; curLng = Double.NaN; curProv = ""
        startRt = SystemClock.elapsedRealtime(); startWall = System.currentTimeMillis(); pausedTotal = 0; pausedAt = 0
        running = true; active = true

        ensureChannels(this)
        try {
            ServiceCompat.startForeground(this, NID, build(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {
            try { startForeground(NID, build()) } catch (_: Exception) { }
        }

        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        // posisi awal dari cache (biar peta langsung ke lokasi kamu)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { m.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 10 * 60_000 && curLat.isNaN()) { curLat = it.latitude; curLng = it.longitude; curProv = "cache" } } } catch (_: Exception) { }
        }
        // fused (GPS + WiFi + seluler) kalau ada → kunci posisi jauh lebih cepat
        val fused = Build.VERSION.SDK_INT >= 31 && m.allProviders.contains(LocationManager.FUSED_PROVIDER)
        if (fused) try { m.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) } catch (_: Exception) { }
        try { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) } catch (_: Exception) { }
        if (!fused) try { m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper()) } catch (_: Exception) { }
        useFused = fused

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { s ->
            try { sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI) } catch (_: Exception) { }
        }
        publish()
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            publish()
            if (++tickN % 2 == 0) notifyUpdate()
            h.postDelayed(this, 1000)
        }
    }

    private fun setRunning(r: Boolean) {
        if (!active || running == r) return
        val now = SystemClock.elapsedRealtime()
        if (r) { pausedTotal += now - pausedAt; lastLat = Double.NaN; lastStep = -1.0; kVar = -1.0 } else pausedAt = now
        running = r
        publish(); notifyUpdate()
    }

    private fun end() {
        active = false; running = false
        h.removeCallbacks(tick)
        try { lm?.removeUpdates(this) } catch (_: Exception) { }
        try { sm?.unregisterListener(this) } catch (_: Exception) { }
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        startRt = 0
        publish()
    }

    override fun onDestroy() { if (active) end(); super.onDestroy() }

    // ------------------ GPS ------------------
    private fun maxSpeed() = when (type) { "Jalan" -> 4.5; "Sepeda" -> 25.0; else -> 9.0 }

    override fun onLocationChanged(l: Location) {
        val prov = l.provider ?: ""
        // posisi & akurasi buat tampilan (semua sumber)
        if (!(prov == LocationManager.NETWORK_PROVIDER && gotFix && acc in 0f..50f)) {
            acc = if (l.hasAccuracy()) l.accuracy else -1f
            curLat = l.latitude; curLng = l.longitude; curProv = prov
        }
        gotFix = true
        if (!running) { lastLat = Double.NaN; kVar = -1.0; publish(); return }
        // jarak: hanya dari sumber akurat; pakai fused kalau ada, GPS murni sebagai cadangan
        if (prov == LocationManager.NETWORK_PROVIDER) { publish(); return }
        val t = System.currentTimeMillis()
        if (useFused && prov == LocationManager.GPS_PROVIDER && t - lastPtTime < 2500) return
        if (l.hasAccuracy() && l.accuracy > 50) { publish(); return }
        lastPtTime = t
        val a = maxOf(if (l.hasAccuracy()) l.accuracy.toDouble() else 10.0, 3.0)
        val q = if (type == "Sepeda") 8.0 else 3.0
        if (kVar < 0) { kLat = l.latitude; kLng = l.longitude; kVar = a * a; kT = t }
        else {
            val dtS = maxOf(0.0, (t - kT) / 1000.0)
            kVar += dtS * q * q
            val k = kVar / (kVar + a * a)
            kLat += k * (l.latitude - kLat); kLng += k * (l.longitude - kLng)
            kVar *= (1 - k); kT = t
        }
        if (!lastLat.isNaN()) {
            val r = FloatArray(1)
            Location.distanceBetween(lastLat, lastLng, kLat, kLng, r)
            val d = r[0].toDouble(); val dt = (t - lastT) / 1000.0
            if (d < 2) return
            if (dt > 0 && d / dt > maxSpeed()) return
            val sp = if (l.hasSpeed()) l.speed.toDouble() else if (dt > 0) d / dt else 0.0
            spdS = spdS * 0.6 + sp * 0.4
            dist += d; distAt = t
            checkSplits()
        }
        lastLat = kLat; lastLng = kLng; lastT = t; lastGood = t
        synchronized(Track.path) { if (Track.path.size < 20000) Track.path.add(doubleArrayOf(kLat, kLng)) }
        pathVer++
        publish()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }

    // ------------------ langkah ------------------
    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values[0].toDouble()
        if (lastStep >= 0 && running) {
            val d = v - lastStep
            if (d > 0 && d < 500) {
                steps += d.toLong()
                if (type != "Sepeda" && System.currentTimeMillis() - lastGood > 15000) {
                    dist += d * stride / 100.0; est = true; distAt = System.currentTimeMillis(); checkSplits()
                }
            }
        }
        lastStep = v
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun checkSplits() {
        while (dist >= kmNext) {
            val tot = (elapsedMs() / 1000).toInt()
            val sec = tot - splits.sum()
            splits.add(sec); kmNext += 1000
            milestone(splits.size, sec, tot)
            Track.splitEvents.tryEmit(splits.size to sec)
        }
    }

    // ------------------ notifikasi ------------------
    private fun appPI(extra: String?, code: Int): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extra != null) i.putExtra("sf_action", extra)
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun svcPI(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun fill(rv: RemoteViews, big: Boolean) {
        val el = elapsedMs(); val sec = el / 1000.0; val bike = type == "Sepeda"
        rv.setTextViewText(R.id.sf_label, (if (running) "● MEREKAM · " else "❚❚ DIJEDA · ") + type.uppercase(Locale.ROOT))
        rv.setTextViewText(R.id.sf_km, Fmt.km(dist))
        rv.setChronometer(R.id.sf_time, SystemClock.elapsedRealtime() - el, null, running)
        rv.setTextViewText(R.id.sf_pace, if (bike) Fmt.speed(dist, sec) + " km/j" else Fmt.pace(if (dist > 30) sec / (dist / 1000.0) else Double.NaN) + " /km")
        if (big) {
            rv.setTextViewText(R.id.sf_steps, if (bike) "${(dist / 1000).toInt()} km" else Fmt.num(steps))
            rv.setTextViewText(R.id.sf_steps_lbl, if (bike) "KM PENUH" else "LANGKAH")
            rv.setProgressBar(R.id.sf_prog, 1000, (dist % 1000).toInt(), false)
            rv.setTextViewText(R.id.sf_next, "Km ${(dist / 1000).toInt() + 1} · sisa ${1000 - (dist % 1000).toInt()} m")
            rv.setTextViewText(R.id.sf_btn_pause, if (running) "Jeda" else "Lanjut")
            rv.setOnClickPendingIntent(R.id.sf_btn_pause, svcPI(if (running) A_PAUSE else A_RESUME, 11))
            rv.setOnClickPendingIntent(R.id.sf_btn_finish, appPI("finish", 12))
        }
    }

    private fun build(): Notification {
        val small = RemoteViews(packageName, R.layout.sf_notif_small).also { fill(it, false) }
        val big = RemoteViews(packageName, R.layout.sf_notif_big).also { fill(it, true) }
        return NotificationCompat.Builder(this, CH_TRACK)
            .setSmallIcon(R.drawable.sf_ic_notif)
            .setContentTitle("Strafunk · ${Fmt.km(dist)} km")
            .setContentText("$type · ${Fmt.dur((elapsedMs() / 1000).toInt())}")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setColor(0xFFC5F82A.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small).setCustomBigContentView(big)
            .setContentIntent(appPI(null, 10))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyUpdate() {
        if (!active) return
        try { getSystemService(NotificationManager::class.java)?.notify(NID, build()) } catch (_: Exception) { }
    }

    private fun milestone(km: Int, sec: Int, tot: Int) {
        val bike = type == "Sepeda"
        val detail = (if (bike) Fmt.speed(1000.0, sec.toDouble()) + " km/j" else Fmt.pace(sec.toDouble()) + " /km") + " · total " + Fmt.dur(tot)
        val n = NotificationCompat.Builder(this, CH_KM)
            .setSmallIcon(R.drawable.sf_ic_notif).setColor(0xFFC5F82A.toInt())
            .setContentTitle("Km $km beres ⚡").setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true).setTimeoutAfter(6000).setContentIntent(appPI(null, 13)).build()
        try { getSystemService(NotificationManager::class.java)?.notify(NID + 100, n) } catch (_: Exception) { }
    }
}
