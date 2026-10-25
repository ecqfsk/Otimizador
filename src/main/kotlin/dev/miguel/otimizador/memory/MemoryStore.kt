package dev.miguel.otimizador.memory

import dev.miguel.otimizador.core.GcStats
import dev.miguel.otimizador.core.RingBuffer
import dev.miguel.otimizador.core.TtlCache
import dev.miguel.otimizador.world.WorldSample

/** Evento de GC observado entre amostras. */
data class GcEvent(
    val timeMillis: Long,
    val collections: Long,
    /** Pausa estimada em ms (null quando várias coletas ocorreram no intervalo). */
    val estimatedPauseMs: Long?,
)

/**
 * Evidência registrada pelo diagnóstico: o que foi observado, quando e de onde
 * veio. Mantida numa fila limitada ([maxEvidencias]) com deduplicação por id.
 */
data class Evidence(
    val id: String,
    val timeMillis: Long,
    val message: String,
    val details: List<String>,
)

/**
 * Repositório de todo o histórico do plugin — com TODOS os tamanhos limitados:
 *  - buffers circulares de capacidade fixa para as métricas escalares;
 *  - fila de eventos de GC com TTL e teto;
 *  - fila de amostras de mundo com teto;
 *  - evidências com teto e deduplicação.
 *
 * Nada aqui cresce sem limite: o próprio plugin não pode virar fonte de
 * consumo de memória. Todas as operações são thread-safe porque a coleta é
 * assíncrona e os comandos rodam na thread principal.
 */
