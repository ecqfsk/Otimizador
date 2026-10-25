package dev.miguel.otimizador.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testa a máquina de estados: limiares, histerese, persistência e ausência de
 * "flapping" (alternância constante).
 */
class PressureClassifierTest {

    private fun thresholds(
        heap: DoubleArray = doubleArrayOf(70.0, 85.0, 95.0),
        gc: DoubleArray = doubleArrayOf(5.0, 10.0, 25.0),
        rssLimit: Long = 0L,
        hysteresis: Double = 5.0,
        upMs: Long = 30_000L,
        downMs: Long = 60_000L,
    ) = PressureThresholds(heap, gc, rssLimit, hysteresis, upMs, downMs)

    private fun inputs(heapPct: Double, gcPct: Double = 0.0, rss: Long = 0L) =
        PressureInputs(heapUsedRatio = heapPct / 100.0, gcOverheadRatio = gcPct / 100.0, rssBytes = rss)

    @Test
    fun `estado inicial e normal`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        assertEquals(MemoryState.NORMAL, c.state)
        assertNull(c.update(0L, inputs(30.0)))
        assertEquals(MemoryState.NORMAL, c.state)
    }

    @Test
    fun `sobe de nivel apenas apos persistencia`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        // 87% => nível ALTA (limiar 85)
        assertNull(c.update(0L, inputs(87.0)))
        assertNull(c.update(29_000L, inputs(87.0)), "não deve subir antes da persistência")
        assertEquals(MemoryState.NORMAL, c.state)
        val t = c.update(30_000L, inputs(87.0))
        assertNotNull(t, "deve transacionar ao completar a persistência")
        assertEquals(MemoryState.NORMAL, t.from)
        assertEquals(MemoryState.PRESSAO_ALTA, t.to)
        assertEquals(MemoryState.PRESSAO_ALTA, c.state)
        assertTrue(t.reasons.isNotEmpty())
    }

    @Test
    fun `histerese impede flapping na descida`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        c.update(0L, inputs(87.0))
        c.update(30_000L, inputs(87.0))
        assertEquals(MemoryState.PRESSAO_ALTA, c.state)

        // Queda para 81%: abaixo do limiar cru (85), mas dentro da banda de
        // histerese (85-5=80) => deve PERMANECER em ALTA sem transição.
        repeat(20) { i -> assertNull(c.update(35_000L + i * 1_000L, inputs(81.0))) }
        assertEquals(MemoryState.PRESSAO_ALTA, c.state, "histerese deveria manter o estado")
    }

    @Test
    fun `descida exige persistencia de queda`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        c.update(0L, inputs(87.0))
        c.update(30_000L, inputs(87.0))
        assertEquals(MemoryState.PRESSAO_ALTA, c.state)

        // 60% => abaixo de todos os limiares com histerese (65/80/90)
        assertNull(c.update(40_000L, inputs(60.0)))
        assertNull(c.update(99_000L, inputs(60.0)), "descida deve esperar persistência de queda (60s)")
        assertEquals(MemoryState.PRESSAO_ALTA, c.state)
        val t = c.update(100_000L, inputs(60.0))
        assertNotNull(t)
        assertEquals(MemoryState.NORMAL, t.to)
        assertEquals(MemoryState.NORMAL, c.state)
    }

    @Test
    fun `candidato revertido zera a persistencia`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        c.update(0L, inputs(87.0))      // pendente ALTA desde 0
        assertNull(c.update(10_000L, inputs(50.0))) // normalizou: zera pendência
        assertEquals(MemoryState.NORMAL, c.state)

        assertNull(c.update(100_000L, inputs(87.0))) // nova pendência em 100s
        assertNull(c.update(129_000L, inputs(87.0))) // 29s < 30s
        assertEquals(MemoryState.NORMAL, c.state)
        assertNotNull(c.update(130_000L, inputs(87.0)))
        assertEquals(MemoryState.PRESSAO_ALTA, c.state)
    }

    @Test
    fun `overhead de gc classifica de forma independente do heap`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        // heap baixo (30%) mas overhead de GC 12% => ALTA
        assertNull(c.update(0L, inputs(30.0, gcPct = 12.0)))
        val t = c.update(30_000L, inputs(30.0, gcPct = 12.0))
        assertNotNull(t)
        assertEquals(MemoryState.PRESSAO_ALTA, t.to)
        assertTrue(t.reasons.any { it.contains("GC") }, "razão deve citar o overhead de GC: ${t.reasons}")
    }

    @Test
    fun `teto de rss funciona como piso em atencao`() {
        val giga = 1024L * 1024 * 1024
        val c = PressureClassifier(thresholds(rssLimit = 4 * giga), initialAtMillis = 0L)
        assertNull(c.update(0L, inputs(30.0, rss = 5 * giga)))
        val t = c.update(30_000L, inputs(30.0, rss = 5 * giga))
        assertNotNull(t)
        assertEquals(MemoryState.ATENCAO, t.to)
    }

    @Test
    fun `critico exige o pior caso entre as familias`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 0L)
        // heap 96% (critico) OU gc 30% (critico) => qualquer um basta
        c.update(0L, inputs(96.0))
        val t = c.update(30_000L, inputs(96.0))
        assertNotNull(t)
        assertEquals(MemoryState.PRESSAO_CRITICA, t.to)

        val c2 = PressureClassifier(thresholds(), initialAtMillis = 0L)
        c2.update(0L, inputs(50.0, gcPct = 30.0))
        val t2 = c2.update(30_000L, inputs(50.0, gcPct = 30.0))
        assertNotNull(t2)
        assertEquals(MemoryState.PRESSAO_CRITICA, t2.to)
    }

    @Test
    fun `tempo no estado e rastreado`() {
        val c = PressureClassifier(thresholds(), initialAtMillis = 1_000L)
        assertEquals(0L, c.timeInState(1_000L))
        assertEquals(5_000L, c.timeInState(6_000L))
    }

    @Test
    fun `limites invalidos sao rejeitados`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PressureThresholds(doubleArrayOf(70.0, 85.0), doubleArrayOf(5.0, 10.0, 25.0), 0, 5.0, 1, 1)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PressureThresholds(doubleArrayOf(70.0, 85.0, 95.0), doubleArrayOf(5.0, 10.0, 25.0), 0, -1.0, 1, 1)
        }
    }
}
