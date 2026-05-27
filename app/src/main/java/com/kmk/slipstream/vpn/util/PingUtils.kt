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
            var minMs: Long? = null
            var lastCode: Int? = null

            repeat(3) {
                runCatching {
                    val start = System.nanoTime()
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2000 // Timeout más corto para evitar esperas largas
                        readTimeout = 2000
                        instanceFollowRedirects = true
                        requestMethod = "GET"
                        useCaches = false // Asegura que el ping sea real
                    }
                    conn.connect()
                    val code = conn.responseCode
                    lastCode = code
                    runCatching { conn.inputStream?.close() }
                    conn.disconnect()

                    val ms = (System.nanoTime() - start) / 1_000_000
                    
                    // Si la respuesta es exitosa, guardamos el tiempo más bajo
                    if (code == 204 || code == 200) {
                        minMs = if (minMs == null) ms else min(minMs!!, ms)
                    }
                }
            }
            Pair(minMs, lastCode)
        }
    }
}
