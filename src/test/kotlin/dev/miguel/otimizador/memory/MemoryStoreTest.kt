package dev.miguel.otimizador.memory

import dev.miguel.otimizador.gcReading
import dev.miguel.otimizador.memorySnapshot
import dev.miguel.otimizador.world.WorldSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Garantia central do repositório: NADA aqui cresce sem teto (buffers com
 * capacidade fixa, filas com limite, evidências deduplicadas) e toda a
 * coleta é segura para rodar assíncrona lendo da thread principal.
 */
class MemoryStoreTest {

    private fun store(
        capacity: Int = 60,
        maxEvidences: Int = 10,
        ttlMillis: Long = 900_000L,
    ) = MemoryStore(historyCapacity = capacity, maxEvidences = maxEvidences, gcEventTtlMillis = ttlMillis)

    @Test
    fun `primeira amostra nao tem janela de gc`() {
        val s = store()
        val w = s.record(memorySnapshot(0L, gc = listOf(gcReading(count = 10, timeMs = 100))))
        assertNull(w, "sem leitura anterior não há diferença para calcular")
        assertNotNull(s.latestSnapshot())
        assertEquals(1, s.heapUsed.size)
        assertTrue(s.gcEvents().isEmpty(), "evento exige janela calculada")
        assertEquals(0L, s.sinceMillis)
    }

    @Test
    fun `segunda amostra calcula janela e evento de gc`() {
        val s = store()
        s.record(memorySnapshot(0L, gc = listOf(gcReading(count = 10, timeMs = 100))))
        val w = s.record(memorySnapshot(5_000L, gc = listOf(gcReading(count = 12, timeMs = 140))))

        assertNotNull(w)
        assertEquals(5_000L, w.wallMs)
        assertEquals(2L, w.totalCollections)
        assertEquals(40L, w.totalTimeMs)
        assertEquals(40.0 / 5_000.0, w.overheadRatio, 1e-9)
        assertEquals(1, w.deltas.size)

        assertEquals(1, s.gcOverhead.size, "série de overhead de GC ganhou uma amostra")
        assertEquals(1, s.gcEvents().size, "coleta gerou um evento")
        assertEquals(5_000L, s.gcEvents().single().timeMillis)
        // 2 coletas em 40 ms → pausa média de 20 ms; pausa máxima só quando
        // exatamente 1 coleta ocorre no intervalo (aí o tempo é a própria pausa).
        assertEquals(20L, s.gcEvents().single().estimatedPauseMs)
        assertNull(w.estimatedMaxPauseMs, "múltiplas coletas não permitem estimar a pausa máxima")
        assertEquals(20L, w.estimatedAvgPauseMs)
        assertEquals(w, s.lastGcWindow())
        assertEquals(0L, s.sinceMillis, "instante da primeira amostra é preservado")
    }

    @Test
    fun `historicos tem capacidade rigida`() {
        val s = store(capacity = 5)
        repeat(50) { i -> s.record(memorySnapshot(i * 1_000L)) }

        assertEquals(5, s.heapUsed.size)
        assertEquals(5, s.heapCommitted.size)
        assertEquals(5, s.heapMaxSeries.size)
        assertEquals(5, s.nonHeapUsed.size)
        assertEquals(5, s.threads.size)
        assertEquals(5, s.rss.size)
        assertEquals(49_000L, s.heapUsed.latest()?.timeMillis, "a amostra mais antiga foi sobrescrita")
        assertEquals(45_000L, s.heapUsed.oldest()?.timeMillis)
        assertEquals(5, s.heapUsed.capacity)
    }

    @Test
    fun `eventos de gc expiram pelo ttl`() {
        val s = store(ttlMillis = 10_000L)
        s.record(memorySnapshot(0L, gc = listOf(gcReading(count = 0, timeMs = 0))))
        s.record(memorySnapshot(1_000L, gc = listOf(gcReading(count = 1, timeMs = 10))))
        assertEquals(1, s.gcEvents().size, "evento novo registrado")

        s.record(memorySnapshot(100_000L, gc = listOf(gcReading(count = 2, timeMs = 20))))
        assertEquals(1, s.gcEvents().size, "o evento antigo (99 s atrás) saiu pelo TTL")
        assertEquals(100_000L, s.gcEvents().single().timeMillis)
    }

