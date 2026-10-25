package dev.miguel.otimizador.config

/**
 * Configuração tipada e imutável do Otimizador.
 *
 * Os valores aqui já passaram pela validação do [SettingsParser]: limites
 * aplicados, valores inválidos substituídos por padrões e avisos emitidos.
 * Nenhuma parte do plugin lê o YAML diretamente.
 */
data class Settings(
    val coleta: Coleta = Coleta(),
    val historico: Historico = Historico(),
    val memoria: Memoria = Memoria(),
    val chunks: Chunks = Chunks(),
    val entidades: Entidades = Entidades(),
    val tarefas: Tarefas = Tarefas(),
    val controle: Controle = Controle(),
    val jvm: Jvm = Jvm(),
    val relatorios: Relatorios = Relatorios(),
    val logs: Logs = Logs(),
    val integracoes: Integracoes = Integracoes(),
) {
    /** Intervalo de coleta de memória em ticks (20 ticks = 1s). */
    fun intervaloMemoriaTicks(): Long = (coleta.intervaloMemoriaSegundos * 20L).coerceAtLeast(20L)

    /** Intervalo base de coleta de mundo em ticks. */
    fun intervaloMundoTicks(): Long = (coleta.intervaloMundoSegundos * 20L).coerceAtLeast(100L)

    /** Intervalo de mundo efetivo (ampliado sob pressão crítica, se autorizado). */
    fun intervaloMundoTicksEfetivo(sobPressaoCritica: Boolean): Long {
        val base = intervaloMundoTicks()
        if (!sobPressaoCritica) return base
        return base * coleta.multiplicadorPressaoCritica.coerceIn(1, 10)
    }
}

data class Coleta(
    val intervaloMemoriaSegundos: Int = 5,
    val intervaloMundoSegundos: Int = 10,
    val multiplicadorPressaoCritica: Int = 3,
)

data class Historico(
    val tamanho: Int = 720,
    val ttlEventosGcSegundos: Int = 900,
    val maxEvidencias: Int = 50,
)

data class Memoria(
    val alertas: Alertas = Alertas(),
    val tendencia: Tendencia = Tendencia(),
)

data class Alertas(
    val heapAtencaoPercent: Double = 70.0,
    val heapAltaPercent: Double = 85.0,
    val heapCriticoPercent: Double = 95.0,
    val gcAtencaoPercent: Double = 5.0,
    val gcAltaPercent: Double = 10.0,
    val gcCriticoPercent: Double = 25.0,
    /** Teto de RSS em bytes; 0 = desativado. */
    val rssLimiteBytes: Long = 0L,
)

data class Tendencia(
    val janelaAmostras: Int = 60,
    val crescimentoMinimoMiBHora: Double = 48.0,
    val minimoAmostras: Int = 20,
)

data class Chunks(
    val amostrar: Boolean = true,
    val alertaPorMundo: Int = 8192,
    val alertaPercSemJogador: Int = 70,
    val raioJogadorBlocos: Int = 128,
    val desativacao: Desativacao = Desativacao(),
)

data class Desativacao(
    val habilitado: Boolean = false,
    val distanciaMinimaJogadores: Int = 192,
    val distanciaSpawnBlocos: Int = 256,
    val maxPorCiclo: Int = 16,
    val intervaloSegundos: Int = 60,
)

data class Entidades(
    val amostrar: Boolean = true,
    /** Intervalo mínimo entre ciclos automáticos de remoção autorizados (s). */
    val intervaloRemocaoSegundos: Int = 60,
    /** Nome do mundo (ou "*" = todos) → limite de entidades (0 = só monitorar). */
    val limitesPorMundo: Map<String, Int> = mapOf("*" to 0),
    val itensNoChao: ItensNoChao = ItensNoChao(),
    val mobs: Mobs = Mobs(),
    val protegerEntidadesNomeadas: Boolean = true,
    val protegerEntidadesComDados: Boolean = true,
    val blocoEntidades: BlocoEntidades = BlocoEntidades(),
    val concentracaoMaximaPercent: Double = 60.0,
)

data class ItensNoChao(
    val limiarAlerta: Int = 300,
    val remocao: Remocao = Remocao(),
)

data class Remocao(
    val habilitado: Boolean = false,
    val maxPorMundo: Int = 120,
    val idadeMinimaSegundos: Int = 300,
    val maxPorCiclo: Int = 40,
    /** Materiais que nunca podem ser removidos (nomes do enum Material, maiúsculos). */
    val protegerMateriais: Set<String> = MATERIAIS_PROTEGIDOS_PADRAO,
)

