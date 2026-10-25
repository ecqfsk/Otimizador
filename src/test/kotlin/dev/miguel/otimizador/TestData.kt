package dev.miguel.otimizador

import dev.miguel.otimizador.core.GcStats
import dev.miguel.otimizador.memory.MemorySnapshot
import dev.miguel.otimizador.memory.PoolUsage

/**
 * Fábrica de amostras para testes.
 *
 * Todos os campos têm valores plausíveis (heap de 1 GiB num máximo de 4 GiB,
 * RSS um pouco acima do heap, GC ocioso), para que cada teste só precise
 * sobrescrever o que interessa à sua asserção.
 */
fun memorySnapshot(
    timeMillis: Long,
    heapUsed: Long = 1024L * 1024 * 1024,
    heapCommitted: Long = 2048L * 1024 * 1024,
    heapMax: Long = 4096L * 1024 * 1024,
    nonHeapUsed: Long = 300L * 1024 * 1024,
    nonHeapCommitted: Long = 384L * 1024 * 1024,
    pools: List<PoolUsage> = emptyList(),
    gc: List<GcStats.CollectorReading> = emptyList(),
    threadCount: Int = 40,
    daemonThreads: Int = 20,
    rssBytes: Long? = 1536L * 1024 * 1024,
    vmSizeBytes: Long? = 6L * 1024 * 1024 * 1024,
    peakRssBytes: Long? = 1600L * 1024 * 1024,
    swapBytes: Long? = 0L,
    committedVirtualBytes: Long? = null,
    directUsed: Long? = 8L * 1024 * 1024,
    directCapacity: Long? = 16L * 1024 * 1024,
    mappedUsed: Long? = 0L,
    loadedClasses: Long = 12_000L,
    jvmUptimeMs: Long = 60_000L,
    processCpuLoad: Double? = null,
    nmtLevel: String = "off",
    heapFloorUsed: Long? = null,
): MemorySnapshot = MemorySnapshot(
    timeMillis = timeMillis,
    heapUsed = heapUsed,
    heapCommitted = heapCommitted,
    heapMax = heapMax,
    nonHeapUsed = nonHeapUsed,
    nonHeapCommitted = nonHeapCommitted,
    pools = pools,
    gc = gc,
    threadCount = threadCount,
    daemonThreads = daemonThreads,
    rssBytes = rssBytes,
    vmSizeBytes = vmSizeBytes,
    peakRssBytes = peakRssBytes,
    swapBytes = swapBytes,
    committedVirtualBytes = committedVirtualBytes,
    directUsed = directUsed,
    directCapacity = directCapacity,
    mappedUsed = mappedUsed,
    loadedClasses = loadedClasses,
    jvmUptimeMs = jvmUptimeMs,
    processCpuLoad = processCpuLoad,
    nmtLevel = nmtLevel,
    heapFloorUsed = heapFloorUsed,
)

/** Leitura de coletor para testes de janelas de GC. */
fun gcReading(
    name: String = "G1 Young Generation",
    count: Long,
    timeMs: Long,
): GcStats.CollectorReading = GcStats.CollectorReading(name, count, timeMs)
