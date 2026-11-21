package com.dzaky.strafunk.ui

import android.Manifest
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzaky.strafunk.Net
import com.dzaky.strafunk.cloud.Cloud
import com.dzaky.strafunk.cloud.Live
import com.dzaky.strafunk.cloud.LiveRun
import com.dzaky.strafunk.cloud.Rival
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.track.Track
import kotlin.math.roundToInt

/* ============================ panel battle (layar Rekam) ============================ */
@Composable
fun BattlePanel(rivals: List<Rival>, bike: Boolean) {
    val inf = rememberInfiniteTransition(label = "bt")
    val blink by inf.animateFloat(.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "bl")
    CardBox(Modifier.fillMaxWidth(), radius = 24.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).graphicsLayer { alpha = blink }.clip(CircleShape).background(C.Red)); Spacer(Modifier.width(7.dp))
                T("Battle live", 14, FontWeight.Bold, modifier = Modifier.weight(1f))
                T("sejak gabung", 11, color = C.Mut)
            }
            rivals.take(3).forEach { r ->
                val gap = r.myDist - r.theirDist
                val mx = maxOf(r.myDist, r.theirDist, 1.0)
                val a by animateFloatAsState((r.myDist / mx).toFloat(), tween(900, easing = EaseOutQuint), label = "a")
                val b by animateFloatAsState((r.theirDist / mx).toFloat(), tween(900, easing = EaseOutQuint), label = "b")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(r.color)), contentAlignment = Alignment.Center) { T(Fmt.initials(r.run.name), 11, FontWeight.Bold, C.Ink) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row { T("Kamu ${Fmt.km(r.myDist)}", 11, FontWeight.SemiBold, modifier = Modifier.weight(1f)); T("${r.run.name.split(" ").first()} ${Fmt.km(r.theirDist)}", 11, FontWeight.SemiBold, C.Mut2) }
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Card3)) { Box(Modifier.fillMaxWidth(a).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(C.Lime)) }
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Card3)) { Box(Modifier.fillMaxWidth(b).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(r.color))) }
                    }
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(gap >= 0, label = "g") { up ->
                        T((if (up) "+" else "−") + Fmt.km(kotlin.math.abs(gap)), 13, FontWeight.ExtraBold, if (up) C.Lime else C.Red)
                    }
                }
            }
            if (rivals.size > 3) T("+${rivals.size - 3} pelari lain", 11, color = C.Mut)
        }
    }
}

/* ============================ pilihan Public / Private (Beranda) ============================ */
@Composable
fun VisToggle() {
    val vis by Live.vis.collectAsState()
    val me by Cloud.user.collectAsState()
    val online by Net.online.collectAsState()
    val enabled = me != null && online
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card2).padding(3.dp)) {
            listOf("private" to "Private", "public" to "Public").forEach { (k, label) ->
                val on = vis == k
                val bg by animateColorAsState(if (on) (if (k == "public") C.Lime else Color(0xFF3A3A3E)) else Color.Transparent, label = "v")
                val fg = if (on && k == "public") C.Ink else if (on) Color.White else C.Mut
                Row(Modifier.weight(1f).height(36.dp).press {
                    if (!enabled) { Bus.toast(if (me == null) "Login dulu buat lari Public & battle" else "Butuh internet buat Public"); return@press }
                    Live.setVis(k)
                }.clip(RoundedCornerShape(11.dp)).background(bg), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (k == "public") Icons.Rounded.Public else Icons.Rounded.Lock, null, tint = fg, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); T(label, 14, FontWeight.SemiBold, fg)
                }
            }
        }
        T(if (!enabled) "Login & online buat bisa lari bareng" else if (vis == "public") "Orang di sekitar bisa lihat & gabung larimu" else "Cuma teman yang bisa minta gabung",
            12, color = C.Mut, modifier = Modifier.padding(start = 4.dp))
    }
}

/* ============================ lari di sekitar (Beranda) ============================ */
@Composable
fun NearbySection() {
    val ctx = LocalContext.current
    val me by Cloud.user.collectAsState()
    val online by Net.online.collectAsState()
    val list by Live.nearby.collectAsState()
    val waiting by Live.waiting.collectAsState()
    var here by remember { mutableStateOf<DoubleArray?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    if (me == null || !online) return
    LaunchedEffect(tick) {
        if (granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) here = Live.currentLoc(ctx)
        kotlinx.coroutines.delay(60_000); tick++
    }
    DisposableEffect(me?.uid, me?.following?.size, here?.let { (it[0] * 100).roundToInt() * 1000 + (it[1] * 100).roundToInt() }) {
        val regs = Live.watchNearby(here)
        onDispose { regs.forEach { it.remove() } }
    }
    val w = waiting
    if (w != null) CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingDots(); Spacer(Modifier.width(10.dp))
            T("Nunggu ${w.name} nerima…", 14, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            T("Batal", 14, FontWeight.SemiBold, C.Red, Modifier.press { Live.cancelWait() })
        }
    }
    if (list.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(C.Red)); Spacer(Modifier.width(8.dp))
        T("Lagi lari sekarang", 17, FontWeight.Bold)
    }
    list.take(5).forEach { r -> LiveCard(r, here) }
}

