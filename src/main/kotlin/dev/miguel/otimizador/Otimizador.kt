package dev.miguel.otimizador

import dev.miguel.otimizador.command.OtimizadorCommand
import dev.miguel.otimizador.config.ConfigService
import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.controller.AlertManager
import dev.miguel.otimizador.controller.ControllerDecision
import dev.miguel.otimizador.controller.PressureController
import dev.miguel.otimizador.core.Format
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.core.PressureClassifier
import dev.miguel.otimizador.diag.DiagnosticEngine
import dev.miguel.otimizador.diag.Finding
import dev.miguel.otimizador.diag.FindingLevel
import dev.miguel.otimizador.integration.WebhookSender
import dev.miguel.otimizador.jvm.JvmAnalyzer
import dev.miguel.otimizador.jvm.JvmInfo
import dev.miguel.otimizador.jvm.ObservedStats
import dev.miguel.otimizador.memory.Evidence
import dev.miguel.otimizador.memory.MemorySampler
import dev.miguel.otimizador.memory.MemorySnapshot
import dev.miguel.otimizador.memory.MemoryStore
import dev.miguel.otimizador.report.ReportContext
import dev.miguel.otimizador.report.ReportGenerator
import dev.miguel.otimizador.report.ReportWriter
import dev.miguel.otimizador.world.ChunkController
import dev.miguel.otimizador.world.EntityController
import dev.miguel.otimizador.world.WorldSampler
import org.bukkit.Bukkit
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Classe principal do Otimizador.
 *
 * Arquitetura:
 *  - coleta de memória: timer ASSÍNCRONO (JMX + /proc, nunca toca no mundo);
 *  - coleta de mundo: timer SÍNCRONO com janela de tempo (custo limitado);
 *  - classificação/adaptação: pura, sobre os dados coletados;
 *  - ações destrutivas (chunks/entidades): somente com autorização explícita
 *    em configuração, com guardas e limites por ciclo.
 *
 * Ciclo de vida: tudo que é agendado é cancelado em [onDisable] e o histórico
 * é liberado — nenhuma tarefa ou referência sobrevive ao desligamento.
 */
class Otimizador : JavaPlugin() {
    // ------------------------------------------------------------------
    // Configuração e componentes
    // ------------------------------------------------------------------
    internal val configService = ConfigService(this)

    @Volatile
    internal var settings: Settings = Settings()
        private set

    internal lateinit var store: MemoryStore
        private set
    internal lateinit var sampler: MemorySampler
        private set
    internal lateinit var worldSampler: WorldSampler
        private set
    internal lateinit var alertManager: AlertManager
        private set
    internal lateinit var classifier: PressureClassifier
        private set
    internal lateinit var controller: PressureController
        private set
    internal lateinit var chunkController: ChunkController
        private set
    internal lateinit var entityController: EntityController
        private set
    internal lateinit var diagEngine: DiagnosticEngine
        private set
    internal lateinit var reportWriter: ReportWriter
        private set
    internal lateinit var reportGenerator: ReportGenerator
        private set

    internal var webhook: WebhookSender? = null
        private set

    /** Informações estáticas da JVM, coletadas uma única vez. */
    internal val jvmInfo: JvmInfo by lazy { JvmAnalyzer.collect() }

    // ------------------------------------------------------------------
    // Estado operacional
    // ------------------------------------------------------------------
    internal var lastDecision: ControllerDecision? = null
        private set
    internal var lastFindings: List<Finding> = emptyList()
        private set
    internal var lastReportFile: File? = null
        private set
    internal var lastChunkReclaimResult: ChunkController.Result? = null
        private set
    internal var lastEntityCleanup: EntityController.Result? = null
        private set

    private val startedAt = System.currentTimeMillis()
    private var nextWorldSampleAt = 0L
    private var lastAutoReportAt = 0L
    private var lastEntityCycleAt = 0L
    private var lastChunkReclaimAt = 0L

    private val modules = ConcurrentHashMap<String, ModuleState>()

