package com.dzaky.strafunk.steps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.dzaky.strafunk.data.Store
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Deteksi "lagi di kendaraan" pakai Activity Recognition bawaan Google (hemat baterai,
 * jalan walau app ditutup). Strafunk cuma nyatet kapan mulai & selesai di kendaraan,
 * lalu langkah di rentang itu dibuang dari angka harian.
 */
object Vehicle {
    private const val ACTION = "com.dzaky.strafunk.VEHICLE"
    private val KINDS = intArrayOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE)

    private fun pi(ctx: Context): PendingIntent {
        val i = Intent(ctx, VehicleReceiver::class.java).setAction(ACTION)
        val f = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 77, i, f)
    }

    fun register(ctx: Context) {
        if (!Steps.sensorGranted(ctx) || !Store.filterVehicle) return
        val list = KINDS.flatMap { k ->
            listOf(
                ActivityTransition.Builder().setActivityType(k).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(k).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
            )
        }
        try { ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(ActivityTransitionRequest(list), pi(ctx)) } catch (_: SecurityException) { } catch (_: Exception) { }
    }

    fun unregister(ctx: Context) {
        try { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pi(ctx)) } catch (_: Exception) { }
    }

    internal fun handle(intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val res = ActivityTransitionResult.extractResult(intent) ?: return
        val nowWall = System.currentTimeMillis(); val nowRt = SystemClock.elapsedRealtimeNanos()
        res.transitionEvents.sortedBy { it.elapsedRealTimeNanos }.forEach { e ->
            val wall = nowWall - (nowRt - e.elapsedRealTimeNanos) / 1_000_000
            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) Store.vehStart(wall - 90_000) // deteksi telat ±1 menit
            else Store.vehEnd(wall)
        }
    }
}

class VehicleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Store.vehEnd(System.currentTimeMillis()) // tutup rentang yang mungkin kegantung
            Vehicle.register(ctx); return
        }
        Vehicle.handle(intent)
    }
}
