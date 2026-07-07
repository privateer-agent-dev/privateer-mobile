package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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

private val BG = Color(0xFF060608)
private val CARD = Color(0xFF101014)
private val TXT = Color(0xFFEDEDF2)
private val TXT_DIM = Color(0xFF8A8A96)
private val ACCENT = Color(0xFF32D6C8)
// свечение орба (морской бирюзово-синий градиент, как в референсе)
private val GLOW_TEAL = Color(0xFF19D6C6)
private val GLOW_BLUE = Color(0xFF1E86C4)
private val GLOW_DEEP = Color(0xFF0B1E36)

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
        targetValue = if (running) ACCENT else if (connecting) GLOW_BLUE else TXT_DIM,
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
                statusText = statusText,
                statusColor = statusColor,
                onClick = {
                    if (running) { MainActivity.currentActivity?.disconnectTunnel(); connecting = false }
                    else if (!hasProfile) { subInput = clipboardTextOrEmpty(context); showAddDialog = true }
                    else connectSelected(currentProfileId.takeIf { it.isNotEmpty() })
                }
            )

            if (running && trafficMb != null) {
                Spacer(Modifier.height(14.dp))
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
                    if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(color = GLOW_BLUE) }
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
                ) { Text("Добавить", color = GLOW_BLUE) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) showAddDialog = false }) { Text("Отмена", color = TXT_DIM) } }
        )
    }

    if (showDiag) DiagnosticsDialog(stats = stats, onDismiss = { showDiag = false })
}

@Composable
private fun ConnectOrb(
    running: Boolean,
    connecting: Boolean,
    statusText: String,
    statusColor: Color,
    onClick: () -> Unit
) {
    val active = running || connecting
    val t = rememberInfiniteTransition(label = "orb")
    val shimmer by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "shimmer")
    val pulse by t.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "pulse")
    val dot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "dot")
    val intensity by animateFloatAsState(if (active) 1f else 0.5f, tween(900), label = "intensity")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(260.dp).clip(CircleShape).clickable(onClick = onClick)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = size.minDimension / 2f
            // смещаем центр свечения по кругу → «перелив» света
            val rad = Math.toRadians(pulse.toDouble())
            val gc = Offset(
                c.x + (outer * 0.12f) * kotlin.math.cos(rad).toFloat(),
                c.y + (outer * 0.12f) * kotlin.math.sin(rad).toFloat()
            )
            // основное свечение-сфера
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        GLOW_TEAL.copy(alpha = 0.95f * intensity),
                        GLOW_BLUE.copy(alpha = 0.70f * intensity),
                        GLOW_DEEP.copy(alpha = 0.92f),
                        Color.Transparent
                    ),
                    center = gc, radius = outer
                ),
                radius = outer, center = c
            )
            // мерцающий перелив (медленно вращается)
            rotate(shimmer, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(GLOW_TEAL, GLOW_BLUE, Color(0xFF3A6FB0), GLOW_TEAL), center = c
                    ),
                    radius = outer * 0.9f, center = c, alpha = 0.14f * intensity
                )
            }
            // тонкое кольцо + бегущая точка (как в референсе)
            drawCircle(Color.White.copy(alpha = 0.22f), radius = outer * 0.97f, center = c, style = Stroke(width = 1.5.dp.toPx()))
            if (active) {
                val da = Math.toRadians(dot.toDouble())
                val dp = Offset(c.x + (outer * 0.97f) * kotlin.math.cos(da).toFloat(), c.y + (outer * 0.97f) * kotlin.math.sin(da).toFloat())
                drawCircle(Color.White, radius = 5.dp.toPx(), center = dp)
            }
            // внутренняя «кнопка»
            drawCircle(Color.White.copy(alpha = 0.05f), radius = outer * 0.44f, center = c)
            drawCircle(Color.White.copy(alpha = 0.14f), radius = outer * 0.44f, center = c, style = Stroke(width = 1.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (running) "ОТКЛЮЧИТЬ" else if (connecting) "…" else "ПОДКЛЮЧИТЬ",
                color = TXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(statusText, color = statusColor, fontSize = 12.sp)
        }
    }
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
            Icon(Icons.Filled.Public, contentDescription = null, tint = GLOW_BLUE, modifier = Modifier.size(20.dp))
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
        pinging -> CircularProgressIndicator(color = GLOW_BLUE, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = GLOW_BLUE) } }
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
