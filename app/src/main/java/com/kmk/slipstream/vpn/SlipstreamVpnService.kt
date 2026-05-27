package com.kmk.slipstream.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kmk.slipstream.vpn.util.AppLogger
import hev.htproxy.TProxyService
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max

enum class VpnUiState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

class SlipstreamVpnService : VpnService() {

    companion object {

        const val ACTION_CONNECT = "com.kmk.slipstream.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.kmk.slipstream.vpn.DISCONNECT"

        // ✅ Status broadcast for UI
        const val ACTION_STATUS = "com.kmk.slipstream.vpn.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATUS_REASON = "reason"

        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_SOCKS5_AUTH_ENABLED = "socks5_auth_enabled"
        const val EXTRA_SOCKS5_AUTH_USERNAME = "socks5_auth_username"
        const val EXTRA_SOCKS5_AUTH_PASSWORD = "socks5_auth_password"

        val EXTRA_RESOLVER_LIST: String? = "resolver"
        private const val TAG = "slipstream_vpn"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "slipstream_vpn"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunPfd: ParcelFileDescriptor? = null
    @Volatile private var isStarting = false
    @Volatile private var isReconnecting = false
    @Volatile private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val baseReconnectDelayMs = 2000L

    // Slipstream binding
    private var slipstream: SlipstreamService? = null
    private var bound = false
    private var didBind = false

    // stop guard
    @Volatile private var stopping = false
    private val stopLock = Any()
    private val reconnectLock = Any()

    // traffic for notification (UID-based)
    private var notifJob: Job? = null
    private val myUid = android.os.Process.myUid()
    private var baseRx = -1L
    private var baseTx = -1L
    private var lastRx = -1L
    private var lastTx = -1L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as? SlipstreamService.LocalBinder
            slipstream = b?.getService()
            bound = slipstream != null
            slipstream?.setOnExitListener { code ->
                if (!stopping && !isStarting) {
                    AppLogger.w(TAG, "Slipstream core exited (code=$code). Reconnecting...")
                    reconnect()
                } else if (isStarting) {
                    AppLogger.i(TAG, "Slipstream exited during initial start, will handle in start flow")
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            slipstream = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat("Starting…", "Down: 0 B/s • Up: 0 B/s\nTotal: 0 B • 0 B")

        didBind = bindService(
            Intent(this, SlipstreamService::class.java),
            conn,
            Context.BIND_AUTO_CREATE
        )

        sendStatus(VpnUiState.DISCONNECTED, "created")
    }

    private var lastIntent: Intent? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_DISCONNECT) {
            lastIntent = intent
        }

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                sendStatus(VpnUiState.DISCONNECTING, "ACTION_DISCONNECT")
                stopAll("ACTION_DISCONNECT")
                sendStatus(VpnUiState.DISCONNECTED, "stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT, null -> { /* continue */ }
            else -> { /* ignore */ }
        }

        // Already running?
        if (tunPfd != null || isStarting) {
            AppLogger.i(TAG, "VPN already running or starting. Ignoring request.")
            sendStatus(if (isStarting) VpnUiState.CONNECTING else VpnUiState.CONNECTED, "already running")
            return START_STICKY
        }

        isStarting = true // Marcamos que el proceso ha comenzado

        // reset stop guard for a new run
        synchronized(stopLock) { stopping = false }

        val dnsList = intent?.getStringArrayExtra(EXTRA_RESOLVER_LIST) ?: emptyArray()
        val resolver = dnsList.toList() // convert to List<String>

        val domain = intent?.getStringExtra(EXTRA_DOMAIN) ?: "google.com"
        val port = 5201

        val socksAuthEnabled = intent?.getBooleanExtra(EXTRA_SOCKS5_AUTH_ENABLED, false) ?: false
        val socksAuthUsername = intent?.getStringExtra(EXTRA_SOCKS5_AUTH_USERNAME)
        val socksAuthPassword = intent?.getStringExtra(EXTRA_SOCKS5_AUTH_PASSWORD)

        sendStatus(VpnUiState.CONNECTING, "start requested")

        scope.launch {
            try {
                tunPfd = buildTun()

                // wait for bind (2s max)
                val ok = waitForBind(timeoutMs = 2000)
                if (!ok || slipstream == null) {
                    throw IllegalStateException("SlipstreamService bind timeout")
                }

                AppLogger.i(TAG, "Starting core services...")
                slipstream?.startSlipstream(resolver, domain, port)

                startTProxy(
                    tun = tunPfd!!,
                    socksHost = "127.0.0.1",
                    socksPort = port,
                    authEnabled = socksAuthEnabled,
                    username = socksAuthUsername,
                    password = socksAuthPassword
                )

                startTrafficNotificationUpdates()
                updateNotification("Connected", "Starting traffic…")
                sendStatus(VpnUiState.CONNECTED, "connected")
                AppLogger.i(TAG, "VPN Connected successfully")
                isStarting = false
                reconnectAttempts = 0 // Reset reconnect counter on successful connection
            } catch (t: Throwable) {
                isStarting = false
                reconnectAttempts = 0
                AppLogger.e(TAG, "VPN start failed: ${t.message}")
                sendStatus(VpnUiState.ERROR, "start failed: ${t.message ?: t::class.java.simpleName}")
                stopAll("start failed")
                sendStatus(VpnUiState.DISCONNECTED, "after error")
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopAll("onDestroy")
        super.onDestroy()
        scope.cancel()
    }

    override fun onRevoke() {
        sendStatus(VpnUiState.DISCONNECTING, "onRevoke")
        stopAll("onRevoke")
        sendStatus(VpnUiState.DISCONNECTED, "revoked")
        super.onRevoke()
    }

    private suspend fun waitForBind(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (bound && slipstream != null) return true
            delay(50)
        }
        return bound && slipstream != null
    }

    private fun stopAll(reason: String) {
        synchronized(stopLock) {
            if (stopping) return
            stopping = true
        }

        AppLogger.i(TAG, "stopAll: $reason")

        // Cancel reconnection
        isReconnecting = false
        reconnectAttempts = 0

        notifJob?.cancel()
        notifJob = null

        try { TProxyService.TProxyStopService() } catch (e: Throwable) {
            Log.w(TAG, "TProxyStopService failed: ${e.message}")
        }

        try { tunPfd?.close() } catch (_: Throwable) {}
        tunPfd = null

        try { slipstream?.stopSlipstream() } catch (_: Throwable) {}
        safeUnbind()

        try { stopForeground(true) } catch (_: Throwable) {}
    }

    private fun buildTun(): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("Slipstream VPN")
            .setMtu(1280) // Reducido de 1500 para evitar fragmentación y errores de escritura
            .addAddress("10.10.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        // prevent loop
        try { builder.addDisallowedApplication(packageName) } catch (_: Throwable) {}

        return builder.establish() ?: throw IllegalStateException("TUN establish failed")
    }

    private fun reconnect() {
        if (stopping) return
        if (isStarting) {
            AppLogger.i(TAG, "Reconnect skipped: already starting")
            return
        }
        
        synchronized(reconnectLock) {
            if (isReconnecting) {
                AppLogger.i(TAG, "Reconnect skipped: already reconnecting")
                return
            }
            
            if (reconnectAttempts >= maxReconnectAttempts) {
                AppLogger.e(TAG, "Max reconnect attempts ($maxReconnectAttempts) reached. Giving up.")
                sendStatus(VpnUiState.ERROR, "max reconnect attempts reached")
                stopAll("max reconnect attempts")
                sendStatus(VpnUiState.DISCONNECTED, "after max reconnects")
                stopSelf()
                return
            }
            
            isReconnecting = true
            reconnectAttempts++
        }
        
        scope.launch {
            try {
                val attempt = reconnectAttempts
                val delayMs = calculateBackoffDelay(attempt)
                
                AppLogger.i(TAG, "Starting reconnection attempt $attempt/$maxReconnectAttempts (delay: ${delayMs}ms)")
                sendStatus(VpnUiState.CONNECTING, "reconnecting (attempt $attempt)")
                
                // Validate TUN interface
                if (tunPfd == null) {
                    AppLogger.e(TAG, "Reconnect failed: TUN interface is null")
                    throw IllegalStateException("TUN interface is null")
                }
                
                // Clean up old session with proper timeout
                AppLogger.i(TAG, "Cleaning up old session...")
                try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
                
                // Wait for TProxy to stop
                delay(500)
                
                try { slipstream?.stopSlipstream() } catch (_: Throwable) {}
                
                // Wait for slipstream to stop
                delay(500)
                
                // Exponential backoff delay
                delay(delayMs)
                
                val intent = lastIntent
                if (intent == null) {
                    Log.e(TAG, "Reconnect failed: lastIntent is null")
                    throw IllegalStateException("lastIntent is null")
                }

                val dnsList = intent.getStringArrayExtra(EXTRA_RESOLVER_LIST) ?: emptyArray()
                val resolver = dnsList.toList()
                val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: "google.com"
                val port = 5201
                val socksAuthEnabled = intent.getBooleanExtra(EXTRA_SOCKS5_AUTH_ENABLED, false)
                val socksAuthUsername = intent.getStringExtra(EXTRA_SOCKS5_AUTH_USERNAME)
                val socksAuthPassword = intent.getStringExtra(EXTRA_SOCKS5_AUTH_PASSWORD)

                AppLogger.i(TAG, "Reconnection attempt $attempt: starting core...")
                slipstream?.startSlipstream(resolver, domain, port)
                
                // Wait a bit for slipstream to be ready
                delay(1000)
                
                startTProxy(
                    tun = tunPfd!!,
                    socksHost = "127.0.0.1",
                    socksPort = port,
                    authEnabled = socksAuthEnabled,
                    username = socksAuthUsername,
                    password = socksAuthPassword
                )
                
                sendStatus(VpnUiState.CONNECTED, "reconnected")
                updateNotification("Connected", "Reconnected after interruption")
                AppLogger.i(TAG, "VPN Reconnected successfully (attempt $attempt)")
                
                // Reset reconnect counter on successful reconnection
                reconnectAttempts = 0
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Reconnection attempt $reconnectAttempts failed: ${t.message}")
                
                if (reconnectAttempts >= maxReconnectAttempts) {
                    AppLogger.e(TAG, "Max reconnect attempts reached. Stopping.")
                    sendStatus(VpnUiState.ERROR, "reconnection failed after $maxReconnectAttempts attempts")
                    stopAll("reconnection failed")
                    sendStatus(VpnUiState.DISCONNECTED, "after reconnection failure")
                    stopSelf()
                } else {
                    // Schedule next reconnection attempt via onExitListener
                    AppLogger.i(TAG, "Will attempt reconnection again if process exits")
                }
            } finally {
                isReconnecting = false
            }
        }
    }
    
    private fun calculateBackoffDelay(attempt: Int): Long {
        // Exponential backoff: 2s, 4s, 8s, 16s, 32s (capped at 30s)
        val delay = (baseReconnectDelayMs * (1 shl (attempt - 1))).coerceAtMost(30000L)
        return delay
    }

    private fun safeUnbind() {
        if (!didBind) return
        try {
            unbindService(conn)
        } catch (_: IllegalArgumentException) {
        } catch (_: Throwable) {
        } finally {
            didBind = false
            bound = false
            slipstream = null
        }
    }

    private fun writeTproxyConfig(
        socksHost: String,
        socksPort: Int,
        mtu: Int = 1280, // Sincronizado con el MTU del túnel
        authEnabled: Boolean = false,
        username: String? = null,
        password: String? = null
    ): File {
        val f = File(cacheDir, "tproxy.conf")
        val sb = StringBuilder()

        sb.append("misc:\n")
        sb.append("  task-stack-size: 16384\n") // Aumentado de 8192 para mejor manejo de colas
        sb.append("tunnel:\n")
        sb.append("  mtu: $mtu\n")
        sb.append("socks5:\n")
        sb.append("  port: $socksPort\n")
        sb.append("  address: '$socksHost'\n")
        sb.append("  udp: 'udp'\n")

        if (authEnabled && !username.isNullOrBlank() && !password.isNullOrBlank()) {
            sb.append("  username: '${escapeYaml(username)}'\n")
            sb.append("  password: '${escapeYaml(password)}'\n")
        }

        f.writeText(sb.toString())
        return f
    }

    private fun startTProxy(
        tun: ParcelFileDescriptor,
        socksHost: String,
        socksPort: Int,
        authEnabled: Boolean = false,
        username: String? = null,
        password: String? = null
    ) {
        val conf = writeTproxyConfig(socksHost, socksPort, 1280, authEnabled, username, password)
        val fd = tun.fd
        AppLogger.i(TAG, "TProxyStartService conf=${conf.absolutePath} fd=$fd socks=$socksHost:$socksPort auth=$authEnabled")
        TProxyService.TProxyStartService(conf.absolutePath, fd)
    }

    private fun escapeYaml(s: String): String =
        s.replace("\\", "\\\\").replace("'", "''")

    // -------- Status broadcast --------

    private fun sendStatus(state: VpnUiState, reason: String) {
        sendBroadcast(
            Intent(ACTION_STATUS).apply {
                setPackage(packageName) // فقط داخل اپ
                putExtra(EXTRA_STATUS, state.name)
                putExtra(EXTRA_STATUS_REASON, reason)
            }
        )
    }

    // -------- Notification + Traffic (UID-based) --------

    private fun startTrafficNotificationUpdates() {
        baseRx = TrafficStats.getUidRxBytes(myUid)
        baseTx = TrafficStats.getUidTxBytes(myUid)
        lastRx = baseRx
        lastTx = baseTx

        if (baseRx < 0 || baseTx < 0) {
            updateNotification("Connected", "TrafficStats unsupported on this device")
            return
        }

        notifJob?.cancel()
        notifJob = scope.launch {
            while (isActive && tunPfd != null && !stopping) {
                delay(1000)

                val rx = TrafficStats.getUidRxBytes(myUid)
                val tx = TrafficStats.getUidTxBytes(myUid)
                if (rx < 0 || tx < 0) continue

                val totalRx = max(0, rx - baseRx)
                val totalTx = max(0, tx - baseTx)

                val rxRate = max(0, rx - lastRx)
                val txRate = max(0, tx - lastTx)

                lastRx = rx
                lastTx = tx

                val line1 = "Down: ${fmtRate(rxRate)} • Up: ${fmtRate(txRate)}"
                val line2 = "Total: ${fmtBytes(totalRx)} • ${fmtBytes(totalTx)}"
                updateNotification("Connected", "$line1\n$line2")
            }
        }
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun startForegroundCompat(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Slipstream Client",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        startForeground(NOTIF_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)

        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val openPi = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SlipstreamVpnService::class.java).apply { action = ACTION_DISCONNECT }
        val stopPi = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    stopPi
                ).build()
            )
            .build()
    }

    private fun fmtBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun fmtRate(bps: Long): String {
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format("%.2f MB/s", mb)
            kb >= 1 -> String.format("%.1f KB/s", kb)
            else -> "$bps B/s"
        }
    }
}
