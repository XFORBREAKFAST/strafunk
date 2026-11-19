package com.dzaky.strafunk.steps

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Langkah harian:
 *  1. Health Connect (data Samsung Health) → akurat per hari kalender, ada riwayat.
 *  2. Cadangan: sensor langkah HP dibaca tiap 15 menit (WorkManager) + saat app dibuka.
 */
object Steps {
    const val HC_PKG = "com.google.android.apps.healthdata"
    val PERMS = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    fun hcStatus(ctx: Context): Int = try { HealthConnectClient.getSdkStatus(ctx, HC_PKG) } catch (e: Exception) { HealthConnectClient.SDK_UNAVAILABLE }
    fun hcAvailable(ctx: Context) = hcStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hcGranted(ctx: Context): Boolean = try {
        hcAvailable(ctx) && HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMS)
    } catch (e: Exception) { false }

    fun sensorGranted(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasSensor(ctx: Context) = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Baca langkah untuk rentang tanggal & simpan. */
    suspend fun refresh(ctx: Context, from: LocalDate, to: LocalDate) {
        if (hcGranted(ctx)) {
            Store.stepSource = "hc"
            try {
                val res = withContext(Dispatchers.IO) {
                    HealthConnectClient.getOrCreate(ctx).aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                            timeRangeSlicer = Period.ofDays(1)
                        )
                    )
                }
                val m = HashMap<String, Long>()
                var d = from
                while (!d.isAfter(to)) { m[Fmt.key(d)] = 0L; d = d.plusDays(1) }
                res.forEach { g -> m[Fmt.key(g.startTime.toLocalDate())] = g.result[StepsRecord.COUNT_TOTAL] ?: 0L }
                // jangan nol-kan hari depan
                val today = LocalDate.now()
                Store.mergeDays(m.filterKeys { !LocalDate.parse(it).isAfter(today) })
            } catch (e: Exception) { }
            cancelWorker(ctx)
        } else if (sensorGranted(ctx) && hasSensor(ctx)) {
            Store.stepSource = "sensor"
            sensorTick(ctx)
            scheduleWorker(ctx)
        } else Store.stepSource = "none"
    }

    /** Baca sensor sekali, tambahkan selisih ke hari ini. */
    suspend fun sensorTick(ctx: Context) {
        if (!sensorGranted(ctx)) return
        val v = readCounter(ctx) ?: return
        val last = Store.lastCounter
        if (last >= 0) {
            val d = if (v >= last) v - last else v
            if (d > 0 && d < 60000) Store.addSteps(Fmt.key(LocalDate.now()), d.toLong())
        }
        Store.lastCounter = v
    }

    private suspend fun readCounter(ctx: Context): Float? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(4000) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) { sm.unregisterListener(this); if (cont.isActive) cont.resume(e.values[0]) }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    fun scheduleWorker(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sf_steps",
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES).build())
    }
    fun cancelWorker(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork("sf_steps") }
}

class StepWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        try { Steps.sensorTick(applicationContext) } catch (_: Exception) { }
        return Result.success()
    }
}
