package dev.miguel.otimizador.world

import dev.miguel.otimizador.config.Settings
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World

/**
 * Controle opcional de chunks — DESLIGADO POR PADRÃO.
 *
 * Filosofia de segurança:
 *  - só age com autorização explícita em configuração (opt-in duplo);
 *  - usa APENAS `World#unloadChunkRequest`, que é uma REQUISIÇÃO ao Paper:
 *    o próprio servidor decide se o chunk está livre para descarregar e
 *    recusa silenciosamente qualquer chunk em uso;
 *  - nunca chama `Chunk#unload` forçado, nunca interrompe operações válidas;
 *  - aplica guardas adicionais (sem jogadores por perto, longe do spawn,
 *    sem entidades) e limite rígido de requisições por ciclo.
 *
 * Objetivo: reduzir pressão de memória de carregamentos persistentes que não
 * servem a ninguém (ex.: chunks presos por tickets antigos de plugins).
 */
class ChunkController(private val settings: Settings) {

    data class Result(
        val executado: Boolean,
        val analisados: Int,
        val requisitados: Int,
        val recusados: Int,
        val motivos: List<String>,
    )

    /**
     * Executa um ciclo limitado. DEVE rodar na thread principal.
     * Retorna um resumo; nunca lança exceção.
     */
    fun reclaim(nowMillis: Long): Result {
        val cfg = settings.chunks.desativacao
        if (!cfg.habilitado) {
            return Result(false, 0, 0, 0, listOf("desativação de chunks não está autorizada (chunks.desativacao.habilitado=false)"))
        }

        var analisados = 0
        var requisitados = 0
        var recusados = 0
        val motivos = ArrayList<String>(2)

        try {
            loop@ for (world in Bukkit.getWorlds()) {
                val players = try { world.players } catch (_: Throwable) { emptyList() }
                val chunks: Array<Chunk> = try { world.loadedChunks } catch (_: Throwable) { continue@loop }

                val minDist2 = cfg.distanciaMinimaJogadores.toDouble().let { it * it }
                val spawnDist2 = cfg.distanciaSpawnBlocos.toDouble().let { it * it }
                val spawn = try { world.spawnLocation } catch (_: Throwable) { null }

                val px = DoubleArray(players.size)
                val pz = DoubleArray(players.size)
                for (i in players.indices) {
                    val loc = try { players[i].location } catch (_: Throwable) { null }
                    px[i] = loc?.x ?: 0.0
                    pz[i] = loc?.z ?: 0.0
                }

                for (chunk in chunks) {
                    if (requisitados >= cfg.maxPorCiclo) break@loop
                    analisados++
                    if (!isSafeToRequest(world, chunk, px, pz, minDist2, spawn, spawnDist2)) continue

                    val ok = try {
                        world.unloadChunkRequest(chunk.x, chunk.z)
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) requisitados++ else recusados++
                }
            }
        } catch (t: Throwable) {
            motivos.add("erro durante o ciclo: ${t.javaClass.simpleName}: ${t.message}")
        }

        if (requisitados == 0 && motivos.isEmpty()) {
            motivos.add("nenhum chunk elegível nesta rodada (guardas de segurança)")
        }

        return Result(true, analisados, requisitados, recusados, motivos)
    }

    private fun isSafeToRequest(
        world: World,
        chunk: Chunk,
        px: DoubleArray,
        pz: DoubleArray,
        minDist2: Double,
        spawn: org.bukkit.Location?,
        spawnDist2: Double,
    ): Boolean {
        try {
            if (!chunk.isLoaded) return false

            val cx = chunk.x * 16.0 + 8.0
            val cz = chunk.z * 16.0 + 8.0

            // 1) nenhum jogador próximo
            for (i in px.indices) {
                val dx = cx - px[i]
                val dz = cz - pz[i]
                if (dx * dx + dz * dz < minDist2) return false
            }

            // 2) longe da área de spawn (regras fixas de carregamento do servidor)
            if (spawn != null) {
                val sx = cx - spawn.x
                val sz = cz - spawn.z
                if (sx * sx + sz * sz < spawnDist2) return false
            }

            // 3) sem entidades carregadas no chunk (não mexer em nada em uso)
            val entities = chunk.entities
            if (entities.isNotEmpty()) return false

            return true
        } catch (_: Throwable) {
            return false
        }
    }
}
