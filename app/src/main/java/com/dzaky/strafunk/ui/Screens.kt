package com.dzaky.strafunk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dzaky.strafunk.BuildConfig
import com.dzaky.strafunk.Net
import com.dzaky.strafunk.Story
import com.dzaky.strafunk.Updater
import com.dzaky.strafunk.cloud.Cloud
import com.dzaky.strafunk.cloud.Live
import com.dzaky.strafunk.cloud.LiveRun
import com.dzaky.strafunk.data.Act
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import com.dzaky.strafunk.sound.Sfx
import com.dzaky.strafunk.steps.Steps
import com.dzaky.strafunk.track.Track
import com.dzaky.strafunk.track.TrackingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.roundToInt

/* ============================ navigasi ============================ */
sealed class Scr(val tab: Boolean) {
    data object Home : Scr(true)
    data object Feed : Scr(true)
    data object Ranking : Scr(true)
    data object Profile : Scr(true)
    data class Record(val type: String, val fresh: Boolean) : Scr(false)
    data class Detail(val id: String, val fresh: Boolean = false) : Scr(false)
    data object History : Scr(false)
    data object Auth : Scr(false)
    data class Remote(val id: String) : Scr(false)
}

object Nav {
    val stack = mutableStateListOf<Scr>(Scr.Home)
    var dir by mutableIntStateOf(0)
    val cur: Scr get() = stack.last()
    fun push(s: Scr) { dir = 1; stack.add(s) }
    fun pop() { if (stack.size > 1) { dir = -1; stack.removeAt(stack.lastIndex) } }
    fun tab(s: Scr) { dir = 0; stack.clear(); stack.add(s) }
    fun replace(s: Scr) { dir = 0; stack[stack.lastIndex] = s }
}

object Ui {
    var mode by mutableStateOf("Lari")
    var locked by mutableStateOf(false)
    val scope = MainScope()
}

/* ============================ root ============================ */
@Composable
fun StrafunkApp(act: ComponentActivity) {
    val ctx = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }

    BackHandler {
        when {
            Ui.locked -> {}
            Sheet.isOpen -> Sheet.close()
            Nav.cur is Scr.Record && TrackingService.active -> exitSheet(ctx)
            Nav.stack.size > 1 -> Nav.pop()
            Nav.cur != Scr.Home -> Nav.tab(Scr.Home)
            System.currentTimeMillis() - lastBack < 2000 -> act.finish()
            else -> { lastBack = System.currentTimeMillis(); Bus.toast("Tekan sekali lagi untuk keluar") }
        }
    }

    LaunchedEffect(Unit) {
        Track.splitEvents.collect { (km, sec) ->
            Sfx.play(Sfx.KM)
            val bike = Track.snap.value.type == "Sepeda"
            Bus.toast("Kilometer $km · " + if (bike) Fmt.speed(1000.0, sec.toDouble()) + " km/j" else Fmt.pace(sec.toDouble()) + " /km")
        }
    }
    val fin by Bus.finishReq.collectAsState()
    LaunchedEffect(fin) {
        if (fin) {
            Bus.finishReq.value = false
            if (TrackingService.active) {
                if (Nav.cur !is Scr.Record) Nav.push(Scr.Record(Track.snap.value.type, false))
                delay(400); exitSheet(ctx)
            }
        }
    }
    LaunchedEffect(Unit) { delay(2500); checkUpdate(ctx, false) }
    val openRec by Bus.openRecordReq.collectAsState()
    LaunchedEffect(openRec) { if (openRec) { Bus.openRecordReq.value = false; if (TrackingService.active && Nav.cur !is Scr.Record) Nav.push(Scr.Record(Track.snap.value.type, false)) } }
    LaunchedEffect(Unit) {
        Live.accepted.collect { h ->
            Sheet.open {
                SheetTitle("${h.name} nerima kamu 🎉", "Mulai ${h.type.lowercase()} sekarang? Battle dihitung sejak kamu mulai.")
                Btn("Mulai sekarang", icon = Icons.Rounded.PlayArrow) { Sheet.close(); Live.pendingJoin = h; Ui.mode = h.type; Nav.push(Scr.Record(h.type, true)) }
                Btn("Nanti", BK.Ghost) { Sheet.close() }
            }
        }
    }
    val online by Net.online.collectAsState()
    LaunchedEffect(online) { if (online) Cloud.sync(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) Ui.scope.launch { refreshWeek(ctx, Fmt.monday(LocalDate.now())); Cloud.sync(false) } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(targetState = Nav.cur, label = "nav", transitionSpec = {
            when (Nav.dir) {
                1 -> slideInHorizontally(tween(420, easing = EaseOutQuint)) { it } togetherWith
                    (slideOutHorizontally(tween(420, easing = EaseOutQuint)) { -it / 4 } + fadeOut(tween(420), targetAlpha = .4f))
                -1 -> ((slideInHorizontally(tween(420, easing = EaseOutQuint)) { -it / 4 } + fadeIn(tween(420), initialAlpha = .4f)) togetherWith
                    slideOutHorizontally(tween(420, easing = EaseOutQuint)) { it }).apply { targetContentZIndex = -1f }
                else -> (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 40 }) togetherWith fadeOut(tween(160))
            }
        }) { s ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                when (s) {
                    Scr.Home -> HomeScreen()
                    Scr.Feed -> FeedScreen()
                    Scr.Ranking -> RankingScreen()
                    Scr.Auth -> AuthScreen()
                    is Scr.Remote -> DetailScreen(Scr.Detail(s.id), remote = true)
                    Scr.Profile -> ProfileScreen()
                    Scr.History -> HistoryScreen()
                    is Scr.Record -> RecordScreen(s)
                    is Scr.Detail -> DetailScreen(s)
                }
            }
        }
        AnimatedVisibility(Nav.cur.tab, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(380, easing = EaseOutQuint)) { it }, exit = slideOutVertically(tween(300)) { it }) { TabBar() }
        SheetHost()
        ToastHost()
        if (Ui.locked) LockOverlay()
    }
}