@Composable
private fun LiveCard(r: LiveRun, here: DoubleArray?) {
    val pub = r.vis == "public"
    val far = if (here != null && r.cLat != null) Live.dist(here, r) else null
    val mins = ((System.currentTimeMillis() - r.startedAt) / 60000).toInt()
    CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T(Fmt.initials(r.name), 14, FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("${r.name} lagi ${r.type.lowercase()}", 15, FontWeight.Bold, maxLines = 1)
                T((if (far != null) (if (far < 1000) "±${(far / 50).roundToInt() * 50} m dari kamu" else "±${Fmt.km(far, 1)} km dari kamu") else if (pub) "Di sekitar kamu" else "Teman · private") +
                    " · ${Fmt.km(r.dist)} km · $mins mnt", 12, color = C.Mut, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.press { joinSheet(r) }.clip(RoundedCornerShape(50)).background(if (pub) C.Lime else C.Card3).padding(horizontal = 14.dp, vertical = 9.dp)) {
                T(if (pub) "Gabung" else "Minta gabung", 13, FontWeight.Bold, if (pub) C.Ink else C.Txt)
            }
        }
    }
}

@Composable
fun LoadingDots() {
    val inf = rememberInfiniteTransition(label = "ld")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a by inf.animateFloat(.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(C.Lime))
        }
    }
}

fun joinSheet(r: LiveRun) {
    if (Track.snap.value.active) { Bus.toast("Selesaikan rekaman kamu dulu"); return }
    val pub = r.vis == "public"
    Sheet.open {
        SheetTitle(if (pub) "Gabung lari ${r.name}?" else "Minta gabung ke ${r.name}?",
            if (pub) "Kamu mulai ${r.type.lowercase()} sekarang & langsung battle. Skor dihitung sejak kamu gabung."
            else "${r.name} lari private. Kalau diterima, kamu bisa mulai ${r.type.lowercase()} & battle bareng.")
        Btn(if (pub) "Gabung & mulai" else "Kirim permintaan", icon = if (pub) Icons.Rounded.PlayArrow else Icons.Rounded.Send) {
            Sheet.close()
            if (pub) { Live.pendingJoin = r; Ui.mode = r.type; Nav.push(Scr.Record(r.type, true)) }
            else Live.requestJoin(r) { Bus.toast(it) }
        }
        Btn("Batal", BK.Ghost) { Sheet.close() }
    }
}

@Composable
fun OfflineNearby() {
    val ctx = LocalContext.current
    val list by com.dzaky.strafunk.cloud.Offline.runners.collectAsState()
    var ok by remember { mutableStateOf(com.dzaky.strafunk.cloud.Offline.granted(ctx)) }
    var bt by remember { mutableStateOf(com.dzaky.strafunk.cloud.Offline.btOn(ctx)) }
    val permL = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        ok = com.dzaky.strafunk.cloud.Offline.granted(ctx)
    }
    val btL = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        bt = com.dzaky.strafunk.cloud.Offline.btOn(ctx)
    }
    // cek ulang tiap 5 dtk (user bisa nyalain BT dari quick settings)
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); bt = com.dzaky.strafunk.cloud.Offline.btOn(ctx); ok = com.dzaky.strafunk.cloud.Offline.granted(ctx) } }
    DisposableEffect(ok, bt) {
        if (ok && bt) com.dzaky.strafunk.cloud.Offline.startDiscovery(ctx)
        onDispose { com.dzaky.strafunk.cloud.Offline.stopDiscovery() }
    }
    if (!ok || !bt) {
        CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bluetooth, null, tint = C.Lime, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T("Battle offline", 14, FontWeight.Bold)
                    T(if (!ok) "Izinkan perangkat sekitar buat nemuin teman" else "Nyalain Bluetooth buat nemuin teman", 12, color = C.Mut)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.press {
                    if (!ok) permL.launch(com.dzaky.strafunk.cloud.Offline.perms())
                    else try { btL.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { Bus.toast("Nyalain Bluetooth dari setelan") }
                }.clip(RoundedCornerShape(50)).background(C.Card3).padding(horizontal = 14.dp, vertical = 9.dp)) { T(if (!ok) "Izinkan" else "Nyalain", 13, FontWeight.Bold) }
            }
        }
        return
    }
    if (list.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Bluetooth, null, tint = C.Lime, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
        T("Di dekat kamu (offline)", 17, FontWeight.Bold)
    }
    list.values.take(5).forEach { f ->
        val pub = f.vis == "public"
        CardBox(Modifier.fillMaxWidth(), radius = 22.dp) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T(Fmt.initials(f.name), 14, FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T("${f.name} lagi ${f.type.lowercase()}", 15, FontWeight.Bold, maxLines = 1)
                    T("Bluetooth · " + (if (pub) "public" else "private"), 12, color = C.Mut, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.press {
                    com.dzaky.strafunk.cloud.Offline.pendingJoin = f; Ui.mode = f.type; Nav.push(Scr.Record(f.type, true))
                }.clip(RoundedCornerShape(50)).background(if (pub) C.Lime else C.Card3).padding(horizontal = 14.dp, vertical = 9.dp)) {
                    T(if (pub) "Gabung" else "Minta gabung", 13, FontWeight.Bold, if (pub) C.Ink else C.Txt)
                }
            }
        }
    }
}
