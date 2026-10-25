package dev.miguel.otimizador.world

import dev.miguel.otimizador.config.Settings
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.Tameable

/**
 * Controle opcional de entidades — REMOÇÃO DESLIGADA POR PADRÃO.
 *
 * Princípios (alinhados ao restante do plugin):
 *  - remoção NUNCA é estratégia principal: diagnóstico é;
 *  - opt-in explícito, com teto por ciclo e limite por mundo;
 *  - proteções inegociáveis: entidades nomeadas, com dados de plugin
 *    (PDC), com passageiros, invulneráveis, domadas, filhotes, chefes,
 *    aldeões, golems, decorações e itens valiosos/protegidos;
 *  - só o EXCESSO acima do limite é removido, do mais antigo para o mais novo;
 *  - nenhuma entidade é removida se o cálculo não for conclusivo.
 *
 * DEVE rodar na thread principal. Nunca lança exceção.
 */
class EntityController(private val settings: Settings) {

    data class WorldResult(
        val world: String,
        val totalItens: Int,
        val candidatosItens: Int,
        val removidosItens: Int,
        val totalMobs: Int,
        val candidatosMobs: Int,
        val removidosMobs: Int,
        val observacao: String,
    )

    data class Result(val autorizado: Boolean, val results: List<WorldResult>)

    fun cleanup(): Result {
        val remocaoItens = settings.entidades.itensNoChao.remocao
        val remocaoMobs = settings.entidades.mobs.remocao
        if (!remocaoItens.habilitado && !remocaoMobs.habilitado) {
            return Result(false, emptyList())
        }

        val out = ArrayList<WorldResult>(4)
        for (world in Bukkit.getWorlds()) {
            try {
                out.add(cleanupWorld(world.name, remocaoItens.habilitado, remocaoMobs.habilitado))
            } catch (t: Throwable) {
                out.add(
                    WorldResult(world.name, 0, 0, 0, 0, 0, 0, "erro: ${t.javaClass.simpleName}: ${t.message}")
                )
            }
        }
        return Result(true, out)
    }

    private fun cleanupWorld(worldName: String, itensOn: Boolean, mobsOn: Boolean): WorldResult {
        val world = Bukkit.getWorld(worldName) ?: return WorldResult(worldName, 0, 0, 0, 0, 0, 0, "mundo não encontrado")
        val entities = try { world.entities } catch (_: Throwable) { return WorldResult(worldName, 0, 0, 0, 0, 0, 0, "sem acesso à lista de entidades") }

        var totalItens = 0
        var totalMobs = 0
        val itensRemoviveis = ArrayList<Item>(32)
        val mobsRemoviveis = ArrayList<Entity>(32)

        for (entity in entities) {
            try {
                when (entity) {
                    is Item -> {
                        totalItens++
                        if (itensOn && isRemovableItem(entity)) itensRemoviveis.add(entity)
                    }
                    else -> {
                        if (entity.type == EntityType.PLAYER) continue
                        totalMobs++
                        if (mobsOn && isRemovableMob(entity)) mobsRemoviveis.add(entity)
                    }
                }
            } catch (_: Throwable) {
                // entidade inacessível: ignora (nunca remove sob dúvida)
            }
        }

        // -------- itens: apenas o EXCESSO acima do teto --------------------
        var removidosItens = 0
        var obs = ""
        if (itensOn) {
            val cfg = settings.entidades.itensNoChao.remocao
            val excesso = totalItens - cfg.maxPorMundo
            if (excesso > 0 && itensRemoviveis.isNotEmpty()) {
                itensRemoviveis.sortByDescending { it.ticksLived }
                val alvo = minOf(excesso, cfg.maxPorCiclo, itensRemoviveis.size)
                for (i in 0 until alvo) {
                    try {
                        itensRemoviveis[i].remove()
                        removidosItens++
                    } catch (_: Throwable) {
                        // já removida: ignora
                    }
                }
                obs = "excesso de itens: $excesso (removidos $removidosItens nesta rodada)"
            } else if (excesso > 0) {
                obs = "excesso de itens: $excesso, mas nenhum é removível (todos protegidos/associados)"
            }
        }

        // -------- mobs: apenas o EXCESSO acima do teto ---------------------
        var removidosMobs = 0
        if (mobsOn) {
            val cfg = settings.entidades.mobs.remocao
            val excesso = totalMobs - cfg.maxPorMundo
            if (excesso > 0 && mobsRemoviveis.isNotEmpty()) {
                mobsRemoviveis.sortByDescending { it.ticksLived }
                val alvo = minOf(excesso, cfg.maxPorCiclo, mobsRemoviveis.size)
                for (i in 0 until alvo) {
                    try {
                        mobsRemoviveis[i].remove()
                        removidosMobs++
                    } catch (_: Throwable) {
                        // já removido: ignora
                    }
                }
                if (obs.isNotEmpty()) obs += " | "
                obs += "excesso de mobs: $excesso (removidos $removidosMobs nesta rodada)"
            } else if (excesso > 0) {
                if (obs.isNotEmpty()) obs += " | "
                obs += "excesso de mobs: $excesso, mas nenhum é removível (protegidos)"
            }
        }

        if (obs.isEmpty()) obs = "nenhuma ação necessária"

        return WorldResult(
            world = worldName,
            totalItens = totalItens,
            candidatosItens = itensRemoviveis.size,
            removidosItens = removidosItens,
            totalMobs = totalMobs,
            candidatosMobs = mobsRemoviveis.size,
            removidosMobs = removidosMobs,
            observacao = obs,
        )
    }

    // ------------------------------------------------------------------
    // Regras de proteção — itens
    // ------------------------------------------------------------------

    private fun isRemovableItem(item: Item): Boolean {
        val cfg = settings.entidades.itensNoChao.remocao

        // idade mínima (em ticks)
        val idadeMinTicks = cfg.idadeMinimaSegundos * 20
        if (item.ticksLived < idadeMinTicks) return false

        // material protegido (valioso)
        val material: Material = try { item.itemStack.type } catch (_: Throwable) { return false }
        if (material.name in cfg.protegerMateriais) return false

        // nomeado ou com dados de plugin
        if (settings.entidades.protegerEntidadesNomeadas && item.getCustomName() != null) return false
        if (settings.entidades.protegerEntidadesComDados && hasPluginData(item)) return false

        return true
    }

    // ------------------------------------------------------------------
    // Regras de proteção — mobs
    // ------------------------------------------------------------------

    private fun isRemovableMob(entity: Entity): Boolean {
        val cfg = settings.entidades.mobs.remocao

        // tipos explicitamente ignorados (chefes, aldeões, decoração, jogador…)
        if (entity.type.name in cfg.ignorarTipos) return false

        // nunca sob dúvida
        if (entity.isInvulnerable) return false
        if (entity.passengers.isNotEmpty()) return false
        if (settings.entidades.protegerEntidadesNomeadas && entity.getCustomName() != null) return false
        if (settings.entidades.protegerEntidadesComDados && hasPluginData(entity)) return false

        // domados (animais de estimação, montarias)
        if (entity is Tameable && (entity.isTamed || entity.owner != null)) return false

        // filhotes/fetos em crescimento
        if (entity is Ageable && !entity.isAdult) return false

        return true
    }

    private fun hasPluginData(entity: Entity): Boolean = try {
        !entity.persistentDataContainer.keys.isEmpty()
    } catch (_: Throwable) {
        false
    }
}
