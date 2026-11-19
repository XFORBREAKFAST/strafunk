package com.dzaky.strafunk.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzaky.strafunk.Net
import com.dzaky.strafunk.cloud.Cloud
import com.dzaky.strafunk.cloud.Prof
import com.dzaky.strafunk.cloud.RAct
import com.dzaky.strafunk.data.Fmt
import com.dzaky.strafunk.data.Store
import com.dzaky.strafunk.sound.Sfx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/* ============================ loading ============================ */
@Composable
fun LoadingBar(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut(tween(400))) {
        val inf = rememberInfiniteTransition(label = "lb")
        val x by inf.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)), label = "lbx")
        BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Card2)) {
            Box(Modifier.width(maxWidth * .4f).fillMaxHeight().graphicsLayer { translationX = x * size.width * 2.5f }.clip(RoundedCornerShape(2.dp)).background(C.Lime))
        }
    }
}

@Composable
fun Skeleton(height: Int, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "sk")
    val x by inf.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "skx")
    BoxWithConstraints(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(28.dp)).background(C.Card)) {
        val w = constraints.maxWidth.toFloat()
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x14FFFFFF), Color.Transparent), startX = x * w, endX = x * w + w * .6f)))
    }
}

@Composable
fun StatusLine(text: String) { T(text, 12, FontWeight.SemiBold, C.Mut) }

@Composable
fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, msg: String, btn: String? = null, onBtn: () -> Unit = {}) {
    CardBox(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
            T(msg, 14, color = C.Mut2, align = TextAlign.Center)
            if (btn != null) Btn(btn, height = 46.dp, onClick = onBtn)
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, email: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val bc by animateColorAsState(if (focused) C.Lime else Color.Transparent, label = "fb")
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card2).border(1.5.dp, bc, RoundedCornerShape(16.dp)).padding(16.dp)) {
        if (value.isEmpty()) T(placeholder, 16, color = C.Mut)
        BasicTextField(value, onChange, singleLine = true,
            textStyle = TextStyle(color = C.Txt, fontSize = 16.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(C.Lime),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else if (email) KeyboardType.Email else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
    }
}


/* ============================ AUTH ============================ */
@Composable
fun AuthScreen() {
    val online by Net.online.collectAsState()
    var mode by remember { mutableStateOf("Masuk") }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var mail by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Glow(Modifier.align(Alignment.TopCenter).offset(y = (-80).dp).size(520.dp, 420.dp))
        ScreenCol(gap = 14.dp, bottom = 40.dp) {
            CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", 38.dp, C.Card) { Nav.pop() }
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.Bottom) { T("Strafunk", 48, FontWeight.ExtraBold, spacing = -2.5f); T(".", 48, FontWeight.ExtraBold, C.Lime) }
            T("Masuk buat sinkron aktivitas, lihat teman, ranking & duel.", 16, color = C.Mut2)
            Spacer(Modifier.height(10.dp))
            Seg(listOf("Masuk", "Daftar"), mode) { mode = it; err = "" }
            AnimatedVisibility(mode == "Daftar") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(name, { name = it.take(30) }, "Nama")
                    Field(user, { user = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.take(20) }, "Username (huruf kecil, tanpa spasi)")
                }
            }
            Field(mail, { mail = it.trim() }, "Email", email = true)
            Field(pass, { pass = it }, "Password (min. 6 karakter)", password = true)
            AnimatedVisibility(err.isNotEmpty()) { T(err, 13, color = C.Red, align = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            Btn(if (busy) "Sebentar…" else mode) {
                if (busy) return@Btn
                if (!online) { err = "Butuh internet"; return@Btn }
                if (!Cloud.ready) { err = "Server belum siap, coba buka ulang app"; return@Btn }
                if (mode == "Daftar" && (name.isBlank() || user.length < 3)) { err = "Isi nama & username (min. 3 huruf)"; return@Btn }
                busy = true; err = ""
                Ui.scope.launch {
                    try {
                        if (mode == "Daftar") Cloud.register(name.trim(), user, mail, pass) else Cloud.login(mail, pass)
                        Sfx.play(Sfx.SUCCESS); Bus.toast("Halo, ${Cloud.user.value?.name ?: Store.name}!")
                        Nav.pop()
                    } catch (e: Exception) { err = Cloud.errMsg(e); Sfx.play(Sfx.ERROR) } finally { busy = false }
                }
            }
            if (mode == "Masuk") Btn("Lupa password?", BK.Ghost) {
                if (mail.isBlank() || !mail.contains("@")) { err = "Isi email kamu dulu di atas"; return@Btn }
                if (!online || !Cloud.ready) { err = "Butuh internet"; return@Btn }
                Ui.scope.launch {
                    try { Cloud.resetPassword(mail); err = ""; Sheet.open {
                        SheetTitle("Cek email kamu 📩", "Link buat bikin password baru sudah dikirim ke $mail. Cek juga folder Spam.")
                        Btn("Oke") { Sheet.close() }
                    } } catch (e: Exception) { err = Cloud.errMsg(e) }
                }
            }
        }
    }
}

