package dev.miguel.otimizador.diag

import dev.miguel.otimizador.config.Entidades
import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.memory.MemoryStore
import dev.miguel.otimizador.memorySnapshot
import dev.miguel.otimizador.world.WorldSample
import dev.miguel.otimizador.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Motor de diagnóstico: evidências com horário e unidade, achados honestos
 * (sinal ≠ prova) e indicação explícita de quando a API pública não basta.
 */
class DiagnosticEngineTest {

    private val mib = 1024L * 1024

    private fun store() = MemoryStore(historyCapacity = 120, maxEvidences = 20, gcEventTtlMillis = 900_000L)

    @Test
    fun `sem historico so declara os limites da analise`() {
        val out = DiagnosticEngine(Settings()).analyze(0L, store(), MemoryState.NORMAL)

        assertTrue(out.any { it.id == "limites-api-publica" }, "achados: ${out.map { it.id }}")
        assertTrue(out.none { it.id == "crescimento-heap" })
        assertTrue(out.none { it.id == "tendencia-heap" }, "sem séries não há tendência a declarar")
        assertTrue(out.none { it.id == "heap-proximo-do-limite" })
        assertTrue(out.none { it.id == "estado-atual" }, "estado normal não gera achado")

        val limites = out.first { it.id == "limites-api-publica" }
        assertEquals(FindingLevel.INFO, limites.level)
        assertTrue(limites.requiresExternalProfiling, "limites da API pública devem ficar explícitos")
        assertTrue(limites.recommendations.any { it.contains("jcmd") })
    }

    @Test
    fun `crescimento do pos gc vira sinal com recomendacao de profiling`() {
        val s = store()
        repeat(25) { i ->
            s.record(
                memorySnapshot(
                    timeMillis = i * 5_000L,
                    heapUsed = 1024L * mib, // 25% do máximo: não dispara alerta de heap
                    heapFloorUsed = (100L + i * 20L) * mib,
                    rssBytes = 1536L * mib,
                )
            )
        }

        val out = DiagnosticEngine(Settings()).analyze(125_000L, s, MemoryState.NORMAL)
        val f = out.firstOrNull { it.id == "crescimento-heap" }
        assertNotNull(f, "achados gerados: ${out.map { it.id }}")

        assertEquals(FindingLevel.ALERTA, f.level)
        assertTrue(f.requiresExternalProfiling, "sem profiler não há como atribuir a origem")
        assertTrue(f.evidence.any { it.contains("piso pós-GC") }, "a série analisada deve ser nomeada: ${f.evidence}")
        assertTrue(f.evidence.any { it.contains("MiB/h") }, "taxa com unidade: ${f.evidence}")
        assertTrue(f.evidence.any { it.contains("R²") }, "aderência linear explicitada: ${f.evidence}")
        assertTrue(f.evidence.any { it.contains("horário") }, "evidência com horário: ${f.evidence}")
        assertTrue(f.recommendations.any { it.contains("heap dump") })
        assertTrue(f.recommendations.any { it.contains("profiler") })
        assertTrue(
            f.recommendations.any { it.contains("NÃO prova vazamento") },
            "sinal nunca pode ser apresentado como prova",
        )

        // Nenhum achado do relatório afirma conclusão de vazamento
        assertTrue(out.none { it.title.contains("vazamento") })
        assertTrue(out.none { it.id == "tendencia-heap" }, "o achado detectado substitui o de tendência ok")
    }

    @Test
    fun `heap perto do limite e critico e o estado atual entra no diagnostico`() {
        val s = store()
        val heapMax = 4096L * mib
        s.record(
            memorySnapshot(
                timeMillis = 1_000L,
                heapUsed = heapMax * 96 / 100,
                heapCommitted = heapMax,
                heapMax = heapMax,
                rssBytes = heapMax / 2,
            )
        )

        val out = DiagnosticEngine(Settings()).analyze(1_000L, s, MemoryState.PRESSAO_CRITICA)
        val f = out.first { it.id == "heap-proximo-do-limite" }
        assertEquals(FindingLevel.CRITICO, f.level)
        assertTrue(f.evidence.any { it.contains("%") }, "percentual presente: ${f.evidence}")
        assertTrue(f.evidence.any { it.contains("-Xmx") }, "heap máximo explicitado como limite configurado")
        assertTrue(f.evidence.any { it.contains("comprometido") }, "diferencia usado de comprometido")
        assertTrue(f.recommendations.any { it.contains("Reduza a origem") })

        val estado = out.first { it.id == "estado-atual" }
        assertEquals(FindingLevel.CRITICO, estado.level)
        assertTrue(out.any { it.id == "tendencia-heap" }, "uma amostra só: sem conclusão de crescimento")
    }

