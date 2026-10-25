package dev.miguel.otimizador.core

/**
 * Estatísticas de coleta de lixo entre duas amostras.
 *
 * A JVM expõe apenas contadores acumulados (tempo total e quantidade total de
 * coletas por coletor). A partir das diferenças entre amostras derivamos:
 *  - frequência (coletas por minuto),
 *  - overhead (tempo de GC / tempo real decorrido),
 *  - estimativas de pausa (com a resolução da amostragem — ver limitações).
 *
 * Pura e testável sem servidor.
 */
object GcStats {

    /** Leitura atual de um coletor. */
    data class CollectorReading(val name: String, val count: Long, val timeMs: Long)

    /** Diferença para um coletor entre duas leituras. */
    data class CollectorDelta(val name: String, val countDelta: Long, val timeDeltaMs: Long)

    /**
     * Janela de GC agregada.
     *
     * @param wallMs tempo real coberto pela janela
     * @param overheadRatio tempoGC/tempoReal (bruto; pode exceder 1 em GC paralelo)
     * @param overheadRatioClamped igual, limitado a 1.0 (usado pela classificação)
     * @param estimatedMaxPauseMs pausa máxima estimada (só quando exatamente 1
     *        coleta ocorreu no intervalo — nesse caso o tempo do intervalo é a pausa)
     * @param estimatedAvgPauseMs tempo médio por coleta na janela
     */
    data class Window(
        val wallMs: Long,
        val deltas: List<CollectorDelta>,
        val totalCollections: Long,
        val totalTimeMs: Long,
        val overheadRatio: Double,
        val overheadRatioClamped: Double,
        val collectionsPerMinute: Double,
        val estimatedMaxPauseMs: Long?,
        val estimatedAvgPauseMs: Long?,
    )

    /**
     * Calcula a janela atual a partir da leitura anterior e da atual.
     * Retorna null na primeira amostra (não há diferença a calcular) ou se o
     * tempo decorrido não for positivo.
     */
    fun window(
        previous: List<CollectorReading>?,
        current: List<CollectorReading>,
        wallMs: Long,
    ): Window? {
        if (wallMs <= 0) return null
        if (previous == null) return null

        val prevBy = previous.associateBy { it.name }
        val deltas = ArrayList<CollectorDelta>(current.size)
        var totalCollections = 0L
        var totalTime = 0L
        var maxPause: Long? = null

        for (cur in current) {
            val prev = prevBy[cur.name]
            val c = if (prev == null) cur.count else (cur.count - prev.count).coerceAtLeast(0)
            val t = if (prev == null) 0L else (cur.timeMs - prev.timeMs).coerceAtLeast(0)
            deltas.add(CollectorDelta(cur.name, c, t))
            totalCollections += c
            totalTime += t
            if (c == 1L && t > (maxPause ?: 0L)) maxPause = t
        }

        val raw = totalTime.toDouble() / wallMs
        val minutes = wallMs / 60_000.0
        val avg = if (totalCollections > 0) totalTime.toDouble() / totalCollections else null

        return Window(
            wallMs = wallMs,
            deltas = deltas,
            totalCollections = totalCollections,
            totalTimeMs = totalTime,
            overheadRatio = raw,
            overheadRatioClamped = raw.coerceIn(0.0, 1.0),
            collectionsPerMinute = if (minutes > 0) totalCollections / minutes else 0.0,
            estimatedMaxPauseMs = maxPause,
            estimatedAvgPauseMs = avg?.toLong(),
        )
    }
}
