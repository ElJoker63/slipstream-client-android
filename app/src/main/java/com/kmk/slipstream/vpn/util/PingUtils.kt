package com.kmk.slipstream.vpn.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

object PingUtils {

    /**
     * Realiza un test de latencia haciendo 3 intentos y devolviendo el mejor resultado.
     * Esto evita picos de lag irreales y muestra la latencia mínima posible.
     */
    suspend fun ping204(url: String = "https://www.google.com/generate_204"): Pair<Long?, Int?> {
        return withContext(Dispatchers.IO) {
            // Test 1: Intentar con la URL original (Prueba DNS + HTTP)
            val result = performPing(url)
            if (result.first != null) return@withContext result

            // Test 2: Si falla, intentar con IP directa (Prueba de conectividad pura, sin DNS)
            // Usamos http://1.1.1.1 que es el resolver de Cloudflare
            android.util.Log.w("PingUtils", "Fallo con dominio, intentando con IP directa (1.1.1.1)...")
            performPing("http://1.1.1.1")
        }
    }

    private fun performPing(url: String): Pair<Long?, Int?> {
        var minMs: Long? = null
        var lastCode: Int? = null

        repeat(2) { i ->
            runCatching {
                val start = System.nanoTime()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    useCaches = false
                }
                conn.connect()
                val code = conn.responseCode
                lastCode = code
                runCatching { conn.inputStream?.close() }
                conn.disconnect()

                val ms = (System.nanoTime() - start) / 1_000_000
                if (code in 200..399) {
                    minMs = if (minMs == null) ms else kotlin.math.min(minMs!!, ms)
                }
            }
        }
        return Pair(minMs, lastCode)
    }
}