    @Test
    fun `rss muito acima do heap gera achado com as fontes conhecidas`() {
        val s = store()
        s.record(memorySnapshot(1_000L, heapUsed = 1024L * mib, rssBytes = 4096L * mib, nmtLevel = "off"))

        val out = DiagnosticEngine(Settings()).analyze(1_000L, s, MemoryState.NORMAL)
        val f = out.first { it.id == "rss-fora-do-heap" }
        assertTrue(f.requiresExternalProfiling, "a API pública não detalha o RSS")
        assertTrue(f.evidence.any { it.contains("RSS") })
        assertTrue(f.evidence.any { it.contains("buffers diretos") })
        assertTrue(f.evidence.any { it.contains("Native Memory Tracking: off") })
        assertTrue(f.recommendations.any { it.contains("NativeMemoryTracking") })
        assertTrue(out.any { it.id == "nmt-desligado" })
    }

    @Test
    fun `mundo com chunks ociosos entidades e tarefas gera achados datados`() {
        val settings = Settings(entidades = Entidades(limitesPorMundo = mapOf("*" to 1_000)))
        val s = store()
        s.record(memorySnapshot(1_000L))
        s.recordWorld(
            WorldSample(
                timeMillis = 2_000L,
                worlds = listOf(
                    WorldSnapshot(
                        name = "world",
                        environment = "NORMAL",
                        loadedChunks = 9_000,
                        totalChunks = 50_000,
                        chunksSemJogadorProximo = 7_000,
                        chunksNaAreaDeSpawn = 500,
                        entities = 2_000,
                        itensNoChao = 400,
                        entidadesNomeadas = 10,
                        entidadesComDados = 5,
                        blockEntities = 7_000,
                        blockEntitiesTickaveis = 100,
                        players = 0,
                        topEntityTypes = listOf("PIG" to 1_300),
                    ),
                ),
                pendingTasks = 5_000,
                pendingTasksByPlugin = mapOf("pluginCrítico" to 4_500),
            ),
        )

        val out = DiagnosticEngine(settings).analyze(3_000L, s, MemoryState.NORMAL)

        val chunks = out.firstOrNull { it.id == "chunks-mundo:world" }
        assertNotNull(chunks, "achados: ${out.map { it.id }}")
        assertTrue(chunks.evidence.any { it.contains("chunks carregados") })
        assertTrue(chunks.evidence.any { it.contains("/") }, "horário na evidência: ${chunks.evidence}")

        val ociosos = out.first { it.id == "chunks-ociosos:world" }
        assertTrue(ociosos.requiresExternalProfiling, "a API não diz qual plugin retém o chunk")
        assertTrue(ociosos.evidence.any { it.contains("%") })

        val entidades = out.first { it.id == "entidades-mundo:world" }
        assertEquals(FindingLevel.ALERTA, entidades.level, "2000 > 1,5 × limite 1000")
        assertTrue(entidades.evidence.any { it.contains("itens no chão") })

        assertTrue(out.any { it.id == "itens-chao:world" }, "400 > limiar 300")
        assertTrue(out.any { it.id == "block-entities:world" }, "7000 > limiar 6000")
        assertTrue(out.any { it.id == "concentracao:world" }, "PIG = 65% das entidades")
        assertTrue(out.any { it.id == "tarefas:pluginCrítico" }, "4500 ≥ limiar 4000")

        val tarefas = out.first { it.id == "tarefas:pluginCrítico" }
        assertTrue(tarefas.recommendations.any { it.contains("NÃO prova") })
    }

    @Test
    fun `resultado e limitado e ordenado por severidade`() {
        val s = store()
        repeat(25) { i -> s.record(memorySnapshot(i * 5_000L, heapUsed = 3932L * mib, heapMax = 4096L * mib)) }

        val out = DiagnosticEngine(Settings()).analyze(125_000L, s, MemoryState.PRESSAO_CRITICA)
        assertTrue(out.size <= 40, "saída limitada a 40 achados (recebidos: ${out.size})")
        assertTrue(out.size >= 2)
        val orders = out.map { it.level.order }
        assertEquals(orders.sortedDescending(), orders, "achados ordenados por severidade")
        assertTrue(out.all { it.evidence.isNotEmpty() }, "todo achado carrega evidência")
        assertTrue(out.all { it.atMillis >= 0 })
    }
}
