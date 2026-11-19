package com.dzaky.strafunk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.sound.Sfx
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/* ============================ warna ============================ */
object C {
    val Bg = Color(0xFF000000); val Card = Color(0xFF121214); val Card2 = Color(0xFF1C1C1F); val Card3 = Color(0xFF2C2C2E)
    val Line = Color(0x0FFFFFFF); val Txt = Color(0xFFF5F5F7); val Mut = Color(0xFF8E8E93); val Mut2 = Color(0xFFA1A1A6)
    val Lime = Color(0xFFC5F82A); val Ink = Color(0xFF0B0B0C); val Red = Color(0xFFFF6961); val Green = Color(0xFF30D158); val Yellow = Color(0xFFFFCC00)
}

/* ============================ event global ============================ */
object Bus {
    val toast = MutableStateFlow<Pair<String, Long>?>(null)
    val finishReq = MutableStateFlow(false)
    val openRecordReq = MutableStateFlow(false)
    fun toast(msg: String) { toast.value = msg to System.nanoTime() }
}

/* ============================ dasar ============================ */
@Composable
fun T(text: String, size: Int, weight: FontWeight = FontWeight.Normal, color: Color = C.Txt, modifier: Modifier = Modifier,
      spacing: Float = 0f, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp,
        textAlign = align, lineHeight = (size * 1.25f).sp, maxLines = maxLines)
}

/** Efek tekan: mengecil lalu memantul + tik + getar halus. */
fun Modifier.press(enabled: Boolean = true, sound: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    val haptic = LocalHapticFeedback.current
    this.graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(src, null, enabled) {
            if (sound) Sfx.play(Sfx.TAP, .7f)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, radius: Dp = 28.dp, bg: Color = C.Card, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(bg).border(1.dp, C.Line, RoundedCornerShape(radius)), content = content)
}

enum class BK { Lime, Dark, Ghost, Danger, White }

@Composable
fun Btn(text: String, kind: BK = BK.Lime, icon: ImageVector? = null, modifier: Modifier = Modifier, height: Dp = 54.dp, onClick: () -> Unit) {
    val (bg, fg) = when (kind) {
        BK.Lime -> C.Lime to C.Ink; BK.Dark -> C.Card3 to C.Txt; BK.Ghost -> Color.Transparent to C.Lime
        BK.Danger -> Color.Transparent to C.Red; BK.White -> Color.White to Color.Black
    }
    Row(modifier.fillMaxWidth().height(if (kind == BK.Ghost || kind == BK.Danger) 46.dp else height)
        .press(onClick = onClick).clip(RoundedCornerShape(18.dp)).background(bg),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        T(text, 16, if (kind == BK.Ghost || kind == BK.Danger) FontWeight.SemiBold else FontWeight.Bold, fg)
    }
}

@Composable
fun CircleBtn(icon: ImageVector, desc: String, size: Dp = 44.dp, bg: Color = Color(0xE01C1C1F), tint: Color = C.Txt, onClick: () -> Unit) {
    Box(Modifier.size(size).press(onClick = onClick).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun Seg(options: List<String>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card2).padding(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            val bg by animateColorAsState(if (on) Color(0xFF3A3A3E) else Color.Transparent, label = "seg")
            Box(Modifier.weight(1f).height(36.dp).press { onSelect(o) }.clip(RoundedCornerShape(11.dp)).background(bg),
                contentAlignment = Alignment.Center) { T(o, 14, FontWeight.SemiBold, if (on) Color.White else C.Mut) }
        }
    }
}

@Composable
fun Pill(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(34.dp).clip(RoundedCornerShape(50)).background(Color(0xE0161618)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(7.dp))
        T(text, 12, FontWeight.SemiBold, Color(0xFFD1D1D6), maxLines = 1)
    }
}

@Composable
fun Stat3(items: List<Pair<String, String>>, modifier: Modifier = Modifier, big: Int = 21) {
    CardBox(modifier.fillMaxWidth(), radius = 24.dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            items.forEachIndexed { i, (l, v) ->
                if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(Color(0x14FFFFFF)))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(l, 12, color = C.Mut); T(v, big, FontWeight.Bold, spacing = -.5f)
                }
            }
        }
    }
}

