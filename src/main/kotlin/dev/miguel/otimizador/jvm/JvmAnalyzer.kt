package dev.miguel.otimizador.jvm

import com.sun.management.HotSpotDiagnosticMXBean
import dev.miguel.otimizador.core.Format
import java.lang.management.ManagementFactory

/**
 * Informações coletadas da JVM em execução (somente leitura).
 * Nenhum campo aqui pode ser alterado em tempo de execução — a maioria das
 * flags de JVM é somente leitura depois do start.
 */
data class JvmInfo(
    val javaVersion: String,
    val javaMajor: Int,
    val vmName: String,
    val vmVendor: String,
    val vmVersion: String,
    val javaHome: String,
    val osName: String,
    val osArch: String,
    /** Argumentos de inicialização (-Xms, -Xmx, flags...) como passados no start. */
    val inputArguments: List<String>,
    val maxHeapBytes: Long,
    val initialHeapBytes: Long,
    val availableProcessors: Int,
    val gcCollectors: List<String>,
    /** "off", "summary", "detail" ou "desconhecido". */
    val nmtLevel: String,
    /** Opções de diagnóstico lidas da JVM (apenas as que existirem). */
    val flags: Map<String, String>,
    val uptimeMs: Long,
)

/** Estatísticas observadas pelo plugin (derivadas do histórico). */
data class ObservedStats(
    val peakHeapUsed: Long,
    val avgHeapUsed: Long,
    val gcOverheadPercent: Double,
    val latestRssBytes: Long?,
    val peakRssBytes: Long?,
    val threadCount: Int,
    val heapMax: Long,
    val heapFloorLatest: Long?,
)

/** Recomendação textual para o script de inicialização. Nada é aplicado aqui. */
data class JvmRecommendation(
    val id: String,
    val title: String,
    val detail: String,
    /** Risco/contraindicação da mudança sugerida. */
    val risk: String,
    /** 1 = alta relevância observada, 2 = média, 3 = informativo. */
    val priority: Int,
)

/**
 * Analisador da JVM: versão, argumentos, limites de heap, coletores e
 * recomendações baseadas em dados observados.
 *
 * TODAS as saídas são textuais: este módulo não altera argumentos da JVM em
 * execução (a maioria é imutável após o start) e não chama System.gc().
 */
object JvmAnalyzer {

    private val FLAGS_DE_INTERESSE = listOf(
        "MaxHeapSize",
        "InitialHeapSize",
        "MaxNewSize",
        "NewSize",
        "MaxGCPauseMillis",
        "G1HeapRegionSize",
        "AlwaysPreTouch",
        "UseCompressedOops",
        "ParallelGCThreads",
        "ConcGCThreads",
        "CICompilerCount",
        "StringDeduplication",
        "UseStringDeduplication",
        "CodeCacheMinimumFreeSpace",
        "ReservedCodeCacheSize",
    )

