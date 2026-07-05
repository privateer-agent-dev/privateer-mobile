package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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

// Privateer: единственный потребительский экран — статус, кнопка подключения,
// список серверов и добавление подписки. Никаких вкладок/режимов.
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
    var subInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

    // как только туннель поднялся — снимаем состояние "подключение…"
    LaunchedEffect(running) { if (running) connecting = false }
    // страховка: не залипать в "подключение…" если коннект не удался
    LaunchedEffect(connecting) {
        if (connecting) {
            delay(25000)
            if (!TunnelManager.running.value) connecting = false
        }
    }

    // импорт по deep-link (privateer:// / qwdtt://) — тап по ссылке из бота
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
        running -> "Подключено"
        connecting -> "Подключение…"
        else -> "Отключено"
    }
    val accent by animateColorAsState(
        targetValue = if (running) Color(0xFF2E7D32) else if (connecting) Color(0xFFF9A825) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(400), label = "accent"
    )

    fun connectSelected(profileId: String?) {
        scope.launch {
            val id = profileId ?: profiles.firstOrNull()?.id ?: return@launch
            profilesStore.applyProfile(context, id)
            if (running) {
                // переключение сервера — переподключаемся
                MainActivity.currentActivity?.disconnectTunnel()
                delay(600)
            }
            connecting = true
            MainActivity.currentActivity?.connectTunnel()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Privateer", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        // ── Кнопка подключения с пульсацией ──
        ConnectButton(
            running = running,
            connecting = connecting,
            accent = accent,
            onClick = {
                if (running) {
                    MainActivity.currentActivity?.disconnectTunnel()
                    connecting = false
                } else if (!hasProfile) {
                    subInput = clipboardTextOrEmpty(context)
                    showAddDialog = true
                } else {
                    connectSelected(currentProfileId.takeIf { it.isNotEmpty() })
                }
            }
        )

        Spacer(Modifier.height(16.dp))
        Text(statusText, color = accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (running && stats.isNotBlank() && stats != "Ожидание данных...") {
            Spacer(Modifier.height(6.dp))
            Text(
                stats,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(28.dp))

        // ── Список серверов ──
        if (hasProfile) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Серверы", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${profiles.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            profiles.forEach { p ->
                ServerRow(
                    name = p.name.ifBlank { "Сервер" },
                    selected = p.id == currentProfileId,
                    accent = accent,
                    onClick = { connectSelected(p.id) }
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = {
                subInput = clipboardTextOrEmpty(context)
                showAddDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasProfile) "Добавить / обновить подписку" else "Добавить подписку")
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showAddDialog = false },
            title = { Text("Подписка Privateer") },
            text = {
                Column {
                    Text("Вставьте ссылку из бота (подписку или конфиг).", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = subInput,
                        onValueChange = { subInput = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…  или  qwdtt://…") }
                    )
                    if (busy) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
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
                                showAddDialog = false
                                subInput = ""
                                Toast.makeText(context, "Готово", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    res.exceptionOrNull()?.message ?: "Не удалось добавить",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showAddDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ConnectButton(
    running: Boolean,
    connecting: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (running || connecting) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulseScale"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(230.dp)) {
        // внешнее кольцо-пульс
        Box(
            modifier = Modifier
                .size(210.dp)
                .scale(if (running || connecting) pulse else 1f)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f))
        )
        // основная кнопка
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(onClick = onClick)
        ) {
            Text(
                text = if (running) "Отключить" else if (connecting) "…" else "Подключить",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ServerRow(
    name: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val border = if (selected) accent else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "выбран", tint = accent, modifier = Modifier.size(20.dp))
        }
    }
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
