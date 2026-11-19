package com.dzaky.strafunk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.FileProvider
import com.dzaky.strafunk.data.Act
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import com.dzaky.strafunk.sound.Sfx
import com.dzaky.strafunk.track.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Net.init(this)
        Sfx.init(this)
        TrackingService.ensureChannels(this)
    }
}

/** Status koneksi internet. */
object Net {
    val online = MutableStateFlow(false)
    fun init(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        online.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { online.value = true }
                override fun onLost(network: Network) { online.value = false }
            })
        } catch (_: Exception) { }
    }
}

/** Cek build baru di GitHub Releases. */
object Updater {
    data class Info(val build: Int, val url: String)

    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/XFORBREAKFAST/strafunk/releases?per_page=8").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            var best: Info? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val n = r.optString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                if (assets.length() == 0) continue
                val url = assets.getJSONObject(0).optString("browser_download_url")
                if (best == null || n > best.build) best = Info(n, url)
            }
            best
        } catch (e: Exception) { null }
    }

    fun open(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}

/** Kartu Story 1080×1920 untuk dibagikan. */
object Story {
    fun share(ctx: Context, a: Act) {
        val bmp = render(a)
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "strafunk-story.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, "com.dzaky.strafunk.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Bagikan ke…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun render(a: Act): Bitmap {
        val W = 1080; val H = 1920
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF000000.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(540f, 620f, 720f, 0x3DC5F82A, 0x00C5F82A, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p); p.shader = null
        p.color = 0x10FFFFFF; p.strokeWidth = 2f
        var i = 0f; while (i <= W) { c.drawLine(i, 220f, i, 1180f, p); i += 90f }
        var j = 220f; while (j <= 1180f) { c.drawLine(0f, j, W.toFloat(), j, p); j += 90f }
        val pts = com.dzaky.strafunk.ui.Geo.fit(Fmt.downsample(a.path, 500), 880f, 860f, 40f).map { floatArrayOf(it[0] + 100f, it[1] + 270f) }
        if (pts.size > 1) {
            val path = com.dzaky.strafunk.ui.Geo.smoothAndroidPath(pts)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = 0x80C5F82A.toInt(); p.strokeWidth = 44f; p.maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path, p); p.maskFilter = null
            p.color = 0xFFC5F82A.toInt(); p.strokeWidth = 20f; c.drawPath(path, p)
            p.style = Paint.Style.FILL; p.color = 0xFF000000.toInt(); c.drawCircle(pts[0][0], pts[0][1], 18f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.color = 0xFFC5F82A.toInt(); c.drawCircle(pts[0][0], pts[0][1], 18f, p)
            val e = pts.last(); p.style = Paint.Style.FILL; c.drawCircle(e[0], e[1], 22f, p)
            p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); c.drawCircle(e[0], e[1], 22f, p)
        }
        p.style = Paint.Style.FILL; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fun t(s: String, size: Float, col: Int, x: Float, y: Float): Float { p.textSize = size; p.color = col; c.drawText(s, x, y, p); return p.measureText(s) }
        val w0 = t("Strafunk", 64f, -1, 80f, 150f); t(".", 64f, 0xFFC5F82A.toInt(), 80f + w0, 150f)
        t(a.type.uppercase() + " · " + Fmt.dateStr(a.start).uppercase(), 34f, 0xFFA1A1A6.toInt(), 80f, 1290f)
        t(a.title, 56f, -1, 80f, 1370f)
        val big = Fmt.km(a.distance); val bw = t(big, 260f, -1, 70f, 1620f); t("km", 64f, 0xFF8E8E93.toInt(), 90f + bw, 1620f)
        val bike = a.type == "Sepeda"
        val stats = listOf(
            (if (bike) "KM/JAM" else "PACE") to (if (bike) Fmt.speed(a.distance, a.duration.toDouble()) else Fmt.pace(a.duration / maxOf(a.distance / 1000, .001))),
            "WAKTU" to Fmt.dur(a.duration),
            (if (bike) "KALORI" else "LANGKAH") to (if (bike) Fmt.num(a.kcal) else Fmt.num(a.steps))
        )
        stats.forEachIndexed { k, s -> t(s.first, 30f, 0xFF8E8E93.toInt(), 80f + k * 330, 1730f); t(s.second, 64f, if (k == 0) 0xFFC5F82A.toInt() else -1, 80f + k * 330, 1810f) }
        return b
    }
}
