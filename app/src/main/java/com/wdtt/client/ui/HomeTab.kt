package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.MainActivity
import com.wdtt.client.PingHelper
import com.wdtt.client.ProfileSubscription
import com.wdtt.client.ProfilesStore
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Палитра «корсар в космосе»: фиолетово-магента сфера с тёплыми искрами на пустоте
private const val TWO_PI = 6.2831855f
private val BG = Color(0xFF0A0612)        // void — near-black violet
private val CARD = Color(0xFF16101F)
private val TXT = Color(0xFFEDE8F5)
private val TXT_DIM = Color(0xFF8B84A0)
private val ACCENT = Color(0xFFC96BE6)     // magenta-violet (защищено)
private val P_PINK = Color(0xFFE86AD0)     // bright core
private val P_MAGENTA = Color(0xFFB24BE0)
private val P_ROYAL = Color(0xFF6A34B0)
private val P_DEEP = Color(0xFF2E1758)     // edge shard
private val P_EMBER = Color(0xFFFF8A63)    // warm spark accent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrivateerHome(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profilesStore = remember { ProfilesStore(context) }

    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val stats by TunnelManager.stats.collectAsStateWithLifecycle()
    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList<ConnectionProfile>())
    val subs by profilesStore.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList<ProfileSubscription>())
    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")

    var showAddDialog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    var subInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

    LaunchedEffect(running) { if (running) connecting = false }
    LaunchedEffect(connecting) {
        if (connecting) { delay(25000); if (!TunnelManager.running.value) connecting = false }
    }

    val pendingImport = MainActivity.pendingImportText.value
    LaunchedEffect(pendingImport) {
        val input = pendingImport ?: return@LaunchedEffect
        MainActivity.pendingImportText.value = null
        val res = importSubscription(profilesStore, context, input)
        Toast.makeText(
            context,
            if (res.isSuccess) "Подписка добавлена" else (res.exceptionOrNull()?.message ?: "Ошибка импорта"),
            Toast.LENGTH_LONG
        ).show()
    }

    fun ping(p: ConnectionProfile) {
        if (PingHelper.pingingState[p.id] == true) return
        PingHelper.pingingState[p.id] = true
        scope.launch {
            val r = PingHelper.measurePing(context, p)
            PingHelper.pingResults[p.id] = r
            PingHelper.pingingState[p.id] = false
        }
    }

    // авто-пинг при раскрытии списка (один раз для непроверенных)
    LaunchedEffect(expanded, profiles.size) {
        if (expanded) profiles.forEach { if (PingHelper.pingResults[it.id] == null) ping(it) }
    }

    val hasProfile = profiles.isNotEmpty()
    val subName = subs.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Privateer"
    val statusText = when {
        running -> "Защищено"
        connecting -> "Подключение"
        else -> "Отключено"
    }
    val statusColor by animateColorAsState(
        targetValue = if (running) ACCENT else if (connecting) P_MAGENTA else TXT_DIM,
        animationSpec = tween(400), label = "status"
    )
    val trafficMb = remember(stats) { parseTrafficMb(stats) }

    fun connectSelected(profileId: String?) {
        scope.launch {
            val id = profileId ?: profiles.firstOrNull()?.id ?: return@launch
            profilesStore.applyProfile(context, id)
            if (running) { MainActivity.currentActivity?.disconnectTunnel(); delay(600) }
            connecting = true
            MainActivity.currentActivity?.connectTunnel()
        }
    }

    Box(Modifier.fillMaxSize().background(BG)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                "P R I V A T E E R",
                color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showDiag = true })
            )
            Spacer(Modifier.height(30.dp))

            ConnectOrb(
                running = running,
                connecting = connecting,
                onClick = {
                    if (running) { MainActivity.currentActivity?.disconnectTunnel(); connecting = false }
                    else if (!hasProfile) { subInput = clipboardTextOrEmpty(context); showAddDialog = true }
                    else connectSelected(currentProfileId.takeIf { it.isNotEmpty() })
                }
            )

            Spacer(Modifier.height(18.dp))
            Text(statusText, color = statusColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            if (running && trafficMb != null) {
                Spacer(Modifier.height(6.dp))
                Text("↑↓  $trafficMb МБ", color = TXT_DIM, fontSize = 13.sp)
            }

            Spacer(Modifier.height(34.dp))

            // ── Выпадающий список серверов подписки (как в Happ) ──
            if (hasProfile) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subName, color = TXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(8.dp))
                        Text("${profiles.size}", color = TXT_DIM, fontSize = 13.sp)
                    }
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null, tint = TXT_DIM
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    profiles.forEach { p ->
                        ServerRow(
                            name = p.name.ifBlank { "Сервер" },
                            selected = p.id == currentProfileId,
                            pinging = PingHelper.pingingState[p.id] == true,
                            pingMs = PingHelper.pingResults[p.id],
                            onPing = { ping(p) },
                            onClick = { connectSelected(p.id) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Text(
                text = if (hasProfile) "Добавить / обновить подписку" else "Добавить подписку",
                color = TXT, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF26262E), RoundedCornerShape(14.dp))
                    .clickable { subInput = clipboardTextOrEmpty(context); showAddDialog = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            containerColor = CARD,
            onDismissRequest = { if (!busy) showAddDialog = false },
            title = { Text("Подписка Privateer", color = TXT) },
            text = {
                Column {
                    Text("Вставьте ссылку из бота.", color = TXT_DIM, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = subInput, onValueChange = { subInput = it }, singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…  или  privateer://…", color = TXT_DIM) }
                    )
                    if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(color = P_MAGENTA) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && subInput.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val res = importSubscription(profilesStore, context, subInput)
                            busy = false
                            if (res.isSuccess) {
                                showAddDialog = false; subInput = ""
                                Toast.makeText(context, "Готово", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, res.exceptionOrNull()?.message ?: "Не удалось", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Добавить", color = P_MAGENTA) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) showAddDialog = false }) { Text("Отмена", color = TXT_DIM) } }
        )
    }

    if (showDiag) DiagnosticsDialog(stats = stats, onDismiss = { showDiag = false })
}

@Composable
private fun ConnectOrb(running: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val shards = remember { buildShards() }
    val active = running || connecting
    // главный параметр: 0 = сфера разбита (отключено), 1 = собрана (защищено).
    // Собирается при подключении, разлетается при отключении — сигнатурный момент.
    val a by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(1150, easing = FastOutSlowInEasing),
        label = "assemble"
    )
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(26000, easing = LinearEasing)), label = "spin")
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "glow")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(288.dp).clickable(onClick = onClick)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = size.minDimension / 2f
            val rBase = outer * 0.58f
            val scatter = 1f - a

            // свечение ядра (ярче когда сфера цела)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        P_PINK.copy(alpha = 0.30f * a * glow),
                        P_MAGENTA.copy(alpha = 0.16f * a),
                        Color.Transparent
                    ),
                    center = c, radius = outer
                ),
                radius = outer, center = c
            )

            rotate(spin, pivot = c) {
                for (sh in shards) {
                    val ang = sh.rot * scatter
                    val cosA = kotlin.math.cos(ang)
                    val sinA = kotlin.math.sin(ang)
                    val off = sh.ex * scatter * 0.9f
                    val path = Path()
                    sh.verts.forEachIndexed { i, v ->
                        val relX = v.x - sh.ctr.x
                        val relY = v.y - sh.ctr.y
                        val rrx = relX * cosA - relY * sinA
                        val rry = relX * sinA + relY * cosA
                        val ox = sh.ctr.x + rrx + sh.dir.x * off
                        val oy = sh.ctr.y + rry + sh.dir.y * off
                        val sx = c.x + ox * rBase
                        val sy = c.y + oy * rBase
                        if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                    }
                    path.close()
                    val fillA = 0.5f + 0.5f * a           // разбитые осколки чуть тусклее
                    drawPath(path, sh.color.copy(alpha = fillA))
                    drawPath(path, Color.Black.copy(alpha = 0.28f), style = Stroke(width = 1f)) // грани фасетов
                }
            }

            // блик-сфера (сверху-слева) — читается как объём, только когда собрана
            if (a > 0.05f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.16f * a), Color.Transparent),
                        center = Offset(c.x - rBase * 0.35f, c.y - rBase * 0.42f),
                        radius = rBase * 0.95f
                    ),
                    radius = rBase, center = c
                )
            }
        }
    }
}

