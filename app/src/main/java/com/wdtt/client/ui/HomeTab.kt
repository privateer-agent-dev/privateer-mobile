package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Privateer: простой потребительский экран. Два действия — добавить подписку и подключиться.
// Вся мощь qWDTT (профили, деплой, логи) прячется за тумблером «Режим разработчика».
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
    var subUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val hasProfile = profiles.isNotEmpty()
    val accent = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Privateer", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (running) "Подключено" else "Отключено",
            color = accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (running) {
            Spacer(Modifier.height(4.dp))
            Text(stats, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = {
                if (running) {
                    MainActivity.currentActivity?.disconnectTunnel()
                } else if (!hasProfile) {
                    showAddDialog = true
                } else {
                    scope.launch {
                        // применяем профиль, если ещё не выбран
                        if (currentProfileId.isEmpty() || profiles.none { it.id == currentProfileId }) {
                            profilesStore.applyProfile(context, profiles.first().id)
                        }
                        MainActivity.currentActivity?.connectTunnel()
                    }
                }
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.size(200.dp)
        ) {
            Text(
                text = if (running) "Отключить" else "Подключить",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(40.dp))

        OutlinedButton(
            onClick = {
                subUrl = clipboardTextOrEmpty(context)
                showAddDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasProfile) "Обновить подписку" else "Добавить подписку")
        }

        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Режим разработчика", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(
                checked = false,
                onCheckedChange = { scope.launch { settingsStore.saveDeveloperMode(it) } }
            )
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showAddDialog = false },
            title = { Text("Подписка Privateer") },
            text = {
                Column {
                    Text("Вставьте ссылку на подписку из бота.", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…") }
                    )
                    if (busy) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && subUrl.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val res = profilesStore.addSubscription(subUrl.trim())
                            if (res.isSuccess) {
                                profilesStore.profiles.first().firstOrNull()?.let {
                                    profilesStore.applyProfile(context, it.id)
                                }
                                busy = false
                                showAddDialog = false
                                subUrl = ""
                                Toast.makeText(context, "Подписка добавлена", Toast.LENGTH_SHORT).show()
                            } else {
                                busy = false
                                Toast.makeText(
                                    context,
                                    res.exceptionOrNull()?.message ?: "Не удалось добавить подписку",
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

private fun clipboardTextOrEmpty(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("qwdtt:") || text.startsWith("privateer:")) text else ""
    } catch (_: Exception) {
        ""
    }
}