class MemoryStore(
    historyCapacity: Int,
    private val maxEvidences: Int,
    private val gcEventTtlMillis: Long,
) {
    // Séries temporais (capacidade fixa = historyCapacity)
    val heapUsed = RingBuffer(historyCapacity)
    val heapCommitted = RingBuffer(historyCapacity)
    val heapMaxSeries = RingBuffer(historyCapacity)
    val nonHeapUsed = RingBuffer(historyCapacity)
    val heapFloor = RingBuffer(historyCapacity)
    val rss = RingBuffer(historyCapacity)
    val threads = RingBuffer(historyCapacity)
    val gcOverhead = RingBuffer(historyCapacity)

    private val maxGcEvents = 200
    private val maxWorldSamples = 60

    private val gcEvents = ArrayDeque<GcEvent>()
    private val evidences = ArrayDeque<Evidence>()
    private val worlds = ArrayDeque<WorldSample>()

    private var previousGc: List<GcStats.CollectorReading>? = null
    private var lastSampleTime: Long = 0L
    /** Existe amostra anterior? (evita usar 0 como "sentinela" — 0 é um horário válido) */
    private var hasSample: Boolean = false
    private var latest: MemorySnapshot? = null
    private var previous: MemorySnapshot? = null
    private var currentWindow: GcStats.Window? = null
    private var startedAt: Long = 0L

    /** Rastreio de tarefas pendentes por plugin (limitado em plugins e amostras). */
    val taskTracker = TaskTracker(maxPlugins = 100, capacityPerPlugin = 60)

    /** Instante da primeira amostra (para mostrar tempo de coleta). */
    val sinceMillis: Long get() = startedAt

    /**
     * Registra uma amostra de memória. Retorna a janela de GC calculada
     * (null na primeira amostra, pois não há diferença a comparar).
     */
    @Synchronized
    fun record(snapshot: MemorySnapshot): GcStats.Window? {
        val now = snapshot.timeMillis
        if (!hasSample) startedAt = now

        val wall = if (!hasSample) 0L else now - lastSampleTime
        val window = GcStats.window(previousGc, snapshot.gc, wall)
        if (window != null) {
            currentWindow = window
            gcOverhead.add(now, window.overheadRatioClamped)
            if (window.totalCollections > 0) {
                val pause = window.estimatedMaxPauseMs ?: window.estimatedAvgPauseMs
                gcEvents.addLast(GcEvent(now, window.totalCollections, pause))
            }
            trimGcEvents(now)
        }

        heapUsed.add(now, snapshot.heapUsed.toDouble())
        heapCommitted.add(now, snapshot.heapCommitted.toDouble())
        heapMaxSeries.add(now, snapshot.heapMax.toDouble())
        nonHeapUsed.add(now, snapshot.nonHeapUsed.toDouble())
        threads.add(now, snapshot.threadCount.toDouble())
        if (snapshot.heapFloorUsed != null) heapFloor.add(now, snapshot.heapFloorUsed.toDouble())
        if (snapshot.rssBytes != null) rss.add(now, snapshot.rssBytes.toDouble())

        previousGc = snapshot.gc
        lastSampleTime = now
        hasSample = true
        previous = latest
        latest = snapshot
        return window
    }

    /** Registra uma amostra de mundo (feita na thread principal). */
    @Synchronized
    fun recordWorld(sample: WorldSample) {
        worlds.addLast(sample)
        while (worlds.size > maxWorldSamples) worlds.removeFirst()
        taskTracker.record(sample.timeMillis, sample.pendingTasksByPlugin)
    }

    /**
     * Registra uma evidência; substitui uma anterior de mesmo id (dedupe) e
     * descarta a mais antiga quando o teto é atingido.
     */
    @Synchronized
    fun addEvidence(evidence: Evidence) {
        evidences.removeAll { it.id == evidence.id }
        evidences.addLast(evidence)
        while (evidences.size > maxEvidences) evidences.removeFirst()
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    @Synchronized
    fun latestSnapshot(): MemorySnapshot? = latest

    @Synchronized
    fun previousSnapshot(): MemorySnapshot? = previous

    @Synchronized
    fun lastGcWindow(): GcStats.Window? = currentWindow

    @Synchronized
    fun gcEvents(): List<GcEvent> = gcEvents.toList()

    @Synchronized
    fun evidences(): List<Evidence> = evidences.toList()

    @Synchronized
    fun worldSamples(): List<WorldSample> = worlds.toList()

    @Synchronized
    fun latestWorldSample(): WorldSample? = worlds.lastOrNull()

    @Synchronized
    fun clear() {
        heapUsed.clear(); heapCommitted.clear(); heapMaxSeries.clear()
        nonHeapUsed.clear(); heapFloor.clear(); rss.clear(); threads.clear()
        gcOverhead.clear()
        gcEvents.clear(); evidences.clear(); worlds.clear()
        previousGc = null; lastSampleTime = 0L; hasSample = false
        latest = null; previous = null; currentWindow = null
        taskTracker.clear()
        startedAt = 0L
    }

    private fun trimGcEvents(now: Long) {
        while (gcEvents.isNotEmpty() && now - gcEvents.first().timeMillis > gcEventTtlMillis) {
            gcEvents.removeFirst()
        }
        while (gcEvents.size > maxGcEvents) gcEvents.removeFirst()
    }
}

/**
 * Série de contagem de tarefas pendentes por plugin.
 *
 * Permite detectar plugins cuja fila de agendamentos cresce de forma
 * persistente — um sinal de tarefas que "ficam ativas indevidamente". Limita
 * simultaneamente a quantidade de plugins rastreados (LRU) e o tamanho de cada
 * série (ring buffer), além de expirar séries inativas.
 */
class TaskTracker(
    private val maxPlugins: Int,
    private val capacityPerPlugin: Int,
    private val ttlMillis: Long = 3_600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val series = TtlCache<String, RingBuffer>(maxPlugins, ttlMillis, clock)

    fun record(nowMillis: Long, counts: Map<String, Int>) {
        for ((plugin, count) in counts) {
            val buf = series.get(plugin) ?: RingBuffer(capacityPerPlugin)
            buf.add(nowMillis, count.toDouble())
            series.put(plugin, buf) // renova TTL: a série existe enquanto houver amostras
        }
        series.cleanup()
    }

    /** Primeira × última amostra e tendência de cada série ativa. */
    fun snapshot(): List<TaskSeries> {
        val out = ArrayList<TaskSeries>(series.size)
        for ((plugin, buf) in series.entries()) {
            val samples = buf.all()
            if (samples.size < 2) continue
            val fit = dev.miguel.otimizador.core.Trend.fit(samples)
            out.add(
                TaskSeries(
                    plugin = plugin,
                    first = samples.first().value.toInt(),
                    last = samples.last().value.toInt(),
                    min = samples.minOf { it.value }.toInt(),
                    max = samples.maxOf { it.value }.toInt(),
                    samples = samples.size,
                    slopePerHour = fit?.slopePerHour ?: 0.0,
                    r2 = fit?.r2 ?: 0.0,
                )
            )
        }
        return out.sortedByDescending { it.last }
    }

    /** Remove séries expiradas agora. Retorna quantas entradas foram liberadas. */
    fun cleanup(): Int = series.cleanup()

    fun clear() = series.clear()
}

data class TaskSeries(
    val plugin: String,
    val first: Int,
    val last: Int,
    val min: Int,
    val max: Int,
    val samples: Int,
    val slopePerHour: Double,
    val r2: Double,
)
