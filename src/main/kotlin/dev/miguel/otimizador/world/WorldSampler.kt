package dev.miguel.otimizador.world

import dev.miguel.otimizador.config.Settings
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.EntityType

/**
 * Coletor de métricas de mundo — executa NA THREAD PRINCIPAL.
 *
 * Custo controlado:
 *  - uma passada por mundo sobre a lista de chunks (contagem de proximidade
 *    com jogadores usando coordenadas pré-calculadas);
 *  - uma passada sobre a lista de entidades (contagem por tipo + protegidas);
 *  - nenhum carregamento/descarregamento de chunk, nenhum acesso a blocos.
 *
 * O intervalo é configurável e é ampliado sob pressão crítica, garantindo que
 * nada pesado rode continuamente no tick principal.
 */
class WorldSampler(private val settings: Settings) {

    /** Máximo de tipos de entidade retidos por mundo (limita o objeto). */
    private val maxTopTypes = 32

    /** Máximo de plugins rastreados na fila de tarefas (limita o objeto). */
    private val maxTrackedPlugins = 50

    /** Deve ser chamado na thread principal. */
    fun sample(nowMillis: Long): WorldSample {
        val worlds: List<WorldSnapshot> = try {
            Bukkit.getWorlds().map { snapshotWorld(it) }
        } catch (t: Throwable) {
            emptyList()
        }

        val pending = if (settings.tarefas.amostrar) {
            try {
                Bukkit.getScheduler().getPendingTasks()
            } catch (_: Throwable) {
                null
            }
        } else null

        val byPlugin: Map<String, Int> = if (pending == null) {
            emptyMap()
        } else {
            try {
                pending.groupingBy { task -> task.owner.name ?: "?" }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(maxTrackedPlugins)
                    .associate { it.key to it.value }
            } catch (_: Throwable) {
                emptyMap()
            }
        }

        return WorldSample(
            timeMillis = nowMillis,
            worlds = worlds,
            pendingTasks = pending?.size ?: 0,
            pendingTasksByPlugin = byPlugin,
        )
    }

    private fun snapshotWorld(world: World): WorldSnapshot {
        // --- chunks -------------------------------------------------------
        val players = try { world.players } catch (_: Throwable) { emptyList() }
        val chunks = try { world.loadedChunks } catch (_: Throwable) { emptyArray() }

        val raio2 = settings.chunks.raioJogadorBlocos.toDouble().let { it * it }
        val spawnRaio2 = settings.chunks.desativacao.distanciaSpawnBlocos.toDouble().let { it * it }
        val spawn = try { world.spawnLocation } catch (_: Throwable) { null }

        val px = DoubleArray(players.size)
        val pz = DoubleArray(players.size)
        for (i in players.indices) {
            val loc = try { players[i].location } catch (_: Throwable) { null }
            px[i] = loc?.x ?: 0.0
            pz[i] = loc?.z ?: 0.0
        }

        var semJogador = 0
        var naSpawn = 0
        for (chunk in chunks) {
            val cx = chunk.x * 16.0 + 8.0
            val cz = chunk.z * 16.0 + 8.0

            var perto = false
            for (i in px.indices) {
                val dx = cx - px[i]
                val dz = cz - pz[i]
                if (dx * dx + dz * dz <= raio2) {
                    perto = true
                    break
                }
            }
            if (!perto) semJogador++

            if (spawn != null) {
                val sx = cx - spawn.x
                val sz = cz - spawn.z
                if (sx * sx + sz * sz <= spawnRaio2) naSpawn++
            }
        }

        // --- entidades ------------------------------------------------------
        val entities = try { world.entities } catch (_: Throwable) { emptyList() }
        val typeCounts = HashMap<String, Int>(64)
        var itens = 0
        var nomeadas = 0
        var comDados = 0
        for (entity in entities) {
            try {
                if (entity.type == EntityType.ITEM) itens++
                typeCounts.merge(entity.type.name, 1) { a, b -> a + b }
                if (entity.getCustomName() != null) nomeadas++
                if (!entity.persistentDataContainer.keys.isEmpty()) comDados++
            } catch (_: Throwable) {
                // entidade removida durante a iteração: ignora
            }
        }
        val top = typeCounts.entries
            .sortedByDescending { it.value }
            .take(maxTopTypes)
            .map { it.key to it.value }

        // --- block entities / contexto --------------------------------------
        val blockEntities = try { world.tileEntityCount } catch (_: Throwable) { 0 }
        val tickable = try { world.tickableTileEntityCount } catch (_: Throwable) { 0 }
        val totalChunks = try { world.chunkCount } catch (_: Throwable) { 0 }

        return WorldSnapshot(
            name = world.name,
            environment = try { world.environment.name } catch (_: Throwable) { "?" },
            loadedChunks = chunks.size,
            totalChunks = totalChunks,
            chunksSemJogadorProximo = semJogador,
            chunksNaAreaDeSpawn = naSpawn,
            entities = entities.size,
            itensNoChao = itens,
            entidadesNomeadas = nomeadas,
            entidadesComDados = comDados,
            blockEntities = blockEntities,
            blockEntitiesTickaveis = tickable,
            players = players.size,
            topEntityTypes = top,
        )
    }
}
