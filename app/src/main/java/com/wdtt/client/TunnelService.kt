package com.wdtt.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TUNNEL_NOTIFICATION_CHANNEL_ID = "wdtt_tunnel_v4"
private const val TUNNEL_NOTIFICATION_ID = 1

// Сколько ждать поднятия туннеля, пока пользователь его хочет, прежде чем
// честно остановиться. Перекрывает самый долгий путь: получение ключей VK,
// дозвон до TURN и рукопожатие DTLS с бэкоффом вотчдога.
private const val TUNNEL_DOWN_TIMEOUT_MS = 120_000L

// Сколько ждать, пока события смены сети перестанут сыпаться, и как часто
// максимум перезапускать транспорт.
private const val NETWORK_CHANGE_COALESCE_MS = 1_500L
private const val NETWORK_CHANGE_MIN_INTERVAL_MS = 5_000L

class TunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var lastNotificationText: String? = null
    
    // Network Monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeTime = 0L
    private var networkChangeJob: Job? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentDefaultNetwork: Network? = null
    private val activeNetworks = mutableSetOf<Network>()
    private var isTunnelPaused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Сразу берем лок при создании
        acquireWakeLock()
        setupNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            restoreTunnel()
            return START_STICKY
        }

        when (intent.action) {
            "START", "START_FORCED" -> {
                val notification = createNotification("Запуск...")
                startPersistentForeground(notification)

                val appContext = applicationContext
                TunnelManager.scope.launch {
                    try {
                        val store = SettingsStore(appContext)
                        val basePeer = intent.getStringExtra("peer")?.takeIf { it.isNotEmpty() } ?: store.peer.first()
                        val manualPortsEnabled = store.manualPortsEnabled.first()
                        val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                        val peerWithPort = if (basePeer.isBlank()) basePeer else PeerAddress.ensurePort(basePeer, serverDtlsPort)
                        
                        val params = TunnelParams(
                            peer = peerWithPort,
                            vkHashes = intent.getStringExtra("vk_hashes")?.takeIf { it.isNotEmpty() } ?: store.vkHashes.first(),
                            secondaryVkHash = intent.getStringExtra("secondary_vk_hash")?.takeIf { it.isNotEmpty() } ?: store.secondaryVkHash.first(),
                            workersPerHash = intent.getIntExtra("workers_per_hash", 0).takeIf { it > 0 } ?: store.workersPerHash.first(),
                            port = intent.getIntExtra("port", 0).takeIf { it > 0 } ?: store.listenPort.first(),
                            sni = intent.getStringExtra("sni")?.takeIf { it.isNotEmpty() } ?: store.sni.first(),
                            connectionPassword = intent.getStringExtra("connection_password")?.takeIf { it.isNotEmpty() } ?: store.connectionPassword.first(),
                            protocol = intent.getStringExtra("protocol")?.takeIf { it.isNotEmpty() } ?: store.protocol.first(),
                            captchaMode = sanitizeCaptchaMode(intent.getStringExtra("captcha_mode")?.takeIf { it.isNotEmpty() } ?: store.captchaMode.first()),
                            captchaSolveMethod = intent.getStringExtra("captcha_solve_method")?.takeIf { it.isNotEmpty() } ?: store.captchaSolveMethod.first(),
                            vkAuthMode = intent.getStringExtra("vk_auth_mode")?.takeIf { it.isNotEmpty() } ?: store.vkAuthMode.first(),
                            vkAnonPath = sanitizeVkAnonPath(intent.getStringExtra("vk_anon_path")?.takeIf { it.isNotEmpty() } ?: store.vkAnonPath.first()),
                            detailedLogs = store.detailedLogs.first()
                        )
                        launch(Dispatchers.Main) {
                            if (intent.action == "START_FORCED") {
                                TunnelManager.showBlockerWarning.value = false
                                startTunnel(params, forceStart = true)
                            } else {
                                startTunnel(params, forceStart = false)
                            }
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { stopTunnel() }
                    }
                }
            }
            "STOP" -> stopTunnel()
            "DEPLOY_START" -> {
                val notification = createNotification("Установка на сервер...", "DEPLOY_CANCEL", "Отменить")
                startPersistentForeground(notification)
                acquireWakeLock()
            }
            "DEPLOY_CANCEL" -> {
                com.wdtt.client.DeployManager.writeError("[!] ❌ Установка отменена пользователем")
                com.wdtt.client.DeployManager.stopDeploy("error: Отменена пользователем")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            "DEPLOY_STOP" -> {
                if (!TunnelManager.running.value) {
                    stopTunnel()
                } else {
                    updateNotification("Туннель активен")
                }
            }
        }
        return START_STICKY
    }

    private fun restoreTunnel() {
        val notification = createNotification("Восстановление соединения...")
        startPersistentForeground(notification)
        
        val appContext = applicationContext
        TunnelManager.scope.launch {
            try {
                val store = SettingsStore(appContext)
                val basePeer = store.peer.first()
                val manualPortsEnabled = store.manualPortsEnabled.first()
                val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                val peerWithPort = if (basePeer.isBlank()) basePeer else PeerAddress.ensurePort(basePeer, serverDtlsPort)
                val params = TunnelParams(
                    peer = peerWithPort,
                    vkHashes = store.vkHashes.first(),
                    secondaryVkHash = store.secondaryVkHash.first(),
                    workersPerHash = store.workersPerHash.first(),
                    port = store.listenPort.first(),
                    sni = store.sni.first(),
                    connectionPassword = store.connectionPassword.first(),
                    captchaMode = sanitizeCaptchaMode(store.captchaMode.first()),
                    captchaSolveMethod = store.captchaSolveMethod.first(),
                    vkAuthMode = store.vkAuthMode.first(),
                    vkAnonPath = sanitizeVkAnonPath(store.vkAnonPath.first()),
                    detailedLogs = store.detailedLogs.first()
                )
                if (params.peer.isNotEmpty() && params.vkHashes.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        startTunnel(params)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        stopTunnel()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    stopTunnel()
                }
            }
        }
    }

    private fun startTunnel(params: TunnelParams, forceStart: Boolean = false) {
        updateNotification("Подключение...")
        acquireWakeLock()
        acquireWifiLock()

        // Подготавливаем CaptchaWebViewManager (не создаёт WebView — просто сохраняет контекст)
        // Вызываем всегда — дёшево, а WebView создаётся на лету при каждом запросе капчи
        CaptchaWebViewManager.onTunnelStart(applicationContext)

        TunnelManager.start(this, params, forceStart)
        startStatsUpdater()
    }

    private fun stopTunnel() {
        updateJob?.cancel()

        // Уничтожаем текущий WebView (если капча решается) и чистим контекст
        CaptchaWebViewManager.onTunnelStop()

        TunnelManager.stop()
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(TUNNEL_NOTIFICATION_ID)
        TunnelWidgetProvider.updateWidgetState(applicationContext, false, "Нажмите для подключения")
        QuickToggleTileService.requestTileUpdate(applicationContext)
        stopSelf()
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeNetworks.clear()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                // Появление ЛЮБОЙ сети больше не перезапускает транспорт: телефон
                // на Wi-Fi постоянно гасит и поднимает мобильную сеть ради
                // батареи, и каждый такой цикл убивал туннель. Смену маршрута
                // ловит defaultNetworkCallback, здесь — только выход из паузы,
                // когда сетей не было вовсе.
                if (wasEmpty && isTunnelPaused) {
                    isTunnelPaused = false
                    Log.d("TunnelService", "Сеть появилась, возобновляем туннель")
                    TunnelManager.resume()
                    updateNotification("Подключение...")
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                // ВАЖНО: на потерю отдельной сети перезапускаться НЕЛЬЗЯ.
                // Телефон на Wi-Fi регулярно сам гасит и поднимает мобильную
                // сеть ради батареи — реакция на каждое такое событие убивала
                // туннель по кругу. Смену маршрута ловит defaultNetworkCallback.
                if (activeNetworks.isEmpty() && TunnelManager.running.value && !isTunnelPaused) {
                    isTunnelPaused = true
                    Log.d("TunnelService", "Сеть потеряна, приостанавливаем туннель")
                    TunnelManager.pause()
                    updateNotification("Ожидание сети (Фоновый сон)")
                }
            }
        }

        // ВАЖНО: Слушаем только реальные (не VPN) сети с доступом в интернет.
        // Иначе интерфейс VPN (tun0) считается активной сетью, и при "Режиме полёта" activeNetworks не падает до 0.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
            
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)

        // Отдельно следим за сетью ПО УМОЛЧАНИЮ — именно через неё уходит трафик
        // Go-процесса. Её смена (Wi-Fi → мобильная) и есть тот случай, когда
        // сокеты привязаны к умершему интерфейсу и нужен перезапуск.
        defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Когда туннель поднимается, сетью по умолчанию становится он сам.
                // Без этой проверки получился бы цикл: подключились → маршрут
                // сменился на tun → перезапуск → туннель упал → маршрут вернулся
                // на Wi-Fi → снова перезапуск.
                val caps = connectivityManager?.getNetworkCapabilities(network)
                if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return
                }

                val previous = currentDefaultNetwork
                currentDefaultNetwork = network
                if (previous != null && previous != network) {
                    Log.d("TunnelService", "Сменилась сеть по умолчанию, перезапуск транспорта")
                    handleNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // Новый маршрут придёт отдельным onAvailable — там и перезапустимся.
                if (currentDefaultNetwork == network) currentDefaultNetwork = null
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(defaultNetworkCallback!!)
    }
    
    private fun handleNetworkChange() {
        // Коалесинг вместо отбрасывания: при переключении сетей события сыплются
        // пачкой, и прежний `return` по таймеру просто терял последнее из них —
        // перезапуск не случался вовсе. Теперь откладываем и схлопываем.
        networkChangeJob?.cancel()
        networkChangeJob = TunnelManager.scope.launch {
            delay(NETWORK_CHANGE_COALESCE_MS)
            val sinceLast = System.currentTimeMillis() - lastNetworkChangeTime
            if (sinceLast < NETWORK_CHANGE_MIN_INTERVAL_MS) {
                // Защита от шторма на нестабильном Wi-Fi.
                delay(NETWORK_CHANGE_MIN_INTERVAL_MS - sinceLast)
            }
            if (!TunnelManager.desiredRunning.value || isTunnelPaused) return@launch
            lastNetworkChangeTime = System.currentTimeMillis()
            Log.d("TunnelService", "Сеть изменилась, мягкий перезапуск Go-клиента")
            TunnelManager.restartTransport()
        }
    }

    private fun sanitizeCaptchaMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "auto" -> "auto"
            "rjs" -> "rjs"
            "wv" -> "wv"
            else -> "auto"
        }
    }

    private fun sanitizeVkAnonPath(path: String?): String {
        return when (path?.lowercase()) {
            "legacy" -> "legacy"
            else -> "vkcalls"
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wdtt:tunnel_cpu"
        ).apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        // Используем WIFI_MODE_FULL_LOW_LATENCY для Android 10+, 
        // это предотвращает отключение радиомодуля при выключенном экране
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        
        wifiLock = wm.createWifiLock(mode, "wdtt:wifi_perf").apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = TunnelManager.scope.launch(Dispatchers.Main) {
            delay(1000)
            var downSinceMs = 0L
            while (isActive) {
                // Смотрим на НАМЕРЕНИЕ, а не на живость процесса. Раньше здесь
                // стоял running, который падает в false на каждом перезапуске
                // (смена сети, вотчдог, пауза) — сервис успевал убить себя в эту
                // щель, уведомление пропадало, туннель опускался, и система
                // поднимала всё заново через START_STICKY. Отсюда мигающая плашка.
                if (!TunnelManager.desiredRunning.value && !isTunnelPaused) {
                    stopSelf()
                    break
                }

                // Страховка от зависания: намерение есть, а процесс не поднимается.
                // Без неё сервис мог бы вечно висеть с уведомлением и wakelock.
                if (!TunnelManager.running.value && !isTunnelPaused) {
                    if (downSinceMs == 0L) {
                        downSinceMs = System.currentTimeMillis()
                        updateNotification("Переподключение...")
                    } else if (System.currentTimeMillis() - downSinceMs > TUNNEL_DOWN_TIMEOUT_MS) {
                        Log.w("TunnelService", "Туннель не поднялся за ${TUNNEL_DOWN_TIMEOUT_MS / 1000}с — останавливаемся")
                        TunnelManager.stop()
                        stopSelf()
                        break
                    }
                    delay(2000)
                    continue
                }

                downSinceMs = 0L
                if (!isTunnelPaused) {
                    updateNotification(buildTunnelNotificationText())
                }
                delay(2000)
            }
        }
    }

    private fun buildTunnelNotificationText(): String {
        val statsText = TunnelManager.stats.value.trim()
        return when {
            statsText.isEmpty() -> "Туннель активен"
            statsText == "Ожидание данных..." -> "Туннель активен"
            else -> statsText
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TUNNEL_NOTIFICATION_CHANNEL_ID,
            "Privateer Туннель",
            NotificationManager.IMPORTANCE_DEFAULT // ВАЖНО: DEFAULT, а не LOW, иначе на многих китайских прошивках иконка скрывается
        ).apply {
            description = "Уведомление о работе туннеля"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String, actionName: String = "STOP", actionTitle: String = "Отключить"): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Privateer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
            // ВАЖНО: Делаем уведомление публичным (видимым на локскрине)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Категория SERVICE помогает системе понять важность
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true) // Не издавать звук и не будить экран при обновлении статистики!
            .setSilent(true) // Делаем тихим само уведомление
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setWhen(0L)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPersistentForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TUNNEL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TUNNEL_NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        val notification = createNotification(text)
        getSystemService(NotificationManager::class.java).notify(TUNNEL_NOTIFICATION_ID, notification)
        TunnelWidgetProvider.updateWidgetState(applicationContext, TunnelManager.running.value, text)
        QuickToggleTileService.requestTileUpdate(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkChangeJob?.cancel()
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        defaultNetworkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        stopTunnel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