private class Shard(
    val verts: List<Offset>,
    val ctr: Offset,
    val dir: Offset,
    val rot: Float,
    val ex: Float,
    val color: Color
)

private fun polar(r: Float, a: Float) = Offset(r * kotlin.math.cos(a), r * kotlin.math.sin(a))

private fun colorForRadius(r: Float): Color = when {
    r < 0.14f -> lerp(P_EMBER, P_PINK, r / 0.14f)          // тёплое ядро — искра
    r < 0.38f -> lerp(P_PINK, P_MAGENTA, (r - 0.14f) / 0.24f)
    r < 0.68f -> lerp(P_MAGENTA, P_ROYAL, (r - 0.38f) / 0.30f)
    else -> lerp(P_ROYAL, P_DEEP, (r - 0.68f) / 0.32f)
}

// Тесселируем диск на кольца×сегменты → фасеты сферы. Каждый осколок знает свой
// «дом» и направление разлёта — из этого строится и сборка, и взрыв.
private fun buildShards(): List<Shard> {
    val rnd = kotlin.random.Random(7)
    val out = ArrayList<Shard>()
    val rings = 5
    for (ri in 0 until rings) {
        val r0 = ri / rings.toFloat()
        val r1 = (ri + 1) / rings.toFloat()
        val segs = 5 + ri * 3
        val aOff = ri * 0.35f
        for (si in 0 until segs) {
            val a0 = si.toFloat() / segs * TWO_PI + aOff
            val a1 = (si + 1).toFloat() / segs * TWO_PI + aOff
            val verts = if (ri == 0)
                listOf(Offset(0f, 0f), polar(r1, a0), polar(r1, a1))
            else
                listOf(polar(r0, a0), polar(r1, a0), polar(r1, a1), polar(r0, a1))
            var cx = 0f; var cy = 0f
            for (v in verts) { cx += v.x; cy += v.y }
            val ctr = Offset(cx / verts.size, cy / verts.size)
            val mag = kotlin.math.hypot(ctr.x, ctr.y)
            val amid = (a0 + a1) / 2f
            val dir = if (mag < 0.02f) Offset(kotlin.math.cos(amid), kotlin.math.sin(amid))
            else Offset(ctr.x / mag, ctr.y / mag)
            val rot = (rnd.nextFloat() - 0.5f) * 2.4f
            val ex = 0.35f + rnd.nextFloat() * 0.7f
            val base = colorForRadius((r0 + r1) / 2f)
            val j = 0.85f + rnd.nextFloat() * 0.3f
            val col = Color(
                (base.red * j).coerceIn(0f, 1f),
                (base.green * j).coerceIn(0f, 1f),
                (base.blue * j).coerceIn(0f, 1f),
                1f
            )
            out.add(Shard(verts, ctr, dir, rot, ex, col))
        }
    }
    return out
}

