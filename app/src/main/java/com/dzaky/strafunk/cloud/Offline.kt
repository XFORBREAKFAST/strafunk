package com.dzaky.strafunk.cloud

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import com.dzaky.strafunk.sound.Sfx
import com.dzaky.strafunk.track.Track
import com.dzaky.strafunk.track.TrackingService
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Pelari offline yang ketemu lewat Bluetooth/WiFi Direct. */
data class Found(val endpoint: String, val name: String, val type: String, val vis: String, val id: String)

/**
 * Battle offline jarak dekat (tanpa internet) pakai Nearby Connections.
 *  - Nyari pelari di sekitar: cuma waktu Beranda kebuka.
 *  - Siaran "aku lagi lari": cuma selama merekam.
 *  - Tiap 3 detik saling kirim jarak/langkah/posisi.
 */
object Offline {
    private const val SERVICE = "com.dzaky.strafunk.battle"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val scope = MainScope()
    private var client: ConnectionsClient? = null
    private var appCtx: Context? = null

    val runners = MutableStateFlow<Map<String, Found>>(emptyMap())
    val rivals = MutableStateFlow<List<Rival>>(emptyList())
    val requests = MutableStateFlow<Map<String, String>>(emptyMap())
    var pendingJoin: Found? = null

    private var discovering = false
    private var advertising = false
    private var running = false
    private var loop: Job? = null
    private var myJoinedAt = 0L
    private var myType = "Lari"
    private var joinTarget: String? = null
    private val connected = LinkedHashSet<String>()
    private val outgoing = HashSet<String>()
    private val peers = HashMap<String, JSONObject>()
    private val myBase = HashMap<String, Pair<Double, Long>>()
    private val lead = HashMap<String, Boolean>()

    // ============================ izin ============================
    fun perms(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    fun granted(ctx: Context) = perms().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun btOn(ctx: Context): Boolean = try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true } catch (_: Exception) { false }

    private fun c(ctx: Context): ConnectionsClient { appCtx = ctx.applicationContext; return client ?: Nearby.getConnectionsClient(ctx.applicationContext).also { client = it } }

    private fun myName() = Cloud.user.value?.name ?: Store.name
    private fun myInfo(): ByteArray = listOf(myName().replace("|", " ").take(24), myType, Live.vis.value, Store.deviceId).joinToString("|").toByteArray()

    private fun parseInfo(endpoint: String, b: ByteArray?, fallback: String?): Found? {
        val s = b?.toString(Charsets.UTF_8) ?: fallback ?: return null
        val p = s.split("|")
        if (p.size < 4) return null
        return Found(endpoint, p[0], p[1], p[2], p[3])
    }