@Composable
fun SwitchSf(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (on) 20f else 0f, spring(dampingRatio = .55f, stiffness = 600f), label = "sw")
    val bg by animateColorAsState(if (on) C.Lime else Color(0xFF39393D), label = "swb")
    Box(Modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(50)).background(bg).clickable { Sfx.play(Sfx.TAP, .7f); onChange(!on) }.padding(2.dp)) {
        Box(Modifier.graphicsLayer { translationX = x * density }.size(27.dp).clip(CircleShape).background(Color.White))
    }
}

/* ============================ sheet & toast ============================ */
object Sheet {
    var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    var onDismiss: (() -> Unit)? = null
    fun open(onDismiss: (() -> Unit)? = null, c: @Composable ColumnScope.() -> Unit) { Sfx.play(Sfx.POP, .6f); this.onDismiss = onDismiss; content = c }
    fun close() { val d = onDismiss; onDismiss = null; content = null; d?.invoke() }
    val isOpen get() = content != null
}

@Composable
fun SheetHost() {
    val c = Sheet.content
    var last by remember { mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null) }
    if (c != null) last = c
    AnimatedVisibility(c != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { Sheet.close() })
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(c != null,
            enter = slideInVertically(spring(dampingRatio = .82f, stiffness = 420f)) { it },
            exit = slideOutVertically(tween(260)) { it }) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(34.dp)).background(Color(0xFF1A1A1D))
                .clickable(remember { MutableInteractionSource() }, null) { }
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF3A3A3E)))
                Spacer(Modifier.height(4.dp))
                last?.invoke(this)
            }
        }
    }
}

