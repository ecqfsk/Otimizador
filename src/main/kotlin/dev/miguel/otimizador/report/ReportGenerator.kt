package dev.miguel.otimizador.report

import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.core.Format
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.core.Trend
import dev.miguel.otimizador.diag.Finding
import dev.miguel.otimizador.jvm.JvmInfo
import dev.miguel.otimizador.jvm.JvmRecommendation
import dev.miguel.otimizador.memory.MemoryStore

/** Dados de ambiente necessários para o relatório (coletados no momento da geração). */
data class ReportContext(
    val nowMillis: Long,
    val pluginVersion: String,
    val serverVersion: String,
    val tps: List<Double>,
    val msptMillis: Double?,
    val onlinePlayers: Int,
    val worldCount: Int,
    val state: MemoryState,
    val stateSinceMillis: Long,
    val modules: List<String>,
)

/**
 * Gera o relatório completo em texto, com unidades, horários e limitações.
 * É uma função pura sobre dados já coletados (pode rodar em thread assíncrona:
 * não toca em mundo nem em entidades).
 */
class ReportGenerator(
    private val settings: Settings,
    private val store: MemoryStore,
    private val jvm: JvmInfo,
) {

    fun generate(
        ctx: ReportContext,
        findings: List<Finding>,
        recommendations: List<JvmRecommendation>,
    ): String {
        val sb = StringBuilder(16 * 1024)
        val line = "=".repeat(78)
        val thin = "-".repeat(78)

        sb.appendLine(line)
        sb.appendLine("RELATÓRIO COMPLETO — OTIMIZADOR ${ctx.pluginVersion}")
        sb.appendLine("Gerado em: ${Format.timestamp(ctx.nowMillis)} (horário local do servidor)")
        sb.appendLine(line)
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("1. AMBIENTE")
        sb.appendLine(thin)
        sb.appendLine("  Servidor:      ${ctx.serverVersion}")
        sb.appendLine("  Java:          ${jvm.javaVersion} (${jvm.vmName} ${jvm.vmVersion}) — ${jvm.osName}/${jvm.osArch}")
        sb.appendLine("  Processadores: ${jvm.availableProcessors}")
        sb.appendLine("  Uptime da JVM: ${Format.duration(jvm.uptimeMs)}")
        sb.appendLine("  Jogadores:     ${ctx.onlinePlayers} online | mundos: ${ctx.worldCount}")
        sb.appendLine("  TPS:           ${ctx.tps.joinToString(" / ") { Format.dec(it, 2) }}")
        sb.appendLine("  MSPT médio:    ${ctx.msptMillis?.let { "${Format.dec(it)} ms" } ?: "n/d"}")
        sb.appendLine("  Estado:        ${ctx.state.label} há ${Format.duration(ctx.nowMillis - ctx.stateSinceMillis)}")
        sb.appendLine("  Módulos:       ${ctx.modules.joinToString(", ")}")
        sb.appendLine()

        // ------------------------------------------------------------------
        val latest = store.latestSnapshot()
        sb.appendLine("2. RESUMO DE MEMÓRIA (diferenciando cada conceito)")
        sb.appendLine(thin)
        if (latest == null) {
            sb.appendLine("  Sem amostras ainda — a primeira coleta ocorre em instantes.")
        } else {
            sb.appendLine("  Heap usado:       ${Format.bytes(latest.heapUsed)} (${Format.percent(latest.heapUsedRatio)} do máximo) — efetivamente ocupado")
            sb.appendLine("  Heap comprometido:${" ".repeat(1)}${Format.bytes(latest.heapCommitted)} — reservado pela JVM (pode estar ocioso)")
            sb.appendLine("  Heap máximo (-Xmx): ${Format.bytes(latest.heapMax)} — limite configurado, NÃO é consumo físico")
            sb.appendLine("  Piso pós-GC:      ${latest.heapFloorUsed?.let { Format.bytes(it) } ?: "n/d"} (collectionUsage; null até a 1ª coleta)")
            sb.appendLine("  Não-heap:         ${Format.bytes(latest.nonHeapUsed)} (comprometido: ${Format.bytes(latest.nonHeapCommitted)})")
            sb.appendLine("  RSS (residente):  ${latest.rssBytes?.let { Format.bytes(it) } ?: "n/d"} — memória física do processo (leitura /proc)")
            sb.appendLine("  Pico de RSS:      ${latest.peakRssBytes?.let { Format.bytes(it) } ?: "n/d"} (VmHWM)")
            sb.appendLine("  Espaço virtual:   ${latest.vmSizeBytes?.let { Format.bytes(it) } ?: "n/d"} (VmSize) | swap: ${latest.swapBytes?.let { Format.bytes(it) } ?: "n/d"}")
            sb.appendLine("  Threads:          ${latest.threadCount} (${latest.daemonThreads} daemon) | classes: ${latest.loadedClasses}")
            sb.appendLine("  CPU do processo:  ${latest.processCpuLoad?.let { Format.percent(it) } ?: "n/d"}")
            sb.appendLine("  NMT:              ${latest.nmtLevel}")
            sb.appendLine("  Amostras:         heap=${store.heapUsed.size}/${store.heapUsed.capacity}, rss=${store.rss.size}/${store.rss.capacity}, evidências=${store.evidences().size}/${settings.historico.maxEvidencias}")
            sb.appendLine(
                "  Desde:            " + if (store.heapUsed.size > 0) Format.timestamp(store.sinceMillis) else "n/d"
            )
        }
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("3. HISTÓRICO E TENDÊNCIAS")
        sb.appendLine(thin)
        appendTrend(sb, "Heap usado", store.heapUsed.last(settings.memoria.tendencia.janelaAmostras))
        appendTrend(sb, "Piso pós-GC", store.heapFloor.last(settings.memoria.tendencia.janelaAmostras))
        appendTrend(sb, "RSS", store.rss.last(settings.memoria.tendencia.janelaAmostras))
        appendTrend(sb, "Não-heap", store.nonHeapUsed.last(settings.memoria.tendencia.janelaAmostras))
        appendTrend(sb, "Threads", store.threads.last(settings.memoria.tendencia.janelaAmostras))
        val overhead = store.gcOverhead.last(settings.memoria.tendencia.janelaAmostras)
        if (overhead.size >= 2) {
            val avg = overhead.map { it.value }.average()
            sb.appendLine("  Overhead de GC:   média ${Format.percent(avg)} na janela (${overhead.size} amostras)")
        } else {
            sb.appendLine("  Overhead de GC:   insuficiente para média (${overhead.size} amostras)")
        }
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("4. POOLS DE MEMÓRIA")
        sb.appendLine(thin)
        if (latest != null) {
            sb.appendLine("  %-26s %-7s %12s %12s %12s %12s".format("POOL", "TIPO", "USADO", "COMMIT", "MÁXIMO", "PÓS-GC"))
            for (p in latest.pools.take(24)) {
                sb.appendLine(
                    "  %-26s %-7s %12s %12s %12s %12s".format(
                        p.name.take(26),
                        if (p.heap) "heap" else "não-heap",
                        Format.bytes(p.used),
                        Format.bytes(p.committed),
                        if (p.max >= 0) Format.bytes(p.max) else "s/limite",
                        p.collectionUsage?.let { Format.bytes(it) } ?: "n/d",
                    )
                )
            }
            sb.appendLine("  Buffers NIO: direto=${latest.directUsed?.let { Format.bytes(it) } ?: "n/d"} " +
                "(capacidade ${latest.directCapacity?.let { Format.bytes(it) } ?: "n/d"}), " +
                "mapeado=${latest.mappedUsed?.let { Format.bytes(it) } ?: "n/d"}")
        } else {
            sb.appendLine("  Sem dados.")
        }
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("5. GARBAGE COLLECTION")
        sb.appendLine(thin)
        val window = store.lastGcWindow()
        if (window != null) {
            sb.appendLine("  Janela: ${Format.duration(window.wallMs)} | coletas: ${window.totalCollections} " +
                "(${Format.dec(window.collectionsPerMinute)}/min) | tempo total: ${Format.duration(window.totalTimeMs)}")
            sb.appendLine("  Overhead: ${Format.percent(window.overheadRatioClamped)} (bruto ${Format.percent(window.overheadRatio, 2)})")
            sb.appendLine("  Pausa média estimada: ${window.estimatedAvgPauseMs?.let { Format.duration(it) } ?: "n/d"} | " +
                "pausa máx. estimada: ${window.estimatedMaxPauseMs?.let { Format.duration(it) } ?: "n/d"}")
            sb.appendLine("  Coletores: ${latest?.gc?.joinToString(", ") { "${it.name}=${Format.count(it.count)} coletas/${Format.duration(it.timeMs)}" } ?: "n/d"}")
        } else {
            sb.appendLine("  Primeira amostra ainda não gerou janela de GC.")
        }
        val events = store.gcEvents()
        if (events.isNotEmpty()) {
            sb.appendLine("  Últimos eventos de GC (até ${events.size} retidos):")
            for (e in events.takeLast(10)) {
                sb.appendLine("    ${Format.timestamp(e.timeMillis)} | ${e.collections} coleta(s) | pausa est. ${e.estimatedPauseMs?.let { Format.duration(it) } ?: "n/d"}")
            }
        }
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("6. MUNDO (CHUNKS E ENTIDADES)")
        sb.appendLine(thin)
        val ws = store.latestWorldSample()
        if (ws == null) {
            sb.appendLine("  Sem amostra de mundo ainda (intervalo de ${settings.coleta.intervaloMundoSegundos}s).")
        } else {
            sb.appendLine("  Amostrado em ${Format.timestamp(ws.timeMillis)}")
            sb.appendLine("  %-20s %9s %9s %9s %9s %9s %9s".format("MUNDO", "CHUNKS", "SEM-JOG", "%", "ENTID.", "ITENS", "BLOCK-E"))
            for (w in ws.worlds) {
                sb.appendLine(
                    "  %-20s %9s %9s %8s %9s %9s %9s".format(
                        w.name.take(20),
                        Format.count(w.loadedChunks),
                        Format.count(w.chunksSemJogadorProximo),
                        Format.percent(w.percSemJogador, 0),
                        Format.count(w.entities),
                        Format.count(w.itensNoChao),
                        Format.count(w.blockEntities),
                    )
                )
            }
            for (w in ws.worlds) {
                if (w.topEntityTypes.isNotEmpty()) {
                    sb.appendLine("  Top tipos em '${w.name}': " +
                        w.topEntityTypes.take(6).joinToString(", ") { "${it.first}=${it.second}" })
                }
            }
            sb.appendLine("  Tarefas pendentes: ${ws.pendingTasks} total")
            if (ws.pendingTasksByPlugin.isNotEmpty()) {
                sb.appendLine("  Por plugin: " + ws.pendingTasksByPlugin.entries.take(8).joinToString(", ") { "${it.key}=${it.value}" })
            }
        }
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("7. JVM")
        sb.appendLine(thin)
        sb.appendLine("  Versão: ${jvm.javaVersion} | VM: ${jvm.vmName} ${jvm.vmVersion} (${jvm.vmVendor})")
        sb.appendLine("  Java home: ${jvm.javaHome}")
        sb.appendLine("  Argumentos de start: ${if (jvm.inputArguments.isEmpty()) "(nenhum)" else jvm.inputArguments.joinToString(" ")}")
        sb.appendLine("  Heap: -Xmx≈${Format.bytes(jvm.maxHeapBytes)} | em uso agora: ${Format.bytes(jvm.initialHeapBytes)}")
        sb.appendLine("  Coletores disponíveis: ${jvm.gcCollectors.joinToString(", ")}")
        if (jvm.flags.isNotEmpty()) {
            sb.appendLine("  Flags lidas (somente leitura em execução):")
            jvm.flags.entries.take(16).forEach { (k, v) -> sb.appendLine("    $k = $v") }
        }
        if (recommendations.isNotEmpty()) {
            sb.appendLine("  Recomendações para o script de inicialização (NADA é aplicado automaticamente):")
            for (r in recommendations) {
                sb.appendLine("    [P${r.priority}] ${r.title}")
                sb.appendLine("         ${r.detail}")
                sb.appendLine("         Risco: ${r.risk}")
            }
        } else {
            sb.appendLine("  Sem recomendações pendentes com os dados atuais.")
        }
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("8. DIAGNÓSTICO (achados)")
        sb.appendLine(thin)
        for (f in findings) {
            sb.appendLine("  [${f.level.label}] ${f.title}")
            sb.appendLine("    id=${f.id} | observado em ${Format.timestamp(f.atMillis)}")
            for (e in f.evidence) sb.appendLine("    • $e")
            for (r in f.recommendations) sb.appendLine("    → $r")
            if (f.requiresExternalProfiling) {
                sb.appendLine("    ! Requer ferramenta externa (profiling/heap dump): a API pública não fecha esta análise.")
            }
            sb.appendLine()
        }

        // ------------------------------------------------------------------
        sb.appendLine("9. CONFIGURAÇÃO EFETIVA")
        sb.appendLine(thin)
        sb.appendLine("  Coleta: memória a cada ${settings.coleta.intervaloMemoriaSegundos}s | mundo a cada ${settings.coleta.intervaloMundoSegundos}s (x${settings.coleta.multiplicadorPressaoCritica} sob pressão crítica)")
        sb.appendLine("  Histórico: ${settings.historico.tamanho} amostras | TTL eventos GC ${settings.historico.ttlEventosGcSegundos}s | máx. evidências ${settings.historico.maxEvidencias}")
        sb.appendLine("  Limiares heap: ${Format.dec(settings.memoria.alertas.heapAtencaoPercent)}/${Format.dec(settings.memoria.alertas.heapAltaPercent)}/${Format.dec(settings.memoria.alertas.heapCriticoPercent)}% | GC: ${Format.dec(settings.memoria.alertas.gcAtencaoPercent)}/${Format.dec(settings.memoria.alertas.gcAltaPercent)}/${Format.dec(settings.memoria.alertas.gcCriticoPercent)}%")
        sb.appendLine("  Histerese: ${Format.dec(settings.controle.hysteresisPoints)} pts | persistência subida ${settings.controle.persistenciaSubidaSegundos}s / queda ${settings.controle.persistenciaQuedaSegundos}s")
        sb.appendLine("  Chunks: alerta ${settings.chunks.alertaPorMundo}/mundo | desativação ${if (settings.chunks.desativacao.habilitado) "AUTORIZADA (máx ${settings.chunks.desativacao.maxPorCiclo}/ciclo)" else "desligada"}")
        sb.appendLine("  Itens no chão: remoção ${if (settings.entidades.itensNoChao.remocao.habilitado) "AUTORIZADA (teto ${settings.entidades.itensNoChao.remocao.maxPorMundo}/mundo)" else "desligada (padrão)"}")
        sb.appendLine("  Mobs: remoção ${if (settings.entidades.mobs.remocao.habilitado) "AUTORIZADA (teto ${settings.entidades.mobs.remocao.maxPorMundo}/mundo)" else "desligada (padrão)"}")
        sb.appendLine("  Relatórios: ${if (settings.relatorios.habilitado) "ativos (máx ${settings.relatorios.maxArquivos} arquivos)" else "desativados"}")
        sb.appendLine()

        // ------------------------------------------------------------------
        sb.appendLine("10. LIMITAÇÕES DESTE RELATÓRIO")
        sb.appendLine(thin)
        sb.appendLine("  • Heap comprometido ≠ heap usado; heap máximo ≠ consumo físico do processo.")
        sb.appendLine("  • RSS vem de /proc/self/status (Linux); em outros SO pode aparecer como n/d.")
        sb.appendLine("  • Pausas de GC são estimativas com a resolução do intervalo de coleta (${settings.coleta.intervaloMemoriaSegundos}s).")
        sb.appendLine("  • A API pública não expõe memória por classe, objeto ou plugin: atribuição exige heap dump/MAT, NMT (jcmd) ou profiler.")
        sb.appendLine("  • NMT (nível ${jvm.nmtLevel}) é necessário para detalhar a memória fora do heap; leitura externa.")
        sb.appendLine("  • Nenhum System.gc() foi chamado, nenhum dado foi apagado, nenhum servidor foi reiniciado por este plugin.")
        sb.appendLine(line)
        return sb.toString()
    }

    private fun appendTrend(sb: StringBuilder, label: String, samples: List<dev.miguel.otimizador.core.TimedSample>) {
        if (samples.size < 2) {
            sb.appendLine("  $label: séries insuficientes (${samples.size} amostras)")
            return
        }
        val fit = Trend.fit(samples)
        val first = samples.first().value
        val last = samples.last().value
        val mib = 1024.0 * 1024.0
        if (label == "Threads") {
            sb.appendLine("  $label: ${Format.dec(first, 0)} → ${Format.dec(last, 0)} (${samples.size} amostras; " +
                "${fit?.let { "${Format.dec(it.slopePerHour)}/h" } ?: "s/ajuste"})")
        } else {
            sb.appendLine("  $label: ${Format.mib(first)} → ${Format.mib(last)} " +
                "(Δ ${Format.mib(last - first)}; ${fit?.let { Format.rateMiBPerHour(it.slopePerHour / mib) } ?: "s/ajuste"}; " +
                "${samples.size} amostras)")
        }
    }
}
