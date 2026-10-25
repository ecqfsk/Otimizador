package dev.miguel.otimizador.command

import dev.miguel.otimizador.Otimizador
import dev.miguel.otimizador.core.Format
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.diag.FindingLevel
import dev.miguel.otimizador.jvm.JvmAnalyzer
import dev.miguel.otimizador.report.ReportContext
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.io.File
import java.util.Locale

/**
 * Implementa `/otimizador <subcomando>`.
 *
 * Cada subcomando tem sua própria permissão (todas concedidas por
 * `otimizador.admin`). A saída sempre traz valores com unidade, horário e —
 * quando aplicável — as limitações das métricas exibidas.
 */
class OtimizadorCommand(private val plugin: Otimizador) : CommandExecutor, TabCompleter {

    companion object {
        private val SUBCOMANDOS = listOf(
            "status", "memory", "diagnose", "chunks", "entities",
            "report", "optimize", "modules", "reload", "help",
        )

        private val PERMISSOES = mapOf(
            "status" to "otimizador.status",
            "memory" to "otimizador.memory",
            "diagnose" to "otimizador.diagnose",
            "chunks" to "otimizador.chunks",
            "entities" to "otimizador.entities",
            "report" to "otimizador.report",
            "optimize" to "otimizador.optimize",
            "modules" to "otimizador.modules",
            "reload" to "otimizador.reload",
            "help" to "otimizador.help",
        )
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "help"

        if (sub !in SUBCOMANDOS) {
            send(sender, "Subcomando desconhecido: '$sub'. Use /otimizador help.")
            return true
        }

        val perm = PERMISSOES[sub]
        if (perm != null && !sender.hasPermission(perm)) {
            send(sender, "Sem permissão: é necessário '$perm' (otimizador.admin concede tudo).")
            return true
        }

        try {
            when (sub) {
                "status" -> status(sender)
                "memory" -> memory(sender)
                "diagnose" -> diagnose(sender)
                "chunks" -> chunks(sender)
                "entities" -> entities(sender)
                "report" -> report(sender)
                "optimize" -> optimize(sender)
                "modules" -> modules(sender)
                "reload" -> reload(sender)
                "help" -> help(sender)
            }
        } catch (t: Throwable) {
            plugin.logger.warning("Erro no comando '/otimizador $sub': ${t.javaClass.simpleName}: ${t.message}")
            send(sender, "Erro ao executar '$sub': ${t.javaClass.simpleName}: ${t.message}")
        }
        return true
    }

