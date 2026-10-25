package dev.miguel.otimizador.diag

import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.core.Format
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.core.Trend
import dev.miguel.otimizador.jvm.JvmAnalyzer
import dev.miguel.otimizador.memory.GrowthAnalyzer
import dev.miguel.otimizador.memory.MemoryStore

/**
 * Motor de diagnóstico: transforma o histórico coletado em achados com
 * evidências, horários e recomendações.
 *
 * Regras de honestidade deste motor:
 *  - nunca afirma "vazamento" — no máximo "crescimento persistente com
 *    evidências compatíveis", sempre marcando a necessidade de profiling;
 *  - nunca atribui consumo a um plugin/classe sem dados que a API pública
 *    permita (aí marca [Finding.requiresExternalProfiling]);
 *  - toda evidência carrega horário e unidade.
 */
class DiagnosticEngine(private val settings: Settings) {

    fun analyze(nowMillis: Long, store: MemoryStore, state: MemoryState): List<Finding> {
        val out = ArrayList<Finding>(12)
        val trend = settings.memoria.tendencia
        val alerts = settings.memoria.alertas

        val latest = store.latestSnapshot()
        val worldSample = store.latestWorldSample()

        // ------------------------------------------------------------------
        // 1) Crescimento persistente do piso pós-GC (sinal de possível vazamento)
        // ------------------------------------------------------------------
        val floorSeries = store.heapFloor.all()
        val useFloor = floorSeries.size >= trend.minimoAmostras
        val series = if (useFloor) floorSeries else store.heapUsed.all()
        val seriesLabel = if (useFloor) "piso pós-GC (collectionUsage)" else "heap usado"

        if (series.isNotEmpty()) {
            val signal = GrowthAnalyzer.analyze(
                samples = series,
                minSamples = trend.minimoAmostras,
                windowSamples = trend.janelaAmostras,
                minMiBPerHour = trend.crescimentoMinimoMiBHora,
            )
            if (signal.detected) {
                out.add(
                    Finding(
                        id = "crescimento-heap",
                        level = FindingLevel.ALERTA,
                        title = "Crescimento persistente do heap após ciclos de GC",
                        atMillis = nowMillis,
                        evidence = listOf(
                            "série analisada: $seriesLabel (${signal.samples} amostras, janela de ${Format.dec(signal.windowMinutes, 0)} min)",
                            "primeira medição: ${Format.mib(signal.firstMiB * 1024 * 1024)}; última: ${Format.mib(signal.lastMiB * 1024 * 1024)} (Δ ${Format.mib(signal.deltaMiB * 1024 * 1024)})",
                            "taxa: ${Format.rateMiBPerHour(signal.slopeMiBPerHour)} com aderência R²=${Format.dec(signal.r2, 2)}",
                            "horário da última amostra: ${Format.timestamp(nowMillis)}",
                        ),
                        recommendations = listOf(
                            "Comportamento compatível com retenção de objetos, mas NÃO prova vazamento: caches legítimos e aquecimento também crescem.",
                            "Gere um heap dump em horário de pico: jcmd <pid> GC.heap_dump=/tmp/heap.hprof (ferramenta externa) e analise com Eclipse MAT ou VisualVM.",
                            "Rode um profiler de alocação (async-profiler, spark profiler) para identificar classes/plugins responsáveis.",
                            "Correlacione o início do crescimento com instalações/atualizações de plugins (horários nas evidências).",
                        ),
                        requiresExternalProfiling = true,
                    )
                )
            } else {
                out.add(
                    Finding(
                        id = "tendencia-heap",
                        level = FindingLevel.INFO,
                        title = "Tendência do heap sob controle",
                        atMillis = nowMillis,
                        evidence = listOf(
                            "série: $seriesLabel — ${signal.reason}",
                            "última leitura: ${latest?.let { Format.bytes(it.heapUsed) } ?: "n/d"} de ${latest?.let { Format.bytes(it.heapMax) } ?: "n/d"} (heap máximo configurado)",
                        ),
                        recommendations = emptyList(),
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // 2) Overhead de GC observado
        // ------------------------------------------------------------------
        val gcWindow = store.lastGcWindow()
        if (gcWindow != null && gcWindow.wallMs > 0) {
            val pct = gcWindow.overheadRatioClamped * 100.0
            val level = when {
                pct >= alerts.gcCriticoPercent -> FindingLevel.CRITICO
                pct >= alerts.gcAltaPercent -> FindingLevel.ALERTA
                pct >= alerts.gcAtencaoPercent -> FindingLevel.ATENCAO
                else -> null
            }
            if (level != null) {
                out.add(
                    Finding(
                        id = "gc-overhead",
                        level = level,
                        title = "Tempo de GC representativo no intervalo medido",
                        atMillis = nowMillis,
                        evidence = listOf(
                            "overhead de GC: ${Format.dec(pct)}% em janela de ${Format.duration(gcWindow.wallMs)} (limiares: ${Format.dec(alerts.gcAtencaoPercent)}/${Format.dec(alerts.gcAltaPercent)}/${Format.dec(alerts.gcCriticoPercent)}%)",
                            "coletas no intervalo: ${gcWindow.totalCollections} (${Format.dec(gcWindow.collectionsPerMinute)}/min), tempo total ${Format.duration(gcWindow.totalTimeMs)}",
                            "pausa média estimada: ${gcWindow.estimatedAvgPauseMs?.let { Format.duration(it) } ?: "n/d"}; pausa máxima estimada: ${gcWindow.estimatedMaxPauseMs?.let { Format.duration(it) } ?: "n/d"} (resolução = intervalo de coleta)",
                            "coletores: ${latest?.gc?.joinToString(", ") { it.name } ?: "n/d"}",
                        ),
                        recommendations = listOf(
                            "Investigue o que estava ativo no horário: geração de chunks, entidades, plugins com tarefas intensas (use /otimizador diagnose e /otimizador chunks).",
                            "Overhead alto de GC normalmente é SINTOMA de alocação excessiva: descubra a origem com profiler, não com System.gc().",
                            "Ajustes de heap/GC só no script de inicialização e sempre com benchmark antes/depois (ver /otimizador status → recomendações).",
                        ),
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // 3) Heap próximo do limite
        // ------------------------------------------------------------------
        if (latest != null) {
            val pct = latest.heapUsedRatio * 100.0
            val level = when {
                pct >= alerts.heapCriticoPercent -> FindingLevel.CRITICO
                pct >= alerts.heapAltaPercent -> FindingLevel.ALERTA
                pct >= alerts.heapAtencaoPercent -> FindingLevel.ATENCAO
                else -> null
            }
            if (level != null) {
                out.add(
                    Finding(
                        id = "heap-proximo-do-limite",
                        level = level,
                        title = "Heap usado próximo do limite configurado",
                        atMillis = latest.timeMillis,
                        evidence = listOf(
                            "heap usado: ${Format.bytes(latest.heapUsed)} (${Format.dec(pct)}%) em ${Format.timestamp(latest.timeMillis)}",
                            "heap comprometido: ${Format.bytes(latest.heapCommitted)} (reservado pela JVM, não necessariamente ocupado)",
                            "heap máximo (-Xmx): ${Format.bytes(latest.heapMax)} (limite configurado, não é consumo físico)",
                        ),
                        recommendations = listOf(
                            "Reduza a origem do consumo antes de aumentar -Xmx (chunks/entidades/plugins sob pressão).",
                            "Se o heap for constantemente apertado, avalie -Xmx maior SOMENTE com medição em condições equivalentes.",
                        ),
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // 4) RSS muito acima do heap (memória fora do heap)
        // ------------------------------------------------------------------
        if (latest != null && latest.rssBytes != null) {
            val diff = latest.rssBytes - latest.heapUsed
            if (diff >= 1024L * 1024 * 1024) {
                out.add(
                    Finding(
                        id = "rss-fora-do-heap",
                        level = if (diff >= 2L * 1024 * 1024 * 1024) FindingLevel.ALERTA else FindingLevel.ATENCAO,
                        title = "Memória residente do processo muito acima do heap usado",
                        atMillis = latest.timeMillis,
                        evidence = listOf(
                            "RSS (memória residente): ${Format.bytes(latest.rssBytes)}; heap usado: ${Format.bytes(latest.heapUsed)}; diferença: ${Format.bytes(diff)}",
                            "heap comprometido: ${Format.bytes(latest.heapCommitted)}; não-heap: ${Format.bytes(latest.nonHeapUsed)} (comprometido: ${Format.bytes(latest.nonHeapCommitted)})",
                            "buffers diretos: ${latest.directUsed?.let { Format.bytes(it) } ?: "n/d"}; classes carregadas: ${latest.loadedClasses}; threads: ${latest.threadCount}",
                            "Native Memory Tracking: ${latest.nmtLevel}",
                            "fontes conhecidas fora do heap: pilhas de threads, code cache, metaspace, buffers NIO, overhead do GC e do runtime",
                        ),
                        recommendations = listOf(
                            if (latest.nmtLevel == "off")
                                "Ative -XX:NativeMemoryTracking=summary no script de start e use `jcmd <pid> VM.native_memory summary` (ferramenta externa)."
                            else
                                "Execute `jcmd <pid> VM.native_memory summary` duas vezes (mesmas condições) e compare as seções.",
                            "A API pública do Java não expõe a composição do RSS: sem NMT/perf não é possível atribuir essa diferença.",
                        ),
                        requiresExternalProfiling = true,
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // 5) Crescimento de memória não-heap
        // ------------------------------------------------------------------
        val nonHeapSeries = store.nonHeapUsed.all()
        if (nonHeapSeries.size >= trend.minimoAmostras) {
            val signal = GrowthAnalyzer.analyze(
                samples = nonHeapSeries,
                minSamples = trend.minimoAmostras,
                windowSamples = trend.janelaAmostras,
                minMiBPerHour = 16.0,
            )
            if (signal.detected) {
                out.add(
                    Finding(
                        id = "crescimento-nao-heap",
                        level = FindingLevel.ATENCAO,
                        title = "Memória não-heap em crescimento persistente",
                        atMillis = nowMillis,
                        evidence = listOf(
                            "não-heap: ${signal.firstMiB} → ${signal.lastMiB} MiB (Δ ${Format.dec(signal.deltaMiB, 1)} MiB) em ${Format.dec(signal.windowMinutes, 0)} min",
                            "taxa: ${Format.rateMiBPerHour(signal.slopeMiBPerHour)} (R²=${Format.dec(signal.r2, 2)})",
                            "tipos de pool não-heap: ${latest?.nonHeapPools?.joinToString(", ") { "${it.name}=${Format.bytes(it.used)}" } ?: "n/d"}",
                        ),
                        recommendations = listOf(
                            "Crescimento de Metaspace/CodeCache costuma indicar carregamento contínuo de classes (plugins recarregados, reflect em loop).",
                            "Valide com `jcmd <pid> VM.native_memory summary` e verificando recargas de plugins (hot-reload não suportado é o esperado).",
                        ),
                        requiresExternalProfiling = true,
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // 6) Chunks
        // ------------------------------------------------------------------
        if (worldSample != null) {
            for (w in worldSample.worlds) {
                val hora = Format.timestamp(worldSample.timeMillis)

                if (settings.chunks.alertaPorMundo > 0 && w.loadedChunks > settings.chunks.alertaPorMundo) {
                    out.add(
                        Finding(
                            id = "chunks-mundo:${w.name}",
                            level = FindingLevel.ATENCAO,
                            title = "Muitos chunks carregados no mundo '${w.name}'",
                            atMillis = worldSample.timeMillis,
                            evidence = listOf(
                                "${w.loadedChunks} chunks carregados (limite de alerta ${settings.chunks.alertaPorMundo}) em $hora",
                                "total de chunks gerados no mundo: ${w.totalChunks}; jogadores: ${w.players}",
                                "chunks sem jogador em ${settings.chunks.raioJogadorBlocos} blocos: ${w.chunksSemJogadorProximo} (${Format.percent(w.percSemJogador)})",
                                "chunks na área de spawn (carregamento fixo do servidor): ${w.chunksNaAreaDeSpawn}",
                            ),
                            recommendations = listOf(
                                "Identifique retenção: tickets de chunks de plugins (comandos de chunks/tickets), regiões protegidas, spawn chunks.",
                                "Se autorizado, `/otimizador optimize` executa um ciclo limitado de descarregamento por requisição segura.",
                                "Nunca descarregue chunks em uso: o Otimizador usa apenas unloadChunkRequest com guardas de distância/entidades.",
                            ),
                        )
                    )
                }

                if (settings.chunks.alertaPercSemJogador > 0 && w.loadedChunks > 0) {
                    val perc = w.percSemJogador * 100.0
                    if (perc >= settings.chunks.alertaPercSemJogador) {
                        out.add(
                            Finding(
                                id = "chunks-ociosos:${w.name}",
                                level = FindingLevel.ATENCAO,
                                title = "Carregamento persistente de chunks no mundo '${w.name}'",
                                atMillis = worldSample.timeMillis,
                                evidence = listOf(
                                    "${Format.dec(perc)}% dos chunks carregados não têm jogador próximo (${w.chunksSemJogadorProximo} de ${w.loadedChunks}) em $hora",
                                    "destes, ${w.chunksNaAreaDeSpawn} estão na área de spawn",
                                ),
                                recommendations = listOf(
                                    "Chunks de spawn são carregados por regra do servidor — conferir world spawn-chunks no settings do Paper.",
                                    "Plugins com tickets de chunk mantêm chunks vivos mesmo sem jogadores; verifique os plugins instalados.",
                                    "A API pública não informa QUAL plugin retém cada chunk: use /spark chunks ou profiling para atribuir.",
                                ),
                                requiresExternalProfiling = true,
                            )
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 7) Entidades, itens e block entities
        // ------------------------------------------------------------------
        if (worldSample != null) {
            val hora = Format.timestamp(worldSample.timeMillis)
            for (w in worldSample.worlds) {
                val limite = settings.entidades.limitesPorMundo[w.name]
                    ?: settings.entidades.limitesPorMundo["*"]
                    ?: 0
                if (limite > 0 && w.entities > limite) {
                    out.add(
                        Finding(
                            id = "entidades-mundo:${w.name}",
                            level = if (w.entities > limite * 1.5) FindingLevel.ALERTA else FindingLevel.ATENCAO,
                            title = "Entidades acima do limite no mundo '${w.name}'",
                            atMillis = worldSample.timeMillis,
                            evidence = listOf(
                                "${w.entities} entidades (limite configurado $limite) em $hora",
                                "itens no chão: ${w.itensNoChao}; nomeadas: ${w.entidadesNomeadas}; com dados de plugin: ${w.entidadesComDados}",
                                "concentração: ${w.topEntityTypes.take(3).joinToString(", ") { "${it.first}=${it.second}" }.ifEmpty { "n/d" }}",
                            ),
                            recommendations = listOf(
                                "Entidades nomeadas e com dados de plugin são preservadas por regra do plugin.",
                                "Identifique a origem: geradores de farms/spawners concentrados em um mundo.",
                                "Remoção automática está ${if (settings.entidades.itensNoChao.remocao.habilitado || settings.entidades.mobs.remocao.habilitado) "autorizada" else "DESLIGADA (padrão)"} — ative apenas em configuração explícita.",
                            ),
                        )
                    )
                }

                if (settings.entidades.itensNoChao.limiarAlerta > 0 && w.itensNoChao > settings.entidades.itensNoChao.limiarAlerta) {
                    out.add(
                        Finding(
                            id = "itens-chao:${w.name}",
                            level = FindingLevel.ATENCAO,
                            title = "Acúmulo de itens no chão no mundo '${w.name}'",
                            atMillis = worldSample.timeMillis,
                            evidence = listOf(
                                "${w.itensNoChao} itens no chão (limiar ${settings.entidades.itensNoChao.limiarAlerta}) em $hora",
                                "total de entidades do mundo: ${w.entities}",
                            ),
                            recommendations = listOf(
                                "Causa comum: mobs morrendo fora de áreas com coleta (xp/item farms, chunks sem player).",
                                "A remoção automática respeita idade mínima, teto por mundo e lista de materiais protegidos (padrão desligado).",
                            ),
                        )
                    )
                }

                if (settings.entidades.blocoEntidades.limiarAlertaPorMundo > 0 &&
                    w.blockEntities > settings.entidades.blocoEntidades.limiarAlertaPorMundo
                ) {
                    out.add(
                        Finding(
                            id = "block-entities:${w.name}",
                            level = FindingLevel.ATENCAO,
                            title = "Muitas block entities no mundo '${w.name}'",
                            atMillis = worldSample.timeMillis,
                            evidence = listOf(
                                "${w.blockEntities} block entities (${w.blockEntitiesTickaveis} tickáveis, limiar ${settings.entidades.blocoEntidades.limiarAlertaPorMundo}) em $hora",
                                "chunks carregados: ${w.loadedChunks} — block entities vivem nos chunks carregados",
                            ),
                            recommendations = listOf(
                                "Reduzir chunks carregados reduz block entities ativas: investigue a retenção de chunks primeiro.",
                                "A distribuição por tipo não é exposta pela API pública sem varredura pesada — use spark/profiler para detalhar.",
                            ),
                            requiresExternalProfiling = true,
                        )
                    )
                }

                val concentracaoMax = settings.entidades.concentracaoMaximaPercent
                if (concentracaoMax > 0 && w.entities > 100) {
                    val top = w.topEntityTypes.firstOrNull()
                    if (top != null) {
                        val share = top.second * 100.0 / w.entities
                        if (share >= concentracaoMax) {
                            out.add(
                                Finding(
                                    id = "concentracao:${w.name}",
                                    level = FindingLevel.ATENCAO,
                                    title = "Concentração anormal de '${top.first}' no mundo '${w.name}'",
                                    atMillis = worldSample.timeMillis,
                                    evidence = listOf(
                                        "${top.first}: ${top.second} de ${w.entities} entidades (${Format.dec(share)}% — limiar ${Format.dec(concentracaoMax)}%) em $hora",
                                        "top tipos: ${w.topEntityTypes.take(5).joinToString(", ") { "${it.first}=${it.second}" }}",
                                    ),
                                    recommendations = listOf(
                                        "Concentração de um único tipo geralmente aponta para farm/gerador ativo ou vazamento de spawns.",
                                        "Verifique se o tipo é item no chão (itens retidos por chunks sem coleta) ou mobs presos.",
                                    ),
                                )
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 8) Tarefas pendentes por plugin (acúmulo de agendamentos)
        // ------------------------------------------------------------------
        if (worldSample != null && worldSample.pendingTasks > 0) {
            val limiar = settings.tarefas.limiarAlertaPorPlugin
            if (limiar > 0) {
                for ((plugin, count) in worldSample.pendingTasksByPlugin) {
                    if (count >= limiar) {
                        out.add(
                            Finding(
                                id = "tarefas:$plugin",
                                level = FindingLevel.ALERTA,
                                title = "Fila de tarefas muito alta do plugin '$plugin'",
                                atMillis = worldSample.timeMillis,
                                evidence = listOf(
                                    "$count tarefas pendentes (limiar $limiar) em ${Format.timestamp(worldSample.timeMillis)}",
                                    "total de tarefas pendentes no scheduler: ${worldSample.pendingTasks}",
                                ),
                                recommendations = listOf(
                                    "Fila crescente sugere agendamentos que se reprogramam sem fim ou bloqueio de conclusão.",
                                    "Isto NÃO prova consumo de memória do plugin: confirme com profiler de alocação e heap dump.",
                                ),
                                requiresExternalProfiling = true,
                            )
                        )
                    }
                }
            }

            for (ts in store.taskTracker.snapshot()) {
                if (ts.samples < 10) continue
                val cresceu = ts.last > (ts.first * 3 / 2) && ts.last >= 500
                val persistente = ts.slopePerHour > 0 && ts.r2 >= 0.4
                if (cresceu && persistente) {
                    out.add(
                        Finding(
                            id = "tarefas-crescimento:${ts.plugin}",
                            level = FindingLevel.ATENCAO,
                            title = "Tarefas pendentes do plugin '${ts.plugin}' em crescimento",
                            atMillis = nowMillis,
                            evidence = listOf(
                                "de ${ts.first} para ${ts.last} tarefas (mín ${ts.min}, máx ${ts.max}) em ${ts.samples} amostras",
                                "taxa: ${Format.dec(ts.slopePerHour)}/h (R²=${Format.dec(ts.r2, 2)})",
                            ),
                            recommendations = listOf(
                                "Correlação de agendamentos é sinal de tarefa ativa indevidamente, não prova de vazamento.",
                                "Atualize/verifique o plugin e confirme com profiler.",
                            ),
                            requiresExternalProfiling = true,
                        )
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // 9) Crescimento de threads
        // ------------------------------------------------------------------
        val threadSeries = store.threads.all()
        if (threadSeries.size >= settings.memoria.tendencia.minimoAmostras) {
            val window = threadSeries.takeLast(settings.memoria.tendencia.janelaAmostras)
            val first = window.first().value
            val last = window.last().value
            if (last - first >= 40 && last >= first * 1.5) {
                out.add(
                    Finding(
                        id = "threads-crescimento",
                        level = FindingLevel.ATENCAO,
                        title = "Quantidade de threads em crescimento",
                        atMillis = nowMillis,
                        evidence = listOf(
                            "threads: ${Format.dec(first, 0)} → ${Format.dec(last, 0)} em ${window.size} amostras",
                            "última medição: ${latest?.threadCount ?: Format.dec(last, 0)} threads (${latest?.daemonThreads ?: 0} daemon)",
                        ),
                        recommendations = listOf(
                            "Cada thread consome pilha nativa (geralmente 1 MiB por padrão da JVM) — threads demais pressionam o RSS.",
                            "Verifique plugins que criam pool de threads próprio (ex.: HTTP/bancos) pela janela de threads por processo com `jcmd <pid> Thread.print`.",
                        ),
                        requiresExternalProfiling = true,
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // 10) Informações operacionais sempre presentes
        // ------------------------------------------------------------------
        if (latest?.nmtLevel == "off") {
            out.add(
                Finding(
                    id = "nmt-desligado",
                    level = FindingLevel.INFO,
                    title = "Native Memory Tracking desativado",
                    atMillis = nowMillis,
                    evidence = listOf("VM: NMT = off (leitura em ${Format.timestamp(nowMillis)})"),
                    recommendations = listOf(
                        "Sem NMT não é possível detalhar a memória fora do heap; ative -XX:NativeMemoryTracking=summary e reinicie (ferramenta externa).",
                    ),
                )
            )
        }

        out.add(
            Finding(
                id = "limites-api-publica",
                level = FindingLevel.INFO,
                title = "Limites desta análise",
                atMillis = nowMillis,
                evidence = listOf(
                    "A API pública do Paper/JVM não expõe consumo por classe, por objeto ou por plugin.",
                    "Heap dump, NMT, async-profiler e spark são necessários para atribuir memória a um componente específico.",
                    "Estados e alertas deste plugin usam apenas métricas seguras (JMX + /proc + contagens do servidor).",
                ),
                recommendations = listOf(
                    "Para investigação profunda: jcmd GC.heap_dump, jcmd VM.native_memory, async-profiler, spark profiler (ferramentas externas).",
                ),
                requiresExternalProfiling = true,
            )
        )

        if (state == MemoryState.PRESSAO_CRITICA || state == MemoryState.PRESSAO_ALTA) {
            out.add(
                Finding(
                    id = "estado-atual",
                    level = if (state == MemoryState.PRESSAO_CRITICA) FindingLevel.CRITICO else FindingLevel.ALERTA,
                    title = "Estado adaptativo: ${state.label}",
                    atMillis = nowMillis,
                    evidence = listOf("classificado em ${Format.timestamp(nowMillis)} a partir de heap usado e overhead de GC"),
                    recommendations = listOf("Consulte /otimizador status para os valores e /otimizador report para o relatório completo."),
                )
            )
        }

        // Remove duplicatas por id (mantém o de nível mais alto) e ordena.
        val deduped = LinkedHashMap<String, Finding>()
        for (f in out) {
            val existing = deduped[f.id]
            if (existing == null || f.level.order > existing.level.order) deduped[f.id] = f
        }
        return deduped.values
            .sortedWith(compareByDescending<Finding> { it.level.order }.thenBy { it.id })
            .take(40)
    }
}
