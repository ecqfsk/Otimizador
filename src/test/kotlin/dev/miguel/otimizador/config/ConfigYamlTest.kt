package dev.miguel.otimizador.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida o config.yml EMPACOTADO contra o parser real.
 *
 * O YAML é lido com snakeyaml (mesma base do YamlConfiguration do Bukkit) e
 * achatado no mesmo formato de caminho pontilhado que o ConfigService entrega
 * ao parser — assim qualquer chave mal escrita, valor fora de faixa ou
 * divergência entre arquivo e padrão aparece aqui, sem servidor.
 *
 * O config é propositalmente mínimo: tudo que não é ajustado com frequência
 * vive como padrão no código (Settings).
 */
class ConfigYamlTest {

    private fun flatConfig(): Map<String, Any?> {
        val file = File("src/main/resources/config.yml")
        assertTrue(file.isFile, "config.yml deve existir em src/main/resources")

        val root: Any? = file.inputStream().use { org.yaml.snakeyaml.Yaml().load(it) }
        val out = LinkedHashMap<String, Any?>()

        fun flatten(prefix: String, node: Any?) {
            when (node) {
                is Map<*, *> -> for ((k, v) in node) {
                    val key = k?.toString() ?: continue
                    flatten(if (prefix.isEmpty()) key else "$prefix.$key", v)
                }
                null -> Unit
                else -> out[prefix] = node
            }
        }
        flatten("", root)
        return out
    }

    @Test
    fun `yaml e valido e passa na validacao sem avisos`() {
        val flat = flatConfig()
        assertTrue(flat.isNotEmpty(), "o YAML deve produzir chaves")

        val result = SettingsParser.parse(flat)
        assertTrue(
            result.warnings.isEmpty(),
            "config.yml deve validar limpo, mas gerou: ${result.warnings}",
        )
    }

    @Test
    fun `config empacotado reproduz os padroes do codigo`() {
        val result = SettingsParser.parse(flatConfig())
        assertEquals(Settings(), result.settings, "config.yml e Settings() devem coincidir")
    }

    @Test
    fun `so as chaves minimas estao presentes`() {
        val flat = flatConfig()
        val expected = setOf(
            "coleta.intervalo-memoria-segundos",
            "coleta.intervalo-mundo-segundos",
            "memoria.alertas.percentual-heap-atencao",
            "memoria.alertas.percentual-heap-alta",
            "memoria.alertas.percentual-heap-critico",
            "memoria.alertas.overhead-gc-atencao-percent",
            "memoria.alertas.overhead-gc-alta-percent",
            "memoria.alertas.overhead-gc-critico-percent",
            "controle-adaptativo.habilitado",
            "controle-adaptativo.histerese-percentual",
            "controle-adaptativo.persistencia-subida-segundos",
            "controle-adaptativo.persistencia-queda-segundos",
            "chunks.desativacao.habilitado",
            "entidades.itens-no-chao.remocao.habilitado",
            "entidades.mobs.remocao.habilitado",
        )
        assertEquals(expected, flat.keys, "config deve conter exatamente as chaves mínimas")
    }

    @Test
    fun `chaves ausentes caem nos padroes do codigo`() {
        // Remover tudo do arquivo equivale a não ter config.yml
        val result = SettingsParser.parse(emptyMap())
        assertEquals(Settings(), result.settings)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `chaves desconhecidas nao quebram a validacao`() {
        val flat = flatConfig().toMutableMap()
        flat["secao.que.nao.existe.qualquer coisa"] = "x"
        flat["coleta.chave-estranha"] = 123

        val result = SettingsParser.parse(flat)
        assertTrue(result.warnings.isEmpty(), "chaves desconhecidas devem ser ignoradas: ${result.warnings}")
        assertEquals(Settings(), result.settings)
    }

    @Test
    fun `chaves de seguranca vem desligadas por padrao`() {
        val flat = flatConfig()
        assertEquals(false, flat["chunks.desativacao.habilitado"], "descarga de chunks exige autorização")
        assertEquals(false, flat["entidades.itens-no-chao.remocao.habilitado"], "remoção de itens exige autorização")
        assertEquals(false, flat["entidades.mobs.remocao.habilitado"], "remoção de mobs exige autorização")
        assertEquals(true, flat["controle-adaptativo.habilitado"], "controle adaptativo (somente observacional) ligado")
    }

    @Test
    fun `sem comentarios extensos o arquivo permanece enxuto`() {
        val lines = File("src/main/resources/config.yml").readText().lines()
        assertTrue(lines.size <= 50, "config deve ser curto (linhas: ${lines.size})")
        val comments = lines.count { it.trimStart().startsWith("#") }
        assertTrue(comments <= 8, "comentários só em pontos de segurança (encontrados: $comments)")
    }
}