    // ------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------
    private fun status(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val latest = plugin.store.latestSnapshot()
        val ws = plugin.store.latestWorldSample()
        val decision = plugin.lastDecision
        val state = plugin.currentState()

        send(sender, "=== Otimizador — Status ===")
        send(sender, "Estado: ${state.label} há ${Format.duration(now - plugin.stateSinceMillis())}" +
            if (plugin.settings.controle.habilitado) "" else " (classificação desativada)")

        if (latest == null) {
            send(sender, "Sem amostras de memória ainda — primeira coleta em até ${plugin.settings.coleta.intervaloMemoriaSegundos}s.")
        } else {
            send(
                sender,
                "Heap: usado ${Format.bytes(latest.heapUsed)} (${Format.percent(latest.heapUsedRatio)}) | " +
                    "comprometido ${Format.bytes(latest.heapCommitted)} | " +
                    "máximo ${Format.bytes(latest.heapMax)} (-Xmx)"
            )
            send(
                sender,
                "Processo: RSS ${latest.rssBytes?.let { Format.bytes(it) } ?: "n/d"}" +
                    (latest.peakRssBytes?.let { " (pico ${Format.bytes(it)})" } ?: "") +
                    " | não-heap ${Format.bytes(latest.nonHeapUsed)} | " +
                    "threads ${latest.threadCount} | classes ${latest.loadedClasses} | NMT ${latest.nmtLevel}"
            )
            val w = plugin.store.lastGcWindow()
            if (w != null) {
                send(
                    sender,
                    "GC: overhead ${Format.percent(w.overheadRatioClamped)} na janela de ${Format.duration(w.wallMs)} | " +
                        "${Format.dec(w.collectionsPerMinute)} coletas/min | " +
                        "pausa média est. ${w.estimatedAvgPauseMs?.let { Format.duration(it) } ?: "n/d"}"
                )
            }
            send(
                sender,
                "Piso pós-GC: ${latest.heapFloorUsed?.let { Format.bytes(it) } ?: "n/d (até a 1ª coleta de GC)" } | " +
                    "buffers diretos ${latest.directUsed?.let { Format.bytes(it) } ?: "n/d"}"
            )
        }

        send(
            sender,
            "Servidor: TPS ${plugin.envTps()} | MSPT ${plugin.envMspt()} | " +
                "jogadores ${plugin.envPlayers()} | mundos ${plugin.envWorlds()}"
        )
        if (ws != null) {
            send(
                sender,
                "Mundo (amostra ${Format.age(ws.timeMillis, now)}): chunks ${ws.totalLoadedChunks} | " +
                    "entidades ${ws.totalEntities} | itens no chão ${ws.totalItems} | " +
                    "tarefas pendentes ${ws.pendingTasks}"
            )
        }

        val findings = plugin.lastFindings
        val counts = findings.groupingBy { it.level }.eachCount()
        send(
            sender,
            "Diagnóstico: ${counts[FindingLevel.CRITICO] ?: 0} crítico(s), ${counts[FindingLevel.ALERTA] ?: 0} alerta(s), " +
                "${counts[FindingLevel.ATENCAO] ?: 0} atenção(ões), ${counts[FindingLevel.INFO] ?: 0} info(s)"
        )
        send(
            sender,
            "Coleta: memória ${plugin.store.heapUsed.size}/${plugin.store.heapUsed.capacity} amostras | " +
                "início ${Format.timestamp(plugin.store.sinceMillis)} | uptime do plugin ${Format.duration(plugin.uptimeMillis())}"
        )

        if (decision != null) {
            send(
                sender,
                "Ações: operações opcionais ${if (decision.suspendOptionalOps) "limitadas (pressão crítica)" else "normais"} | " +
                    "relatório automático ${if (decision.autoReportAllowed) "permitido" else "inativo"} | " +
                    "controle chunks ${if (decision.chunkReclaimAllowed) "permitido" else "não autorizado"} | " +
                    "controle entidades ${if (decision.entityControlAllowed) "permitido" else "não autorizado"}"
            )
        }

        if (plugin.settings.jvm.recomendar) {
            val recs = try {
                JvmAnalyzer.recommendations(plugin.jvmInfo, plugin.observedStats())
            } catch (_: Throwable) {
                emptyList()
            }
            val top = recs.take(2)
            if (top.isNotEmpty()) {
                send(sender, "Recomendações da JVM (nada é aplicado automaticamente):")
                for (r in top) {
                    send(sender, "  • ${r.title} — detalhe: ${r.detail}")
                }
            }
        }
        send(sender, "Detalhes: /otimizador memory | diagnóstico: /otimizador diagnose | relatório: /otimizador report")
    }

