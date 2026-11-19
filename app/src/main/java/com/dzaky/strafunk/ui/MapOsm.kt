package com.dzaky.strafunk.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

object Osm {
    fun init(ctx: Context) {
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osm", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "Strafunk/2 (com.dzaky.strafunk)"
    }

    /** Filter peta gelap: invert + kurangi saturasi + redupkan. */
    val dark: ColorMatrixColorFilter by lazy {
        val inv = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        val sat = ColorMatrix().apply { setSaturation(.35f) }
        val dim = ColorMatrix().apply { setScale(.82f, .86f, .8f, 1f) }
        inv.postConcat(sat); inv.postConcat(dim)
        ColorMatrixColorFilter(inv)
    }

    fun dot(ctx: Context, sizeDp: Int): BitmapDrawable {
        val d = ctx.resources.displayMetrics.density; val s = (sizeDp * d).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x55C5F82A; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s * .26f, p)
        p.color = 0xFFC5F82A.toInt(); c.drawCircle(s / 2f, s / 2f, s * .19f, p)
        return BitmapDrawable(ctx.resources, b)
    }
}

private class MapHolder(val map: MapView, val glow: Polyline, val line: Polyline, val me: Marker) { var lastVer = -1; var first = true }

/** Peta asli (OpenStreetMap, versi gelap). live = kamera ngikutin titik terakhir. */
@Composable
fun OsmMap(path: List<DoubleArray>, pathVer: Int, live: Boolean, modifier: Modifier = Modifier, here: DoubleArray? = null) {
    val ctx = LocalContext.current
    val h = remember {
        val mv = MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(Osm.dark)
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFF0C0C0E.toInt()
            overlayManager.tilesOverlay.loadingLineColor = 0xFF1A1A1D.toInt()
            setBackgroundColor(0xFF0C0C0E.toInt())
            controller.setZoom(17.0)
            isTilesScaledToDpi = true
        }
        val glow = Polyline(mv).apply { outlinePaint.color = 0x40C5F82A; outlinePaint.strokeWidth = 34f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND }
        val line = Polyline(mv).apply { outlinePaint.color = 0xFFC5F82A.toInt(); outlinePaint.strokeWidth = 13f; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND }
        val me = Marker(mv).apply { icon = Osm.dot(ctx, 40); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null) }
        mv.overlays.add(glow); mv.overlays.add(line); mv.overlays.add(me)
        MapHolder(mv, glow, line, me)
    }
    DisposableEffect(Unit) { h.map.onResume(); onDispose { h.map.onPause(); h.map.onDetach() } }
    AndroidView(factory = { h.map }, modifier = modifier, update = {
        if (path.isEmpty() && here != null) {
            val g = GeoPoint(here[0], here[1]); h.me.position = g
            if (h.first) h.map.controller.setCenter(g) else h.map.controller.animateTo(g)
            h.first = false; h.map.invalidate()
        }
        if (path.isNotEmpty() && (h.lastVer != pathVer || h.first)) {
            h.lastVer = pathVer
            val pts = path.map { GeoPoint(it[0], it[1]) }
            h.glow.setPoints(pts); h.line.setPoints(pts)
            if (pts.isNotEmpty()) {
                h.me.position = pts.last()
                if (live) { if (h.first) h.map.controller.setCenter(pts.last()) else h.map.controller.animateTo(pts.last()) }
                else if (h.first) {
                    if (pts.size > 1) h.map.post { try { h.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.35f), false) } catch (_: Exception) { } }
                    else h.map.controller.setCenter(pts.last())
                }
                h.first = false
            }
            h.map.invalidate()
        }
    })
}