data class BlocoEntidades(
    val limiarAlertaPorMundo: Int = 6000,
)

data class Mobs(
    val remocao: MobsRemocao = MobsRemocao(),
)

data class MobsRemocao(
    val habilitado: Boolean = false,
    val maxPorMundo: Int = 200,
    val maxPorCiclo: Int = 20,
    /** Tipos de entidade que jamais podem ser removidos (enum EntityType). */
    val ignorarTipos: Set<String> = TIPOS_IGNORADOS_PADRAO,
)

data class Tarefas(
    val amostrar: Boolean = true,
    val limiarAlertaPorPlugin: Int = 4000,
)

data class Controle(
    val habilitado: Boolean = true,
    val hysteresisPoints: Double = 5.0,
    val persistenciaSubidaSegundos: Int = 30,
    val persistenciaQuedaSegundos: Int = 60,
    val acoes: Acoes = Acoes(),
)

data class Acoes(
    val alertarConsole: Boolean = true,
    val alertarJogadores: Boolean = true,
    val intervaloAlertaSegundos: Int = 60,
    val relatorioAutomatico: Boolean = true,
    val intervaloRelatorioSegundos: Int = 600,
    val limitarOperacoesOpcionais: Boolean = true,
    val permitirControleChunksSobPressao: Boolean = false,
    val permitirControleEntidadesSobPressao: Boolean = false,
)

data class Jvm(
    val habilitado: Boolean = true,
    val recomendar: Boolean = true,
)

data class Relatorios(
    val habilitado: Boolean = true,
    val diretorio: String = "reports",
    val maxArquivos: Int = 10,
)

data class Logs(
    val nivel: String = "INFO",
    val maxAvisosPorMinuto: Int = 60,
)

data class Integracoes(
    val webhook: Webhook = Webhook(),
)

data class Webhook(
    val url: String = "",
    val timeoutSegundos: Int = 3,
) {
    val habilitado: Boolean get() = url.isNotBlank()
}

/**
 * Tipos de entidade jamais removidos por padrão: chefes, aldeões, golems,
 * decorações colocadas por jogadores, filhotes de coleta e o próprio jogador.
 */
val TIPOS_IGNORADOS_PADRAO: Set<String> = setOf(
    "PLAYER", "WITHER", "ENDER_DRAGON", "ELDER_GUARDIAN",
    "VILLAGER", "WANDERING_TRADER", "IRON_GOLEM", "SNOW_GOLEM",
    "ALLAY", "AXOLOTL", "TURTLE",
    "ARMOR_STAND", "PAINTING", "ITEM_FRAME", "GLOW_ITEM_FRAME", "INTERACTION",
)

/**
 * Materiais protegidos por padrão: itens valiosos ou de progressão que o
 * Otimizador nunca remove mesmo quando a remoção está autorizada.
 */
val MATERIAIS_PROTEGIDOS_PADRAO: Set<String> = setOf(
    "NETHERITE_INGOT", "NETHERITE_SCRAP", "DIAMOND", "EMERALD",
    "NETHERITE_SWORD", "NETHERITE_PICKAXE", "NETHERITE_AXE", "NETHERITE_SHOVEL",
    "NETHERITE_HOE", "NETHERITE_HELMET", "NETHERITE_CHESTPLATE",
    "NETHERITE_LEGGINGS", "NETHERITE_BOOTS",
    "ELYTRA", "DIAMOND_SWORD", "DIAMOND_PICKAXE", "DIAMOND_AXE",
    "DIAMOND_SHOVEL", "DIAMOND_HOE", "DIAMOND_HELMET", "DIAMOND_CHESTPLATE",
    "DIAMOND_LEGGINGS", "DIAMOND_BOOTS",
    "ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE", "TOTEM_OF_UNDYING",
    "BEEHIVE", "BEE_NEST", "SHULKER_BOX", "ENDER_CHEST",
    "BOW", "CROSSBOW", "TRIDENT", "MACE", "WIND_CHARGE",
    "NAME_TAG", "SADDLE", "CARVED_PUMPKIN",
    "HORSE_ARMOR_DIAMOND", "NETHERITE_HORSE_ARMOR",
    "DIAMOND_HORSE_ARMOR",
)