@Composable
private fun ServerRow(
    name: String,
    selected: Boolean,
    pinging: Boolean,
    pingMs: Long?,
    onPing: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD)
            .border(1.dp, if (selected) ACCENT else Color(0xFF1E1E26), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = P_MAGENTA, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Text(name, color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PingBadge(pinging = pinging, pingMs = pingMs, onPing = onPing)
            if (selected) {
                Spacer(Modifier.size(10.dp))
                Icon(Icons.Filled.CheckCircle, contentDescription = "выбран", tint = ACCENT, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PingBadge(pinging: Boolean, pingMs: Long?, onPing: () -> Unit) {
    when {
        pinging -> CircularProgressIndicator(color = P_MAGENTA, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        pingMs == null -> Icon(
            Icons.Filled.Refresh, contentDescription = "проверить пинг", tint = TXT_DIM,
            modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onPing)
        )
        pingMs < 0 -> Text(
            "нет", color = Color(0xFFFF6B6B), fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onPing)
        )
        else -> Text(
            "$pingMs ms",
            color = when {
                pingMs < 150 -> Color(0xFF4FD1A5)
                pingMs < 300 -> Color(0xFFE3C15A)
                else -> Color(0xFFFF6B6B)
            },
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onPing)
        )
    }
}

@Composable
private fun DiagnosticsDialog(stats: String, onDismiss: () -> Unit) {
    val workers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val logs by TunnelManager.logs.collectAsStateWithLifecycle()
    AlertDialog(
        containerColor = CARD,
        onDismissRequest = onDismiss,
        title = { Text("Диагностика", color = TXT) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Активных воркеров: $workers", color = TXT, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(stats, color = TXT_DIM, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text("ЛОГИ", color = TXT_DIM, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                logs.takeLast(60).forEach { e ->
                    Text(
                        (if (e.count > 1) "(${e.count}) " else "") + e.message,
                        color = if (e.isError) Color(0xFFFF6B6B) else TXT_DIM, fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = P_MAGENTA) } }
    )
}

private suspend fun importSubscription(store: ProfilesStore, context: Context, rawInput: String): Result<Int> {
    val input = rawInput.trim()
    if (input.isEmpty()) return Result.failure(IllegalArgumentException("Пустая ссылка"))
    val isHttp = input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)
    val res = if (isHttp) store.addSubscription(input).map { 1 } else store.addFromText(input)
    if (res.isSuccess) {
        store.profiles.first().firstOrNull()?.let { store.applyProfile(context, it.id) }
    }
    return res
}

private fun parseTrafficMb(stats: String): String? {
    val m = Regex("Трафик:\\s*([\\d.,]+)").find(stats) ?: return null
    return m.groupValues.getOrNull(1)
}

private fun clipboardTextOrEmpty(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        if (text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("qwdtt:") || text.startsWith("privateer:")
        ) text else ""
    } catch (_: Exception) {
        ""
    }
}
