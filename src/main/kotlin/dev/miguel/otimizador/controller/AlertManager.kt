package dev.miguel.otimizador.controller

import dev.miguel.otimizador.core.TtlCache

/**
 * Limitador de taxa de alertas.
 *
 * - por chave: intervalo mínimo entre o mesmo tipo de alerta;
 * - global: teto de alertas por minuto (evita inundar o log).
 *
 * Puro (relógio injetável) e com o mapa de chaves limitado em tamanho.
 */
class AlertManager(
    private val minIntervalMs: Long,
    private val maxPerMinute: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastByKey = TtlCache<String, Long>(
        maxSize = 256,
        ttlMillis = (minIntervalMs * 2).coerceAtLeast(60_000L),
        clock = clock,
    )

    private var windowStart = 0L
    private var windowCount = 0

    /** Tenta emitir o alerta [key]; true = permitido agora. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()

        // janela deslizante simples de 1 minuto
        if (now - windowStart >= 60_000L) {
            windowStart = now
            windowCount = 0
        }
        if (windowCount >= maxPerMinute) return false

        val last = lastByKey.get(key)
        if (last != null && now - last < minIntervalMs) return false

        lastByKey.put(key, now)
        windowCount++
        return true
    }

    @Synchronized
    fun reset() {
        lastByKey.clear()
        windowStart = 0L
        windowCount = 0
    }
}