@Composable
private fun TabBar() {
    val snap by Track.snap.collectAsState()
    Column(Modifier.fillMaxWidth().background(Color(0xF20E0E10))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(70.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            TabItem(Icons.Rounded.Home, "Beranda", Scr.Home)
            TabItem(Icons.Rounded.Groups, "Feed", Scr.Feed)
            val inf = rememberInfiniteTransition(label = "rec")
            val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "p")
            Box(contentAlignment = Alignment.Center) {
                if (snap.active) Box(Modifier.size(52.dp).graphicsLayer { scaleX = 1f + .35f * pulse; scaleY = scaleX; alpha = 1f - pulse }.clip(CircleShape).background(C.Lime))
                Box(Modifier.size(52.dp).press { openRecord() }.clip(CircleShape).background(C.Lime), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, "Rekam aktivitas", tint = C.Ink, modifier = Modifier.size(28.dp))
                }
            }
            TabItem(Icons.Rounded.EmojiEvents, "Ranking", Scr.Ranking)
            TabItem(Icons.Rounded.Person, "Profil", Scr.Profile)
        }
    }
}

@Composable
private fun TabItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, s: Scr) {
    val on = Nav.cur == s
    val col by animateColorAsState(if (on) C.Lime else C.Mut, label = "tab")
    val sc by animateFloatAsState(if (on) 1.1f else 1f, spring(dampingRatio = .45f), label = "tabs")
    Column(Modifier.width(64.dp).press { if (Nav.cur != s) Nav.tab(s) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = col, modifier = Modifier.size(25.dp).graphicsLayer { scaleX = sc; scaleY = sc })
        T(label, 11, FontWeight.SemiBold, col)
    }
}

fun openRecord() {
    if (TrackingService.active) Nav.push(Scr.Record(Track.snap.value.type, false)) else Nav.push(Scr.Record(Ui.mode, true))
}

/* ============================ statistik ============================ */
fun dayActive(k: String, days: Map<String, Long>, acts: List<Act>) = (days[k] ?: 0L) >= 3000 || acts.any { Fmt.key(it.date) == k }

fun streakNow(days: Map<String, Long>, acts: List<Act>): Int {
    var d = LocalDate.now(); if (!dayActive(Fmt.key(d), days, acts)) d = d.minusDays(1)
    var n = 0; while (dayActive(Fmt.key(d), days, acts) && n < 3650) { n++; d = d.minusDays(1) }
    return n
}

fun bestStreak(days: Map<String, Long>, acts: List<Act>): Int {
    val keys = (days.keys + acts.map { Fmt.key(it.date) }).sorted()
    if (keys.isEmpty()) return 0
    var d = LocalDate.parse(keys.first()); val end = LocalDate.now(); var best = 0; var cur = 0
    while (!d.isAfter(end)) { if (dayActive(Fmt.key(d), days, acts)) { cur++; best = maxOf(best, cur) } else cur = 0; d = d.plusDays(1) }
    return best
}

suspend fun refreshWeek(ctx: Context, mon: LocalDate) {
    val today = LocalDate.now()
    val end = mon.plusDays(6).let { if (it.isAfter(today)) today else it }
    try { Steps.refresh(ctx, mon, end) } catch (_: Exception) { }
}

fun checkUpdate(ctx: Context, manual: Boolean) {
    Ui.scope.launch {
        val info = Updater.latest()
        if (info == null) { if (manual) Bus.toast("Gagal cek update"); return@launch }
        val mine = BuildConfig.VERSION_CODE
        if (info.build > mine && (manual || Store.skipBuild != info.build)) {
            Sheet.open {
                SheetTitle("Update tersedia ✨", "Build ${info.build} sudah keluar (kamu di build $mine). Download, buka file-nya, lalu tekan Update. Data kamu tetap aman.")
                Btn("Download update") { Sheet.close(); Updater.open(ctx, info.url) }
                Btn("Nanti aja", BK.Ghost) { Store.skipBuild = info.build; Sheet.close() }
            }
        } else if (manual) Bus.toast("Sudah versi terbaru (build $mine)")
    }
}