    // ============================ cari pelari (Beranda) ============================
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val f = parseInfo(id, info.endpointInfo, info.endpointName) ?: return
            if (f.id == Store.deviceId) return
            runners.value = runners.value + (id to f)
            val t = joinTarget
            if (t != null && f.id == t) { joinTarget = null; connectTo(id) }
        }
        override fun onEndpointLost(id: String) { runners.value = runners.value - id }
    }

    fun startDiscovery(ctx: Context) {
        if (discovering || !Store.offlineBattle || !granted(ctx) || !btOn(ctx)) return
        discovering = true
        c(ctx).startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { discovering = false }
    }

    fun stopDiscovery() {
        if (!discovering || joinTarget != null) return
        discovering = false; runners.value = emptyMap()
        try { client?.stopDiscovery() } catch (_: Exception) { }
    }

    // ============================ koneksi ============================
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            val f = parseInfo(id, info.endpointInfo, info.endpointName)
            if (!info.isIncomingConnection || id in outgoing || Live.vis.value == "public") accept(id)
            else {
                requests.value = requests.value + (id to (f?.name ?: "Pelari"))
                notify("${f?.name ?: "Seseorang"} minta gabung (offline)", "Buka Strafunk buat terima atau tolak"); Sfx.play(Sfx.POP)
            }
        }
        override fun onConnectionResult(id: String, res: ConnectionResolution) {
            outgoing.remove(id)
            if (res.status.statusCode == ConnectionsStatusCodes.STATUS_OK) { connected.add(id); scope.launch { send() } }
        }
        override fun onDisconnected(id: String) { connected.remove(id); recompute() }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val b = p.asBytes() ?: return
            val o = try { JSONObject(String(b, Charsets.UTF_8)) } catch (_: Exception) { return }
            val isNew = id !in peers
            peers[id] = o
            // dia gabung setelah kita → catat titik nol kita buat dia
            val pid = o.optString("id")
            if (o.optLong("joinedAt") > myJoinedAt && pid !in myBase) {
                val s = Track.snap.value
                myBase[pid] = s.dist to s.steps
                if (isNew) { notify("${o.optString("name")} gabung lari kamu ⚡", "Battle offline dimulai"); Sfx.play(Sfx.GO) }
            }
            recompute()
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private fun accept(id: String) { client?.acceptConnection(id, payloads) }
    fun acceptReq(id: String) { requests.value = requests.value - id; accept(id) }
    fun rejectReq(id: String) { requests.value = requests.value - id; try { client?.rejectConnection(id) } catch (_: Exception) { } }

    private fun connectTo(endpoint: String) {
        outgoing.add(endpoint)
        client?.requestConnection(myInfo(), endpoint, lifecycle)?.addOnFailureListener { outgoing.remove(endpoint) }
    }

    // ============================ selama merekam ============================
    fun startRun(ctx: Context, type: String) {
        if (!Store.offlineBattle || !granted(ctx) || !btOn(ctx)) { pendingJoin = null; return }
        running = true; myType = type; myJoinedAt = System.currentTimeMillis()
        myBase.clear(); peers.clear(); lead.clear(); connected.clear(); rivals.value = emptyList(); requests.value = emptyMap()
        val cl = c(ctx)
        advertising = true
        cl.startAdvertising(myInfo(), SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { advertising = false }
        val j = pendingJoin; pendingJoin = null
        if (j != null) {
            val known = runners.value.values.firstOrNull { it.id == j.id }
            if (known != null) connectTo(known.endpoint)
            else { joinTarget = j.id; discovering = false; startDiscovery(ctx) }
        }
        loop?.cancel()
        loop = scope.launch { while (running && TrackingService.active) { send(); delay(3000) } }
    }

    private fun send() {
        if (connected.isEmpty()) return
        val s = Track.snap.value
        val base = JSONObject(); myBase.forEach { (k, v) -> base.put(k, JSONObject().put("d", v.first).put("s", v.second)) }
        val o = JSONObject().put("id", Store.deviceId).put("uid", Cloud.uid ?: "").put("name", myName()).put("type", myType)
            .put("joinedAt", myJoinedAt).put("dist", s.dist).put("steps", s.steps).put("base", base)
        if (!s.lat.isNaN()) o.put("lat", s.lat).put("lng", s.lng)
        try { client?.sendPayload(connected.toList(), Payload.fromBytes(o.toString().toByteArray())) } catch (_: Exception) { }
        recompute()
    }

    private fun recompute() {
        val s = Track.snap.value
        val list = peers.entries.mapIndexed { i, (ep, o) ->
            val pid = o.optString("id")
            val mb = myBase[pid]                                        // titik nol kita buat dia
            val tb = o.optJSONObject("base")?.optJSONObject(Store.deviceId) // titik nol dia buat kita
            val my = s.dist - (mb?.first ?: 0.0); val th = o.optDouble("dist", 0.0) - (tb?.optDouble("d", 0.0) ?: 0.0)
            val mys = s.steps - (mb?.second ?: 0L); val ths = o.optLong("steps") - (tb?.optLong("s", 0L) ?: 0L)
            val lat = o.optDouble("lat", Double.NaN); val lng = o.optDouble("lng", Double.NaN)
            val run = LiveRun(o.optString("uid").ifEmpty { "off:$pid" }, o.optString("name", "Pelari") + (if (ep in connected) "" else " (putus)"),
                o.optString("type", "Lari"), "offline", "", true, 0, System.currentTimeMillis(), o.optLong("joinedAt"),
                o.optDouble("dist", 0.0), o.optLong("steps"), emptyMap(), null, null, emptyList(), emptyMap())
            Rival(run, if (lat.isNaN()) emptyList() else listOf(doubleArrayOf(lat, lng)), Live.COLORS[(i + 2) % Live.COLORS.size],
                maxOf(0.0, my), maxOf(0.0, th), maxOf(0L, mys), maxOf(0L, ths))
        }
        list.forEach { r ->
            val ahead = r.myDist - r.theirDist; val prev = lead[r.run.uid]
            if (ahead > 20 && prev != true) { if (prev == false) { notify("Kamu nyalip ${r.run.name}! 🔥", "Unggul ${Fmt.km(ahead)} km"); Sfx.play(Sfx.SUCCESS) }; lead[r.run.uid] = true }
            else if (ahead < -20 && prev != false) { if (prev == true) { notify("${r.run.name} nyalip kamu!", "Ketinggalan ${Fmt.km(-ahead)} km, gas!"); Sfx.play(Sfx.ERROR) }; lead[r.run.uid] = false }
        }
        rivals.value = list
    }

    fun stopRun(): List<String> {
        val summary = rivals.value.map { r ->
            val d = r.myDist - r.theirDist
            val n = r.run.name.removeSuffix(" (putus)")
            if (d >= 0) "Menang +${Fmt.km(d)} km dari $n (offline)" else "Kalah ${Fmt.km(-d)} km dari $n (offline)"
        }
        running = false; loop?.cancel(); loop = null; joinTarget = null
        try { client?.stopAdvertising(); client?.stopAllEndpoints() } catch (_: Exception) { }
        if (discovering) { discovering = false; try { client?.stopDiscovery() } catch (_: Exception) { } }
        advertising = false; connected.clear(); peers.clear(); rivals.value = emptyList(); requests.value = emptyMap()
        return summary
    }

    val active get() = running

    private fun notify(t: String, x: String) { appCtx?.let { TrackingService.notifyEvent(it, t, x) } }
}
