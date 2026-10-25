package dev.miguel.otimizador.config

import org.bukkit.Material

/**
 * Resultado da validação: configuração pronta + avisos gerados.
 * Os avisos são exibidos no console (limitados) e no /otimizador reload.
 */
data class ParseResult(
    val settings: Settings,
    val warnings: List<String>,
)

/**
 * Parser puro e validável de configuração.
 *
 * Entrada: mapa "plano" de caminhos pontilhadas → valor (exatamente o que
 * `YamlConfiguration#getValues(true)` do Bukkit produz). Isso mantém o parser
 * independente do Bukkit e totalmente testável com mapas literais.
 *
 * Regras:
 *  - valor ausente → padrão;
 *  - valor fora do intervalo → valor corrigido (clamp) + aviso;
 *  - valor com tipo errado → padrão + aviso;
 *  - inconsistências entre campos → correção determinística + aviso.
 *
 * O arquivo do usuário NUNCA é reescrito por este parser.
 */
object SettingsParser {

    fun parse(flat: Map<String, Any?>): ParseResult {
        val w = ArrayList<String>(8)
        val s = Settings()

        // ------------------------------------------------------------------
        // coleta
        // ------------------------------------------------------------------
        val coleta = Coleta(
            intervaloMemoriaSegundos = int(flat, "coleta.intervalo-memoria-segundos", s.coleta.intervaloMemoriaSegundos, 1, 300, w),
            intervaloMundoSegundos = int(flat, "coleta.intervalo-mundo-segundos", s.coleta.intervaloMundoSegundos, 5, 600, w),
            multiplicadorPressaoCritica = int(flat, "coleta.multiplicador-pressao-critica", s.coleta.multiplicadorPressaoCritica, 1, 10, w),
        )

        // ------------------------------------------------------------------
        // historico
        // ------------------------------------------------------------------
        val historico = Historico(
            tamanho = int(flat, "historico.tamanho", s.historico.tamanho, 60, 10_000, w),
            ttlEventosGcSegundos = int(flat, "historico.ttl-eventos-gc-segundos", s.historico.ttlEventosGcSegundos, 60, 86_400, w),
            maxEvidencias = int(flat, "historico.max-evidencias", s.historico.maxEvidencias, 5, 500, w),
        )

        // ------------------------------------------------------------------
        // memoria.alertas (limiares com ordem obrigatória)
        // ------------------------------------------------------------------
        var heapAtencao = dbl(flat, "memoria.alertas.percentual-heap-atencao", s.memoria.alertas.heapAtencaoPercent, 10.0, 99.0, w)
        var heapAlta = dbl(flat, "memoria.alertas.percentual-heap-alta", s.memoria.alertas.heapAltaPercent, 11.0, 99.0, w)
        var heapCritico = dbl(flat, "memoria.alertas.percentual-heap-critico", s.memoria.alertas.heapCriticoPercent, 12.0, 100.0, w)
        if (!(heapAtencao <= heapAlta && heapAlta <= heapCritico)) {
            w.add("memoria.alertas: limiares de heap fora de ordem ($heapAtencao/$heapAlta/$heapCritico) — usando valores padrão")
            heapAtencao = s.memoria.alertas.heapAtencaoPercent
            heapAlta = s.memoria.alertas.heapAltaPercent
            heapCritico = s.memoria.alertas.heapCriticoPercent
        }

        var gcAtencao = dbl(flat, "memoria.alertas.overhead-gc-atencao-percent", s.memoria.alertas.gcAtencaoPercent, 1.0, 99.0, w)
        var gcAlta = dbl(flat, "memoria.alertas.overhead-gc-alta-percent", s.memoria.alertas.gcAltaPercent, 2.0, 99.0, w)
        var gcCritico = dbl(flat, "memoria.alertas.overhead-gc-critico-percent", s.memoria.alertas.gcCriticoPercent, 3.0, 100.0, w)
        if (!(gcAtencao <= gcAlta && gcAlta <= gcCritico)) {
            w.add("memoria.alertas: limiares de overhead de GC fora de ordem ($gcAtencao/$gcAlta/$gcCritico) — usando valores padrão")
            gcAtencao = s.memoria.alertas.gcAtencaoPercent
            gcAlta = s.memoria.alertas.gcAltaPercent
            gcCritico = s.memoria.alertas.gcCriticoPercent
        }

        val alertas = Alertas(
            heapAtencaoPercent = heapAtencao,
            heapAltaPercent = heapAlta,
            heapCriticoPercent = heapCritico,
            gcAtencaoPercent = gcAtencao,
            gcAltaPercent = gcAlta,
            gcCriticoPercent = gcCritico,
            rssLimiteBytes = long(flat, "memoria.alertas.rss-limite-mib", s.memoria.alertas.rssLimiteBytes / (1024 * 1024), 0, 1_048_576, w) * 1024 * 1024,
        )

        val tamanhoHistorico = historico.tamanho
        val janela = int(flat, "memoria.tendencia.janela-amostras", s.memoria.tendencia.janelaAmostras, 10, 10_000, w)
            .coerceAtMost(tamanhoHistorico)
        val minimo = int(flat, "memoria.tendencia.minimo-amostras", s.memoria.tendencia.minimoAmostras, 5, 10_000, w)
            .coerceAtMost(janela)

        val tendencia = Tendencia(
            janelaAmostras = janela,
            crescimentoMinimoMiBHora = dbl(flat, "memoria.tendencia.crescimento-minimo-mib-por-hora", s.memoria.tendencia.crescimentoMinimoMiBHora, 1.0, 100_000.0, w),
            minimoAmostras = minimo,
        )

        val memoria = Memoria(alertas = alertas, tendencia = tendencia)

        // ------------------------------------------------------------------
        // chunks
        // ------------------------------------------------------------------
        val chunks = Chunks(
            amostrar = bool(flat, "chunks.amostrar", s.chunks.amostrar, w),
            alertaPorMundo = int(flat, "chunks.alerta-por-mundo", s.chunks.alertaPorMundo, 0, 1_048_576, w),
            alertaPercSemJogador = int(flat, "chunks.alerta-perc-sem-jogador", s.chunks.alertaPercSemJogador, 0, 100, w),
            raioJogadorBlocos = int(flat, "chunks.raio-jogador-blocos", s.chunks.raioJogadorBlocos, 16, 1024, w),
            desativacao = Desativacao(
                habilitado = bool(flat, "chunks.desativacao.habilitado", s.chunks.desativacao.habilitado, w),
                distanciaMinimaJogadores = int(flat, "chunks.desativacao.distancia-minima-jogadores", s.chunks.desativacao.distanciaMinimaJogadores, 32, 2048, w),
                distanciaSpawnBlocos = int(flat, "chunks.desativacao.distancia-spawn-blocos", s.chunks.desativacao.distanciaSpawnBlocos, 32, 4096, w),
                maxPorCiclo = int(flat, "chunks.desativacao.max-por-ciclo", s.chunks.desativacao.maxPorCiclo, 1, 1024, w),
                intervaloSegundos = int(flat, "chunks.desativacao.intervalo-segundos", s.chunks.desativacao.intervaloSegundos, 10, 3600, w),
            ),
        )

        // ------------------------------------------------------------------
        // entidades
        // ------------------------------------------------------------------
        val limites = HashMap<String, Int>()
        val limitePrefix = "entidades.limites-por-mundo."
        for ((path, value) in flat) {
            if (!path.startsWith(limitePrefix)) continue
            val world = path.removePrefix(limitePrefix)
            if (world.isEmpty()) continue
            val n = asInt(value)
            if (n == null) {
                w.add("$path: valor inválido ($value) — ignorado")
                continue
            }
            if (n < 0 || n > 1_000_000) {
                w.add("$path: limite fora do intervalo (0..1000000) — corrigido")
            }
            limites[world] = n.coerceIn(0, 1_000_000)
        }
        if (limites.isEmpty()) limites["*"] = 0

        val materiaisValidos = HashSet<String>()
        for (m in s.entidades.itensNoChao.remocao.protegerMateriais) materiaisValidos.add(m)
        val listaBruta = flat["entidades.itens-no-chao.remocao.proteger-materiais"]
        if (listaBruta != null) {
            val list = asStringList(listaBruta)
            if (list == null) {
                w.add("entidades.itens-no-chao.remocao.proteger-materiais: formato inválido — usando lista padrão")
            } else {
                materiaisValidos.clear()
                for (raw in list) {
                    val name = raw.uppercase().trim().replace(' ', '_')
                    if (name.isEmpty()) continue
                    if (Material.getMaterial(name) == null) {
                        w.add("entidades.itens-no-chao.remocao.proteger-materiais: material desconhecido '$raw' — ignorado")
                        continue
                    }
                    materiaisValidos.add(name)
                }
            }
        }

        val tiposIgnorados = HashSet<String>()
        for (t in s.entidades.mobs.remocao.ignorarTipos) tiposIgnorados.add(t)
        val tiposBrutos = flat["entidades.mobs.remocao.ignorar-tipos"]
        if (tiposBrutos != null) {
            val list = asStringList(tiposBrutos)
            if (list == null) {
                w.add("entidades.mobs.remocao.ignorar-tipos: formato inválido — usando lista padrão")
            } else {
                tiposIgnorados.clear()
                for (raw in list) {
                    val name = raw.uppercase().trim().replace(' ', '_')
                    if (name.isEmpty()) continue
                    if (!entityTypeExists(name)) {
                        w.add("entidades.mobs.remocao.ignorar-tipos: tipo desconhecido '$raw' — ignorado")
                        continue
                    }
                    tiposIgnorados.add(name)
                }
            }
        }

        val entidades = Entidades(
            amostrar = bool(flat, "entidades.amostrar", s.entidades.amostrar, w),
            intervaloRemocaoSegundos = int(flat, "entidades.intervalo-remocao-segundos", s.entidades.intervaloRemocaoSegundos, 10, 3600, w),
            limitesPorMundo = limites,
            itensNoChao = ItensNoChao(
                limiarAlerta = int(flat, "entidades.itens-no-chao.limiar-alerta", s.entidades.itensNoChao.limiarAlerta, 0, 100_000, w),
                remocao = Remocao(
                    habilitado = bool(flat, "entidades.itens-no-chao.remocao.habilitado", s.entidades.itensNoChao.remocao.habilitado, w),
                    maxPorMundo = int(flat, "entidades.itens-no-chao.remocao.max-por-mundo", s.entidades.itensNoChao.remocao.maxPorMundo, 1, 100_000, w),
                    idadeMinimaSegundos = int(flat, "entidades.itens-no-chao.remocao.idade-minima-segundos", s.entidades.itensNoChao.remocao.idadeMinimaSegundos, 0, 86_400, w),
                    maxPorCiclo = int(flat, "entidades.itens-no-chao.remocao.max-por-ciclo", s.entidades.itensNoChao.remocao.maxPorCiclo, 1, 1000, w),
                    protegerMateriais = materiaisValidos,
                ),
            ),
            mobs = Mobs(
                remocao = MobsRemocao(
                    habilitado = bool(flat, "entidades.mobs.remocao.habilitado", s.entidades.mobs.remocao.habilitado, w),
                    maxPorMundo = int(flat, "entidades.mobs.remocao.max-por-mundo", s.entidades.mobs.remocao.maxPorMundo, 10, 100_000, w),
                    maxPorCiclo = int(flat, "entidades.mobs.remocao.max-por-ciclo", s.entidades.mobs.remocao.maxPorCiclo, 1, 1000, w),
                    ignorarTipos = tiposIgnorados,
                ),
            ),
            protegerEntidadesNomeadas = bool(flat, "entidades.proteger-entidades-nomeadas", s.entidades.protegerEntidadesNomeadas, w),
            protegerEntidadesComDados = bool(flat, "entidades.proteger-entidades-com-dados", s.entidades.protegerEntidadesComDados, w),
            blocoEntidades = BlocoEntidades(
                limiarAlertaPorMundo = int(flat, "entidades.bloco-entidades.limiar-alerta-por-mundo", s.entidades.blocoEntidades.limiarAlertaPorMundo, 0, 1_000_000, w),
            ),
            concentracaoMaximaPercent = dbl(flat, "entidades.concentracao-maxima-percent", s.entidades.concentracaoMaximaPercent, 10.0, 100.0, w),
        )

        // ------------------------------------------------------------------
        // tarefas
        // ------------------------------------------------------------------
        val tarefas = Tarefas(
            amostrar = bool(flat, "tarefas.amostrar", s.tarefas.amostrar, w),
            limiarAlertaPorPlugin = int(flat, "tarefas.limiar-alerta-por-plugin", s.tarefas.limiarAlertaPorPlugin, 0, 1_000_000, w),
        )

        // ------------------------------------------------------------------
        // controle adaptativo
        // ------------------------------------------------------------------
        var persistenciaSubida = int(flat, "controle-adaptativo.persistencia-subida-segundos", s.controle.persistenciaSubidaSegundos, 0, 3600, w)
        var persistenciaQueda = int(flat, "controle-adaptativo.persistencia-queda-segundos", s.controle.persistenciaQuedaSegundos, 1, 86_400, w)
        if (persistenciaQueda < persistenciaSubida) {
            w.add("controle-adaptativo: persistencia-queda ($persistenciaQueda s) < persistencia-subida ($persistenciaSubida s) — subida limitada ao valor de queda")
            persistenciaSubida = persistenciaQueda
        }

        val controle = Controle(
            habilitado = bool(flat, "controle-adaptativo.habilitado", s.controle.habilitado, w),
            hysteresisPoints = dbl(flat, "controle-adaptativo.histerese-percentual", s.controle.hysteresisPoints, 1.0, 50.0, w),
            persistenciaSubidaSegundos = persistenciaSubida,
            persistenciaQuedaSegundos = persistenciaQueda,
            acoes = Acoes(
                alertarConsole = bool(flat, "controle-adaptativo.acoes.alertar-console", s.controle.acoes.alertarConsole, w),
                alertarJogadores = bool(flat, "controle-adaptativo.acoes.alertar-jogadores", s.controle.acoes.alertarJogadores, w),
                intervaloAlertaSegundos = int(flat, "controle-adaptativo.acoes.intervalo-alerta-segundos", s.controle.acoes.intervaloAlertaSegundos, 5, 3600, w),
                relatorioAutomatico = bool(flat, "controle-adaptativo.acoes.relatorio-automatico", s.controle.acoes.relatorioAutomatico, w),
                intervaloRelatorioSegundos = int(flat, "controle-adaptativo.acoes.intervalo-relatorio-segundos", s.controle.acoes.intervaloRelatorioSegundos, 60, 86_400, w),
                limitarOperacoesOpcionais = bool(flat, "controle-adaptativo.acoes.limitar-operacoes-opcionais", s.controle.acoes.limitarOperacoesOpcionais, w),
                permitirControleChunksSobPressao = bool(flat, "controle-adaptativo.acoes.permitir-controle-chunks-sob-pressao", s.controle.acoes.permitirControleChunksSobPressao, w),
                permitirControleEntidadesSobPressao = bool(flat, "controle-adaptativo.acoes.permitir-controle-entidades-sob-pressao", s.controle.acoes.permitirControleEntidadesSobPressao, w),
            ),
        )

        // ------------------------------------------------------------------
        // jvm / relatorios / logs / integracoes
        // ------------------------------------------------------------------
        val jvm = Jvm(
            habilitado = bool(flat, "jvm.habilitado", s.jvm.habilitado, w),
            recomendar = bool(flat, "jvm.recomendar", s.jvm.recomendar, w),
        )

        val dirBruto = str(flat, "relatorios.diretorio", s.relatorios.diretorio, w)
        val dir = if (dirBruto.contains("..") || dirBruto.startsWith("/") || dirBruto.contains(':')) {
            w.add("relatorios.diretorio: caminho inseguro '$dirBruto' — usando 'reports'")
            "reports"
        } else dirBruto.trim().ifEmpty { "reports" }

        val relatorios = Relatorios(
            habilitado = bool(flat, "relatorios.habilitado", s.relatorios.habilitado, w),
            diretorio = dir,
            maxArquivos = int(flat, "relatorios.max-arquivos", s.relatorios.maxArquivos, 1, 100, w),
        )

        val nivelBruto = str(flat, "logs.nivel", s.logs.nivel, w).uppercase()
        val nivel = if (nivelBruto in setOf("DEBUG", "INFO", "WARN", "ERROR")) nivelBruto else {
            w.add("logs.nivel: '$nivelBruto' inválido — usando INFO")
            "INFO"
        }
        val logs = Logs(
            nivel = nivel,
            maxAvisosPorMinuto = int(flat, "logs.max-avisos-por-minuto", s.logs.maxAvisosPorMinuto, 1, 10_000, w),
        )

        val urlBruta = str(flat, "integracoes.webhook.url", s.integracoes.webhook.url, w).trim()
        val url = when {
            urlBruta.isEmpty() -> ""
            urlBruta.startsWith("https://") || urlBruta.startsWith("http://") -> urlBruta
            else -> {
                w.add("integracoes.webhook.url: protocolo não suportado (use http/https) — webhook desativado")
                ""
            }
        }
        val integracoes = Integracoes(
            webhook = Webhook(
                url = url,
                timeoutSegundos = int(flat, "integracoes.webhook.timeout-segundos", s.integracoes.webhook.timeoutSegundos, 1, 30, w),
            ),
        )

        val settings = Settings(
            coleta = coleta,
            historico = historico,
            memoria = memoria,
            chunks = chunks,
            entidades = entidades,
            tarefas = tarefas,
            controle = controle,
            jvm = jvm,
            relatorios = relatorios,
            logs = logs,
            integracoes = integracoes,
        )
        return ParseResult(settings, w)
    }