/* ============================ BERANDA ============================ */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val days by Store.days.collectAsState()
    val vehDays by Store.vehDays.collectAsState()
    val acts by Store.acts.collectAsState()
    val snap by Track.snap.collectAsState()
    val online by Net.online.collectAsState()
    Store.settingsVer.collectAsState().value
    val today = remember { LocalDate.now() }
    val mon0 = remember { Fmt.monday(today) }
    var sel by rememberSaveable { mutableStateOf(today.toString()) }
    val selD = LocalDate.parse(sel)
    val pager = rememberPagerState(pageCount = { 104 })
    var hcGranted by remember { mutableStateOf(Store.stepSource == "hc") }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        Ui.scope.launch { refreshWeek(ctx, Fmt.monday(selD)); hcGranted = Steps.hcGranted(ctx) }
    }
    val actLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        com.dzaky.strafunk.steps.Vehicle.register(ctx)
        Ui.scope.launch { refreshWeek(ctx, mon0) }
    }
    LaunchedEffect(pager.currentPage) { refreshWeek(ctx, mon0.minusWeeks(pager.currentPage.toLong())); hcGranted = Steps.hcGranted(ctx) }
    LaunchedEffect(sel) {
        val pg = ChronoUnit.WEEKS.between(Fmt.monday(selD), mon0).toInt()
        if (pg != pager.currentPage && pg in 0 until 104) pager.animateScrollToPage(pg)
    }

    Box(Modifier.fillMaxSize()) {
        Glow(Modifier.align(Alignment.TopCenter).offset(y = (-160).dp).size(460.dp, 360.dp))
        ScreenCol(gap = 18.dp) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).press { Nav.tab(Scr.Profile) }.border(1.5.dp, C.Lime, CircleShape).padding(3.dp).clip(CircleShape).background(C.Card2),
                    contentAlignment = Alignment.Center) { T(Fmt.initials(Store.name), 14, FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T("Selamat " + Fmt.partOfDay(java.time.LocalTime.now().hour), 13, color = C.Mut)
                    T(Store.name, 22, FontWeight.Bold, spacing = -.5f)
                }
                Pill(if (online) "Online" else "Offline", if (online) C.Green else C.Yellow)
            }
            if (snap.active) {
                Row(Modifier.fillMaxWidth().press { openRecord() }.clip(RoundedCornerShape(22.dp)).background(C.Lime.copy(alpha = .12f)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.Lime)); Spacer(Modifier.width(10.dp))
                    T("Lagi merekam ${snap.type.lowercase()} · ${Fmt.km(snap.dist)} km", 15, FontWeight.Bold, C.Lime, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = C.Lime)
                }
            }
            // strip minggu (bisa di-slide)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val wm = mon0.minusWeeks(pager.currentPage.toLong())
                T(if (pager.currentPage == 0) "Minggu ini" else "${wm.dayOfMonth} ${Fmt.MONTHS[wm.monthValue - 1]} – ${wm.plusDays(6).dayOfMonth} ${Fmt.MONTHS[wm.plusDays(6).monthValue - 1]}",
                    13, FontWeight.SemiBold, C.Mut, Modifier.weight(1f))
                AnimatedVisibility(selD != today, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Box(Modifier.press { sel = today.toString() }.clip(RoundedCornerShape(50)).background(C.Lime.copy(alpha = .14f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        T("Hari ini", 12, FontWeight.Bold, C.Lime)
                    }
                }
            }
            HorizontalPager(pager, reverseLayout = true, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { page ->
                val mon = mon0.minusWeeks(page.toLong())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0..6) {
                        val d = mon.plusDays(i.toLong()); val k = Fmt.key(d)
                        val future = d.isAfter(today); val on = k == sel
                        val bc by animateColorAsState(if (on) C.Lime else Color.Transparent, label = "dc")
                        Column(Modifier.weight(1f).height(64.dp).alpha(if (future) .35f else 1f)
                            .press(enabled = !future) { sel = k }
                            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF0F0F11)).border(1.5.dp, bc, RoundedCornerShape(18.dp)),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(if (dayActive(k, days, acts)) C.Lime else C.Card3))
                            Spacer(Modifier.height(3.dp))
                            T(Fmt.DAYS[d.dayOfWeek.value - 1], 11, color = C.Mut)
                            T("${d.dayOfMonth}", 15, FontWeight.Bold)
                        }
                    }
                }
            }
            // kartu langkah (geser kiri/kanan = ganti hari)
            val steps = days[sel] ?: 0L
            val prev = days[Fmt.key(selD.minusDays(1))] ?: 0L
            val tgt = Store.target
            val prog by animateFloatAsState(min(1f, steps.toFloat() / tgt), tween(900, easing = FastOutSlowInEasing), label = "ring")
            val shown by animateIntAsState(steps.toInt(), tween(700), label = "steps")
            var drag by remember { mutableFloatStateOf(0f) }
            CardBox(Modifier.fillMaxWidth().pointerInput(sel) {
                detectHorizontalDragGestures(onDragEnd = {
                    if (drag > 90f) sel = selD.minusDays(1).toString()
                    else if (drag < -90f && selD.isBefore(today)) sel = selD.plusDays(1).toString()
                    drag = 0f
                }) { _, dx -> drag += dx }
            }, radius = 30.dp) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val sw = 12.dp.toPx(); val r = Size(size.width - sw, size.height - sw); val tl = Offset(sw / 2, sw / 2)
                            drawArc(Color(0xFF232326), 0f, 360f, false, tl, r, style = Stroke(sw))
                            drawArc(C.Lime, -90f, 360f * prog, false, tl, r, style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            T("${(prog * 100).roundToInt()}%", 26, FontWeight.ExtraBold, spacing = -1f)
                            T("target", 11, color = C.Mut)
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column {
                        T(if (selD == today) "Langkah hari ini" else "Langkah " + Fmt.dayLabel(selD), 13, color = C.Mut)
                        T(Fmt.num(shown), 38, FontWeight.ExtraBold, spacing = -1.5f)
                        T("dari ${Fmt.num(tgt)}", 13, color = C.Mut)
                        val vs = vehDays[sel] ?: 0L
                        AnimatedVisibility(vs > 0 && Store.filterVehicle) {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.TwoWheeler, null, tint = C.Mut, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                T("−${Fmt.num(vs)} di kendaraan", 12, color = C.Mut)
                            }
                        }
                        if (prev > 0) {
                            val diff = ((steps - prev) * 100 / prev).toInt()
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.clip(RoundedCornerShape(50)).background(C.Lime.copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                                T((if (diff >= 0) "+" else "") + "$diff% dari kemarin", 12, FontWeight.SemiBold, C.Lime)
                            }
                        }
                    }
                }
            }
            // streak + jarak/kalori
            val bikeDay = acts.filter { it.type == "Sepeda" && Fmt.key(it.date) == sel }
            val dist = steps * Store.stride / 100.0 + bikeDay.sumOf { it.distance }
            val kcal = (steps * 0.04 * Store.weight / 60).roundToInt() + bikeDay.sumOf { it.kcal }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).height(140.dp).press { Nav.tab(Scr.Profile) }.clip(RoundedCornerShape(28.dp)).background(C.Lime).padding(18.dp)) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = C.Ink, modifier = Modifier.size(30.dp))
                    Box(Modifier.align(Alignment.TopEnd).size(34.dp).clip(CircleShape).background(C.Ink), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.NorthEast, null, tint = C.Lime, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.align(Alignment.BottomStart)) {
                        T("${streakNow(days, acts)}", 32, FontWeight.ExtraBold, C.Ink, spacing = -1f)
                        T("hari streak", 13, FontWeight.SemiBold, C.Ink)
                    }
                }
                CardBox(Modifier.weight(1f).height(140.dp)) {
                    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Column { T("Jarak", 12, color = C.Mut); T(Fmt.km(dist, 1) + " km", 24, FontWeight.Bold, spacing = -.5f) }
                        Column { T("Kalori", 12, color = C.Mut); T(Fmt.num(kcal), 24, FontWeight.Bold, spacing = -.5f) }
                    }
                }
            }
            // sumber langkah
            if (!hcGranted) {
                val st = Steps.hcStatus(ctx)
                Row(Modifier.fillMaxWidth().press {
                    when (st) {
                        HealthConnectClient.SDK_AVAILABLE -> hcLauncher.launch(Steps.PERMS)
                        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Updater.open(ctx, "market://details?id=${Steps.HC_PKG}")
                        else -> if (Build.VERSION.SDK_INT >= 29) actLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }.clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Lime.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Favorite, null, tint = C.Lime, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T(if (st == HealthConnectClient.SDK_UNAVAILABLE) "Aktifkan hitung langkah" else "Sambungkan Health Connect", 15, FontWeight.Bold)
                        T(if (st == HealthConnectClient.SDK_UNAVAILABLE) "Izinkan aktivitas fisik, langkah dibaca dari sensor HP" else "Langkah akurat per hari, sama dengan Samsung Health", 13, color = C.Mut)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = C.Mut)
                }
            }
            // aksi
            AnimatedContent(selD == today, label = "act") { isToday ->
                if (isToday) {
                    CardBox(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!snap.active) VisToggle()
                            Seg(listOf("Jalan", "Lari", "Sepeda"), Ui.mode) { Ui.mode = it }
                            Btn(if (snap.active) "Buka rekaman" else "Mulai ${Ui.mode}", icon = Icons.Rounded.PlayArrow) { openRecord() }
                        }
                    }
                } else Btn("Balik ke hari ini", BK.Dark) { sel = today.toString() }
            }
            if (selD == today && !snap.active) NearbySection()
            if (selD == today && acts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Aktivitas terakhir", 17, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("Lihat semua", 14, FontWeight.SemiBold, C.Lime, Modifier.press { Nav.push(Scr.History) })
                }
                acts.take(3).forEach { ActRow(it) }
            }
        }
    }
}