    // ------------------------------------------------------------------
    // memory
    // ------------------------------------------------------------------
    private fun memory(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val latest = plugin.store.latestSnapshot()
        send(sender, "=== Otimizador — Memória (${Format.timestamp(now)}) ===")

        if (latest == null) {
            send(sender, "Sem amostras ainda. Aguarde ${plugin.settings.coleta.intervaloMemoriaSegundos}s.")
            return
        }

        send(sender, "HEAP")
        send(sender, "  usado:      ${Format.bytes(latest.heapUsed)} (${Format.percent(latest.heapUsedRatio)} do máximo) — efetivamente ocupado")
        send(sender, "  comprometido:${" ".repeat(1)} ${Format.bytes(latest.heapCommitted)} — reservado (pode estar ocioso)")
        send(sender, "  máximo:     ${Format.bytes(latest.heapMax)} — limite -Xmx (não é consumo físico)")
        send(sender, "  piso pós-GC: ${latest.heapFloorUsed?.let { Format.bytes(it) } ?: "n/d (até a 1ª coleta)"}")

        send(sender, "POOLS DE HEAP")
        for (p in latest.heapPools) {
            send(
                sender,
                "  ${p.name}: usado ${Format.bytes(p.used)} | comprometido ${Format.bytes(p.committed)} | " +
                    "máximo ${if (p.max >= 0) Format.bytes(p.max) else "s/limite"} | " +
                    "pico ${Format.bytes(p.peakUsed)} | pós-GC ${p.collectionUsage?.let { Format.bytes(it) } ?: "n/d"}"
            )
        }

        send(sender, "NÃO-HEAP")
        send(sender, "  total: usado ${Format.bytes(latest.nonHeapUsed)} | comprometido ${Format.bytes(latest.nonHeapCommitted)}")
        for (p in latest.nonHeapPools.take(8)) {
            send(sender, "  ${p.name}: ${Format.bytes(p.used)} (comprometido ${Format.bytes(p.committed)})")
        }
        send(
            sender,
            "  buffers NIO: direto ${latest.directUsed?.let { Format.bytes(it) } ?: "n/d"}" +
                " (capacidade ${latest.directCapacity?.let { Format.bytes(it) } ?: "n/d"}) | " +
                "mapeado ${latest.mappedUsed?.let { Format.bytes(it) } ?: "n/d"}"
        )

        send(sender, "PROCESSO")
        send(
            sender,
            "  RSS: ${latest.rssBytes?.let { Format.bytes(it) } ?: "n/d (leitura /proc indisponível neste SO)"} | " +
                "pico: ${latest.peakRssBytes?.let { Format.bytes(it) } ?: "n/d"} | " +
                "VmSize: ${latest.vmSizeBytes?.let { Format.bytes(it) } ?: "n/d"} | swap: ${latest.swapBytes?.let { Format.bytes(it) } ?: "n/d"}"
        )
        send(
            sender,
            "  virtual comprometido (com.sun): ${latest.committedVirtualBytes?.let { Format.bytes(it) } ?: "n/d"} | " +
                "CPU do processo: ${latest.processCpuLoad?.let { Format.percent(it) } ?: "n/d"} | " +
                "NMT: ${latest.nmtLevel}"
        )
        send(sender, "  threads: ${latest.threadCount} (${latest.daemonThreads} daemon) | classes carregadas: ${latest.loadedClasses} | uptime JVM ${Format.duration(latest.jvmUptimeMs)}")

        send(sender, "GC")
        val w = plugin.store.lastGcWindow()
        if (w != null) {
            send(
                sender,
                "  janela ${Format.duration(w.wallMs)}: ${w.totalCollections} coleta(s), ${Format.duration(w.totalTimeMs)} de GC, " +
                    "overhead ${Format.percent(w.overheadRatioClamped)} (bruto ${Format.percent(w.overheadRatio, 2)})"
            )
            send(
                sender,
                "  frequência ${Format.dec(w.collectionsPerMinute)}/min | pausa média est. ${w.estimatedAvgPauseMs?.let { Format.duration(it) } ?: "n/d"} | " +
                    "pausa máx. est. ${w.estimatedMaxPauseMs?.let { Format.duration(it) } ?: "n/d"}"
            )
        }
        for (p in latest.gc.take(6)) {
            send(sender, "  coletor ${p.name}: ${Format.count(p.count)} coletas, ${Format.duration(p.timeMs)} acumulados")
        }
        val events = plugin.store.gcEvents()
        if (events.isNotEmpty()) {
            send(sender, "  eventos recentes (${events.size} retidos, TTL ${plugin.settings.historico.ttlEventosGcSegundos}s):")
            for (e in events.takeLast(5)) {
                send(sender, "    ${Format.timestamp(e.timeMillis)} | ${e.collections} coleta(s) | pausa est. ${e.estimatedPauseMs?.let { Format.duration(it) } ?: "n/d"}")
            }
        }

        val janela = plugin.settings.memoria.tendencia.janelaAmostras
        send(sender, "TENDÊNCIAS (últimas $janela amostras)")
        appendTrendLine(sender, "heap usado", plugin.store.heapUsed.last(janela))
        appendTrendLine(sender, "piso pós-GC", plugin.store.heapFloor.last(janela))
        appendTrendLine(sender, "RSS", plugin.store.rss.last(janela))
        appendTrendLine(sender, "não-heap", plugin.store.nonHeapUsed.last(janela))
        appendTrendLine(sender, "threads", plugin.store.threads.last(janela))

        send(sender, "LIMITAÇÕES: comprometido ≠ usado; máximo = configurado (não físico); RSS via /proc (Linux);")
        send(sender, "pausas de GC são estimativas com a resolução do intervalo (${plugin.settings.coleta.intervaloMemoriaSegundos}s).")
    }

