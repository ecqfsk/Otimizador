package dev.miguel.otimizador.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validação de configuração: padrões, clamps, avisos e correções.
 * O parser é puro (mapa plano de caminhas → valores), então testamos sem
 * servidor e sem arquivos.
 */
class SettingsParserTest {

    private fun parse(vararg pairs: Pair<String, Any?>): ParseResult =
        SettingsParser.parse(pairs.toMap())

    @Test
    fun `mapa vazio devolve padroes sem avisos`() {
        val r = parse()
        assertEquals(Settings(), r.settings)
        assertTrue(r.warnings.isEmpty(), "padrões não devem gerar avisos: ${r.warnings}")
    }

    @Test
    fun `valores validos sao aplicados`() {
        val r = parse(
            "coleta.intervalo-memoria-segundos" to 15,
            "coleta.intervalo-mundo-segundos" to 30,
            "historico.tamanho" to 1000,
            "memoria.alertas.percentual-heap-atencao" to 60,
            "memoria.alertas.percentual-heap-alta" to 80,
            "memoria.alertas.percentual-heap-critico" to 92,
            "controle-adaptativo.habilitado" to false,
            "chunks.desativacao.habilitado" to true,
        )
        assertEquals(15, r.settings.coleta.intervaloMemoriaSegundos)
        assertEquals(30, r.settings.coleta.intervaloMundoSegundos)
        assertEquals(1000, r.settings.historico.tamanho)
        assertEquals(60.0, r.settings.memoria.alertas.heapAtencaoPercent)
        assertEquals(80.0, r.settings.memoria.alertas.heapAltaPercent)
        assertEquals(92.0, r.settings.memoria.alertas.heapCriticoPercent)
        assertFalse(r.settings.controle.habilitado)
        assertTrue(r.settings.chunks.desativacao.habilitado)
        assertTrue(r.warnings.isEmpty(), "não deveria haver avisos: ${r.warnings}")
    }

    @Test
    fun `valores fora do intervalo sao corrigidos com aviso`() {
        val r = parse("coleta.intervalo-memoria-segundos" to 99999)
        assertEquals(300, r.settings.coleta.intervaloMemoriaSegundos, "deve ser limitado ao máximo")
        assertTrue(r.warnings.any { it.contains("coleta.intervalo-memoria-segundos") })
        assertTrue(r.warnings.any { it.contains("fora do intervalo") })
    }

