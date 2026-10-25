package dev.miguel.otimizador.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Carrega e valida o config.yml.
 *
 * Garantias:
 *  - o arquivo do usuário NUNCA é sobrescrito (só é criado se não existir);
 *  - todo valor é validado pelo [SettingsParser] (clamps + avisos);
 *  - em recarga com erro grave, a configuração anterior é mantida;
 *  - avisos impressos no console são limitados (log inundado = poluição).
 */
class ConfigService(private val plugin: JavaPlugin) {

    @Volatile
    var current: Settings = Settings()
        private set

    @Volatile
    var lastWarnings: List<String> = emptyList()
        private set

    /** Garante o arquivo padrão (sem sobrescrever) e carrega/valida. */
    fun loadInitial(): ParseResult {
        val file = File(plugin.dataFolder, "config.yml")
        if (!file.exists()) {
            try {
                plugin.saveResource("config.yml", false)
            } catch (t: Throwable) {
                plugin.logger.severe("Não foi possível criar config.yml: ${t.message}")
            }
        }
        return reload()
    }

    /**
     * Recarrega a partir do arquivo em disco. Se o YAML não puder ser lido,
     * mantém a configuração anterior e reporta o erro.
     */
    fun reload(): ParseResult {
        val file = File(plugin.dataFolder, "config.yml")
        if (!file.exists()) {
            val result = SettingsParser.parse(emptyMap())
            current = result.settings
            lastWarnings = result.warnings
            warn("config.yml ausente — usando configuração padrão")
            return result
        }

        val flat: Map<String, Any?> = try {
            val yml = YamlConfiguration.loadConfiguration(file)
            yml.getValues(true)
        } catch (t: Throwable) {
            val previous = ParseResult(current, listOf("falha ao ler config.yml: ${t.message} — mantendo a configuração anterior"))
            lastWarnings = previous.warnings
            warn("config.yml inválido: ${t.message} — mantendo configuração anterior")
            return previous
        }

        val result = SettingsParser.parse(flat)
        current = result.settings
        lastWarnings = result.warnings
        printWarnings(result.warnings)
        plugin.logger.info(
            "Configuração carregada (${result.warnings.size} aviso(s)); " +
                "histórico=${result.settings.historico.tamanho} amostras, " +
                "coleta memória=${result.settings.coleta.intervaloMemoriaSegundos}s, " +
                "coleta mundo=${result.settings.coleta.intervaloMundoSegundos}s"
        )
        return result
    }

    private fun printWarnings(warnings: List<String>) {
        val limit = 40
        for (w in warnings.take(limit)) plugin.logger.warning("config: $w")
        if (warnings.size > limit) {
            plugin.logger.warning("config: ...e mais ${warnings.size - limit} aviso(s) — corrija o config.yml")
        }
    }

    private fun warn(msg: String) = plugin.logger.warning(msg)
}