@Composable
fun ActRow(a: Act) {
    Row(Modifier.fillMaxWidth().press { Nav.push(Scr.Detail(a.id)) }.clip(RoundedCornerShape(24.dp)).background(C.Card)
        .border(1.dp, C.Line, RoundedCornerShape(24.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A0A0B))) { MiniRoute(a.path, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(a.title, 15, FontWeight.Bold, maxLines = 1)
            T(Fmt.dateStr(a.start), 12, color = C.Mut)
            T("${Fmt.km(a.distance)} km · ${Fmt.dur(a.duration)}", 13, FontWeight.SemiBold)
        }
    }
}

/* ============================ REKAM ============================ */
fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

suspend fun sheetAwait(c: @Composable ColumnScope.() -> Unit) {
    val d = CompletableDeferred<Unit>(); Sheet.open(onDismiss = { d.complete(Unit) }, c); d.await()
}

private fun leaveRecord() { if (Nav.cur is Scr.Record) { if (Nav.stack.size > 1) Nav.pop() else Nav.tab(Scr.Home) } }

fun exitSheet(ctx: Context) {
    val s = Track.snap.value
    Sheet.open {
        SheetTitle("${s.type} kamu masih jalan", "${Fmt.km(Track.snap.value.dist)} km sudah kerekam. Mau diapain?")
        Btn("Lanjut rekam") { Sheet.close() }
        Btn("Selesai & simpan", BK.Dark) { Sheet.close(); finishRec(ctx, true) }
        Btn("Kecilkan, tetap merekam", BK.Ghost) { Sheet.close(); leaveRecord() }
        Btn("Buang aktivitas", BK.Danger) {
            Sheet.close()
            Sheet.open {
                SheetTitle("Yakin dibuang?", "Aktivitas ini nggak bisa dikembalikan.")
                Btn("Ya, buang", BK.Dark) { Sheet.close(); finishRec(ctx, false) }
                Btn("Batal", BK.Ghost) { Sheet.close() }
            }
        }
    }
}

fun finishRec(ctx: Context, save: Boolean) {
    val r = TrackingService.stop(ctx)
    val battle = if (Live.session.value != null) Live.stopRun() else emptyList()
    if (!save) { Bus.toast("Aktivitas dibuang"); leaveRecord(); return }
    val s = r.snap; val sec = (s.elapsedMs / 1000).toInt()
    if (s.dist < 20 && sec < 30) { Bus.toast("Terlalu pendek, nggak disimpan"); leaveRecord(); return }
    val hour = Instant.ofEpochMilli(r.startWall).atZone(ZoneId.systemDefault()).hour
    val a = Act(
        id = "a" + r.startWall.toString(36) + (1000..9999).random(), type = s.type, title = "${s.type} ${Fmt.partOfDay(hour)}",
        start = r.startWall, duration = sec, distance = s.dist,
        steps = if (s.steps > 0) s.steps else if (s.type == "Sepeda") 0 else (s.dist * 100 / Store.stride).toLong(),
        kcal = Fmt.kcal(s.type, s.dist, sec.toDouble(), Store.weight), splits = s.splits, est = s.est,
        path = Fmt.downsample(r.path, 600), battle = battle
    )
    Store.addAct(a); Sfx.play(Sfx.SUCCESS)
    Ui.scope.launch { Cloud.sync(false) }
    Nav.replace(Scr.Detail(a.id, true))
    battle.firstOrNull()?.let { Ui.scope.launch { delay(1200); Bus.toast("Battle: $it") } }
}

