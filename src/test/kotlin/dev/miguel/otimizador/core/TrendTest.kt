package dev.miguel.otimizador.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testa a regressão linear usada para tendência e crescimento persistente.
 */
class TrendTest {

    private fun series(count: Int, stepMs: Long, start: Long = 0L, valueAt: (Int) -> Double): List<TimedSample> =
        (0 until count).map { TimedSample(start + it * stepMs, valueAt(it)) }

    @Test
    fun `crescimento linear perfeito tem slope conhecido`() {
        // 1 MiB por hora, amostrado de hora em hora
        val mib = 1024.0 * 1024.0
        val samples = series(11, 3_600_000L) { it * mib }
        val fit = Trend.fit(samples)
        assertNotNull(fit)
        assertEquals(mib, fit.slopePerHour, 1.0)
        assertEquals(1.0, fit.r2, 0.001)
        assertEquals(10.0, fit.windowHours, 0.01)
    }

    @Test
    fun `serie constante tem slope zero e r2 alto`() {
        val samples = series(20, 5_000L) { 512.0 * 1024 * 1024 }
        val fit = Trend.fit(samples)
        assertNotNull(fit)
        assertEquals(0.0, fit.slopePerHour, 1e-6)
        assertEquals(1.0, fit.r2, 0.001)
    }

    @Test
    fun `serie com uma amostra nao gera ajuste`() {
        assertNull(Trend.fit(listOf(TimedSample(1L, 10.0))))
        assertNull(Trend.fit(emptyList()))
    }

    @Test
    fun `mesmo instante nao gera ajuste`() {
        val samples = listOf(TimedSample(5L, 1.0), TimedSample(5L, 2.0))
        assertNull(Trend.fit(samples))
    }

    @Test
    fun `ruido aleatorio tem r2 baixo`() {
        val rnd = java.util.Random(42)
        val samples = series(60, 5_000L) { 100.0 * 1024 * 1024 + rnd.nextGaussian() * 50_000_000 }
        val fit = Trend.fit(samples)
        assertNotNull(fit)
        assertTrue(fit.r2 < 0.6, "esperava aderência baixa, obteve R²=${fit.r2}")
    }

    @Test
    fun `absolute delta captura crescimento liquido`() {
        val samples = series(5, 1000L) { it * 10.0 }
        assertEquals(40.0, Trend.absoluteDelta(samples)!!)
    }
}
