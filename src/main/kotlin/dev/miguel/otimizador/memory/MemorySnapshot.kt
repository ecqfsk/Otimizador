package dev.miguel.otimizador.memory

import dev.miguel.otimizador.core.GcStats

/**
 * Uso de um pool de memória (heap ou não-heap).
 *
 * Terminologia que este plugin usa de forma rígida:
 *  - used      = efetivamente ocupado;
 *  - committed = comprometido/reservado pela JVM (pode estar ocioso);
 *  - max       = limite configurado (não é consumo físico);
 *  - collectionUsed = uso logo após a última coleta deste pool (piso pós-GC).
 */
data class PoolUsage(
    val name: String,
    val heap: Boolean,
    val used: Long,
    val committed: Long,
    val max: Long,
    val peakUsed: Long,
    val collectionUsage: Long?,
)

/**
 * Amostra imutável de memória do processo, produzida pela thread assíncrona.
 * Todos os valores estão em bytes, exceto onde a unitária indicar o contrário.
 */
data class MemorySnapshot(
    val timeMillis: Long,
    // --- heap ---
    val heapUsed: Long,
    val heapCommitted: Long,
    /** Limite configurado do heap (-Xmx / Runtime.maxMemory) — não é consumo físico. */
    val heapMax: Long,
    // --- não-heap ---
    val nonHeapUsed: Long,
    val nonHeapCommitted: Long,
    // --- pools ---
    val pools: List<PoolUsage>,
    // --- GC ---
    val gc: List<GcStats.CollectorReading>,
    // --- threads ---
    val threadCount: Int,
    val daemonThreads: Int,
    // --- processo (nativo) ---
    val rssBytes: Long?,
    val vmSizeBytes: Long?,
    val peakRssBytes: Long?,
    val swapBytes: Long?,
    /** Memória virtual comprometida do processo (com.sun API), quando disponível. */
    val committedVirtualBytes: Long?,
    // --- buffers NIO ---
    val directUsed: Long?,
    val directCapacity: Long?,
    val mappedUsed: Long?,
    // --- outros ---
    val loadedClasses: Long,
    val jvmUptimeMs: Long,
    /** Carga de CPU do processo 0..1 (null quando a JVM não expõe). */
    val processCpuLoad: Double?,
    /** Nível de Native Memory Tracking: "off", "summary", "detail" ou "desconhecido". */
    val nmtLevel: String,
    /**
     * Soma do uso pós-coleta dos pools de heap = melhor estimativa disponível
     * de "piso do heap após GC". Null quando algum pool ainda não coletou.
     */
    val heapFloorUsed: Long?,
) {
    /** Heap usado em relação ao limite configurado (0..1). */
    val heapUsedRatio: Double
        get() = if (heapMax > 0) heapUsed.toDouble() / heapMax else 0.0

    fun heapPool(name: String): PoolUsage? = pools.firstOrNull { it.heap && it.name.equals(name, ignoreCase = true) }

    val heapPools: List<PoolUsage> get() = pools.filter { it.heap }
    val nonHeapPools: List<PoolUsage> get() = pools.filter { !it.heap }
}