@Composable
fun RecordScreen(s: Scr.Record) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snap by Track.snap.collectAsState()
    val online by Net.online.collectAsState()
    var count by remember { mutableIntStateOf(-1) }
    var permDef by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permDef?.complete(Unit) }
    val type = if (snap.active) snap.type else s.type
    val bike = type == "Sepeda"

    LaunchedEffect(Unit) {
        if (!s.fresh || TrackingService.active) return@LaunchedEffect
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !granted(ctx, it) }
        if (need.isNotEmpty()) { val d = CompletableDeferred<Unit>(); permDef = d; permLauncher.launch(need.toTypedArray()); d.await() }
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
            sheetAwait {
                SheetTitle("Izin lokasi ditolak", "Strafunk butuh lokasi buat ngitung jarak & rute. Buka Pengaturan › Izin › Lokasi › pilih \"Izinkan saat aplikasi digunakan\".")
                Btn("Buka pengaturan") { Sheet.close(); ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                Btn("Batal", BK.Ghost) { Sheet.close() }
            }
            leaveRecord(); return@LaunchedEffect
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) sheetAwait {
            SheetTitle("GPS HP masih mati", "Nyalakan Lokasi di HP biar jarak & rute kerekam. Kalau tetap mati, jarak jalan/lari dihitung dari langkah.")
            Btn("Nyalakan GPS") { Sheet.close(); ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            Btn("Lanjut aja", BK.Ghost) { Sheet.close() }
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName) && !Store.askedBattery) {
            Store.askedBattery = true
            sheetAwait {
                SheetTitle("Biar tracking nggak dimatiin", "Beberapa HP suka matiin app di background. Izinkan Strafunk jalan tanpa batasan baterai supaya tetap merekam saat layar mati.")
                Btn("Izinkan") {
                    Sheet.close()
                    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) { }
                }
                Btn("Nanti", BK.Ghost) { Sheet.close() }
            }
        }
        if (Nav.cur !is Scr.Record) return@LaunchedEffect
        for (i in 0..3) {
            count = i; Sfx.play(if (i < 3) Sfx.TICK else Sfx.GO)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (i == 3) { TrackingService.start(ctx, s.type, Store.stride, Store.weight); if (Cloud.uid != null) Live.startRun(ctx, s.type) }
            delay(800)
        }
        count = -1
    }

    // angka km mulus (prediksi dari kecepatan di antara update GPS)
    var disp by remember { mutableDoubleStateOf(Track.snap.value.dist) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { t ->
                val dt = if (last == 0L) .016 else (t - last) / 1000.0; last = t
                val sn = Track.snap.value; val now = System.currentTimeMillis()
                val fresh = sn.running && sn.distAt > 0 && now - sn.distAt < 4000 && sn.spd > .3
                val target = sn.dist + if (fresh) min(sn.spd * (now - sn.distAt) / 1000.0, 12.0) else 0.0
                if (sn.dist < disp - 50) disp = sn.dist
                if (target > disp) disp += (target - disp) * min(1.0, dt * 5)
            }
        }
    }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(250) } }
    val elapsed = snap.elapsedMs + if (snap.active && snap.running) (now - snap.at).coerceAtLeast(0) else 0
    val sec = elapsed / 1000.0
    val path = remember(snap.pathVer, snap.active) { Track.pathCopy() }
    var map by remember { mutableStateOf(false) }
    val rivals by Live.rivals.collectAsState()
    val reqs by Live.requests.collectAsState()
    val sess by Live.session.collectAsState()
    val host by Live.hostRun.collectAsState()
    val vis by Live.vis.collectAsState()
    LaunchedEffect(snap.dist, snap.steps) { Live.tick() }
    val battle = rivals.isNotEmpty()
    val topH = if (battle) 360.dp else 470.dp

    val (gc, gt) = when {
        !snap.active -> C.Yellow to "Siap"
        !snap.running -> C.Mut to "Dijeda"
        !snap.fix -> C.Yellow to (if (!snap.lat.isNaN()) "Mengunci GPS…" else "Mencari GPS…")
        snap.acc > 50 -> Color(0xFFFF9F0A) to "Sinyal lemah ±${snap.acc.roundToInt()}m · cari tempat terbuka"
        now - snap.lastGood > 15000 && snap.lastGood > 0 -> Color(0xFFFF9F0A) to (if (bike) "GPS lemah" else "GPS lemah · pakai langkah")
        snap.lastGood == 0L -> C.Green to "GPS ±${snap.acc.roundToInt()}m · mulai gerak"

        else -> C.Green to "GPS kuat ±${snap.acc.roundToInt()}m"
    }

    Box(Modifier.fillMaxSize()) {
        Route3D(path, snap.pathVer, live = true, bike = bike, replayKey = 0, modifier = Modifier.fillMaxWidth().height(topH),
            others = rivals.map { it.path to Color(it.color) })
        AnimatedVisibility(map, enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxWidth().height(topH)) {
                OsmMap(path, snap.pathVer, live = true, modifier = Modifier.fillMaxSize(), here = if (snap.lat.isNaN()) null else doubleArrayOf(snap.lat, snap.lng),
                    others = rivals.map { it.path to it.color })
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
            }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") { if (TrackingService.active) exitSheet(ctx) else leaveRecord() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Pill("$type · $gt · " + if (online) "Online" else "Offline", gc) }
            CircleBtn(if (map) Icons.Rounded.ViewInAr else Icons.Rounded.Map, "Ganti tampilan peta") {
                if (!map && !online) Bus.toast("Peta asli butuh internet. Rute tetap direkam") else map = !map
            }
        }
        // chip public/private + permintaan gabung
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 68.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snap.active && sess != null) {
                val me = Cloud.uid
                if (sess == me) {
                    Row(Modifier.press { Live.setVis(if (vis == "public") "private" else "public"); Bus.toast(if (vis == "public") "Private: cuma teman yang bisa minta gabung" else "Public: orang sekitar bisa lihat & gabung") }
                        .clip(RoundedCornerShape(50)).background(if (vis == "public") C.Lime.copy(alpha = .16f) else Color(0xE0161618)).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (vis == "public") Icons.Rounded.Public else Icons.Rounded.Lock, null, tint = if (vis == "public") C.Lime else C.Mut2, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp)); T(if (vis == "public") "Public · bisa digabung" else "Private", 12, FontWeight.SemiBold, if (vis == "public") C.Lime else C.Mut2)
                    }
                } else Row(Modifier.clip(RoundedCornerShape(50)).background(C.Lime.copy(alpha = .16f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = C.Lime, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                    T("Battle bareng ${host?.name ?: "…"}", 12, FontWeight.SemiBold, C.Lime)
                }
            }
            reqs.forEach { (uid, name) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xF21C1C1F)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T(Fmt.initials(name), 12, FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    T("$name minta gabung", 14, FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    Box(Modifier.press { Live.reject(uid) }.clip(RoundedCornerShape(50)).background(C.Card3).padding(horizontal = 12.dp, vertical = 8.dp)) { T("Tolak", 13, FontWeight.SemiBold) }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.press { Live.accept(uid); Sfx.play(Sfx.SUCCESS) }.clip(RoundedCornerShape(50)).background(C.Lime).padding(horizontal = 12.dp, vertical = 8.dp)) { T("Terima", 13, FontWeight.Bold, C.Ink) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = if (battle) 320.dp else 430.dp, start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(if (battle) 14.dp else 22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                T(Fmt.km(disp), if (battle) 72 else 92, FontWeight.ExtraBold, spacing = if (battle) -4f else -5f)
                Spacer(Modifier.width(8.dp)); T("km", 18, FontWeight.SemiBold, C.Mut, Modifier.padding(bottom = 18.dp))
            }
            Stat3(listOf(
                "Waktu" to Fmt.dur(sec.toInt()),
                (if (bike) "km/jam" else "Pace /km") to (if (bike) Fmt.speed(snap.dist, sec) else Fmt.pace(if (snap.dist > 30) sec / (snap.dist / 1000) else Double.NaN)),
                (if (bike) "Kalori" else "Langkah") to (if (bike) Fmt.num(Fmt.kcal(type, snap.dist, sec, Store.weight)) else Fmt.num(snap.steps))
            ))
            AnimatedVisibility(battle, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { BattlePanel(rivals, bike) }
        }
        Row(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(Icons.Rounded.Stop, "Selesai", 60.dp, C.Card2, Color(0xFFFF453A)) { if (TrackingService.active) exitSheet(ctx) else leaveRecord() }
            val running = snap.running
            Box(Modifier.size(92.dp).press {
                if (!TrackingService.active) return@press
                Sfx.play(if (running) Sfx.PAUSE else Sfx.RESUME)
                TrackingService.send(ctx, if (running) TrackingService.A_PAUSE else TrackingService.A_RESUME)
            }.clip(CircleShape).background(C.Lime), contentAlignment = Alignment.Center) {
                AnimatedContent(running || !snap.active, label = "pp", transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }) { r ->
                    Icon(if (r) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (r) "Jeda" else "Lanjutkan", tint = C.Ink, modifier = Modifier.size(40.dp))
                }
            }
            CircleBtn(Icons.Rounded.Lock, "Kunci layar", 60.dp, C.Card2) { Ui.locked = true }
        }
        if (count >= 0) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
                AnimatedContent(count, label = "cd", transitionSpec = {
                    (scaleIn(spring(dampingRatio = .45f), initialScale = .4f) + fadeIn()) togetherWith (scaleOut(tween(250), targetScale = 1.6f) + fadeOut(tween(250)))
                }) { c ->
                    T(if (c < 3) "${3 - c}" else "Gas!", if (c < 3) 160 else 96, FontWeight.ExtraBold, if (c < 3) Color.White else C.Lime, spacing = -6f)
                }
            }
        }
    }
}