    // ----------------------------------------------------------------------
    // Leitores individuais com validação
    // ----------------------------------------------------------------------

    private fun int(
        map: Map<String, Any?>,
        path: String,
        default: Int,
        min: Int,
        max: Int,
        warnings: MutableList<String>,
    ): Int {
        val raw = map[path] ?: return default
        val v = asInt(raw)
            ?: run { warnings.add("$path: valor inválido ($raw) — usando padrão $default"); return default }
        if (v < min || v > max) {
            warnings.add("$path: $v fora do intervalo [$min..$max] — corrigido para ${v.coerceIn(min, max)}")
            return v.coerceIn(min, max)
        }
        return v
    }

    private fun long(
        map: Map<String, Any?>,
        path: String,
        default: Long,
        min: Long,
        max: Long,
        warnings: MutableList<String>,
    ): Long {
        val raw = map[path] ?: return default
        val v = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        } ?: run { warnings.add("$path: valor inválido ($raw) — usando padrão $default"); return default }
        if (v < min || v > max) {
            warnings.add("$path: $v fora do intervalo [$min..$max] — corrigido para ${v.coerceIn(min, max)}")
            return v.coerceIn(min, max)
        }
        return v
    }

    private fun dbl(
        map: Map<String, Any?>,
        path: String,
        default: Double,
        min: Double,
        max: Double,
        warnings: MutableList<String>,
    ): Double {
        val raw = map[path] ?: return default
        val v = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: run { warnings.add("$path: valor inválido ($raw) — usando padrão $default"); return default }
        if (v < min || v > max) {
            warnings.add("$path: $v fora do intervalo [$min..$max] — corrigido para ${v.coerceIn(min, max)}")
            return v.coerceIn(min, max)
        }
        return v
    }

    private fun bool(
        map: Map<String, Any?>,
        path: String,
        default: Boolean,
        warnings: MutableList<String>,
    ): Boolean {
        val raw = map[path] ?: return default
        return when (raw) {
            is Boolean -> raw
            is String -> when (raw.lowercase()) {
                "true" -> true
                "false" -> false
                else -> { warnings.add("$path: valor inválido ($raw) — usando padrão $default"); default }
            }
            else -> { warnings.add("$path: valor inválido ($raw) — usando padrão $default"); default }
        }
    }

    private fun str(
        map: Map<String, Any?>,
        path: String,
        default: String,
        warnings: MutableList<String>,
    ): String {
        val raw = map[path] ?: return default
        return when (raw) {
            is String -> raw
            else -> { warnings.add("$path: valor inválido ($raw) — usando padrão '$default'"); default }
        }
    }

    private fun asInt(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }

    private fun asStringList(raw: Any?): List<String>? = when (raw) {
        is List<*> -> raw.filterIsInstance<String>()
        is String -> listOf(raw)
        else -> null
    }

    /** Verifica a existência do tipo sem depender de exceção no fluxo normal. */
    private fun entityTypeExists(name: String): Boolean = try {
        org.bukkit.entity.EntityType.valueOf(name)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: Throwable) {
        false
    }
}