    @Test
    fun `evidencias deduplicam por id e respeitam o teto`() {
        val s = store(maxEvidences = 2)
        s.addEvidence(Evidence("a", 1, "primeira versão", emptyList()))
        s.addEvidence(Evidence("a", 2, "versão atualizada", emptyList()))
        assertEquals(1, s.evidences().size, "mesmo id substitui a evidência anterior")
        assertEquals("versão atualizada", s.evidences().single().message)

        s.addEvidence(Evidence("b", 3, "b", emptyList()))
        s.addEvidence(Evidence("c", 4, "c", emptyList()))
        assertEquals(2, s.evidences().size, "teto respeitado")
        assertTrue(s.evidences().none { it.id == "a" }, "a mais antiga saiu")
        assertEquals(listOf("b", "c"), s.evidences().map { it.id })
    }

    @Test
    fun `amostras de mundo tem teto`() {
        val s = store()
        repeat(70) { i -> s.recordWorld(WorldSample(i.toLong(), emptyList(), 0, emptyMap())) }
        assertEquals(60, s.worldSamples().size, "fila de mundos limitada a 60")
        assertEquals(69L, s.latestWorldSample()?.timeMillis)
    }

    @Test
    fun `clear zera todo o historico`() {
        val s = store(capacity = 5, maxEvidences = 2)
        s.record(memorySnapshot(1_000L, gc = listOf(gcReading(count = 1, timeMs = 1))))
        s.record(memorySnapshot(2_000L, gc = listOf(gcReading(count = 3, timeMs = 21))))
        s.recordWorld(WorldSample(2_000L, emptyList(), 5, mapOf("X" to 5)))
        s.addEvidence(Evidence("e", 2, "m", emptyList()))

        s.clear()

        assertEquals(0, s.heapUsed.size)
        assertEquals(0, s.gcOverhead.size)
        assertTrue(s.gcEvents().isEmpty())
        assertTrue(s.evidences().isEmpty())
        assertTrue(s.worldSamples().isEmpty())
        assertNull(s.latestSnapshot())
        assertNull(s.previousSnapshot())
        assertNull(s.lastGcWindow())
        assertEquals(0L, s.sinceMillis)
        assertTrue(s.taskTracker.snapshot().isEmpty())
    }

    @Test
    fun `amostra anterior e preservada entre registros`() {
        val s = store()
        s.record(memorySnapshot(1_000L, heapUsed = 100L))
        s.record(memorySnapshot(2_000L, heapUsed = 200L))
        assertEquals(200L, s.latestSnapshot()?.heapUsed)
        assertEquals(100L, s.previousSnapshot()?.heapUsed)
    }

    // ------------------------------------------------------------------
    // TaskTracker
    // ------------------------------------------------------------------

    @Test
    fun `task tracker limita plugins e amostras por plugin`() {
        var now = 0L
        val tracker = TaskTracker(maxPlugins = 2, capacityPerPlugin = 3, ttlMillis = 60_000L, clock = { now })

        repeat(5) { i ->
            now = i * 1_000L
            tracker.record(now, mapOf("alpha" to i, "beta" to i * 2))
        }

        val snap = tracker.snapshot()
        assertEquals(2, snap.size, "os 2 slots configurados são preenchidos")
        assertTrue(snap.any { it.plugin == "alpha" })
        assertTrue(snap.any { it.plugin == "beta" })
        assertEquals(3, snap.first().samples, "amostras por plugin limitadas ao ring buffer")
        assertTrue(snap.first().max >= snap.first().min)
        assertTrue(snap.first().last >= snap.first().first, "ordenação por último valor")

        // Um plugin novo ocupa um slot: o menos recentemente usado sai (LRU).
        tracker.record(now, mapOf("gama" to 99))
        assertTrue(
            tracker.snapshot().none { it.plugin == "alpha" },
            "alpha era o menos acessado e foi evitado: ${tracker.snapshot().map { it.plugin }}",
        )
    }

    @Test
    fun `task tracker expira series inativas`() {
        var now = 0L
        val tracker = TaskTracker(maxPlugins = 10, capacityPerPlugin = 10, ttlMillis = 5_000L, clock = { now })

        now = 0L
        tracker.record(0L, mapOf("pluginA" to 10))
        now = 1_000L
        tracker.record(1_000L, mapOf("pluginA" to 20))
        assertEquals(1, tracker.snapshot().size)

        now = 10_000L // 9 s desde a última gravação > TTL de 5 s
        assertEquals(1, tracker.cleanup(), "série expirada liberada")
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `task tracker com uma amostra nao gera serie`() {
        val tracker = TaskTracker(maxPlugins = 5, capacityPerPlugin = 5)
        tracker.record(0L, mapOf("soUma" to 1))
        assertTrue(tracker.snapshot().isEmpty(), "sem tendência possível com uma amostra")
    }
}