@Composable
fun LockOverlay() {
    val haptic = LocalHapticFeedback.current
    var pressing by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (pressing) 1.25f else 1f, tween(900, easing = LinearEasing), label = "lk")
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Color(0x8C000000)).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape).background(if (pressing) Color(0xFF2C3A0A) else C.Card2)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        pressing = true
                        val job = scope.launch { delay(900); Ui.locked = false; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        tryAwaitRelease(); job.cancel(); pressing = false
                    })
                }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Lock, "Buka kunci", tint = C.Lime, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.height(14.dp))
            T("Layar terkunci · tahan untuk buka", 14, FontWeight.SemiBold, Color(0xFFD1D1D6))
        }
    }
}

/* ============================ DETAIL ============================ */
@Composable
fun DetailScreen(s: Scr.Detail, remote: Boolean = false) {
    val ctx = LocalContext.current
    val acts by Store.acts.collectAsState()
    val online by Net.online.collectAsState()
    val ra = if (remote) Cloud.remote[s.id] else null
    val a = if (remote) ra?.act else acts.firstOrNull { it.id == s.id }
    var replay by remember { mutableIntStateOf(0) }
    var map by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (s.fresh) Bus.toast("Aktivitas tersimpan") }
    if (a == null) {
        ScreenCol { CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") { Nav.pop() }; T("Aktivitas nggak ketemu.", 15, color = C.Mut2) }
        return
    }
    val bike = a.type == "Sepeda"
    Box(Modifier.fillMaxSize()) {
        Route3D(a.path, 0, live = false, bike = bike, replayKey = replay, modifier = Modifier.fillMaxWidth().height(430.dp))
        AnimatedVisibility(map, enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxWidth().height(430.dp)) {
                OsmMap(a.path, 0, live = false, modifier = Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 380.dp, start = 20.dp, end = 20.dp, bottom = 40.dp)
            .windowInsetsPadding(WindowInsets.navigationBars), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                T((if (ra != null) (if (ra.uid == Cloud.uid) "Kamu" else ra.userName) + " · " else "") + "${a.type} · ${Fmt.dateStr(a.start)}" +
                    (if (!remote) " · " + (if (a.synced) "Tersinkron" else "Tersimpan di HP") else ""), 13, color = C.Mut)
                T(a.title, 28, FontWeight.ExtraBold, spacing = -1f)
            }
            Stat3(listOf("Jarak" to "${Fmt.km(a.distance)} km",
                (if (bike) "km/jam" else "Pace") to (if (bike) Fmt.speed(a.distance, a.duration.toDouble()) else Fmt.pace(a.duration / maxOf(a.distance / 1000, .001))),
                "Waktu" to Fmt.dur(a.duration)))
            Stat3(listOf("Langkah" to Fmt.num(a.steps), "Kalori" to Fmt.num(a.kcal), "Titik GPS" to "${a.path.size}"))
            if (a.splits.isNotEmpty()) {
                val mx = a.splits.max(); val mn = a.splits.min()
                CardBox(Modifier.fillMaxWidth(), radius = 24.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row {
                            T(if (bike) "Kecepatan per km" else "Pace per km", 15, FontWeight.Bold, modifier = Modifier.weight(1f))
                            T("Tercepat: km ${a.splits.indexOf(mn) + 1}", 13, FontWeight.SemiBold, C.Lime)
                        }
                        Row(Modifier.height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            a.splits.take(12).forEachIndexed { i, sp ->
                                val h = 30f + if (mx == mn) 40f else (mx - sp).toFloat() / (mx - mn) * 40f
                                val anim = remember(replay) { Animatable(0f) }
                                LaunchedEffect(replay) { delay(400L + i * 70L); anim.animateTo(1f, spring(dampingRatio = .55f, stiffness = 300f)) }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    T(if (bike) Fmt.speed(1000.0, sp.toDouble()) else Fmt.pace(sp.toDouble()), 10, FontWeight.SemiBold, Color(0xFFD1D1D6), maxLines = 1)
                                    Spacer(Modifier.height(6.dp))
                                    Box(Modifier.fillMaxWidth().height((h * anim.value).dp).clip(RoundedCornerShape(10.dp)).background(if (sp == mn) C.Lime else C.Card3))
                                    Spacer(Modifier.height(4.dp)); T("${i + 1}", 10, color = C.Mut)
                                }
                            }
                        }
                    }
                }
            }
            if (a.battle.isNotEmpty()) CardBox(Modifier.fillMaxWidth(), radius = 24.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Bolt, null, tint = C.Lime, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); T("Hasil battle", 15, FontWeight.Bold) }
                    a.battle.forEach { T(it, 14, FontWeight.SemiBold, if (it.startsWith("Menang")) C.Lime else C.Mut2) }
                }
            }
            if (a.est) T("Sebagian jarak diperkirakan dari langkah karena GPS lemah.", 12, color = C.Mut, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Btn("Bagikan ke Story", icon = Icons.Rounded.Share) { try { Story.share(ctx, a) } catch (e: Exception) { Bus.toast("Gagal membagikan") } }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") { Nav.pop() }
            Spacer(Modifier.weight(1f))
            CircleBtn(if (map) Icons.Rounded.ViewInAr else Icons.Rounded.Map, "Ganti tampilan peta") {
                if (!map && !online) Bus.toast("Peta asli butuh internet") else map = !map
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.height(44.dp).press { map = false; replay++ }.clip(RoundedCornerShape(50)).background(Color(0xE01C1C1F)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Replay, null, tint = C.Lime, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                T("Replay 3D", 14, FontWeight.SemiBold)
            }
            if (!remote) { Spacer(Modifier.width(8.dp)); CircleBtn(Icons.Rounded.MoreHoriz, "Lainnya") { actMoreSheet(a) } }
        }
    }
}

