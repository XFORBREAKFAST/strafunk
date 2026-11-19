package com.dzaky.strafunk.cloud

import android.content.Context
import com.dzaky.strafunk.data.Act
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

data class Prof(val uid: String, val name: String, val username: String, val following: List<String>)

/** Aktivitas orang lain (atau milik sendiri) dari cloud. */
data class RAct(val act: Act, val uid: String, val userName: String, val kudos: List<String>)

/**
 * Semua urusan online: akun, sinkron, feed, ranking, duel.
 * Skema Firestore sama dengan versi web: users / activities / daily.
 */
object Cloud {
    private var ok = false
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val scope = MainScope()

    val user = MutableStateFlow<Prof?>(null)
    val syncing = MutableStateFlow(false)
    val users = HashMap<String, Prof>()
    /** cache aktivitas remote buat layar detail */
    val remote = HashMap<String, RAct>()

    fun init(ctx: Context) {
        try {
            val opts = FirebaseOptions.Builder()
                .setApiKey("AIzaSyC0YLKZ9sI86rwMalyZ4jFB702PrwURd1Q")
                .setApplicationId("1:327951771411:web:ea379636aea657a9531ce6")
                .setProjectId("strafunk-fb0cf")
                .setStorageBucket("strafunk-fb0cf.firebasestorage.app")
                .setGcmSenderId("327951771411")
                .build()
            if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, opts)
            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()
            ok = true
            auth.addAuthStateListener { a ->
                val u = a.currentUser
                if (u == null) user.value = null
                else scope.launch { loadProfile(u.uid, u.displayName); sync(false) }
            }
        } catch (e: Exception) { ok = false }
    }

    val ready get() = ok
    val uid: String? get() = if (ok) auth.currentUser?.uid else null

    private suspend fun loadProfile(uid: String, fallbackName: String?) {
        try {
            val d = db.collection("users").document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            val p = Prof(uid, d.getString("name") ?: fallbackName ?: "Kamu", d.getString("username") ?: "",
                (d.get("following") as? List<String>) ?: emptyList())
            user.value = p; users[uid] = p
            if (Store.name == "Kamu" || Store.name.isBlank()) Store.name = p.name
        } catch (e: Exception) {
            if (user.value == null) user.value = Prof(uid, fallbackName ?: Store.name, "", emptyList())
        }
    }

    fun errMsg(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "Password minimal 6 karakter"
        is FirebaseAuthUserCollisionException -> "Email sudah terdaftar"
        is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_INVALID_EMAIL" -> "Format email salah"
            "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL" -> "Email atau password salah"
            "ERROR_TOO_MANY_REQUESTS" -> "Terlalu banyak percobaan, tunggu sebentar"
            else -> e.localizedMessage ?: "Gagal"
        }
        is FirebaseFirestoreException -> if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "Ditolak server, cek Rules Firestore" else "Koneksi bermasalah"
        else -> e.localizedMessage ?: "Gagal, coba lagi"
    }

    suspend fun register(name: String, username: String, email: String, pass: String) {
        val cr = try { auth.createUserWithEmailAndPassword(email, pass).await() }
        catch (e: FirebaseAuthUserCollisionException) {
            // akun setengah jadi dari percobaan sebelumnya → lanjutkan
            val r = auth.signInWithEmailAndPassword(email, pass).await()
            if (db.collection("users").document(r.user!!.uid).get().await().exists()) { loadProfile(r.user!!.uid, null); return }
            r
        }
        val u = cr.user!!
        val ex = db.collection("users").whereEqualTo("username", username).get().await()
        if (ex.documents.any { it.id != u.uid }) { try { u.delete().await() } catch (_: Exception) { }; throw IllegalStateException("Username sudah dipakai, coba yang lain") }
        u.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
        db.collection("users").document(u.uid).set(mapOf("name" to name, "username" to username, "following" to emptyList<String>(),
            "createdAt" to FieldValue.serverTimestamp())).await()
        Store.name = name
        loadProfile(u.uid, name)
    }

    suspend fun login(email: String, pass: String) {
        val r = auth.signInWithEmailAndPassword(email, pass).await()
        loadProfile(r.user!!.uid, r.user!!.displayName)
    }

    suspend fun resetPassword(email: String) { auth.sendPasswordResetEmail(email).await() }

    fun logout() { if (ok) auth.signOut(); user.value = null }

    // ---------------- sinkron ----------------
    private fun actMap(a: Act, uid: String, name: String) = hashMapOf<String, Any>(
        "id" to a.id, "uid" to uid, "userName" to name, "type" to a.type, "title" to a.title, "start" to a.start,
        "duration" to a.duration, "distance" to a.distance, "steps" to a.steps, "kcal" to a.kcal, "splits" to a.splits,
        "est" to a.est, "week" to Fmt.key(Fmt.monday(a.date)), "month" to a.date.toString().substring(0, 7),
        "path" to a.path.map { mapOf("a" to it[0], "o" to it[1]) }
    )

    suspend fun sync(force: Boolean): Boolean {
        val u = uid ?: return false
        if (syncing.value) return false
        syncing.value = true
        return try {
            val name = user.value?.name ?: Store.name
            for (a in Store.acts.value.filter { !it.synced || force }) {
                db.collection("activities").document(a.id).set(actMap(a, u, name), SetOptions.merge()).await()
                Store.updateAct(a.copy(synced = true))
            }
            val today = LocalDate.now()
            for (i in 0 until 14) {
                val d = today.minusDays(i.toLong()); val k = Fmt.key(d); val s = Store.days.value[k] ?: continue
                db.collection("daily").document("${u}_$k").set(mapOf("uid" to u, "date" to k, "week" to Fmt.key(Fmt.monday(d)), "steps" to s)).await()
            }
            true
        } catch (e: Exception) { false } finally { syncing.value = false }
    }

    fun deleteRemote(id: String) { if (uid != null) db.collection("activities").document(id).delete() }

    // ---------------- teman ----------------
    suspend fun follow(username: String): String? {
        val me = user.value ?: return "Belum login"
        val r = db.collection("users").whereEqualTo("username", username).get().await()
        val doc = r.documents.firstOrNull() ?: return "Username nggak ketemu"
        if (doc.id == me.uid) return "Itu kamu sendiri"
        db.collection("users").document(me.uid).update("following", FieldValue.arrayUnion(doc.id)).await()
        user.value = me.copy(following = (me.following + doc.id).distinct())
        return null
    }

    suspend fun profiles(ids: List<String>): Map<String, Prof> {
        val need = ids.filter { it !in users }
        for (chunk in need.chunked(30)) {
            if (chunk.isEmpty()) continue
            val r = db.collection("users").whereIn(FieldPath.documentId(), chunk).get().await()
            @Suppress("UNCHECKED_CAST")
            r.documents.forEach { d -> users[d.id] = Prof(d.id, d.getString("name") ?: "?", d.getString("username") ?: "", (d.get("following") as? List<String>) ?: emptyList()) }
        }
        return ids.mapNotNull { users[it] }.associateBy { it.uid }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toRAct(m: Map<String, Any?>): RAct? = try {
        val path = (m["path"] as? List<Map<String, Any?>>)?.map { doubleArrayOf((it["a"] as Number).toDouble(), (it["o"] as Number).toDouble()) } ?: emptyList()
        val a = Act(m["id"] as String, m["type"] as? String ?: "Lari", m["title"] as? String ?: "Aktivitas", (m["start"] as Number).toLong(),
            (m["duration"] as? Number)?.toInt() ?: 0, (m["distance"] as? Number)?.toDouble() ?: 0.0, (m["steps"] as? Number)?.toLong() ?: 0,
            (m["kcal"] as? Number)?.toInt() ?: 0, (m["splits"] as? List<Number>)?.map { it.toInt() } ?: emptyList(), m["est"] as? Boolean ?: false, path, true)
        RAct(a, m["uid"] as String, m["userName"] as? String ?: "?", (m["kudos"] as? List<String>) ?: emptyList())
    } catch (e: Exception) { null }

    /**
     * Dengarkan aktivitas (live). Hasil pertama biasanya dari cache HP (instan),
     * lalu diperbarui dari server. cb(list, dariServer, error)
     */
    fun listenActs(ids: List<String>, field: String, keys: List<String>, cb: (List<RAct>, Boolean, String?) -> Unit): List<ListenerRegistration> {
        val parts = HashMap<Int, List<RAct>>(); val server = HashMap<Int, Boolean>()
        val regs = ArrayList<ListenerRegistration>(); var idx = 0
        val chunks = ids.distinct().chunked(30)
        val total = chunks.size * keys.size
        for (k in keys) for (ch in chunks) {
            val i = idx++
            regs += db.collection("activities").whereIn("uid", ch).whereEqualTo(field, k)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                    if (err != null) { cb(parts.values.flatten(), false, errMsg(err)); return@addSnapshotListener }
                    if (snap == null) return@addSnapshotListener
                    parts[i] = snap.documents.mapNotNull { d -> d.data?.let { toRAct(it) } }
                    server[i] = !snap.metadata.isFromCache
                    val all = parts.values.flatten().distinctBy { it.act.id }.sortedByDescending { it.act.start }
                    all.forEach { remote[it.act.id] = it }
                    cb(all, server.size == total && server.values.all { it }, null)
                }
        }
        return regs
    }

    suspend fun kudos(id: String, like: Boolean) {
        val u = uid ?: return
        db.collection("activities").document(id).update("kudos", if (like) FieldValue.arrayUnion(u) else FieldValue.arrayRemove(u)).await()
    }

    /** total langkah minggu ini per uid */
    suspend fun weekSteps(ids: List<String>): Map<String, Long> {
        val wk = Fmt.key(Fmt.monday(LocalDate.now()))
        val r = db.collection("daily").whereIn("uid", ids).whereEqualTo("week", wk).get().await()
        val m = HashMap<String, Long>(); ids.forEach { m[it] = 0L }
        r.documents.forEach { d -> val u = d.getString("uid") ?: return@forEach; m[u] = (m[u] ?: 0L) + (d.getLong("steps") ?: 0L) }
        return m
    }
}
