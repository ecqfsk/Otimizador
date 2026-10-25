package dev.miguel.otimizador.core

/**
 * Estados de pressão de memória do servidor.
 */
enum class MemoryState(val label: String, val level: Int) {
    NORMAL("Normal", 0),
    ATENCAO("Atenção", 1),
    PRESSAO_ALTA("Pressão elevada", 2),
    PRESSAO_CRITICA("Pressão crítica", 3);

    companion object {
        fun ofLevel(level: Int): MemoryState = entries.firstOrNull { it.level == level } ?: NORMAL
    }
}

/**
 * Limiares configurados (percentuais de 0 a 100) + parâmetros de estabilidade.
 */
data class PressureThresholds(
    /** Percentual do heap usado em relação ao máximo para cada estado (3 valores). */
    val heapPercent: DoubleArray,
    /** Overhead de GC (%) para cada estado (3 valores). */
    val gcOverheadPercent: DoubleArray,
    /** Teto de RSS em bytes; 0 = desativado. Acima dele o estado nunca fica abaixo de ATENCAO. */
    val rssLimitBytes: Long,
    /** Histerese em pontos percentuais (evita alternância constante). */
    val hysteresisPoints: Double,
    /** Persistência mínima (ms) antes de subir de nível. */
    val persistUpMs: Long,
    /** Persistência mínima (ms) antes de descer de nível. */
    val persistDownMs: Long,
) {
    init {
        require(heapPercent.size == 3 && gcOverheadPercent.size == 3) {
            "é esperado um limiar por nível de pressão (atenção, alta, crítica)"
        }
        require(persistUpMs >= 0 && persistDownMs >= 0) { "persistências não podem ser negativas" }
        require(hysteresisPoints >= 0.0) { "histerese não pode ser negativa" }
    }
}

/** Métricas brutas usadas na classificação de um instante. */
data class PressureInputs(
    val heapUsedRatio: Double,
    val gcOverheadRatio: Double,
    val rssBytes: Long,
)

/** Transição de estado ocorrida, com as razões que a justificaram. */
data class StateTransition(
    val from: MemoryState,
    val to: MemoryState,
    val reasons: List<String>,
    val atMillis: Long,
)

/**
 * Máquina de estados da pressão de memória com:
 *  - classificação por duas famílias de métricas (heap usado e overhead de GC)
 *    tomando o pior caso, mais um piso de RSS quando configurado;
 *  - histerese: para sair de um estado, a métrica precisa cair um patamar
 *    abaixo do limiar que o provocou;
 *  - persistência mínima: o candidato precisa permanecer por um tempo antes da
 *    transição (subidas e descidas podem ter tempos diferentes).
 *
 * Evita "flapping" (alternância constante) sem nunca forçar GC nem alterar o
 * servidor — a classificação é apenas observacional.
 */
class PressureClassifier(
    private val thresholds: PressureThresholds,
    initial: MemoryState = MemoryState.NORMAL,
    initialAtMillis: Long = 0L,
) {

    var state: MemoryState = initial
        private set

    var stateSinceMillis: Long = initialAtMillis
        private set

    var lastTransition: StateTransition? = null
        private set

    private var pending: MemoryState? = null
    private var pendingSince: Long = 0L
    private var pendingReasons: List<String> = emptyList()

    /**
     * Alimenta o classificador. Retorna a transição ocorrida ou null.
     *
     * @param nowMillis instante atual (epoch ms) — injetável para testes.
     */
    fun update(nowMillis: Long, inputs: PressureInputs): StateTransition? {
        val rawLevel = levelWith(thresholds, inputs, hysteresis = 0.0).first
        val candidateLevel: Int
        val reasons: List<String>

        if (rawLevel >= state.level) {
            // Piora: sem histerese (o limiar cru manda).
            val r = levelWith(thresholds, inputs, hysteresis = 0.0)
            candidateLevel = r.first
            reasons = r.second
        } else {
            // Melhora: exige passar abaixo do limiar menos a histerese.
            val r = levelWith(thresholds, inputs, hysteresis = thresholds.hysteresisPoints)
            candidateLevel = r.first
            reasons = r.second
        }
        val candidate = MemoryState.ofLevel(candidateLevel)
        if (candidate == state) {
            pending = null
            pendingReasons = emptyList()
            return null
        }

        val upgrading = candidate.level > state.level
        val requiredPersist = if (upgrading) thresholds.persistUpMs else thresholds.persistDownMs

        if (pending != candidate) {
            pending = candidate
            pendingSince = nowMillis
            pendingReasons = reasons
            return null
        }

        val heldFor = nowMillis - pendingSince
        if (heldFor < requiredPersist) return null

        val from = state
        state = candidate
        stateSinceMillis = pendingSince
        pending = null
        val transition = StateTransition(from, candidate, pendingReasons, nowMillis)
        pendingReasons = emptyList()
        lastTransition = transition
        return transition
    }

    /** Nível atual sem transição (para exibição). */
    fun currentLevel(inputs: PressureInputs): Pair<MemoryState, List<String>> {
        val h = if (state.level > 0) thresholds.hysteresisPoints else 0.0
        val r = levelWith(thresholds, inputs, h)
        return MemoryState.ofLevel(r.first) to r.second
    }

    /** Tempo (ms) que o estado atual já está ativo, dado o instante atual. */
    fun timeInState(nowMillis: Long): Long = (nowMillis - stateSinceMillis).coerceAtLeast(0)

    /**
     * Limiar ajustado pela histerese. O ajuste nunca reduz um limiar a menos da
     * metade do valor original: sem esse piso, um limiar baixo (ex.: overhead de
     * GC de 5%) com histerese de 5 pontos viraria 0 e métricas zeradas ou mínimas
     * manteriam o estado ativo para sempre, bloqueando qualquer descida.
     */
    private fun adjusted(limit: Double, hysteresis: Double): Double =
        limit - minOf(hysteresis, limit * 0.5)

    private fun levelWith(
        t: PressureThresholds,
        inputs: PressureInputs,
        hysteresis: Double,
    ): Pair<Int, List<String>> {
        val reasons = ArrayList<String>(2)

        var heapLevel = 0
        val heapPct = inputs.heapUsedRatio * 100.0
        for (i in 2 downTo 0) {
            val limit = adjusted(t.heapPercent[i], hysteresis)
            if (heapPct >= limit) {
                heapLevel = i + 1
                reasons.add("heap usado ${Format.dec(heapPct)}% ≥ ${Format.dec(limit)}%")
                break
            }
        }

        var gcLevel = 0
        val gcPct = inputs.gcOverheadRatio * 100.0
        for (i in 2 downTo 0) {
            val limit = adjusted(t.gcOverheadPercent[i], hysteresis)
            if (gcPct >= limit) {
                gcLevel = i + 1
                reasons.add("overhead de GC ${Format.dec(gcPct)}% ≥ ${Format.dec(limit)}%")
                break
            }
        }

        var rssLevel = 0
        if (t.rssLimitBytes > 0 && inputs.rssBytes >= t.rssLimitBytes) {
            rssLevel = 1
            reasons.add(
                "RSS ${Format.bytes(inputs.rssBytes)} ≥ teto ${Format.bytes(t.rssLimitBytes)}"
            )
        }

        // Pior caso entre as famílias; RSS com limites tem "piso" em ATENCAO.
        var level = maxOf(heapLevel, gcLevel)
        if (rssLevel > 0) level = maxOf(level, rssLevel)
        if (reasons.isEmpty()) {
            reasons.add("métricas dentro dos limiares de atenção")
        }
        return level to reasons
    }
}