    fun collect(): JvmInfo {
        val runtime = try { ManagementFactory.getRuntimeMXBean() } catch (_: Throwable) { null }
        val os = try { ManagementFactory.getOperatingSystemMXBean() } catch (_: Throwable) { null }
        val gcBeans = try { ManagementFactory.getGarbageCollectorMXBeans() } catch (_: Throwable) { emptyList() }

        val version = try { Runtime.version().toString() } catch (_: Throwable) { "desconhecida" }
        val major = try { Runtime.version().feature() } catch (_: Throwable) { 0 }

        val flags = HashMap<String, String>(FLAGS_DE_INTERESSE.size)
        try {
            val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
            if (bean != null) {
                for (name in FLAGS_DE_INTERESSE) {
                    try {
                        val opt = bean.getVMOption(name)
                        flags[name] = "${opt.value}${if (opt.origin == com.sun.management.VMOption.Origin.DEFAULT) " (padrão)" else ""}"
                    } catch (_: IllegalArgumentException) {
                        // opção inexistente nesta JVM/versão: ignora
                    }
                }
            }
        } catch (_: Throwable) {
            // JVM sem suporte a HotSpotDiagnostic: apenas sem flags
        }

        return JvmInfo(
            javaVersion = version,
            javaMajor = major,
            vmName = try { runtime?.vmName ?: "?" } catch (_: Throwable) { "?" },
            vmVendor = try { runtime?.vmVendor ?: "?" } catch (_: Throwable) { "?" },
            vmVersion = try { runtime?.vmVersion ?: "?" } catch (_: Throwable) { "?" },
            javaHome = try { System.getProperty("java.home") ?: "?" } catch (_: Throwable) { "?" },
            osName = try { "${os?.name ?: "?"} ${os?.version ?: ""}".trim() } catch (_: Throwable) { "?" },
            osArch = try { os?.arch ?: "?" } catch (_: Throwable) { "?" },
            inputArguments = try { runtime?.inputArguments?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() },
            maxHeapBytes = try { Runtime.getRuntime().maxMemory() } catch (_: Throwable) { -1L },
            initialHeapBytes = try { Runtime.getRuntime().totalMemory() } catch (_: Throwable) { -1L },
            availableProcessors = try { os?.availableProcessors ?: 0 } catch (_: Throwable) { 0 },
            gcCollectors = gcBeans.map { it.name },
            nmtLevel = try {
                val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                when (bean?.getVMOption("NativeMemoryTracking")?.value) {
                    "0" -> "off"; "1" -> "summary"; "2" -> "detail"; else -> "desconhecido"
                }
            } catch (_: Throwable) { "desconhecido" },
            flags = flags,
            uptimeMs = try { runtime?.uptime ?: 0L } catch (_: Throwable) { 0L },
        )
    }