@Composable
fun SheetTitle(t: String, sub: String? = null) {
    T(t, 20, FontWeight.Bold, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (sub != null) T(sub, 14, color = C.Mut2, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
}

@Composable
fun ToastHost() {
    val t by Bus.toast.collectAsState()
    var show by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(t) { val v = t ?: return@LaunchedEffect; msg = v.first; show = true; delay(2200); show = false }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(show, enter = fadeIn() + scaleIn(initialScale = .9f) + slideInVertically { it / 2 }, exit = fadeOut() + scaleOut(targetScale = .95f)) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(C.Card3).padding(horizontal = 18.dp, vertical = 12.dp)) {
                T(msg, 14, FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}


/* ============================ geometri rute ============================ */
object Geo {
    /** lat/lng → meter (x timur, y selatan) relatif ke titik pertama. */
    fun meters(path: List<DoubleArray>, origin: DoubleArray? = null): List<FloatArray> {
        if (path.isEmpty()) return emptyList()
        val o = origin ?: path[0]; val c = cos(Math.toRadians(o[0]))
        return path.map { floatArrayOf(((it[1] - o[1]) * c * 111320).toFloat(), (-(it[0] - o[0]) * 110540).toFloat()) }
    }

    /** Muatkan rute ke kotak W×H. */
    fun fit(path: List<DoubleArray>, w: Float, h: Float, pad: Float): List<FloatArray> {
        val m = meters(path); if (m.isEmpty()) return m
        var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE
        m.forEach { mnx = min(mnx, it[0]); mxx = maxOf(mxx, it[0]); mny = min(mny, it[1]); mxy = maxOf(mxy, it[1]) }
        val bw = maxOf(mxx - mnx, 30f); val bh = maxOf(mxy - mny, 30f)
        val sc = min((w - 2 * pad) / bw, (h - 2 * pad) / bh)
        val ox = (w - (mxx - mnx) * sc) / 2; val oy = (h - (mxy - mny) * sc) / 2
        return m.map { floatArrayOf(ox + (it[0] - mnx) * sc, oy + (it[1] - mny) * sc) }
    }

    fun thin(p: List<FloatArray>, minPx: Float): List<FloatArray> {
        if (p.size < 3) return p
        val o = ArrayList<FloatArray>(); o.add(p[0])
        for (i in 1 until p.size - 1) { val l = o.last(); if (hypot(p[i][0] - l[0], p[i][1] - l[1]) >= minPx) o.add(p[i]) }
        o.add(p.last()); return o
    }

    /** Kurva halus Catmull-Rom → Bezier. */
    fun smooth(pts0: List<FloatArray>, minPx: Float = 3f): Path {
        val pts = thin(pts0, minPx); val path = Path()
        if (pts.isEmpty()) return path
        path.moveTo(pts[0][0], pts[0][1])
        if (pts.size < 3) { pts.drop(1).forEach { path.lineTo(it[0], it[1]) }; return path }
        for (i in 0 until pts.size - 1) {
            val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(i + 2, pts.size - 1)]
            path.cubicTo(p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6, p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6, p2[0], p2[1])
        }
        return path
    }
    fun smoothAndroidPath(pts: List<FloatArray>): android.graphics.Path = smooth(pts).asAndroidPath()

    /** Arah hadap (derajat, 0 = atas/utara) dari titik-titik meter. */
    fun heading(m: List<FloatArray>): Float? {
        for (i in m.indices.reversed()) {
            var j = i - 1
            while (j >= 0 && j >= i - 8) {
                val dx = m[i][0] - m[j][0]; val dy = m[i][1] - m[j][1]
                if (hypot(dx, dy) > 4f) return Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                j--
            }
        }
        return null
    }
}

/* ============================ peta 3D ============================ */
private val EaseCam = CubicBezierEasing(.3f, 0f, .2f, 1f)
private val EaseDraw = CubicBezierEasing(.45f, 0f, .2f, 1f)

/**
 * Peta 3D. live=true → kamera ngikutin titik terakhir (rekaman berjalan).
 * live=false → mode replay: kamera berputar + garis tergambar dari start ke finish.
 */
@Composable
fun Route3D(path: List<DoubleArray>, pathVer: Int, live: Boolean, bike: Boolean, replayKey: Int, modifier: Modifier = Modifier,
            others: List<Pair<List<DoubleArray>, Color>> = emptyList()) {
    BoxWithConstraints(modifier.clipToBounds().background(Brush.radialGradient(listOf(Color(0xFF10140A), Color.Black)))) {
        val density = LocalDensity.current
        val planeDp = maxWidth * 1.45f
        val planePx = with(density) { planeDp.toPx() }
        val inf = rememberInfiniteTransition(label = "r3d")
        val orbit by inf.animateFloat(-34f, -14f, infiniteRepeatable(tween(14000), RepeatMode.Reverse), label = "orb")
        val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing)), label = "pul")
        val cam = remember(replayKey) { Animatable(if (live) 1f else 0f) }
        val draw = remember(replayKey) { Animatable(if (live) 1f else 0f) }
        LaunchedEffect(replayKey, live) {
            if (!live) { cam.snapTo(0f); draw.snapTo(0f); coroutineScope {
                launch { cam.animateTo(1f, tween(3200, easing = EaseCam)) }
                launch { delay(200); draw.animateTo(1f, tween(3000, easing = EaseDraw)) }
            } }
        }
        // ---- live: skala tetap, kamera ikut ----
        val scale = planePx / (if (bike) 1500f else 450f)
        val origin = path.firstOrNull() ?: others.firstOrNull { it.first.isNotEmpty() }?.first?.firstOrNull()
        val meters = remember(pathVer, path.size, origin) { Geo.meters(Fmt.downsample(path, 900), origin) }
        val otherPaths = others.map { (p, c) -> val m = Geo.meters(p, origin); Triple(Geo.smooth(m.map { floatArrayOf(it[0] * scale, it[1] * scale) }), c, m.lastOrNull()?.let { Offset(it[0] * scale, it[1] * scale) }) }
        val livePath = remember(pathVer, path.size, scale) { Geo.smooth(meters.map { floatArrayOf(it[0] * scale, it[1] * scale) }) }
        val cur = meters.lastOrNull()?.let { Offset(it[0] * scale, it[1] * scale) } ?: Offset.Zero
        val tr = remember { Animatable(Offset(planePx / 2, planePx / 2) - cur, Offset.VectorConverter) }
        LaunchedEffect(pathVer, planePx) { tr.animateTo(Offset(planePx / 2, planePx / 2) - cur, tween(1000, easing = LinearEasing)) }
        val head = remember(pathVer) { Geo.heading(meters) }
        // ---- replay: dimuatkan ----
        val fitPath = remember(path.size, planePx, live) { if (live) Path() else Geo.smooth(Geo.fit(Fmt.downsample(path, 500), planePx, planePx, planePx * .16f)) }
        val fitPts = remember(path.size, planePx, live) { if (live) emptyList() else Geo.fit(Fmt.downsample(path, 500), planePx, planePx, planePx * .16f) }
        val measure = remember(fitPath) { android.graphics.PathMeasure(fitPath.asAndroidPath(), false) }
        val seg = remember { android.graphics.Path() }
        val pos = remember { FloatArray(2) }

        Canvas(Modifier.size(planeDp).align(Alignment.Center).graphicsLayer {
            translationY = size.height * .04f
            cameraDistance = 14f * density.density
            if (live) { rotationX = 58f; rotationZ = orbit }
            else { val k = cam.value; rotationX = 64f - 9f * k; rotationZ = -46f + 26f * k; scaleX = 1.18f - .18f * k; scaleY = scaleX }
        }) {
            val w = size.width
            // lantai + grid
            drawRect(Brush.radialGradient(0f to Color(0xFF0C0C0E), .72f to Color(0xFF0C0C0E), 1f to Color.Transparent, center = center, radius = w * .55f))
            val sp = w / 8f
            val ox = if (live) ((tr.value.x % sp) + sp) % sp else 0f
            val oy = if (live) ((tr.value.y % sp) + sp) % sp else 0f
            var x = ox; while (x < w) { drawLine(Color(0x14FFFFFF), Offset(x, 0f), Offset(x, w), 2f); x += sp }
            var y = oy; while (y < w) { drawLine(Color(0x14FFFFFF), Offset(0f, y), Offset(w, y), 2f); y += sp }
            val lime = C.Lime
            if (live) {
                translate(tr.value.x, tr.value.y) {
                    otherPaths.forEach { (op, oc, last) ->
                        drawPath(op, oc.copy(alpha = .22f), style = Stroke(20f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(op, oc, style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (last != null) { drawCircle(Color.White, 12f, last); drawCircle(oc, 8.5f, last) }
                    }
                    if (meters.size > 1) {
                        drawPath(livePath, lime.copy(alpha = .14f), style = Stroke(34f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(livePath, lime.copy(alpha = .22f), style = Stroke(22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(livePath, lime, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    if (meters.isNotEmpty()) {
                        drawCircle(Color.Black, 10f, Offset.Zero); drawCircle(lime, 10f, Offset.Zero, style = Stroke(4f))
                        if (head != null) rotate(head, cur) {
                            val wedge = Path().apply { moveTo(cur.x, cur.y); lineTo(cur.x - 34f, cur.y - 84f); quadraticTo(cur.x, cur.y - 100f, cur.x + 34f, cur.y - 84f); close() }
                            drawPath(wedge, Brush.radialGradient(listOf(lime.copy(alpha = .5f), Color.Transparent), center = cur, radius = 100f))
                        }
                    }
                }
                val c = center
                drawCircle(lime.copy(alpha = .45f * (1 - pulse)), 22f * (1 + 1.8f * pulse), c)
                drawCircle(Color.White, 15f, c); drawCircle(lime, 11f, c)
            } else if (fitPts.size > 1) {
                val len = measure.length
                seg.reset(); measure.getSegment(0f, len * draw.value, seg, true)
                val sp2 = seg.asComposePath()
                drawPath(sp2, lime.copy(alpha = .14f), style = Stroke(34f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(sp2, lime.copy(alpha = .22f), style = Stroke(22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(sp2, lime, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val s = fitPts.first()
                drawCircle(Color.Black, 10f, Offset(s[0], s[1])); drawCircle(lime, 10f, Offset(s[0], s[1]), style = Stroke(4f))
                if (draw.value < 1f) {
                    measure.getPosTan(len * draw.value, pos, null)
                    drawCircle(Color.White, 8f, Offset(pos[0], pos[1]))
                } else {
                    val e = fitPts.last()
                    drawCircle(Color.White, 20f, Offset(e[0], e[1])); drawCircle(lime, 15f, Offset(e[0], e[1]))
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
    }
}

/** Rute kecil 2D (riwayat). */
@Composable
fun MiniRoute(path: List<DoubleArray>, modifier: Modifier = Modifier) {
    val pts = remember(path) { path }
    Canvas(modifier) {
        val p = Geo.fit(Fmt.downsample(pts, 200), size.width, size.height, 10f)
        if (p.size > 1) drawPath(Geo.smooth(p, 2f), C.Lime, style = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun Glow(modifier: Modifier) {
    Box(modifier.background(Brush.radialGradient(listOf(Color(0x29C5F82A), Color.Transparent))))
}

@Composable
fun ScreenCol(modifier: Modifier = Modifier, gap: Dp = 16.dp, bottom: Dp = 120.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(gap), content = content)
}
