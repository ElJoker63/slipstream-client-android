package com.kmk.slipstream.vpn

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.kmk.slipstream.vpn.util.AppLogger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class SlipstreamService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var proc: Process? = null
    private var procJob: Job? = null
    @Volatile private var isRunning = false
    private val startLock = Any()

    private var logListener: ((String) -> Unit)? = null
    private var onExitListener: ((Int) -> Unit)? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    inner class LocalBinder : Binder() {
        fun getService(): SlipstreamService = this@SlipstreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startSlipstream(resolvers: List<String>, domain: String, tcpListenPort: Int) {
        synchronized(startLock) {
            if (isRunning || proc != null) {
                log("Slipstream already running.")
                return
            }

            try {
                val exe = getSlipstreamExe()

                val args = mutableListOf<String>()
                args.add(exe.absolutePath)
                resolvers.forEach { args.addAll(listOf("--resolver", it)) }
                args.addAll(listOf("--domain", domain, "--tcp-listen-port", tcpListenPort.toString()))

                log("Starting slipstream: ${args.joinToString(" ")}")

                val p = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()

                proc = p
                isRunning = true

                procJob?.cancel()
                procJob = scope.launch {
                    streamLines(p.inputStream) { log("[SS] $it") }

                    val code = try { p.waitFor() } catch (_: Throwable) { -1 }
                    log("Slipstream exited with code=$code")

                    synchronized(startLock) {
                        proc = null
                        procJob = null
                        isRunning = false
                    }
                    onExitListener?.invoke(code)
                }

            } catch (t: Throwable) {
                log("Slipstream start failed: $t")
                synchronized(startLock) {
                    proc = null
                    procJob = null
                    isRunning = false
                }
            }
        }
    }

    fun stopSlipstream() {
        log("Stopping slipstream...")

        synchronized(startLock) {
            val p = proc
            proc = null
            isRunning = false

            procJob?.cancel()
            procJob = null

            if (p != null) {
                try { p.destroy() } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        // Increased timeout from 200ms to 2000ms for graceful shutdown
                        if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) {
                            log("Process did not exit gracefully, forcing...")
                            try { p.destroyForcibly() } catch (_: Throwable) {}
                            // Wait additional 500ms for forced kill
                            p.waitFor(500, TimeUnit.MILLISECONDS)
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }
    
    fun isProcessRunning(): Boolean {
        return isRunning && proc != null
    }


    override fun onDestroy() {
        super.onDestroy()
        stopSlipstream()
        scope.cancel()
        isRunning = false
    }

    private val logBuffer = ArrayDeque<String>(300)

    private fun log(msg: String) {
        AppLogger.i("CORE", msg)
        val l = logListener ?: return
        mainHandler.post { l.invoke(msg) }
    }

    fun setLogListener(listener: ((String) -> Unit)?) {
        logListener = listener
        if (listener != null) {
            val copy: List<String> = synchronized(logBuffer) { logBuffer.toList() }
            copy.forEach { listener(it) }
        }
    }

    fun setOnExitListener(listener: ((Int) -> Unit)?) {
        onExitListener = listener
    }



    private suspend fun streamLines(input: java.io.InputStream, onLine: (String) -> Unit) {
        try {
            BufferedReader(InputStreamReader(input)).use { reader ->
                while (coroutineContext.isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (_: InterruptedIOException) {
                        break // normal when stopping
                    } catch (_: IOException) {
                        break
                    }
                    if (line == null) break
                    onLine(line)
                }
            }
        } catch (_: Throwable) {
            // never crash app
        }
    }

    private fun getSlipstreamExe(): File {
        val libDir = applicationInfo.nativeLibraryDir
        val exe = File(libDir, "libslipstream.so")

        if (!exe.exists()) {
            throw IllegalStateException(
                "Slipstream binary not found at ${exe.absolutePath}"
            )
        }

        // Just in case (usually already executable)
        exe.setExecutable(true, false)

        log("Using slipstream binary: ${exe.absolutePath}")

        return exe
    }
}
