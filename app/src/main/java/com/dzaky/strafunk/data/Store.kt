package com.dzaky.strafunk.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Satu aktivitas yang direkam. */
data class Act(
    val id: String,
    val type: String,
    val title: String,
    val start: Long,
    val duration: Int,
    val distance: Double,
    val steps: Long,
    val kcal: Int,
    val splits: List<Int>,
    val est: Boolean,
    val path: List<DoubleArray>,
    val synced: Boolean = false,
    val battle: List<String> = emptyList()
) {
    val date: LocalDate get() = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("title", title); put("start", start)
        put("duration", duration); put("distance", distance); put("steps", steps); put("kcal", kcal)
        put("splits", JSONArray(splits)); put("est", est); put("synced", synced); put("battle", JSONArray(battle))
        val p = JSONArray()
        path.forEach { p.put(JSONArray().put(it[0]).put(it[1])) }
        put("path", p)
    }

    companion object {
        fun fromJson(o: JSONObject): Act {
            val sp = o.optJSONArray("splits") ?: JSONArray()
            val pa = o.optJSONArray("path") ?: JSONArray()
            return Act(
                id = o.getString("id"), type = o.optString("type", "Lari"), title = o.optString("title", "Aktivitas"),
                start = o.optLong("start"), duration = o.optInt("duration"), distance = o.optDouble("distance", 0.0),
                steps = o.optLong("steps"), kcal = o.optInt("kcal"),
                splits = List(sp.length()) { sp.getInt(it) }, est = o.optBoolean("est"),
                path = List(pa.length()) { val q = pa.getJSONArray(it); doubleArrayOf(q.getDouble(0), q.getDouble(1)) },
                synced = o.optBoolean("synced"),
                battle = o.optJSONArray("battle")?.let { b -> List(b.length()) { b.getString(it) } } ?: emptyList()
            )
        }
    }
}

/** Penyimpanan lokal sederhana: pengaturan di SharedPreferences, aktivitas & langkah di file JSON. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var actsFile: File

    val acts = MutableStateFlow<List<Act>>(emptyList())
    /** langkah per hari, key "yyyy-MM-dd" */
    val days = MutableStateFlow<Map<String, Long>>(emptyMap())
    val settingsVer = MutableStateFlow(0)
    /** langkah yang dibuang karena di kendaraan, per hari */
    val vehDays = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** rentang waktu di kendaraan [mulai, selesai] (selesai = -1 kalau masih berlangsung) */
    private val veh = ArrayList<LongArray>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("strafunk", Context.MODE_PRIVATE)
        actsFile = File(ctx.filesDir, "acts.json")
        acts.value = try {
            if (actsFile.exists()) JSONArray(actsFile.readText()).let { a -> List(a.length()) { Act.fromJson(a.getJSONObject(it)) } } else emptyList()
        } catch (e: Exception) { emptyList() }
        try {
            val a = JSONArray(prefs.getString("veh", "[]")); for (i in 0 until a.length()) { val x = a.getJSONArray(i); veh.add(longArrayOf(x.getLong(0), x.getLong(1))) }
            val o = JSONObject(prefs.getString("vehDays", "{}") ?: "{}"); vehDays.value = o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (_: Exception) { }
        days.value = try {
            val o = JSONObject(prefs.getString("days", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveActs() {
        val a = JSONArray(); acts.value.forEach { a.put(it.toJson()) }
        try { actsFile.writeText(a.toString()) } catch (e: Exception) { }
    }

    /** gabung aktivitas dari cloud (yang belum ada di HP) */
    fun mergeActs(list: List<Act>) {
        val have = acts.value.map { it.id }.toHashSet()
        val add = list.filter { it.id !in have }
        if (add.isEmpty()) return
        acts.value = (acts.value + add).sortedByDescending { it.start }; saveActs()
    }
    fun addAct(a: Act) { acts.value = listOf(a) + acts.value; saveActs() }
    fun updateAct(a: Act) { acts.value = acts.value.map { if (it.id == a.id) a else it }; saveActs() }
    fun deleteAct(id: String) { acts.value = acts.value.filter { it.id != id }; saveActs() }
    fun act(id: String) = acts.value.firstOrNull { it.id == id }

    fun mergeDays(m: Map<String, Long>) {
        if (m.isEmpty()) return
        days.value = days.value + m
        prefs.edit().putString("days", JSONObject(days.value as Map<*, *>).toString()).apply()
    }
    // ---- rentang kendaraan ----
    private fun saveVeh() {
        val cut = System.currentTimeMillis() - 45L * 86_400_000
        veh.removeAll { it[1] in 0 until cut }
        val a = JSONArray(); veh.forEach { a.put(JSONArray().put(it[0]).put(it[1])) }
        prefs.edit().putString("veh", a.toString()).apply()
    }
    @Synchronized fun vehStart(t: Long) { if (veh.lastOrNull()?.get(1) == -1L) return; veh.add(longArrayOf(t, -1L)); saveVeh() }
    @Synchronized fun vehEnd(t: Long) { val l = veh.lastOrNull() ?: return; if (l[1] == -1L) { l[1] = maxOf(t, l[0]); saveVeh() } }
    @Synchronized fun inVehicle() = veh.lastOrNull()?.get(1) == -1L
    @Synchronized fun vehIntervals(): List<LongArray> = veh.map { it.clone() }
    fun mergeVeh(m: Map<String, Long>) {
        if (m.isEmpty()) return
        vehDays.value = vehDays.value + m
        prefs.edit().putString("vehDays", JSONObject(vehDays.value as Map<*, *>).toString()).apply()
    }

    fun addSteps(day: String, n: Long) { mergeDays(mapOf(day to (days.value[day] ?: 0L) + n)) }

    // ---- pengaturan ----
    private fun bump() { settingsVer.value = settingsVer.value + 1 }
    var stride: Int get() = prefs.getInt("stride", 72); set(v) { prefs.edit().putInt("stride", v).apply(); bump() }
    var target: Int get() = prefs.getInt("target", 10000); set(v) { prefs.edit().putInt("target", v).apply(); bump() }
    var weight: Int get() = prefs.getInt("weight", 60); set(v) { prefs.edit().putInt("weight", v).apply(); bump() }
    var sound: Boolean get() = prefs.getBoolean("sound", true); set(v) { prefs.edit().putBoolean("sound", v).apply(); bump() }
    var name: String get() = prefs.getString("name", "Kamu") ?: "Kamu"; set(v) { prefs.edit().putString("name", v).apply(); bump() }
    var stepSource: String get() = prefs.getString("stepSource", "none") ?: "none"; set(v) { prefs.edit().putString("stepSource", v).apply(); bump() }
    var askedBattery: Boolean get() = prefs.getBoolean("askedBatt", false); set(v) { prefs.edit().putBoolean("askedBatt", v).apply() }
    var duelWith: String? get() = prefs.getString("duelWith", null); set(v) { prefs.edit().putString("duelWith", v).apply(); bump() }
    var filterVehicle: Boolean get() = prefs.getBoolean("filterVeh", true); set(v) { prefs.edit().putBoolean("filterVeh", v).apply(); bump() }
    var skipBuild: Int get() = prefs.getInt("skipBuild", 0); set(v) { prefs.edit().putInt("skipBuild", v).apply() }
    // fallback sensor langkah
    var lastCounter: Float get() = prefs.getFloat("lastCounter", -1f); set(v) { prefs.edit().putFloat("lastCounter", v).apply() }
}
