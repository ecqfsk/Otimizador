package dev.miguel.otimizador.memory

import com.sun.management.HotSpotDiagnosticMXBean
import dev.miguel.otimizador.core.GcStats
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * Coletor de uma amostra de memória.
 *
 * Uso exclusivamente de APIs públicas de gerenciamento (JMX + /proc) — seguro
 * para executar em thread assíncrona, sem tocar no mundo nem no tick principal.
 * Cada leitura é barata (ordem de microssegundos a milissegundos).
 *
 * Falhas em qualquer sub-sistema resultam em campo "n/d" (null), nunca em
 * interrupção da coleta.
 */
class MemorySampler {

    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    private val threadBean = ManagementFactory.getThreadMXBean()
    private val classBean = ManagementFactory.getClassLoadingMXBean()
    private val runtimeBean = ManagementFactory.getRuntimeMXBean()
    private val osBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val bufferPools = try {
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
    } catch (_: Throwable) {
        emptyList()
    }

    fun sample(nowMillis: Long = System.currentTimeMillis()): MemorySnapshot {
        val heapUsage = try { memoryBean.heapMemoryUsage } catch (_: Throwable) { null }
        val nonHeapUsage = try { memoryBean.nonHeapMemoryUsage } catch (_: Throwable) { null }

        val pools = try {
            ManagementFactory.getMemoryPoolMXBeans().map { pool ->
                val u = try { pool.usage } catch (_: Throwable) { null }
                val peak = try { pool.peakUsage } catch (_: Throwable) { null }
                val col = try { pool.collectionUsage } catch (_: Throwable) { null }
                PoolUsage(
                    name = pool.name,
                    heap = pool.type == MemoryType.HEAP,
                    used = u?.used ?: 0L,
                    committed = u?.committed ?: 0L,
                    max = u?.max ?: -1L,
                    peakUsed = peak?.used ?: 0L,
                    collectionUsage = col?.used,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val gc = try {
            gcBeans.map { b ->
                GcStats.CollectorReading(
                    name = b.name,
                    count = b.collectionCount.coerceAtLeast(0L),
                    timeMs = b.collectionTime.coerceAtLeast(0L),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val threads = try {
            threadBean.threadCount to threadBean.daemonThreadCount
        } catch (_: Throwable) {
            0 to 0
        }

        val native = try { NativeMemory.read() } catch (_: Throwable) { ProcStatus(null, null, null, null, null) }

        val direct = bufferPools.firstOrNull { it.name.equals("direct", ignoreCase = true) }
        val mapped = bufferPools.firstOrNull { it.name.equals("mapped", ignoreCase = true) }

        val heapMax = try { Runtime.getRuntime().maxMemory() } catch (_: Throwable) { -1L }

        // Piso pós-GC: soma do uso de coleta dos pools de heap. Se algum pool
        // ainda não passou por coleta, a estimativa fica indisponível (null).
        val heapPools = pools.filter { it.heap }
        val floor = if (heapPools.isNotEmpty() && heapPools.all { it.collectionUsage != null }) {
            heapPools.sumOf { it.collectionUsage!! }
        } else null

        return MemorySnapshot(
            timeMillis = nowMillis,
            heapUsed = heapUsage?.used ?: 0L,
            heapCommitted = heapUsage?.committed ?: 0L,
            heapMax = heapMax,
            nonHeapUsed = nonHeapUsage?.used ?: 0L,
            nonHeapCommitted = nonHeapUsage?.committed ?: 0L,
            pools = pools,
            gc = gc,
            threadCount = threads.first,
            daemonThreads = threads.second,
            rssBytes = native.rssBytes,
            vmSizeBytes = native.vmSizeBytes,
            peakRssBytes = native.peakRssBytes,
            swapBytes = native.swapBytes,
            committedVirtualBytes = try { osBean?.committedVirtualMemorySize } catch (_: Throwable) { null },
            directUsed = direct?.memoryUsed,
            directCapacity = direct?.totalCapacity,
            mappedUsed = mapped?.memoryUsed,
            loadedClasses = try { classBean.loadedClassCount.toLong() } catch (_: Throwable) { 0L },
            jvmUptimeMs = try { runtimeBean.uptime } catch (_: Throwable) { 0L },
            processCpuLoad = try {
                osBean?.processCpuLoad?.takeIf { it >= 0.0 }
            } catch (_: Throwable) { null },
            nmtLevel = readNmtLevel(),
            heapFloorUsed = floor,
        )
    }

    /**
     * Nível de Native Memory Tracking configurado na JVM ("off"/"summary"/"detail").
     * NMT só pode ser lido aqui; ativar/desativar exige reiniciar o servidor.
     */
    private fun readNmtLevel(): String = try {
        val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
        when (bean?.getVMOption("NativeMemoryTracking")?.value) {
            "0" -> "off"
            "1" -> "summary"
            "2" -> "detail"
            null -> "desconhecido"
            else -> "desconhecido"
        }
    } catch (_: Throwable) {
        "desconhecido"
    }
}