    /**
     * Gera recomendações para o script de inicialização com base no que foi
     * observado. Cada recomendação traz explicitamente o risco associado.
     */
    fun recommendations(info: JvmInfo, obs: ObservedStats): List<JvmRecommendation> {
        val out = ArrayList<JvmRecommendation>(6)

        // 1) Versão incompatível com Paper 26.3 (exige Java 25)
        if (info.javaMajor in 1..24) {
            out.add(
                JvmRecommendation(
                    id = "java-versao",
                    title = "JVM abaixo da versão exigida pelo Paper 26.3",
                    detail = "Executando Java ${info.javaMajor} (${info.javaVersion}). O Paper 26.3 exige JDK/JRE 25. Atualize a JVM usada no script de inicialização.",
                    risk = "Servidores com versão inferior podem nem iniciar; não há como trocar a JVM em execução.",
                    priority = 1,
                )
            )
        }

        // 2) Heap com folga grande em relação ao pico observado
        if (obs.heapMax > 0 && obs.peakHeapUsed > 0) {
            val ratio = obs.heapMax.toDouble() / obs.peakHeapUsed
            if (ratio >= 2.5 && obs.heapMax >= 2L * 1024 * 1024 * 1024) {
                out.add(
                    JvmRecommendation(
                        id = "heap-folga",
                        title = "Heap máximo muito acima do pico observado",
                        detail = buildString {
                            append("-Xmx atual: ${Format.bytes(obs.heapMax)}; ")
                            append("pico de heap usado medido: ${Format.bytes(obs.peakHeapUsed)} ")
                            append("(${Format.dec(ratio)}x). ")
                            append("Considere testar um -Xmx na faixa de 1.5x~2x o pico em uma janela de teste equivalente.")
                        },
                        risk = "Reduzir -Xmx NÃO reduz automaticamente o consumo físico (RSS) nem garante ganho de desempenho: o efeito só aparece após reinício e pode aumentar a frequência de GC ou causar OutOfMemoryError em picos. Meça antes/depois com o mesmo mundo e os mesmos jogadores.",
                        priority = 2,
                    )
                )
            } else if (obs.peakHeapUsed >= obs.heapMax * 0.9) {
                out.add(
                    JvmRecommendation(
                        id = "heap-apertado",
                        title = "Heap chegou próximo do limite configurado",
                        detail = "Pico observado atingiu ${Format.percent(obs.peakHeapUsed.toDouble() / obs.heapMax)} do -Xmx (${Format.bytes(obs.heapMax)}).",
                        risk = "Aumentar -Xmx eleva o consumo residente e pode piorar pausas de GC; avalie primeiro reduzir o consumo real (chunks, entidades, plugins) em vez de expandir o heap.",
                        priority = 2,
                    )
                )
            }
        }

        // 3) Overhead de GC observado
        if (obs.gcOverheadPercent >= 10.0) {
            out.add(
                JvmRecommendation(
                    id = "gc-overhead",
                    title = "Tempo de GC relevante durante a janela observada",
                    detail = "Overhead médio de GC: ${Format.dec(obs.gcOverheadPercent)}% do tempo real (coletores: ${info.gcCollectors.joinToString(", ")}).",
                    risk = "Mudanças de flags de GC (tamanho de heap/regions, pausas-alvo) alteram latência e RSS de formas imprevisíveis sem medição. Teste em produção controlada, uma mudança por vez, com benchmark antes/depois.",
                    priority = 1,
                )
            )
        }

        // 4) RSS muito acima do heap
        val rss = obs.latestRssBytes
        if (rss != null && obs.heapMax > 0 && rss > obs.heapMax * 3 / 2) {
            out.add(
                JvmRecommendation(
                    id = "rss-vs-heap",
                    title = "Memória residente muito acima do heap",
                    detail = "RSS: ${Format.bytes(rss)} vs heap máximo: ${Format.bytes(obs.heapMax)}. O excedente está fora do heap (pilhas de ${obs.threadCount} threads, code cache, buffers, overhead do GC e do próprio processo).",
                    risk = "Sem Native Memory Tracking ativo não é possível detalhar esse excedente pela API pública; a medição exige ferramentas externas (jcmd, NMT, perf).",
                    priority = 1,
                )
            )
        }

        // 5) NMT
        if (info.nmtLevel == "off") {
            out.add(
                JvmRecommendation(
                    id = "nmt-desligado",
                    title = "Native Memory Tracking desativado",
                    detail = "Adicione -XX:NativeMemoryTracking=summary ao script de inicialização para poder diagnosticar a memória fora do heap com `jcmd <pid> VM.native_memory summary`.",
                    risk = "Exige reiniciar o servidor; adiciona sob medida baixa (tipicamente abaixo de 5% de memória). Não é alterável em execução.",
                    priority = 2,
                )
            )
        } else {
            out.add(
                JvmRecommendation(
                    id = "nmt-ativo",
                    title = "Native Memory Tracking ativo (${info.nmtLevel})",
                    detail = "Use o comando externo `jcmd <pid> VM.native_memory summary` para detalhar heap, thread stacks, code cache e buffers. Compare duas capturas com o mesmo mundo/jogadores.",
                    risk = "NMT não é legível pela API pública do Java: a leitura depende de ferramenta externa com acesso ao processo.",
                    priority = 3,
                )
            )
        }

        // 6) Sem argumentos de inicialização explícitos
        if (info.inputArguments.isEmpty()) {
            out.add(
                JvmRecommendation(
                    id = "sem-flags",
                    title = "Servidor iniciado sem argumentos de JVM explícitos",
                    detail = "Nenhuma flag foi encontrada no start. Os limites atuais são os padrões da JVM. Qualquer ajuste (Xms/Xmx/flags de GC) precisa ser feito no script de inicialização.",
                    risk = "Flags trocadas sem medição podem aumentar RSS ou latência; estabeleça sempre um baseline antes de alterar.",
                    priority = 3,
                )
            )
        }

        // 7) Heap inicial já igual ao máximo (comum quando -Xms == -Xmx)
        if (info.maxHeapBytes > 0 && info.initialHeapBytes > 0 && info.inputArguments.any { it.startsWith("-Xms") }) {
            out.add(
                JvmRecommendation(
                    id = "xms-xmx",
                    title = "Heap inicial fixado (-Xms) em uso",
                    detail = "-Xms igual/next ao -Xmx reserva o heap desde o início; o RSS tende a ficar alto mesmo com pouca ocupação.",
                    risk = "Reduzir -Xms pode causar expansão/contração do heap em runtime e mais coletas; valide com medição.",
                    priority = 3,
                )
            )
        }

        return out.sortedBy { it.priority }
    }
}