/* ============================ FEED ============================ */
object FeedCache { var posts: List<RAct> = emptyList() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen() {
    val me by Cloud.user.collectAsState()
    val online by Net.online.collectAsState()
    var posts by remember { mutableStateOf(FeedCache.posts) }
    var server by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var slow by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var friends by remember { mutableStateOf<List<Prof>>(emptyList()) }
    val u = me
    DisposableEffect(u?.uid, u?.following?.size, refreshKey) {
        if (u == null) return@DisposableEffect onDispose { }
        server = false; err = null; slow = false
        val ids = listOf(u.uid) + u.following
        val now = LocalDate.now()
        val regs = Cloud.listenActs(ids, "week", listOf(Fmt.key(Fmt.monday(now)), Fmt.key(Fmt.monday(now.minusDays(7))))) { list, fromServer, e ->
            posts = list.take(40); FeedCache.posts = posts
            if (fromServer) { server = true; refreshing = false; slow = false }
            if (e != null) { err = e; refreshing = false }
        }
        val job = Ui.scope.launch { delay(15000); if (!server) { slow = true; refreshing = false } }
        Ui.scope.launch { try { friends = Cloud.profiles(u.following).values.toList() } catch (_: Exception) { } }
        onDispose { regs.forEach { it.remove() }; job.cancel() }
    }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(refreshing, { refreshing = true; refreshKey++ }, Modifier.fillMaxSize(), state = state, indicator = {
        PullToRefreshDefaults.Indicator(state = state, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
            containerColor = C.Card2, color = C.Lime)
    }) {
        ScreenCol {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Feed", 34, FontWeight.ExtraBold, spacing = -1.2f, modifier = Modifier.weight(1f))
                if (u != null) CircleBtn(Icons.Rounded.Search, "Cari teman", 40.dp, C.Card) { addFriendSheet() }
            }
            when {
                u == null -> InfoCard(Icons.Rounded.Groups, C.Lime, "Masuk dulu biar bisa lihat aktivitas teman, kasih kudos, dan ikut ranking.", "Masuk / Daftar") { Nav.push(Scr.Auth) }
                else -> {
                    LoadingBar(!server && err == null && online)
                    if (!server && err == null) StatusLine(if (!online) "Offline · nampilin data terakhir" else if (posts.isEmpty()) "Nyambung ke server…" else "Memperbarui feed…")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.press { addFriendSheet() }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(58.dp).border(2.dp, C.Card3, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = C.Lime) }
                            Spacer(Modifier.height(6.dp)); T("Tambah", 12, color = Color(0xFFD1D1D6))
                        }
                        friends.forEach { f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(58.dp).border(2.dp, C.Lime, CircleShape).padding(4.dp).clip(CircleShape).background(C.Card2), contentAlignment = Alignment.Center) { T(Fmt.initials(f.name), 15, FontWeight.Bold) }
                                Spacer(Modifier.height(6.dp)); T(f.name.split(" ").first(), 12, color = Color(0xFFD1D1D6), maxLines = 1)
                            }
                        }
                    }
                    if (slow && !server) InfoCard(Icons.Rounded.WifiOff, C.Yellow, "Koneksi lambat. Data terakhir tetap ditampilkan.", "Coba lagi") { refreshKey++ }
                    if (err != null) InfoCard(Icons.Rounded.ErrorOutline, C.Red, err!!, "Coba lagi") { refreshKey++ }
                    if (posts.isEmpty() && !server && err == null) { Skeleton(320); Skeleton(320) }
                    else if (posts.isEmpty() && server) InfoCard(Icons.Rounded.DirectionsRun, C.Lime, "Belum ada aktivitas minggu ini. Tambah teman pakai tombol cari, atau mulai rekam duluan!")
                    posts.forEachIndexed { i, p -> key(p.act.id) { PostCard(p, i) } }
                }
            }
        }
    }
}

