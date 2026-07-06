package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.MainActivity
import com.wdtt.client.ProfilesStore
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Privateer: единственный экран. Тёмный минимализм — чёрный орб с неон-кольцом,
// имя PRIVATEER, список серверов, добавление подписки. Диагностика — лонг-пресс по имени.
private val BG = Color(0xFF070709)
private val CARD = Color(0xFF101014)
private val TXT = Color(0xFFEDEDF2)
private val TXT_DIM = Color(0xFF8A8A96)
private val NEON = listOf(
    Color(0xFF7A5CFF), Color(0xFF2E9BFF), Color(0xFF25E6C8), Color(0xFF7A5CFF)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrivateerHome(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profilesStore = remember { ProfilesStore(context) }

    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val stats by TunnelManager.stats.collectAsStateWithLifecycle()
    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList<ConnectionProfile>())
    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")

    var showAddDialog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var subInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

    LaunchedEffect(running) { if (running) connecting = false }
    LaunchedEffect(connecting) {
        if (connecting) { delay(25000); if (!TunnelManager.running.value) connecting = false }
    }

    // импорт по deep-link (privateer:// / qwdtt://)
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

    val hasProfile = profiles.isNotEmpty()
    val statusText = when {
        running -> "Защищено"
        connecting -> "Подключение"
        else -> "Отключено"
    }
    val statusColor by animateColorAsState(
        targetValue = if (running) Color(0xFF25E6C8) else if (connecting) Color(0xFF7A5CFF) else TXT_DIM,
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
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            // имя — лонг-пресс открывает диагностику
            Text(
                "P R I V A T E E R",
                color = TXT,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showDiag = true }
                )
            )
            Spacer(Modifier.height(40.dp))

            ConnectOrb(
                running = running,
                connecting = connecting,
                onClick = {
                    if (running) { MainActivity.currentActivity?.disconnectTunnel(); connecting = false }
                    else if (!hasProfile) { subInput = clipboardTextOrEmpty(context); showAddDialog = true }
                    else connectSelected(currentProfileId.takeIf { it.isNotEmpty() })
                }
            )

            Spacer(Modifier.height(28.dp))
            Text(statusText, color = statusColor, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            if (running && trafficMb != null) {
                Spacer(Modifier.height(6.dp))
                Text("↑↓  $trafficMb МБ", color = TXT_DIM, fontSize = 13.sp)
            }

            Spacer(Modifier.height(40.dp))

            if (hasProfile) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("СЕРВЕРЫ", color = TXT_DIM, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text("${profiles.size}", color = TXT_DIM, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                profiles.forEach { p ->
                    ServerRow(
                        name = p.name.ifBlank { "Сервер" },
                        selected = p.id == currentProfileId,
                        onClick = { connectSelected(p.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = if (hasProfile) "Добавить / обновить подписку" else "Добавить подписку",
                color = TXT,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF26262E), RoundedCornerShape(14.dp))
                    .clickable { subInput = clipboardTextOrEmpty(context); showAddDialog = true }
                    .padding(vertical = 16.dp),
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
                        value = subInput,
                        onValueChange = { subInput = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…  или  privateer://…", color = TXT_DIM) }
                    )
                    if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(color = Color(0xFF2E9BFF)) }
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
                ) { Text("Добавить", color = Color(0xFF2E9BFF)) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) showAddDialog = false }) { Text("Отмена", color = TXT_DIM) } }
        )
    }

    if (showDiag) DiagnosticsDialog(stats = stats, onDismiss = { showDiag = false })
}

@Composable
private fun ConnectOrb(running: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val active = running || connecting
    val t = rememberInfiniteTransition(label = "orb")
    val angle by t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "rot"
    )
    val glow by t.animateFloat(
        0.30f, 0.65f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "glow"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(240.dp).clip(CircleShape).clickable(onClick = onClick)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = size.minDimension / 2f
            val ringR = outer * 0.72f
            // мягкое свечение
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(NEON[1].copy(alpha = if (active) glow else 0.12f), Color.Transparent),
                    center = c, radius = outer
                ),
                radius = outer, center = c
            )
            // неон-кольцо (вращается когда активно)
            rotate(if (active) angle else 0f, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(NEON, center = c),
                    radius = ringR, center = c,
                    style = Stroke(width = 5.dp.toPx())
                )
            }
            // чёрный диск
            drawCircle(Color(0xFF050506), radius = ringR - 5.dp.toPx(), center = c)
        }
        Icon(
            Icons.Filled.PowerSettingsNew,
            contentDescription = if (running) "отключить" else "подключить",
            tint = if (active) Color(0xFF25E6C8) else TXT.copy(alpha = 0.65f),
            modifier = Modifier.size(46.dp)
        )
    }
}

@Composable
private fun ServerRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD)
            .border(1.dp, if (selected) Color(0xFF25E6C8) else Color(0xFF1E1E26), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = Color(0xFF2E9BFF), modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Text(name, color = TXT, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "выбран", tint = Color(0xFF25E6C8), modifier = Modifier.size(20.dp))
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
                        color = if (e.isError) Color(0xFFFF6B6B) else TXT_DIM,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = Color(0xFF2E9BFF)) } }
    )
}

// Единая точка импорта: http(s) → addSubscription (запоминает URL для автообновления),
// иначе (qwdtt://config / JSON / base64) → addFromText. Затем применяет первый сервер.
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