    @Volatile
    private var env: EnvInfo = EnvInfo("?", listOf(20.0, 20.0, 20.0), null, 0, 0)

    @Volatile
    private var closed = false

    data class ModuleState(
        val name: String,
        val enabled: Boolean,
        val intervalSeconds: Double?,
        val lastRunMillis: Long?,
        val records: Int,
        val note: String,
    )

    private data class EnvInfo(
        val serverVersion: String,
        val tps: List<Double>,
        val msptMillis: Double?,
        val players: Int,
        val worlds: Int,
    )

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------
    override fun onEnable() {
        try {
            val parsed = configService.loadInitial()
            settings = parsed.settings
            buildComponents(parsed.settings, first = true)
            registerCommands()
            registerModules()
            scheduleTasks()

            env = EnvInfo(
                serverVersion = server.version,
                tps = listOf(20.0, 20.0, 20.0),
                msptMillis = null,
                players = server.onlinePlayers.size,
                worlds = server.worlds.size,
            )

            logger.info(
                "Otimizador ${description.version} habilitado — coleta de memória a cada " +
                    "${settings.coleta.intervaloMemoriaSegundos}s, coleta de mundo a cada " +
                    "${settings.coleta.intervaloMundoSegundos}s, histórico de ${settings.historico.tamanho} amostras."
            )
            if (!settings.chunks.desativacao.habilitado && !settings.entidades.itensNoChao.remocao.habilitado && !settings.entidades.mobs.remocao.habilitado) {
                logger.info("Controle de recursos: DESATIVADO (padrão). Somente monitoramento e diagnóstico ativos.")
            }
        } catch (t: Throwable) {
            logger.severe("Falha ao habilitar o Otimizador: ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.forEach { logger.severe("  em $it") }
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        closed = true
        try {
            server.scheduler.cancelTasks(this)
        } catch (_: Throwable) {
            // scheduler já encerrando
        }
        try {
            webhook?.close()
        } catch (_: Throwable) {
            // ignora
        }
        try {
            val amostras = store.heapUsed.size
            store.clear()
            logger.info("Otimizador desabilitado — ${amostras} amostra(s) liberadas, tarefas canceladas.")
        } catch (t: Throwable) {
            logger.warning("Limpeza parcial no disable: ${t.message}")
        }
    }

    private fun buildComponents(s: Settings, first: Boolean) {
        if (first || !::store.isInitialized) {
            store = MemoryStore(
                historyCapacity = s.historico.tamanho,
                maxEvidences = s.historico.maxEvidencias,
                gcEventTtlMillis = s.historico.ttlEventosGcSegundos * 1000L,
            )
            sampler = MemorySampler()
        }

        worldSampler = WorldSampler(s)
        alertManager = AlertManager(
            minIntervalMs = s.controle.acoes.intervaloAlertaSegundos * 1000L,
            maxPerMinute = s.logs.maxAvisosPorMinuto,
        )

        val thresholds = PressureController.thresholdsFrom(s)
        classifier = if (first || !::classifier.isInitialized) {
            PressureClassifier(thresholds, MemoryState.NORMAL, System.currentTimeMillis())
        } else {
            // preserva o estado atual em um reload, apenas troca os limiares
            PressureClassifier(thresholds, classifier.state, classifier.stateSinceMillis)
        }
        controller = PressureController(s, classifier, alertManager)

        chunkController = ChunkController(s)
        entityController = EntityController(s)
        diagEngine = DiagnosticEngine(s)
        reportWriter = ReportWriter(
            directory = File(dataFolder, s.relatorios.diretorio),
            maxFiles = s.relatorios.maxArquivos,
            logger = logger,
        )
        reportGenerator = ReportGenerator(s, store, jvmInfo)

        webhook = if (s.integracoes.webhook.habilitado) {
            WebhookSender(s.integracoes.webhook.url, s.integracoes.webhook.timeoutSegundos, logger)
        } else null
    }

    private fun registerCommands() {
        val command: PluginCommand = getCommand("otimizador")
            ?: throw IllegalStateException("comando 'otimizador' ausente no plugin.yml")
        val handler = OtimizadorCommand(this)
        command.setExecutor(handler)
        command.setTabCompleter(handler)
    }

    private fun registerModules() {
        val s = settings
        modules["memoria"] = ModuleState("memoria", true, s.coleta.intervaloMemoriaSegundos.toDouble(), null, 0, "JMX + /proc (assíncrono)")
        modules["mundo"] = ModuleState("mundo", s.chunks.amostrar && s.entidades.amostrar, s.coleta.intervaloMundoSegundos.toDouble(), null, 0, "chunks/entidades (thread principal)")
        modules["controle"] = ModuleState("controle-adaptativo", s.controle.habilitado, null, null, 0, "classificação com histerese")
        modules["diagnostico"] = ModuleState("diagnose", true, s.coleta.intervaloMemoriaSegundos.toDouble(), null, 0, "análise de evidências")
        modules["jvm"] = ModuleState("jvm", s.jvm.habilitado, null, null, 1, "leitura única de flags/versão")
        modules["relatorios"] = ModuleState("relatorios", s.relatorios.habilitado, s.controle.acoes.intervaloRelatorioSegundos.toDouble(), null, 0, "máx ${s.relatorios.maxArquivos} arquivos")
        modules["chunks-controle"] = ModuleState("chunks-controle", s.chunks.desativacao.habilitado, s.chunks.desativacao.intervaloSegundos.toDouble(), null, 0, "unloadChunkRequest com guardas")
        modules["entidades-controle"] = ModuleState("entidades-controle", s.entidades.itensNoChao.remocao.habilitado || s.entidades.mobs.remocao.habilitado, s.entidades.intervaloRemocaoSegundos.toDouble(), null, 0, "remoção limitada com proteções")
        modules["webhook"] = ModuleState("webhook", s.integracoes.webhook.habilitado, null, null, 0, if (s.integracoes.webhook.habilitado) "alertas agregados" else "desativado")
    }

    /** Recarrega configurações preservando o histórico coletado. */
    fun reloadConfigurationFromDisk(): dev.miguel.otimizador.config.ParseResult {
        val parsed = configService.reload()
        settings = parsed.settings
        buildComponents(parsed.settings, first = false)
        registerModules()
        lastFindings = diagEngine.analyze(System.currentTimeMillis(), store, classifier.state)
        return parsed
    }

    // ------------------------------------------------------------------
    // Tarefas
    // ------------------------------------------------------------------
    private fun scheduleTasks() {
        val intervaloMemoria = settings.intervaloMemoriaTicks()

        // 1) Coleta de memória — assíncrona, nunca no tick principal.
        server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { memoryTick() },
            intervaloMemoria,
            intervaloMemoria,
        )

        // 2) Coleta de mundo — síncrona, com janela de tempo própria.
        //    O período de verificação é de 1s; a coleta real respeita o
        //    intervalo configurado (e é ampliado sob pressão crítica).
        server.scheduler.runTaskTimer(
            this,
            Runnable { worldTick() },
            40L,
            20L,
        )
    }

    /** Executa em thread assíncrona. */
    private fun memoryTick() {
        if (closed || !isEnabled) return
        val s = settings

        val snapshot: MemorySnapshot = try {
            sampler.sample()
        } catch (t: Throwable) {
            logger.fine("Falha na amostra de memória: ${t.message}")
            return
        }

        val window = try {
            store.record(snapshot)
        } catch (t: Throwable) {
            logger.fine("Falha ao registrar amostra: ${t.message}")
            return
        }

        val overhead = window?.overheadRatioClamped ?: 0.0

        // classificação + ações permitidas
        val decision = try {
            controller.evaluate(snapshot, overhead)
        } catch (t: Throwable) {
            logger.fine("Falha na avaliação adaptativa: ${t.message}")
            return
        }
        lastDecision = decision

        for (alert in decision.alerts) emitAlert(alert.state, alert.text)

        // diagnóstico (estruturas limitadas; custo decrescente)
        try {
            val findings = diagEngine.analyze(snapshot.timeMillis, store, decision.state)
            lastFindings = findings
            for (f in findings) {
                if (f.level.order >= FindingLevel.ATENCAO.order) {
                    store.addEvidence(
                        Evidence(
                            id = f.id,
                            timeMillis = f.atMillis,
                            message = "[${f.level.label}] ${f.title}",
                            details = f.evidence.take(3),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            logger.fine("Falha no diagnóstico: ${t.message}")
        }

        updateModules(snapshot, decision)

        // relatório automático sob pressão (limitado por intervalo)
        if (decision.autoReportAllowed && s.relatorios.habilitado) {
            val now = snapshot.timeMillis
            val intervaloMs = s.controle.acoes.intervaloRelatorioSegundos * 1000L
            if (now - lastAutoReportAt >= intervaloMs) {
                lastAutoReportAt = now
                generateReportAsync("automático (${decision.state.label})")
            }
        }

        // controle de chunks sob pressão (opt-in duplo) — precisa da main thread
        if (decision.chunkReclaimAllowed) {
            val now = snapshot.timeMillis
            val intervaloMs = s.chunks.desativacao.intervaloSegundos * 1000L
            if (now - lastChunkReclaimAt >= intervaloMs) {
                lastChunkReclaimAt = now
                runChunkReclaimCycle(trigger = "pressão crítica")
            }
        }

        // controle de entidades sob pressão (opt-in duplo) — main thread
        if (decision.entityControlAllowed) {
            val now = snapshot.timeMillis
            val intervaloMs = s.entidades.intervaloRemocaoSegundos * 1000L
            if (now - lastEntityCycleAt >= intervaloMs) {
                lastEntityCycleAt = now
                runEntityCleanupCycle(trigger = "pressão crítica")
            }
        }
    }

    /** Executa na thread principal (janela de tempo controlada). */
    private fun worldTick() {
        if (closed || !isEnabled) return
        val now = System.currentTimeMillis()
        if (now < nextWorldSampleAt) return
        val s = settings

        if (s.chunks.amostrar || s.entidades.amostrar || s.tarefas.amostrar) {
            try {
                val sample = worldSampler.sample(now)
                store.recordWorld(sample)
                modules["mundo"] = modules["mundo"]?.copy(lastRunMillis = now, records = store.worldSamples().size)
                    ?: ModuleState("mundo", true, null, now, 1, "")
            } catch (t: Throwable) {
                logger.fine("Falha na coleta de mundo: ${t.message}")
            }
        }

        // captura de ambiente na thread principal (TPS/MSPT/jogadores)
        try {
            val tps = Bukkit.getTPS()
            val tickTimes = Bukkit.getTickTimes()
            val mspt = if (tickTimes.isNotEmpty()) tickTimes.average() / 1_000_000.0 else null
            env = EnvInfo(
                serverVersion = server.version,
                tps = tps.toList(),
                msptMillis = mspt,
                players = server.onlinePlayers.size,
                worlds = server.worlds.size,
            )
        } catch (_: Throwable) {
            // ambiente permanece com o último valor conhecido
        }

        val suspend = lastDecision?.suspendOptionalOps == true
        val intervaloMs = settings.intervaloMundoTicksEfetivo(suspend) * 50L
        nextWorldSampleAt = now + intervaloMs
    }

    // ------------------------------------------------------------------
    // Ações
    // ------------------------------------------------------------------

    /** Emite alerta de mudança de estado (console, jogadores e webhook). */
    private fun emitAlert(state: MemoryState, text: String) {
        val s = settings
        if (s.controle.acoes.alertarConsole) {
            logger.warning("[pressão de memória] $text")
        }
        if (s.controle.acoes.alertarJogadores) {
            server.scheduler.runTask(this, Runnable {
                for (player: Player in server.onlinePlayers) {
                    try {
                        if (player.hasPermission("otimizador.admin")) {
                            player.sendMessage("[Otimizador] $text")
                        }
                    } catch (_: Throwable) {
                        // jogador desconectado: ignora
                    }
                }
            })
        }
        webhook?.sendAsync("[Otimizador] $text")
    }

    /** Ciclo de descarregamento por requisição segura (thread principal). */
    fun runChunkReclaimCycle(trigger: String) {
        if (!settings.chunks.desativacao.habilitado) return
        server.scheduler.runTask(this, Runnable {
            if (closed || !isEnabled) return@Runnable
            val result = try {
                chunkController.reclaim(System.currentTimeMillis())
            } catch (t: Throwable) {
                ChunkController.Result(true, 0, 0, 0, listOf("erro: ${t.message}"))
            }
            lastChunkReclaimResult = result
            modules["chunks-controle"] = modules["chunks-controle"]?.copy(lastRunMillis = System.currentTimeMillis())
                ?: ModuleState("chunks-controle", true, null, System.currentTimeMillis(), 0, "")
            if (result.executado) {
                logger.info(
                    "Ciclo de chunks ($trigger): ${result.requisitados} requisição(ões) aceita(s), " +
                        "${result.recusados} recusada(s) em ${result.analisados} chunk(s) analisado(s)."
                )
                store.addEvidence(
                    Evidence(
                        id = "chunks-ciclo",
                        timeMillis = System.currentTimeMillis(),
                        message = "Ciclo de desativação de chunks ($trigger): ${result.requisitados} requisições",
                        details = result.motivos,
                    )
                )
            }
        })
    }

    /** Ciclo de limpeza de entidades (thread principal). */
    fun runEntityCleanupCycle(trigger: String) {
        val s = settings
        if (!s.entidades.itensNoChao.remocao.habilitado && !s.entidades.mobs.remocao.habilitado) return
        server.scheduler.runTask(this, Runnable {
            if (closed || !isEnabled) return@Runnable
            val result = try {
                entityController.cleanup()
            } catch (t: Throwable) {
                EntityController.Result(false, emptyList())
            }
            lastEntityCleanup = result
            modules["entidades-controle"] = modules["entidades-controle"]?.copy(lastRunMillis = System.currentTimeMillis())
                ?: ModuleState("entidades-controle", true, null, System.currentTimeMillis(), 0, "")
            if (result.autorizado) {
                val removidos = result.results.sumOf { it.removidosItens + it.removidosMobs }
                if (removidos > 0) {
                    logger.info("Limpeza de entidades ($trigger): $removidos removido(s) com proteções aplicadas.")
                    store.addEvidence(
                        Evidence(
                            id = "entidades-ciclo",
                            timeMillis = System.currentTimeMillis(),
                            message = "Limpeza de entidades ($trigger): $removidos removido(s)",
                            details = result.results.filter { it.removidosItens > 0 || it.removidosMobs > 0 }
                                .map { "${it.world}: ${it.removidosItens} itens, ${it.removidosMobs} mobs" },
                        )
                    )
                }
            }
        })
    }

    /** Gera e grava um relatório em thread assíncrona. */
    fun generateReportAsync(reason: String, callback: ((File?) -> Unit)? = null) {
        if (!settings.relatorios.habilitado) {
            callback?.invoke(null)
            return
        }
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val file = try {
                val text = buildReport(reason)
                reportWriter.write(text)
            } catch (t: Throwable) {
                logger.warning("Falha ao gerar relatório: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (file != null) {
                lastReportFile = file
                logger.info("Relatório gerado ($reason): plugins/Otimizador/${settings.relatorios.diretorio}/${file.name} (${Format.bytes(file.length())})")
                modules["relatorios"] = modules["relatorios"]?.copy(lastRunMillis = System.currentTimeMillis(), records = reportWriter.currentCount())
                    ?: ModuleState("relatorios", true, null, System.currentTimeMillis(), 1, "")
            }
            callback?.invoke(file)
        })
    }

    /** Monta o conteúdo do relatório (puro; pode rodar fora da main thread). */
    fun buildReport(reason: String): String {
        val now = System.currentTimeMillis()
        val s = settings
        val ctx = ReportContext(
            nowMillis = now,
            pluginVersion = description.version,
            serverVersion = env.serverVersion,
            tps = env.tps,
            msptMillis = env.msptMillis,
            onlinePlayers = env.players,
            worldCount = env.worlds,
            state = classifier.state,
            stateSinceMillis = classifier.stateSinceMillis,
            modules = modules.values.map { it.name }.sorted(),
        )
        val findings = try {
            diagEngine.analyze(now, store, classifier.state)
        } catch (t: Throwable) {
            lastFindings
        }
        val recs = if (s.jvm.recomendar) {
            try {
                JvmAnalyzer.recommendations(jvmInfo, observedStats())
            } catch (_: Throwable) {
                emptyList()
            }
        } else emptyList()

        return reportGenerator.generate(ctx, findings, recs) + "\nMotivo da geração: $reason\n"
    }

    /** Estatísticas observadas a partir do histórico (para recomendações JVM). */
    fun observedStats(): ObservedStats {
        val latest = store.latestSnapshot()
        val heapMax = latest?.heapMax ?: 0L
        val peakHeap = store.heapUsed.maxOfLast(store.heapUsed.size)?.toLong() ?: 0L
        val avgHeap = run {
            val all = store.heapUsed.last(settings.memoria.tendencia.janelaAmostras)
            if (all.isEmpty()) 0L else all.map { it.value }.average().toLong()
        }
        val gcAvgPct = run {
            val all = store.gcOverhead.last(settings.memoria.tendencia.janelaAmostras)
            if (all.isEmpty()) 0.0 else all.map { it.value }.average() * 100.0
        }
        val rssLatest = store.rss.latest()?.value?.toLong()
        val rssPeak = store.rss.maxOfLast(store.rss.size)?.toLong()
        val threads = latest?.threadCount ?: 0
        val floor = latest?.heapFloorUsed
        return ObservedStats(
            peakHeapUsed = peakHeap,
            avgHeapUsed = avgHeap,
            gcOverheadPercent = gcAvgPct,
            latestRssBytes = rssLatest,
            peakRssBytes = rssPeak,
            threadCount = threads,
            heapMax = heapMax,
            heapFloorLatest = floor,
        )
    }

    // ------------------------------------------------------------------
    // Utilidades de módulos/comandos
    // ------------------------------------------------------------------

    private fun updateModules(snapshot: MemorySnapshot, decision: ControllerDecision) {
        val now = snapshot.timeMillis
        modules["memoria"] = ModuleState("memoria", true, settings.coleta.intervaloMemoriaSegundos.toDouble(), now, store.heapUsed.size, "JMX + /proc (assíncrono)")
        modules["controle"] = ModuleState(
            "controle-adaptativo",
            settings.controle.habilitado,
            null,
            now,
            1,
            "estado: ${decision.state.label}",
        )
        modules["diagnostico"] = ModuleState("diagnose", true, settings.coleta.intervaloMemoriaSegundos.toDouble(), now, lastFindings.size, "${lastFindings.size} achado(s)")
    }

    fun modulesSnapshot(): List<ModuleState> = modules.values.sortedBy { it.name }

    fun findReportDirectory(): File = File(dataFolder, settings.relatorios.diretorio)

    fun uptimeMillis(): Long = System.currentTimeMillis() - startedAt

    fun stateSinceMillis(): Long = classifier.stateSinceMillis

    fun currentState(): MemoryState = classifier.state

    // Acessos de ambiente capturados na thread principal (para comandos).
    fun envTps(): String = env.tps.joinToString(" / ") { Format.dec(it, 2) }
    fun envMspt(): String = env.msptMillis?.let { "${Format.dec(it)} ms" } ?: "n/d"
    fun envPlayers(): Int = env.players
    fun envWorlds(): Int = env.worlds

    /** Permite ao comando atualizar os achados após um diagnóstico manual. */
    fun lastFindingsExternal(findings: List<Finding>) {
        lastFindings = findings
    }
}