@Composable
fun PostCard(p: RAct, i: Int) {
    val me = Cloud.uid
    var liked by remember(p.act.id) { mutableStateOf(me != null && me in p.kudos) }
    var count by remember(p.act.id) { mutableIntStateOf(p.kudos.size) }
    val a = p.act; val bike = a.type == "Sepeda"
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(minOf(i, 6) * 60L); appear.animateTo(1f, tween(500, easing = EaseOutQuint)) }
    CardBox(Modifier.fillMaxWidth().graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 40f }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T(Fmt.initials(p.userName), 13, FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Column { T(if (p.uid == me) "Kamu" else p.userName, 15, FontWeight.SemiBold); T("${a.type} · ${Fmt.dateStr(a.start)}", 12, color = C.Mut) }
            }
            Column(Modifier.press { Nav.push(Scr.Remote(a.id)) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T(a.title, 17, FontWeight.Bold, spacing = -.3f)
                Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0A0A0B))) { MiniRoute(a.path, Modifier.fillMaxSize().padding(8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column { T("Jarak", 11, color = C.Mut); T("${Fmt.km(a.distance)} km", 16, FontWeight.Bold) }
                    Column { T(if (bike) "km/jam" else "Pace", 11, color = C.Mut); T(if (bike) Fmt.speed(a.distance, a.duration.toDouble()) else Fmt.pace(a.duration / maxOf(a.distance / 1000, .001)), 16, FontWeight.Bold) }
                    Column { T("Waktu", 11, color = C.Mut); T(Fmt.dur(a.duration), 16, FontWeight.Bold) }
                }
            }
            val bg by animateColorAsState(if (liked) C.Lime else C.Card2, label = "kb")
            val fg by animateColorAsState(if (liked) C.Ink else Color(0xFFD1D1D6), label = "kf")
            val pop = remember { Animatable(1f) }
            val scope = rememberCoroutineScope()
            Row(Modifier.height(38.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.press {
                if (me == null) return@press
                liked = !liked; count += if (liked) 1 else -1
                scope.launch { pop.snapTo(.85f); pop.animateTo(1f, spring(dampingRatio = .35f, stiffness = 500f)) }
                Ui.scope.launch { try { Cloud.kudos(a.id, liked) } catch (e: Exception) { Bus.toast("Gagal kasih kudos") } }
            }.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                T("$count kudos", 13, FontWeight.SemiBold, fg)
            }
        }
    }
}

fun addFriendSheet() {
    Sheet.open {
        var u by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        SheetTitle("Tambah teman", "Cari pakai username temanmu.")
        Field(u, { u = it.lowercase().trim().removePrefix("@") }, "username")
        if (msg.isNotEmpty()) T(msg, 13, color = C.Mut2)
        Btn(if (busy) "Mencari…" else "Ikuti") {
            if (u.isBlank() || busy) return@Btn
            busy = true
            Ui.scope.launch {
                val e = try { Cloud.follow(u) } catch (ex: Exception) { Cloud.errMsg(ex) }
                busy = false
                if (e == null) { Sheet.close(); Sfx.play(Sfx.SUCCESS); Bus.toast("Sekarang ngikutin @$u") } else msg = e
            }
        }
        Cloud.user.value?.username?.takeIf { it.isNotEmpty() }?.let { T("Username kamu: @$it", 13, FontWeight.SemiBold, C.Lime) }
    }
}