    private fun appendTrendLine(sender: CommandSender, label: String, samples: List<dev.miguel.otimizador.core.TimedSample>) {
        if (samples.size < 2) {
            send(sender, "  $label: séries insuficientes (${samples.size} amostras)")
            return
        }
        val first = samples.first().value
        val last = samples.last().value
        if (label == "threads") {
            send(sender, "  $label: ${Format.dec(first, 0)} → ${Format.dec(last, 0)} (${samples.size} amostras)")
        } else {
            send(sender, "  $label: ${Format.mib(first)} → ${Format.mib(last)} (Δ ${Format.mib(last - first)} em ${samples.size} amostras)")
        }
    }

    // ------------------------------------------------------------------
    // diagnose
    // ------------------------------------------------------------------
    private fun diagnose(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val findings = try {
            plugin.diagEngine.analyze(now, plugin.store, plugin.currentState())
        } catch (t: Throwable) {
            send(sender, "Falha no diagnóstico: ${t.message}")
            return
        }
        plugin.lastFindingsExternal(findings)

        send(sender, "=== Otimizador — Diagnóstico (${Format.timestamp(now)}) ===")
        send(sender, "Estado atual: ${plugin.currentState().label} | achados: ${findings.size}")
        if (findings.isEmpty()) {
            send(sender, "Nenhum achado com os dados disponíveis.")
        }
        for (f in findings) {
            send(sender, "[${f.level.label}] ${f.title}")
            for (e in f.evidence) send(sender, "    • $e")
            for (r in f.recommendations) send(sender, "    → $r")
            if (f.requiresExternalProfiling) {
                send(sender, "    ! Requer ferramenta externa (profiler/heap dump/NMT): a API pública não fecha esta análise.")
            }
        }
        send(sender, "Dica: /otimizador report grava isto (e mais contexto) em arquivo.")
    }