fun actMoreSheet(a: Act) {
    Sheet.open {
        var name by remember { mutableStateOf(a.title) }
        SheetTitle(a.title)
        BasicTextField(name, { name = it.take(40) }, singleLine = true,
            textStyle = TextStyle(color = C.Txt, fontSize = 16.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(C.Lime),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card2).border(1.5.dp, C.Lime.copy(alpha = .5f), RoundedCornerShape(16.dp)).padding(16.dp))
        Btn("Simpan judul") { if (name.isNotBlank()) Store.updateAct(a.copy(title = name.trim())); Sheet.close() }
        Btn("Hapus aktivitas", BK.Danger) { Store.deleteAct(a.id); Cloud.deleteRemote(a.id); Sheet.close(); Bus.toast("Aktivitas dihapus"); Nav.pop() }
    }
}

/* ============================ RIWAYAT ============================ */
@Composable
fun HistoryScreen() {
    val acts by Store.acts.collectAsState()
    ScreenCol(bottom = 40.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", 38.dp, C.Card) { Nav.pop() }
            Spacer(Modifier.width(12.dp)); T("Riwayat", 28, FontWeight.ExtraBold, spacing = -1f)
        }
        if (acts.isEmpty()) CardBox(Modifier.fillMaxWidth()) { T("Belum ada aktivitas. Yuk gerak dulu!", 14, color = C.Mut2, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(28.dp)) }
        acts.forEach { ActRow(it) }
    }
}

/* ============================ PROFIL ============================ */
@Composable
fun ProfileScreen() {
    val ctx = LocalContext.current
    val days by Store.days.collectAsState()
    val acts by Store.acts.collectAsState()
    val me by Cloud.user.collectAsState()
    val syncing by Cloud.syncing.collectAsState()
    Store.settingsVer.collectAsState().value
    val totalDist = acts.sumOf { it.distance }; val totalSteps = days.values.sum(); val best = bestStreak(days, acts)
    val badges = listOf(
        Triple("5K", "Lari 5 km", acts.any { it.type == "Lari" && it.distance >= 5000 }),
        Triple("10K", "Lari 10 km", acts.any { it.type == "Lari" && it.distance >= 10000 }),
        Triple("7", "Streak 7", best >= 7), Triple("30", "Streak 30", best >= 30),
        Triple("50", "Gowes 50 km", acts.any { it.type == "Sepeda" && it.distance >= 50000 }),
        Triple("100", "Total 100 km", totalDist >= 100000), Triple("100K", "100rb langkah", totalSteps >= 100000),
        Triple("05", "Gerak subuh", acts.any { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour < 6 })
    )
    val inf = rememberInfiniteTransition(label = "pf")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "spin")
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        Ui.scope.launch { refreshWeek(ctx, Fmt.monday(LocalDate.now())) }
    }
    Box(Modifier.fillMaxSize()) {
        Glow(Modifier.align(Alignment.TopCenter).offset(y = (-150).dp).size(440.dp, 340.dp))
        ScreenCol(gap = 18.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).press { editNameSheet() }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }) {
                        drawCircle(C.Lime, size.width / 2 - 2f, style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                    }
                    Box(Modifier.size(82.dp).clip(CircleShape).background(C.Card2), contentAlignment = Alignment.Center) { T(Fmt.initials(Store.name), 28, FontWeight.ExtraBold) }
                }
                Spacer(Modifier.height(10.dp))
                T(Store.name, 26, FontWeight.ExtraBold, spacing = -.8f)
                T(me?.let { "@${it.username} · ${it.following.size} diikuti" } ?: "Mode offline · tap avatar buat ganti nama", 13, color = C.Mut, align = TextAlign.Center)
            }
            Stat3(listOf("km total" to Fmt.km(totalDist, 0), "aktivitas" to "${acts.size}", "streak terbaik" to "$best"), big = 20)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Badge", 17, FontWeight.Bold, modifier = Modifier.weight(1f)); T("${badges.count { it.third }} dari 8", 13, color = C.Mut)
            }
            badges.chunked(4).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { i, (v, n, ok) ->
                        val k = r * 4 + i
                        val sc = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { delay(150L + k * 50L); sc.animateTo(1f, spring(dampingRatio = .45f, stiffness = 400f)) }
                        Column(Modifier.weight(1f).aspectRatio(1f).graphicsLayer { scaleX = sc.value; scaleY = sc.value }
                            .clip(RoundedCornerShape(22.dp)).background(if (!ok) Color(0xFF0F0F11) else if (k % 3 == 0) C.Lime else C.Card2)
                            .border(1.dp, if (ok) C.Line else C.Card3, RoundedCornerShape(22.dp)),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val fg = if (!ok) Color(0xFF48484A) else if (k % 3 == 0) C.Ink else C.Txt
                            T(v, 19, FontWeight.ExtraBold, fg, spacing = -.5f)
                            T(n, 9, FontWeight.SemiBold, fg, align = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }
            CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
                Column {
                    val u = me
                    if (u == null) ListItem("Masuk / Daftar akun", "", last = true) { Nav.push(Scr.Auth) }
                    else {
                        val un = acts.count { !it.synced }
                        ListItem("Sinkron sekarang", if (syncing) "Menyinkronkan…" else if (un > 0) "$un belum" else "Beres") {
                            Ui.scope.launch { Bus.toast("Menyinkronkan…"); Bus.toast(if (Cloud.sync(true)) "Sinkron beres" else "Gagal sinkron") }
                        }
                        ListItem("Tambah teman", "") { addFriendSheet() }
                        ListItem("Keluar akun", "", last = true) {
                            Sheet.open {
                                SheetTitle("Keluar akun?", "Aktivitas di HP tetap aman.")
                                Btn("Keluar", BK.Dark) { Cloud.logout(); Sheet.close(); Bus.toast("Sudah keluar") }
                                Btn("Batal", BK.Ghost) { Sheet.close() }
                            }
                        }
                    }
                }
            }
            CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
                Column {
                    ListItem("Riwayat aktivitas", "${acts.size}") { Nav.push(Scr.History) }
                    ListItem("Target harian", "${Fmt.num(Store.target)} langkah") { stepperSheet("Target harian", Store.target, 500, 1000, 50000, "langkah", null) { Store.target = it } }
                    ListItem("Panjang langkah", "${Store.stride} cm") { stepperSheet("Panjang langkah", Store.stride, 1, 40, 120, "cm", "Dipakai buat estimasi jarak dari langkah. Kira-kira tinggi badan × 0,42.") { Store.stride = it } }
                    ListItem("Berat badan", "${Store.weight} kg", last = true) { stepperSheet("Berat badan", Store.weight, 1, 30, 200, "kg", null) { Store.weight = it } }
                }
            }
            CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("Efek suara", 15, modifier = Modifier.weight(1f)); SwitchSf(Store.sound) { Store.sound = it }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("Saring langkah di kendaraan", 15)
                            T("Buang langkah palsu waktu naik motor/mobil/sepeda", 12, color = C.Mut)
                        }
                        SwitchSf(Store.filterVehicle) {
                            Store.filterVehicle = it
                            if (it) com.dzaky.strafunk.steps.Vehicle.register(ctx) else com.dzaky.strafunk.steps.Vehicle.unregister(ctx)
                            Ui.scope.launch { refreshWeek(ctx, Fmt.monday(LocalDate.now())) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                    ListItem("Sumber langkah", when (Store.stepSource) { "hc" -> "Health Connect"; "sensor" -> "Sensor HP"; else -> "Belum aktif" }) {
                        if (Steps.hcAvailable(ctx)) hcLauncher.launch(Steps.PERMS) else Bus.toast("Health Connect nggak tersedia di HP ini")
                    }
                    ListItem("Cek update", "build ${BuildConfig.VERSION_CODE}") { checkUpdate(ctx, true) }
                    ListItem("Diagnostik", "", last = true) { diagSheet(ctx) }
                }
            }
            T("Strafunk native · build ${BuildConfig.VERSION_CODE}", 12, color = C.Mut, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ListItem(label: String, value: String, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(54.dp).press(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            T(label, 15, modifier = Modifier.weight(1f)); T(value, 15, color = C.Mut)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = C.Mut, modifier = Modifier.size(18.dp))
        }
        if (!last) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(C.Line))
    }
}