    @Test
    fun `valor negativo invalido cai para o padrao ou clamp`() {
        val r = parse("historico.tamanho" to -10)
        assertEquals(60, r.settings.historico.tamanho, "menor que o mínimo → corrigido para 60")
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun `tipo errado usa padrao com aviso`() {
        val r = parse("chunks.amostrar" to "sim")
        assertTrue(r.settings.chunks.amostrar, "padrão deve ser mantido")
        assertTrue(r.warnings.any { it.contains("chunks.amostrar") })
    }

    @Test
    fun `limiares fora de ordem voltam ao padrao`() {
        val r = parse(
            "memoria.alertas.percentual-heap-atencao" to 90,
            "memoria.alertas.percentual-heap-alta" to 70,
            "memoria.alertas.percentual-heap-critico" to 95,
        )
        assertEquals(70.0, r.settings.memoria.alertas.heapAtencaoPercent)
        assertEquals(85.0, r.settings.memoria.alertas.heapAltaPercent)
        assertEquals(95.0, r.settings.memoria.alertas.heapCriticoPercent)
        assertTrue(r.warnings.any { it.contains("fora de ordem") })
    }

    @Test
    fun `persistencia de queda menor que a de subida e corrigida`() {
        val r = parse(
            "controle-adaptativo.persistencia-subida-segundos" to 300,
            "controle-adaptativo.persistencia-queda-segundos" to 60,
        )
        assertEquals(60, r.settings.controle.persistenciaSubidaSegundos)
        assertEquals(60, r.settings.controle.persistenciaQuedaSegundos)
        assertTrue(r.warnings.any { it.contains("persistencia-queda") })
    }

    @Test
    fun `janela de tendencia maior que o historico e limitada`() {
        val r = parse(
            "historico.tamanho" to 120,
            "memoria.tendencia.janela-amostras" to 5000,
        )
        assertTrue(r.settings.memoria.tendencia.janelaAmostras <= 120)
        assertTrue(r.settings.memoria.tendencia.minimoAmostras <= r.settings.memoria.tendencia.janelaAmostras)
    }

    @Test
    fun `materiais protegidos validos sao mantidos e invalidos descartados`() {
        val r = parse(
            "entidades.itens-no-chao.remocao.proteger-materiais" to listOf("diamond", "nao_existe_xyz", "elytra")
        )
        val set = r.settings.entidades.itensNoChao.remocao.protegerMateriais
        assertTrue("DIAMOND" in set)
        assertTrue("ELYTRA" in set)
        assertTrue("NAO_EXISTE_XYZ" !in set)
        assertTrue(r.warnings.any { it.contains("desconhecido") })
    }

    @Test
    fun `tipos de mob invalidos descartados e validos mantidos`() {
        val r = parse(
            "entidades.mobs.remocao.ignorar-tipos" to listOf("villager", "TIPO_FANTASMA", "wither")
        )
        val set = r.settings.entidades.mobs.remocao.ignorarTipos
        assertTrue("VILLAGER" in set)
        assertTrue("WITHER" in set)
        assertTrue("TIPO_FANTASMA" !in set)
        assertTrue(r.warnings.any { it.contains("tipo desconhecido") })
    }

    @Test
    fun `limites por mundo por glob`() {
        val r = parse(
            "entidades.limites-por-mundo.*" to 500,
            "entidades.limites-por-mundo.mundo_minas" to 250,
        )
        assertEquals(500, r.settings.entidades.limitesPorMundo["*"])
        assertEquals(250, r.settings.entidades.limitesPorMundo["mundo_minas"])
        assertTrue(r.warnings.isEmpty(), "sem avisos esperados: ${r.warnings}")
    }

    @Test
    fun `limite por mundo invalido e ignorado com aviso`() {
        val r = parse("entidades.limites-por-mundo.mundo_x" to "muitos")
        assertTrue(r.warnings.any { it.contains("entidades.limites-por-mundo.mundo_x") })
        assertTrue("mundo_x" !in r.settings.entidades.limitesPorMundo)
    }

    @Test
    fun `diretorio inseguro e substituido`() {
        val r = parse("relatorios.diretorio" to "../../etc")
        assertEquals("reports", r.settings.relatorios.diretorio)
        assertTrue(r.warnings.any { it.contains("inseguro") })
    }

    @Test
    fun `webhook com protocolo invalido e desativado`() {
        val r = parse("integracoes.webhook.url" to "ftp://exemplo.com")
        assertFalse(r.settings.integracoes.webhook.habilitado)
        assertTrue(r.warnings.any { it.contains("webhook") })
    }

    @Test
    fun `webhook https valido e aceito`() {
        val r = parse("integracoes.webhook.url" to "https://discord.com/api/webhook/abc")
        assertTrue(r.settings.integracoes.webhook.habilitado)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `nivel de log invalido volta para INFO`() {
        val r = parse("logs.nivel" to "VERBOSE")
        assertEquals("INFO", r.settings.logs.nivel)
        assertTrue(r.warnings.any { it.contains("logs.nivel") })
    }

    @Test
    fun `rss limite em mib convertido para bytes`() {
        val r = parse("memoria.alertas.rss-limite-mib" to 4096)
        assertEquals(4096L * 1024 * 1024, r.settings.memoria.alertas.rssLimiteBytes)
    }

    @Test
    fun `booleano string e interpretado`() {
        val r = parse("controle-adaptativo.habilitado" to "false")
        assertFalse(r.settings.controle.habilitado)
        assertTrue(r.warnings.isEmpty())
    }
}
