package dev.miguel.otimizador.controller

import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.core.PressureClassifier
import dev.miguel.otimizador.core.PressureInputs
import dev.miguel.otimizador.core.PressureThresholds
import dev.miguel.otimizador.core.StateTransition
import dev.miguel.otimizador.memory.MemorySnapshot

/** Mensagem de alerta pronta para ser emitida (console/jogadores/webhook). */
data class AlertMessage(
    val key: String,
    val state: MemoryState,
    val text: String,
)

/**
 * Resultado da avaliação adaptativa: o que o plugin PODE fazer neste instante.
 * A separação entre "decisão" e "ação" mantém toda a lógica testável sem
 * servidor e garante que nenhuma ação destrutiva aconteça sem autorização.
 */
data class ControllerDecision(
    val state: MemoryState,
    val transition: StateTransition?,
    val reasons: List<String>,
    val stateDurationMs: Long,
    /** Pausa coletas opcionais do próprio plugin (não afeta o jogo). */
    val suspendOptionalOps: Boolean,
    /** Pode gerar relatório automático agora (respeitando o intervalo mínimo). */
    val autoReportAllowed: Boolean,
    /** Pode executar ciclo de desativação de chunks (autorização dupla). */
    val chunkReclaimAllowed: Boolean,
    /** Pode executar limpeza de entidades (autorização dupla). */
    val entityControlAllowed: Boolean,
    /** Alertas liberados pelo rate-limiter (já prontos para emissão). */
    val alerts: List<AlertMessage>,
)

/**
 * Controlador adaptativo: avalia o estado da memória e devolve as ações
 * permitidas. Não chama System.gc(), não reinicia, não apaga dados — atua
 * apenas prevenindo novas alocações desnecessárias do próprio plugin e
 * orientando a investigação da origem do consumo.
 */
class PressureController(
    private val settings: Settings,
    private val classifier: PressureClassifier,
    private val alertManager: AlertManager,
) {

    fun evaluate(snapshot: MemorySnapshot, gcOverheadRatio: Double): ControllerDecision {
        val now = snapshot.timeMillis
        val inputs = PressureInputs(
            heapUsedRatio = snapshot.heapUsedRatio,
            gcOverheadRatio = gcOverheadRatio.coerceIn(0.0, 1.0),
            rssBytes = snapshot.rssBytes ?: 0L,
        )

        val transition = if (settings.controle.habilitado) {
            classifier.update(now, inputs)
        } else null

        val state = classifier.state
        val reasons = transition?.reasons
            ?: classifier.currentLevel(inputs).second

        val alerts = ArrayList<AlertMessage>(2)
        if (transition != null) {
            val key = "estado:${transition.to.name}"
            if (alertManager.tryAcquire(key)) {
                val arrow = "${transition.from.label} → ${transition.to.label}"
                alerts.add(
                    AlertMessage(
                        key = key,
                        state = transition.to,
                        text = "Pressão de memória: $arrow. Motivos: ${transition.reasons.joinToString("; ")}",
                    )
                )
            }
        }

        val acoes = settings.controle.acoes
        val critical = state == MemoryState.PRESSAO_CRITICA
        val elevated = state == MemoryState.PRESSAO_ALTA || critical

        return ControllerDecision(
            state = state,
            transition = transition,
            reasons = reasons,
            stateDurationMs = classifier.timeInState(now),
            suspendOptionalOps = critical && acoes.limitarOperacoesOpcionais && settings.controle.habilitado,
            autoReportAllowed = elevated && acoes.relatorioAutomatico && settings.controle.habilitado,
            chunkReclaimAllowed = critical && acoes.permitirControleChunksSobPressao && settings.chunks.desativacao.habilitado,
            entityControlAllowed = critical && acoes.permitirControleEntidadesSobPressao &&
                (settings.entidades.itensNoChao.remocao.habilitado || settings.entidades.mobs.remocao.habilitado),
            alerts = alerts,
        )
    }

    companion object {
        /** Constrói os limiares do classificador a partir da configuração. */
        fun thresholdsFrom(settings: Settings): PressureThresholds {
            val a = settings.memoria.alertas
            return PressureThresholds(
                heapPercent = doubleArrayOf(a.heapAtencaoPercent, a.heapAltaPercent, a.heapCriticoPercent),
                gcOverheadPercent = doubleArrayOf(a.gcAtencaoPercent, a.gcAltaPercent, a.gcCriticoPercent),
                rssLimitBytes = a.rssLimiteBytes,
                hysteresisPoints = settings.controle.hysteresisPoints,
                persistUpMs = settings.controle.persistenciaSubidaSegundos * 1000L,
                persistDownMs = settings.controle.persistenciaQuedaSegundos * 1000L,
            )
        }
    }
}