    // ------------------------------------------------------------------
    // chunks
    // ------------------------------------------------------------------
    private fun chunks(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val ws = plugin.store.latestWorldSample()
        val cfg = plugin.settings.chunks
        send(sender, "=== Otimizador — Chunks ===")
        send(
            sender,
            "Config: alerta ${if (cfg.alertaPorMundo > 0) "${cfg.alertaPorMundo}/mundo" else "desligado"} | " +
                "sem-jogador ≥ ${if (cfg.alertaPercSemJogador > 0) "${cfg.alertaPercSemJogador}%" else "desligado"} | " +
                "raio de jogador ${cfg.raioJogadorBlocos} blocos"
        )
        val d = cfg.desativacao
        send(
            sender,
            "Desativação: ${if (d.habilitado) "AUTORIZADA (máx ${d.maxPorCiclo}/ciclo, a cada ${d.intervaloSegundos}s)" else "desligada (padrão)"} | " +
                "guardas: jogadores ≥ ${d.distanciaMinimaJogadores}b, spawn ≥ ${d.distanciaSpawnBlocos}b, sem entidades"
        )

        if (ws == null) {
            send(sender, "Sem amostra de mundo ainda (intervalo ${plugin.settings.coleta.intervaloMundoSegundos}s).")
            return
        }
        send(sender, "Amostra de ${Format.timestamp(ws.timeMillis)} (${Format.age(ws.timeMillis, now)}):")
        send(sender, String.format(Locale.ROOT, "  %-18s %8s %8s %7s %8s %8s", "MUNDO", "CHUNKS", "SEM-JOG", "%", "SPAWN", "TOTAL"))
        for (w in ws.worlds) {
            send(
                sender,
                String.format(
                    Locale.ROOT, "  %-18s %8s %8s %6s %8s %8s",
                    w.name.take(18),
                    Format.count(w.loadedChunks),
                    Format.count(w.chunksSemJogadorProximo),
                    Format.percent(w.percSemJogador, 0),
                    Format.count(w.chunksNaAreaDeSpawn),
                    Format.count(w.totalChunks),
                )
            )
        }
        send(sender, "\"SEM-JOG\" = chunks carregados sem jogador no raio configurado (carregamento persistente).")
        send(sender, "\"SPAWN\" = chunks na área de spawn (regra fixa do servidor; não são elegíveis à desativação).")

        val last = plugin.lastChunkReclaimResult
        if (last != null) {
            send(sender, "Último ciclo: analisados ${last.analisados}, requisições ${last.requisitados}, recusadas ${last.recusados}.")
            last.motivos.forEach { send(sender, "  • $it") }
        } else {
            send(sender, "Nenhum ciclo de desativação executado até agora.")
        }
        send(sender, "A API pública não informa QUAL plugin retém cada chunk — use /spark chunks ou profiling para atribuir.")
    }

    // ------------------------------------------------------------------
    // entities
    // ------------------------------------------------------------------
    private fun entities(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val ws = plugin.store.latestWorldSample()
        val cfg = plugin.settings.entidades
        send(sender, "=== Otimizador — Entidades ===")
        val limitePadrao = cfg.limitesPorMundo["*"] ?: 0
        send(
            sender,
            "Limites por mundo: " +
                (cfg.limitesPorMundo.entries.joinToString(", ") { "${it.key}=${if (it.value > 0) it.value else "só monitorar"}" }) +
                " | concentração máx ${Format.dec(cfg.concentracaoMaximaPercent)}%"
        )
        send(
            sender,
            "Itens no chão: alerta ≥ ${if (cfg.itensNoChao.limiarAlerta > 0) cfg.itensNoChao.limiarAlerta else "desligado"} | " +
                "remoção ${if (cfg.itensNoChao.remocao.habilitado) "AUTORIZADA (teto ${cfg.itensNoChao.remocao.maxPorMundo}/mundo, idade ≥ ${cfg.itensNoChao.remocao.idadeMinimaSegundos}s, ${cfg.itensNoChao.remocao.protegerMateriais.size} materiais protegidos)" else "desligada (padrão)"}"
        )
        send(
            sender,
            "Mobs: remoção ${if (cfg.mobs.remocao.habilitado) "AUTORIZADA (teto ${cfg.mobs.remocao.maxPorMundo}/mundo, ${cfg.mobs.remocao.ignorarTipos.size} tipos ignorados)" else "desligada (padrão)"} | " +
                "block entities: alerta ≥ ${if (cfg.blocoEntidades.limiarAlertaPorMundo > 0) cfg.blocoEntidades.limiarAlertaPorMundo else "desligado"}"
        )
        send(
            sender,
            "Proteções permanentes: nomeadas=${cfg.protegerEntidadesNomeadas}, com dados de plugin=${cfg.protegerEntidadesComDados}, " +
                "domadas/filhotes/invulneráveis/com passageiros sempre preservadas."
        )

        if (ws == null) {
            send(sender, "Sem amostra de mundo ainda (intervalo ${plugin.settings.coleta.intervaloMundoSegundos}s).")
            return
        }
        send(sender, "Amostra de ${Format.timestamp(ws.timeMillis)} (${Format.age(ws.timeMillis, now)}):")
        send(sender, String.format(Locale.ROOT, "  %-16s %8s %7s %8s %8s %9s %9s", "MUNDO", "ENTID.", "ITENS", "NOMEADAS", "C/PDC", "BLOCK-E", "JOGADORES"))
        for (w in ws.worlds) {
            send(
                sender,
                String.format(
                    Locale.ROOT, "  %-16s %8s %7s %8s %8s %9s %9s",
                    w.name.take(16),
                    Format.count(w.entities),
                    Format.count(w.itensNoChao),
                    Format.count(w.entidadesNomeadas),
                    Format.count(w.entidadesComDados),
                    Format.count(w.blockEntities),
                    Format.count(w.players),
                )
            )
            if (w.topEntityTypes.isNotEmpty()) {
                send(sender, "    top: " + w.topEntityTypes.take(6).joinToString(", ") { "${it.first}=${it.second}" })
            }
            if (limitePadrao > 0 && w.entities > limitePadrao) {
                send(sender, "    ! acima do limite configurado ($limitePadrao)")
            }
        }

        val last = plugin.lastEntityCleanup
        if (last != null && last.autorizado) {
            send(sender, "Último ciclo de remoção:")
            for (r in last.results) {
                if (r.removidosItens > 0 || r.removidosMobs > 0) {
                    send(sender, "  ${r.world}: ${r.removidosItens} item(ns), ${r.removidosMobs} mob(s) — ${r.observacao}")
                }
            }
        } else {
            send(sender, "Nenhum ciclo de remoção executado (remoção desligada por padrão).")
        }
    }

