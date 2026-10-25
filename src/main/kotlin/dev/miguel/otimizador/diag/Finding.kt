package dev.miguel.otimizador.diag

/**
 * Nível de um achado de diagnóstico.
 */
enum class FindingLevel(val label: String, val order: Int) {
    INFO("Info", 0),
    ATENCAO("Atenção", 1),
    ALERTA("Alerta", 2),
    CRITICO("Crítico", 3),
}

/**
 * Um achado do diagnóstico: o que foi observado, quando, com quais evidências
 * e o que recomenda. [requiresExternalProfiling] marca achados que a API
 * pública do Paper NÃO consegue fechar sozinha (ex.: atribuir consumo a uma
 * classe ou plugin) — nesses casos o relatório orienta profiling externo em
 * vez de afirmar conclusões.
 */
data class Finding(
    val id: String,
    val level: FindingLevel,
    val title: String,
    val atMillis: Long,
    val evidence: List<String>,
    val recommendations: List<String>,
    val requiresExternalProfiling: Boolean = false,
)
