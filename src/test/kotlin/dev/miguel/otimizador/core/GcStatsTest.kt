package dev.miguel.otimizador.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testa o cálculo de janelas de GC (diferenças entre amostras), frequência,
 * overhead e estimativas de pausa.
 */
class GcStatsTest {

    private fun reading(name: String = "G1 Young Generation", count: Long, time: Long) =
        GcStats.CollectorReading(name, count, time)

    @Test
    fun `primeira amostra nao gera janela`() {
        assertNull(GcStats.window(null, listOf(reading(count = 10, time = 100)), 5_000))
    }

    @Test
    fun `tempo nao positivo nao gera janela`() {
        val prev = listOf(reading(count = 10, time = 100))
        assertNull(GcStats.window(prev, listOf(reading(count = 12, time = 150)), 0))
        assertNull(GcStats.window(prev, listOf(reading(count = 12, time = 150)), -1))
    }

    @Test
    fun `deltas frequencia e overhead corretos`() {
        val prev = listOf(reading(count = 100, time = 5_000))
        val cur = listOf(reading(count = 112, time = 5_600))
        val w = GcStats.window(prev, cur, 60_000)!!

        assertEquals(12, w.totalCollections)
        assertEquals(600, w.totalTimeMs)
        assertEquals(600.0 / 60_000.0, w.overheadRatio, 1e-9)
        assertEquals(0.01, w.overheadRatioClamped, 1e-9)
        assertEquals(12.0, w.collectionsPerMinute, 1e-9)
        // 12 coletas => média 50ms; máxima só quando exatamente 1 coleta
        assertEquals(50L, w.estimatedAvgPauseMs)
        assertNull(w.estimatedMaxPauseMs)
    }

    @Test
    fun `pausa maxima estimada com uma unica coleta`() {
        val prev = listOf(reading(count = 10, time = 100))
        val cur = listOf(reading(count = 11, time = 350))
        val w = GcStats.window(prev, cur, 5_000)!!
        assertEquals(250L, w.estimatedMaxPauseMs)
        assertEquals(250L, w.estimatedAvgPauseMs)
    }

    @Test
    fun `overhead bruto pode exceder 1 mas o limitado nao`() {
        // 10s de GC em 5s de parede (GC paralelo)
        val prev = listOf(reading(count = 1, time = 0))
        val cur = listOf(reading(count = 5, time = 10_000))
        val w = GcStats.window(prev, cur, 5_000)!!
        assertTrue(w.overheadRatio > 1.0, "bruto deveria exceder 1, veio ${w.overheadRatio}")
        assertEquals(1.0, w.overheadRatioClamped)
    }

    @Test
    fun `contadores que voltam (reset) nao viram deltas negativos`() {
        val prev = listOf(reading(count = 1_000, time = 60_000))
        val cur = listOf(reading(count = 5, time = 100))
        val w = GcStats.window(prev, cur, 5_000)!!
        assertEquals(0, w.totalCollections)
        assertEquals(0, w.totalTimeMs)
    }

    @Test
    fun `coletor novo entra com delta zero de tempo`() {
        val prev = listOf(reading("A", count = 5, time = 100))
        val cur = listOf(reading("A", count = 6, time = 200), reading("B", count = 3, time = 90))
        val w = GcStats.window(prev, cur, 10_000)!!
        val b = w.deltas.first { it.name == "B" }
        assertEquals(3, b.countDelta)
        assertEquals(0, b.timeDeltaMs)
        assertNotNull(w)
    }
}
