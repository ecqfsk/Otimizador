package dev.miguel.otimizador.world

/**
 * Métricas de um mundo em um instante — coletadas na thread principal com
 * custo limitado (uma passada por mundo) e depois somente lidas.
 *
 * "chunksSemJogadorProximo" conta chunks carregados sem nenhum jogador dentro
 * do raio configurado: é a evidência de carregamento persistente (chunks de
 * spawn, tickets de plugins, regiões mantidas por outros plugins).
 */
data class WorldSnapshot(
    val name: String,
    val environment: String,
    val loadedChunks: Int,
    /** Total de chunks existentes no mundo (gerados), para contexto. */
    val totalChunks: Int,
    val chunksSemJogadorProximo: Int,
    /** Chunks carregados dentro da área de spawn (regra de carregamento fixo). */
    val chunksNaAreaDeSpawn: Int,
    val entities: Int,
    val itensNoChao: Int,
    val entidadesNomeadas: Int,
    val entidadesComDados: Int,
    val blockEntities: Int,
    val blockEntitiesTickaveis: Int,
    val players: Int,
    /** Top tipos por quantidade (limitado) — identifica concentrações anormais. */
    val topEntityTypes: List<Pair<String, Int>>,
) {
    /** Fração de chunks sem jogador próximo (0..1), para alertas. */
    val percSemJogador: Double
        get() = if (loadedChunks > 0) chunksSemJogadorProximo.toDouble() / loadedChunks else 0.0

    val concentracaoTopTipo: Pair<String, Int>?
        get() = topEntityTypes.firstOrNull()
}

/**
 * Amostra completa de mundo + fila de tarefas do scheduler.
 * [pendingTasksByPlugin] é limitado aos maiores valores (top 50) para manter
 * o objeto pequeno mesmo com milhares de tarefas pendentes.
 */
data class WorldSample(
    val timeMillis: Long,
    val worlds: List<WorldSnapshot>,
    val pendingTasks: Int,
    val pendingTasksByPlugin: Map<String, Int>,
) {
    val totalLoadedChunks: Int get() = worlds.sumOf { it.loadedChunks }
    val totalEntities: Int get() = worlds.sumOf { it.entities }
    val totalItems: Int get() = worlds.sumOf { it.itensNoChao }
}