/* ============================ RANKING ============================ */
object RankCache { var rows: Map<String, List<Pair<String, Double>>> = emptyMap() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingScreen() {
    val me by Cloud.user.collectAsState()
    val online by Net.online.collectAsState()
    var tab by remember { mutableStateOf("Minggu") }
    var rows by remember(tab) { mutableStateOf(RankCache.rows[tab] ?: emptyList()) }
    var server by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var duel by remember { mutableStateOf<Map<String, Long>?>(null) }
    var names by remember { mutableStateOf<Map<String, Prof>>(emptyMap()) }
    Store.settingsVer.collectAsState().value
    val u = me
    DisposableEffect(u?.uid, u?.following?.size, tab, refreshKey) {
        if (u == null) return@DisposableEffect onDispose { }
        server = false; slow = false; err = null
        val ids = listOf(u.uid) + u.following
        val now = LocalDate.now()
        val field = if (tab == "Minggu") "week" else "month"
        val key = if (tab == "Minggu") Fmt.key(Fmt.monday(now)) else now.toString().substring(0, 7)
        val regs = Cloud.listenActs(ids, field, listOf(key)) { list, fromServer, e ->
            val sum = ids.associateWith { 0.0 }.toMutableMap()
            list.forEach { sum[it.uid] = (sum[it.uid] ?: 0.0) + it.act.distance }
            rows = sum.entries.sortedByDescending { it.value }.map { it.key to it.value }
            RankCache.rows = RankCache.rows + (tab to rows)
            if (fromServer) { server = true; refreshing = false }
            if (e != null) { err = e; refreshing = false }
        }
        val job = Ui.scope.launch { delay(15000); if (!server) { slow = true; refreshing = false } }
        Ui.scope.launch {
            try { names = Cloud.profiles(ids) } catch (_: Exception) { }
            val opp = Store.duelWith
            duel = if (opp != null && opp in u.following) try { Cloud.sync(false); Cloud.weekSteps(listOf(u.uid, opp)) } catch (_: Exception) { null } else null
        }
        onDispose { regs.forEach { it.remove() }; job.cancel() }
    }
    val state = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize()) {
        Glow(Modifier.align(Alignment.TopEnd).offset(x = 160.dp, y = 60.dp).size(400.dp, 340.dp))
        PullToRefreshBox(refreshing, { refreshing = true; refreshKey++ }, Modifier.fillMaxSize(), state = state, indicator = {
            PullToRefreshDefaults.Indicator(state = state, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                containerColor = C.Card2, color = C.Lime)
        }) {
            ScreenCol {
                T("Ranking", 34, FontWeight.ExtraBold, spacing = -1.2f)
                if (u == null) { InfoCard(Icons.Rounded.EmojiEvents, C.Lime, "Masuk dulu buat ikut ranking & duel langkah mingguan bareng teman.", "Masuk / Daftar") { Nav.push(Scr.Auth) }; return@ScreenCol }
                Seg(listOf("Minggu", "Bulan"), tab) { tab = it }
                LoadingBar(!server && err == null && online)
                if (!server && err == null) StatusLine(if (!online) "Offline · nampilin data terakhir" else "Memuat ranking…")
                // duel
                val opp = Store.duelWith?.takeIf { it in u.following }
                CardBox(Modifier.fillMaxWidth()) {
                    Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF1A2208), C.Card))))
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val left = 7 - LocalDate.now().dayOfWeek.value
                        Row { T("Duel langkah minggu ini", 17, FontWeight.Bold, modifier = Modifier.weight(1f)); if (opp != null) T(if (left > 0) "Sisa $left hari" else "Hari terakhir!", 12, color = C.Mut2) }
                        if (opp == null) {
                            T("Tantang satu teman, siapa paling banyak langkah sampai Minggu.", 14, color = C.Mut2)
                            Btn("Pilih lawan", height = 44.dp) { if (u.following.isEmpty()) Bus.toast("Tambah teman dulu di tab Feed") else pickDuelSheet(u.following) }
                        } else {
                            val d = duel
                            if (d == null) Skeleton(60) else {
                                val mine = d[u.uid] ?: 0L; val theirs = d[opp] ?: 0L; val mx = maxOf(mine, theirs, 1L)
                                DuelBar("Kamu", mine, mine.toFloat() / mx, C.Lime, 300)
                                DuelBar(names[opp]?.name ?: "Teman", theirs, theirs.toFloat() / mx, Color(0xFFE5E5EA), 400)
                                T(if (mine >= theirs) "Kamu unggul ${Fmt.num(mine - theirs)} langkah. Pertahankan!" else "Ketinggalan ${Fmt.num(theirs - mine)} langkah. Ayo jalan!", 13, color = C.Mut2)
                            }
                            Btn("Ganti lawan", BK.Dark, height = 44.dp) { pickDuelSheet(u.following) }
                        }
                    }
                }
                if (slow && !server) InfoCard(Icons.Rounded.WifiOff, C.Yellow, "Koneksi lambat. Data terakhir tetap ditampilkan.", "Coba lagi") { refreshKey++ }
                if (err != null) InfoCard(Icons.Rounded.ErrorOutline, C.Red, err!!, "Coba lagi") { refreshKey++ }
                if (rows.isEmpty() && !server) Skeleton(260)
                rows.forEachIndexed { i, (uid, d) ->
                    val isMe = uid == u.uid
                    Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(C.Card)
                        .border(if (isMe) 1.5.dp else 1.dp, if (isMe) C.Lime else C.Line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        T("${i + 1}", 15, FontWeight.ExtraBold, if (i < 3) C.Lime else C.Mut, Modifier.width(24.dp))
                        Box(Modifier.size(36.dp).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) { T(Fmt.initials(if (isMe) u.name else names[uid]?.name ?: "?"), 12, FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        T(if (isMe) "Kamu" else names[uid]?.name ?: "…", 15, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        T("${Fmt.km(d, 1)} km", 15, FontWeight.Bold)
                    }
                }
                if (server && u.following.isEmpty()) InfoCard(Icons.Rounded.Groups, C.Lime, "Ranking makin seru kalau ada teman. Tambah lewat tab Feed.")
            }
        }
    }
}

