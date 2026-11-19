package com.dzaky.strafunk.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.dzaky.strafunk.Net
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import com.dzaky.strafunk.sound.Sfx
import com.dzaky.strafunk.track.Track
import com.dzaky.strafunk.track.TrackingService
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.roundToInt

/** Status lari orang lain / diri sendiri yang lagi live. */
data class LiveRun(
    val uid: String, val name: String, val type: String, val vis: String, val host: String, val active: Boolean,
    val startedAt: Long, val updatedAt: Long, val joinedAt: Long, val dist: Double, val steps: Long,
    val base: Map<String, Pair<Double, Long>>, val cLat: Double?, val cLng: Double?,
    val members: List<String>, val requests: Map<String, String>
) {
    val fresh get() = active && System.currentTimeMillis() - updatedAt < 150_000
}

/** Satu lawan di battle + skor adil (dihitung sejak yang belakangan gabung). */
data class Rival(val run: LiveRun, val path: List<DoubleArray>, val color: Long,
                 val myDist: Double, val theirDist: Double, val mySteps: Long, val theirSteps: Long)

object GeoHash {
    private const val B = "0123456789bcdefghjkmnpqrstuvwxyz"
    fun enc(lat: Double, lng: Double, p: Int = 5): String {
        var latR = doubleArrayOf(-90.0, 90.0); var lngR = doubleArrayOf(-180.0, 180.0)
        val sb = StringBuilder(); var bit = 0; var ch = 0; var even = true
        while (sb.length < p) {
            val r = if (even) lngR else latR; val v = if (even) lng else lat
            val mid = (r[0] + r[1]) / 2
            if (v >= mid) { ch = ch or (1 shl (4 - bit)); r[0] = mid } else r[1] = mid
            even = !even
            if (bit < 4) bit++ else { sb.append(B[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }
    fun around(lat: Double, lng: Double): List<String> {
        val out = LinkedHashSet<String>()
        for (dy in -1..1) for (dx in -1..1) out.add(enc(lat + dy * 0.044, lng + dx * 0.044))
        return out.toList()
    }
}

/**
 * Live run: lari bareng + battle live.
 *  - Dokumen live/{uid}: status lari (jarak, langkah, siapa host, anggota, permintaan gabung).
 *  - live/{uid}/pos/now: posisi persis + rute terakhir (cuma dibaca sesama peserta).
 */
object Live {
    private val scope = MainScope()
    private val db get() = FirebaseFirestore.getInstance()
    val COLORS = longArrayOf(0xFF5AC8FA, 0xFFFF6EC7, 0xFFFF9F0A, 0xFFBF5AF2, 0xFF64D2FF)

    val vis = MutableStateFlow("private")
    val session = MutableStateFlow<String?>(null)
    val hostRun = MutableStateFlow<LiveRun?>(null)
    val rivals = MutableStateFlow<List<Rival>>(emptyList())
    val requests = MutableStateFlow<Map<String, String>>(emptyMap())
    val nearby = MutableStateFlow<List<LiveRun>>(emptyList())
    val waiting = MutableStateFlow<LiveRun?>(null)
    /** host nerima permintaan gabung kita */
    val accepted = MutableSharedFlow<LiveRun>(extraBufferCapacity = 2)
    var pendingJoin: LiveRun? = null

    private var loop: Job? = null
    private var myJoinedAt = 0L
    private var myBase = HashMap<String, Pair<Double, Long>>()
    private val runs = HashMap<String, LiveRun>()
    private val paths = HashMap<String, List<DoubleArray>>()
    private val memberRegs = HashMap<String, List<ListenerRegistration>>()
    private var hostReg: ListenerRegistration? = null
    private var waitReg: ListenerRegistration? = null
    private val lead = HashMap<String, Boolean>()
    private val knownReq = HashSet<String>()
    private var appCtx: Context? = null

    fun available() = Cloud.uid != null && Net.online.value

    @Suppress("UNCHECKED_CAST")
    private fun parse(id: String, m: Map<String, Any?>): LiveRun = LiveRun(
        uid = id, name = m["name"] as? String ?: "?", type = m["type"] as? String ?: "Lari", vis = m["vis"] as? String ?: "private",
        host = m["host"] as? String ?: id, active = m["active"] as? Boolean ?: false,
        startedAt = (m["startedAt"] as? Number)?.toLong() ?: 0, updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0,
        joinedAt = (m["joinedAt"] as? Number)?.toLong() ?: 0, dist = (m["dist"] as? Number)?.toDouble() ?: 0.0,
        steps = (m["steps"] as? Number)?.toLong() ?: 0,
        base = (m["base"] as? Map<String, Map<String, Any?>>)?.mapValues { ((it.value["d"] as? Number)?.toDouble() ?: 0.0) to ((it.value["s"] as? Number)?.toLong() ?: 0L) } ?: emptyMap(),
        cLat = (m["cLat"] as? Number)?.toDouble(), cLng = (m["cLng"] as? Number)?.toDouble(),
        members = (m["members"] as? List<String>) ?: emptyList(),
        requests = (m["requests"] as? Map<String, String>) ?: emptyMap()
    )

    // ============================ mulai / selesai ============================
    fun startRun(ctx: Context, type: String) {
        val me = Cloud.uid ?: return
        appCtx = ctx.applicationContext
        val join = pendingJoin; pendingJoin = null
        val host = join?.uid ?: me
        myJoinedAt = System.currentTimeMillis(); myBase.clear(); lead.clear(); knownReq.clear(); runs.clear(); paths.clear()
        session.value = host
        val name = Cloud.user.value?.name ?: Store.name
        val doc = hashMapOf<String, Any?>(
            "uid" to me, "name" to name, "type" to type, "vis" to if (host == me) vis.value else "private", "host" to host,
            "active" to true, "startedAt" to myJoinedAt, "joinedAt" to myJoinedAt, "updatedAt" to myJoinedAt,
            "dist" to 0.0, "steps" to 0L, "base" to emptyMap<String, Any>(),
            "members" to if (host == me) listOf(me) else emptyList<String>(), "requests" to emptyMap<String, String>(),
            "cLat" to null, "cLng" to null, "gh5" to null
        )
        scope.launch {
            try {
                db.collection("live").document(me).set(doc).await()
                if (host != me) db.collection("live").document(host).update("members", FieldValue.arrayUnion(me)).await()
            } catch (_: Exception) { }
            listenSession(host)
        }
        loop?.cancel()
        loop = scope.launch { while (TrackingService.active) { push(); delay(10_000) } }
    }

    /** Selesai: matikan berbagi lokasi, kembalikan ringkasan battle. */
    fun stopRun(): List<String> {
        val me = Cloud.uid
        val summary = rivals.value.map { r ->
            val d = r.myDist - r.theirDist
            if (d >= 0) "Menang +${Fmt.km(d)} km dari ${r.run.name}" else "Kalah ${Fmt.km(-d)} km dari ${r.run.name}"
        }
        loop?.cancel(); loop = null
        hostReg?.remove(); hostReg = null
        memberRegs.values.flatten().forEach { it.remove() }; memberRegs.clear()
        if (me != null) scope.launch {
            try {
                db.collection("live").document(me).update(mapOf("active" to false, "updatedAt" to System.currentTimeMillis(),
                    "cLat" to null, "cLng" to null, "gh5" to null)).await()
                db.collection("live").document(me).collection("pos").document("now").delete().await()
            } catch (_: Exception) { }
        }
        session.value = null; hostRun.value = null; rivals.value = emptyList(); requests.value = emptyMap()
        return summary
    }

    fun setVis(v: String) {
        vis.value = v
        val me = Cloud.uid ?: return
        if (session.value == me) scope.launch { push() }
    }

    private suspend fun push() {
        val me = Cloud.uid ?: return
        if (!Net.online.value) return
        val s = Track.snap.value
        val pub = session.value == me && vis.value == "public"
        val hasLoc = !s.lat.isNaN()
        val upd = hashMapOf<String, Any?>(
            "updatedAt" to System.currentTimeMillis(), "dist" to s.dist, "steps" to s.steps, "active" to true,
            "vis" to if (session.value == me) vis.value else "private",
            // lokasi kasar (±300 m) cuma kalau public
            "cLat" to if (pub && hasLoc) (s.lat * 300).roundToInt() / 300.0 else null,
            "cLng" to if (pub && hasLoc) (s.lng * 300).roundToInt() / 300.0 else null,
            "gh5" to if (pub && hasLoc) GeoHash.enc(s.lat, s.lng) else null
        )
        try {
            db.collection("live").document(me).set(upd, SetOptions.merge()).await()
            val tail = Fmt.downsample(Track.pathCopy().takeLast(1500), 150).map { mapOf("a" to it[0], "o" to it[1]) }
            if (hasLoc) db.collection("live").document(me).collection("pos").document("now")
                .set(mapOf("lat" to s.lat, "lng" to s.lng, "path" to tail, "t" to System.currentTimeMillis())).await()
        } catch (_: Exception) { }
    }

    // ============================ sesi battle ============================
    private fun listenSession(host: String) {
        val me = Cloud.uid ?: return
        hostReg?.remove()
        hostReg = db.collection("live").document(host).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            val h = parse(host, d); hostRun.value = h; runs[host] = h
            if (host == me) {
                requests.value = h.requests
                h.requests.forEach { (uid, name) ->
                    if (knownReq.add(uid)) { notify("$name minta gabung lari kamu", "Buka Strafunk buat terima atau tolak"); Sfx.play(Sfx.POP) }
                }
            }
            val want = h.members.filter { it != me }.toSet() + (if (host != me) setOf(host) else emptySet())
            (memberRegs.keys - want).forEach { k -> memberRegs.remove(k)?.forEach { it.remove() }; runs.remove(k); paths.remove(k) }
            (want - memberRegs.keys).forEach { m -> memberRegs[m] = listenMember(m) }
            recompute()
        }
    }

    private fun listenMember(uid: String): List<ListenerRegistration> {
        val me = Cloud.uid
        val a = db.collection("live").document(uid).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            val r = parse(uid, d)
            val isNew = uid !in runs
            runs[uid] = r
            // orang ini gabung setelah kita → catat titik nol kita buat dia
            if (me != null && r.joinedAt > myJoinedAt && uid !in myBase) {
                val s = Track.snap.value
                myBase[uid] = s.dist to s.steps
                scope.launch { try { db.collection("live").document(me).update("base.$uid", mapOf("d" to s.dist, "s" to s.steps)).await() } catch (_: Exception) { } }
                if (isNew) { notify("${r.name} gabung lari kamu ⚡", "Battle dimulai, skor dihitung sejak dia gabung"); Sfx.play(Sfx.GO) }
            }
            recompute()
        }
        val b = db.collection("live").document(uid).collection("pos").document("now").addSnapshotListener { snap, _ ->
            @Suppress("UNCHECKED_CAST")
            val p = (snap?.get("path") as? List<Map<String, Any?>>)?.mapNotNull { m ->
                val la = (m["a"] as? Number)?.toDouble(); val lo = (m["o"] as? Number)?.toDouble()
                if (la != null && lo != null) doubleArrayOf(la, lo) else null
            } ?: return@addSnapshotListener
            paths[uid] = p; recompute()
        }
        return listOf(a, b)
    }

    private fun recompute() {
        val me = Cloud.uid ?: return
        val s = Track.snap.value
        val list = runs.values.filter { it.uid != me }.sortedBy { it.joinedAt }.mapIndexed { i, r ->
            val mb = myBase[r.uid]
            val tb = r.base[me]
            val my = s.dist - (mb?.first ?: 0.0); val th = r.dist - (tb?.first ?: 0.0)
            val mys = s.steps - (mb?.second ?: 0L); val ths = r.steps - (tb?.second ?: 0L)
            Rival(r, paths[r.uid] ?: emptyList(), COLORS[i % COLORS.size], maxOf(0.0, my), maxOf(0.0, th), maxOf(0L, mys), maxOf(0L, ths))
        }
        // notif salip-salipan (selisih minimal 20 m biar nggak spam)
        list.forEach { r ->
            val ahead = r.myDist - r.theirDist
            val prev = lead[r.run.uid]
            if (ahead > 20 && prev != true) { if (prev == false) { notify("Kamu nyalip ${r.run.name}! 🔥", "Unggul ${Fmt.km(ahead)} km"); Sfx.play(Sfx.SUCCESS) }; lead[r.run.uid] = true }
            else if (ahead < -20 && prev != false) { if (prev == true) { notify("${r.run.name} nyalip kamu!", "Ketinggalan ${Fmt.km(-ahead)} km, gas!"); Sfx.play(Sfx.ERROR) }; lead[r.run.uid] = false }
        }
        rivals.value = list
    }

    fun tick() { if (session.value != null) recompute() }

    // ============================ host: terima / tolak ============================
    fun accept(uid: String) {
        val me = Cloud.uid ?: return
        scope.launch { try { db.collection("live").document(me).update(mapOf("members" to FieldValue.arrayUnion(uid), "requests.$uid" to FieldValue.delete())).await() } catch (_: Exception) { } }
    }
    fun reject(uid: String) {
        val me = Cloud.uid ?: return
        scope.launch { try { db.collection("live").document(me).update("requests.$uid", FieldValue.delete()).await() } catch (_: Exception) { } }
    }

    // ============================ gabung ============================
    /** private: kirim permintaan, tunggu host nerima */
    fun requestJoin(r: LiveRun, onResult: (String) -> Unit) {
        val me = Cloud.uid ?: return
        val name = Cloud.user.value?.name ?: Store.name
        scope.launch {
            try {
                db.collection("live").document(r.uid).update("requests.$me", name).await()
                waiting.value = r; onResult("Permintaan dikirim ke ${r.name}, tunggu diterima ya")
                waitReg?.remove()
                waitReg = db.collection("live").document(r.uid).addSnapshotListener { snap, _ ->
                    val d = snap?.data ?: return@addSnapshotListener
                    val h = parse(r.uid, d)
                    if (me in h.members) { waitReg?.remove(); waitReg = null; waiting.value = null; accepted.tryEmit(h); notify("${h.name} nerima kamu 🎉", "Buka Strafunk buat mulai battle") }
                    else if (me !in h.requests || !h.active) { waitReg?.remove(); waitReg = null; waiting.value = null; onResult(if (!h.active) "${h.name} sudah selesai lari" else "${h.name} belum bisa nerima sekarang") }
                }
            } catch (e: Exception) { onResult("Gagal kirim permintaan: " + Cloud.errMsg(e)) }
        }
    }

    fun cancelWait() {
        val me = Cloud.uid ?: return
        val r = waiting.value ?: return
        waitReg?.remove(); waitReg = null; waiting.value = null
        scope.launch { try { db.collection("live").document(r.uid).update("requests.$me", FieldValue.delete()).await() } catch (_: Exception) { } }
    }

    // ============================ cari di sekitar ============================
    fun watchNearby(here: DoubleArray?): List<ListenerRegistration> {
        val me = Cloud.uid ?: return emptyList()
        val regs = ArrayList<ListenerRegistration>()
        val pub = HashMap<String, LiveRun>(); val fr = HashMap<String, LiveRun>()
        fun emit() {
            val all = (pub.filterValues { it.vis == "public" } + fr).values.filter { it.uid != me && it.fresh && it.host == it.uid }
                .filter { r -> r.vis != "public" || here == null || r.cLat == null || dist(here, r) < 2500 }
            nearby.value = all.sortedBy { if (here != null && it.cLat != null) dist(here, it) else 99999.0 }
        }
        if (here != null) regs += db.collection("live").whereIn("gh5", GeoHash.around(here[0], here[1])).addSnapshotListener { s, _ ->
                pub.clear(); s?.documents?.forEach { d -> d.data?.let { pub[d.id] = parse(d.id, it) } }; emit()
            }
        val f = Cloud.user.value?.following?.take(30) ?: emptyList()
        if (f.isNotEmpty()) regs += db.collection("live").whereIn("uid", f).addSnapshotListener { s, _ ->
            fr.clear(); s?.documents?.forEach { d -> d.data?.let { fr[d.id] = parse(d.id, it) } }; emit()
        }
        return regs
    }

    fun dist(here: DoubleArray, r: LiveRun): Double = if (r.cLat == null || r.cLng == null) Double.MAX_VALUE
        else Fmt.hav(here, doubleArrayOf(r.cLat, r.cLng))

    @SuppressLint("MissingPermission")
    suspend fun currentLoc(ctx: Context): DoubleArray? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.allProviders) try { lm.getLastKnownLocation(p)?.let { if (best == null || it.time > best!!.time) best = it } } catch (_: Exception) { }
        best?.let { if (System.currentTimeMillis() - it.time < 5 * 60_000) return doubleArrayOf(it.latitude, it.longitude) }
        if (Build.VERSION.SDK_INT >= 30) {
            val prov = if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
            val l = withTimeoutOrNull(8000) {
                suspendCancellableCoroutine<Location?> { c ->
                    try { lm.getCurrentLocation(prov, null, ctx.mainExecutor) { c.resume(it) } } catch (e: Exception) { c.resume(null) }
                }
            }
            if (l != null) return doubleArrayOf(l.latitude, l.longitude)
        }
        return best?.let { doubleArrayOf(it.latitude, it.longitude) }
    }

    private fun notify(title: String, text: String) { appCtx?.let { TrackingService.notifyEvent(it, title, text) } }
}