fun stepperSheet(title: String, start: Int, step: Int, mn: Int, mx: Int, unit: String, note: String?, save: (Int) -> Unit) {
    Sheet.open {
        var v by remember { mutableIntStateOf(start) }
        SheetTitle(title, note)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(56.dp).press { v = (v - step).coerceIn(mn, mx) }.clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T("−", 26, FontWeight.SemiBold) }
            AnimatedContent(v, label = "stp", transitionSpec = {
                if (targetState > initialState) (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                else (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            }) { x -> T(Fmt.num(x), 40, FontWeight.ExtraBold, spacing = -1.5f, align = TextAlign.Center, modifier = Modifier.width(150.dp)) }
            Box(Modifier.size(56.dp).press { v = (v + step).coerceIn(mn, mx) }.clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T("+", 26, FontWeight.SemiBold) }
        }
        T(unit, 13, color = C.Mut)
        Btn("Simpan") { save(v); Sheet.close(); Bus.toast("Tersimpan") }
    }
}

fun editNameSheet() {
    Sheet.open {
        var name by remember { mutableStateOf(Store.name) }
        SheetTitle("Nama kamu")
        BasicTextField(name, { name = it.take(24) }, singleLine = true,
            textStyle = TextStyle(color = C.Txt, fontSize = 16.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(C.Lime),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card2).padding(16.dp))
        Btn("Simpan") { if (name.isNotBlank()) Store.name = name.trim(); Sheet.close() }
    }
}

fun diagSheet(ctx: Context) {
    Ui.scope.launch {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lines = listOf(
            "HP: ${Build.MANUFACTURER} ${Build.MODEL} · Android SDK ${Build.VERSION.SDK_INT}",
            "Build app: ${BuildConfig.VERSION_CODE}",
            "Health Connect: " + when (Steps.hcStatus(ctx)) { HealthConnectClient.SDK_AVAILABLE -> "tersedia"; HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "perlu update"; else -> "tidak tersedia" } +
                " · izin: " + Steps.hcGranted(ctx),
            "Sumber langkah: ${Store.stepSource} · hari ini: ${Store.days.value[LocalDate.now().toString()] ?: 0}",
            "Sensor langkah: ${Steps.hasSensor(ctx)} · izin aktivitas: ${Steps.sensorGranted(ctx)}",
            "Lokasi: ${granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)} · GPS nyala: ${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}",
            "Notifikasi: " + (Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) +
                " · bebas batas baterai: ${pm.isIgnoringBatteryOptimizations(ctx.packageName)}",
            "Online: ${Net.online.value} · merekam: ${TrackingService.active}"
        )
        Sheet.open {
            SheetTitle("Diagnostik", "Kalau ada masalah, screenshot layar ini.")
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F0F11)).padding(14.dp)) {
                T(lines.joinToString("\n"), 12, color = Color(0xFFD1D1D6))
            }
        }
    }
}

/* ============================ segera hadir ============================ */
@Composable
fun SoonScreen(title: String, msg: String) {
    ScreenCol {
        T(title, 34, FontWeight.ExtraBold, spacing = -1.2f)
        CardBox(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Bolt, null, tint = C.Lime, modifier = Modifier.size(36.dp))
                T(msg, 14, color = C.Mut2, align = TextAlign.Center)
            }
        }
    }
}