@Composable
private fun DuelBar(name: String, v: Long, frac: Float, col: Color, delayMs: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(frac) { delay(delayMs.toLong()); a.animateTo(frac, tween(1100, easing = EaseOutQuint)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { T(name, 13, FontWeight.SemiBold, modifier = Modifier.weight(1f)); T(Fmt.num(v), 13, FontWeight.Bold) }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF232326))) {
            Box(Modifier.fillMaxWidth(a.value).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(col))
        }
    }
}

fun pickDuelSheet(ids: List<String>) {
    Sheet.open {
        var list by remember { mutableStateOf<List<Prof>?>(null) }
        LaunchedEffect(Unit) { list = try { Cloud.profiles(ids).values.toList() } catch (_: Exception) { emptyList() } }
        SheetTitle("Pilih lawan duel")
        val l = list
        if (l == null) Skeleton(54) else l.forEach { p ->
            Row(Modifier.fillMaxWidth().height(54.dp).press { Store.duelWith = p.uid; Sheet.close() }.clip(RoundedCornerShape(18.dp)).background(C.Card3).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF3A3A3E)), contentAlignment = Alignment.Center) { T(Fmt.initials(p.name), 12, FontWeight.Bold) }
                Spacer(Modifier.width(12.dp)); T(p.name, 15, FontWeight.SemiBold); Spacer(Modifier.width(6.dp)); T("@${p.username}", 14, color = C.Mut)
            }
        }
    }
}