    // ------------------------------------------------------------------
    // report
    // ------------------------------------------------------------------
    private fun report(sender: CommandSender) {
        if (!plugin.settings.relatorios.habilitado) {
            send(sender, "Relatórios estão desativados (relatorios.habilitado: false).")
            return
        }
        send(sender, "Gerando relatório completo (thread assíncrona)...")
        plugin.generateReportAsync("manual por ${sender.name}") { file: File? ->
            // callback chega em thread assíncrona: volta para a main thread
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (file == null) {
                    send(sender, "Falha ao gerar o relatório (veja o console).")
                } else {
                    send(sender, "Relatório gerado: plugins/Otimizador/${plugin.settings.relatorios.diretorio}/${file.name} (${Format.bytes(file.length())})")
                    send(sender, "Rotação: máximo de ${plugin.settings.relatorios.maxArquivos} arquivos mantidos.")
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // optimize
    // ------------------------------------------------------------------
    private fun optimize(sender: CommandSender) {
        send(sender, "=== Otimizador — Otimizações seguras autorizadas ===")
        var feitas = 0

        // 1) caches internos: expira entradas inativas (nunca toca em terceiros)
        val removidas = plugin.store.taskTracker.cleanup()
        send(sender, "1. Caches internos: ${removidas} série(s) expirada(s) liberada(s) do Otimizador.")
        feitas++

        // 2) rotação de relatórios
        if (plugin.settings.relatorios.habilitado) {
            plugin.reportWriter.trim()
            send(sender, "2. Relatórios: rotação aplicada (máx ${plugin.settings.relatorios.maxArquivos} arquivos, agora ${plugin.reportWriter.currentCount()}).")
            feitas++
        } else {
            send(sender, "2. Relatórios: desativados na configuração.")
        }

        // 3) chunks (opt-in)
        if (plugin.settings.chunks.desativacao.habilitado) {
            plugin.runChunkReclaimCycle(trigger = "manual (/otimizador optimize)")
            feitas++
        } else {
            send(sender, "3. Chunks: desativação NÃO autorizada — nada foi descarregado. Autorize com chunks.desativacao.habilitado: true")
        }

        // 4) entidades (opt-in)
        val remocaoItens = plugin.settings.entidades.itensNoChao.remocao.habilitado
        val remocaoMobs = plugin.settings.entidades.mobs.remocao.habilitado
        if (remocaoItens || remocaoMobs) {
            plugin.runEntityCleanupCycle(trigger = "manual (/otimizador optimize)")
            feitas++
        } else {
            send(sender, "4. Entidades: remoção NÃO autorizada — nada foi removido. Autorize em entidades.*.remocao.habilitado: true")
        }

        send(sender, "Aplicadas: $feitas ação(ões). Nada além do autorizado foi feito:")
        send(sender, "sem System.gc(), sem reinício, sem descarregamento forçado, sem apagar dados do servidor.")
        send(sender, "Observação: a redução real de RAM vem da origem do consumo — veja /otimizador diagnose.")
    }

    // ------------------------------------------------------------------
    // modules
    // ------------------------------------------------------------------
    private fun modules(sender: CommandSender) {
        val now = System.currentTimeMillis()
        send(sender, "=== Otimizador — Módulos ===")
        for (m in plugin.modulesSnapshot()) {
            val estado = if (m.enabled) "ativo" else "inativo"
            val intervalo = m.intervalSeconds?.let { " | intervalo ${Format.dec(it, 0)}s" } ?: ""
            val ultimo = m.lastRunMillis?.let { " | última execução ${Format.age(it, now)}" } ?: " | ainda não executou"
            send(sender, "  • ${m.name}: $estado$intervalo$ultimo | registros: ${m.records}${if (m.note.isNotEmpty()) " | ${m.note}" else ""}")
        }
        val resumo = plugin.store
        send(
            sender,
            "Armazenamento: heap=${resumo.heapUsed.size}/${resumo.heapUsed.capacity}, rss=${resumo.rss.size}/${resumo.rss.capacity}, " +
                "GC=${resumo.gcOverhead.size}/${resumo.gcOverhead.capacity}, mundos=${resumo.worldSamples().size}/60, " +
                "evidências=${resumo.evidences().size}/${plugin.settings.historico.maxEvidencias}"
        )
    }

    // ------------------------------------------------------------------
    // reload
    // ------------------------------------------------------------------
    private fun reload(sender: CommandSender) {
        send(sender, "Recarregando config.yml (o arquivo nunca é sobrescrito)...")
        val result = plugin.reloadConfigurationFromDisk()
        send(sender, "Configuração recarregada com ${result.warnings.size} aviso(s). Histórico preservado.")
        for (w in result.warnings.take(10)) {
            send(sender, "  ! $w")
        }
        if (result.warnings.size > 10) send(sender, "  ...e mais ${result.warnings.size - 10} (veja o console)")
        send(
            sender,
            "Estado do classificador preservado: ${plugin.currentState().label} | " +
                "coleta memória ${plugin.settings.coleta.intervaloMemoriaSegundos}s, mundo ${plugin.settings.coleta.intervaloMundoSegundos}s"
        )
    }

    // ------------------------------------------------------------------
    // help
    // ------------------------------------------------------------------
    private fun help(sender: CommandSender) {
        send(sender, "=== Otimizador — Ajuda ===")
        send(sender, "/otimizador status    — resumo de memória e estado (otimizador.status)")
        send(sender, "/otimizador memory    — métricas detalhadas: heap, pools, GC, RSS, tendências (otimizador.memory)")
        send(sender, "/otimizador diagnose  — diagnóstico de possíveis causas com evidências (otimizador.diagnose)")
        send(sender, "/otimizador chunks    — relatório de chunks por mundo (otimizador.chunks)")
        send(sender, "/otimizador entities  — relatório de entidades, itens e block entities (otimizador.entities)")
        send(sender, "/otimizador report    — gera relatório completo em arquivo (otimizador.report)")
        send(sender, "/otimizador optimize  — aplica apenas otimizações seguras autorizadas (otimizador.optimize)")
        send(sender, "/otimizador modules   — consulta módulos ativos e armazenamento (otimizador.modules)")
        send(sender, "/otimizador reload    — recarrega config.yml com validação (otimizador.reload)")
        send(sender, "/otimizador help      — esta ajuda (otimizador.help)")
        send(sender, "Permissão total: otimizador.admin (default: op).")
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size > 1) return emptyList()
        val prefix = args.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
        return SUBCOMANDOS
            .filter { it.startsWith(prefix) }
            .filter { sender.hasPermission(PERMISSOES[it] ?: "otimizador.admin") }
            .sorted()
    }

    private fun send(sender: CommandSender, message: String) {
        sender.sendMessage(message)
    }
}
