package dev.miguel.otimizador.memory

import dev.miguel.otimizador.core.TimedSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Detector de crescimento sustentado: precisa separar crescimento CONTÍNUO de
 * ruído, picos e aquecimento — e nunca afirmar vazamento, apenas sinal.
 */
class GrowthAnalyzerTest {

    private val mib = 1024.0 * 1024.0

    /**
     * Série com [count] amostras a cada minuto, crescendo [stepMiB] MiB por
     * amostra, com jitter opcional (em MiB) para simular picos.
     */
    private fun series(
        count: Int,
        stepMiB: Double,
        startMiB: Double = 100.0,
        jitter: ((Int) -> Double)? = null,
    ): List<TimedSample> = (0 until count).map { i ->
        val base = startMiB + i * stepMiB + (jitter?.invoke(i) ?: 0.0)
        TimedSample(i * 60_000L, base * mib)
    }

    @Test
    fun `poucas amostras nao geram conclusao`() {
        val s = GrowthAnalyzer.analyze(
            samples = series(5, 10.0),
            minSamples = 20,
            windowSamples = 60,
            minMiBPerHour = 48.0,
        )
        assertFalse(s.detected)
        assertTrue(s.reason.contains("amostras insuficientes"), s.reason)
        assertTrue(s.reason.contains("sem conclusão"), s.reason)
        assertEquals(5, s.samples)
    }

    @Test
    fun `janela menor que o minimo tambem nao conclui`() {
        val s = GrowthAnalyzer.analyze(
            samples = series(60, 10.0),
            minSamples = 20,
            windowSamples = 10,
            minMiBPerHour = 48.0,
        )
        assertFalse(s.detected)
        assertTrue(s.reason.contains("amostras insuficientes"), s.reason)
        assertEquals(10, s.samples, "a janela deve limitar as amostras analisadas")
    }

    @Test
    fun `crescimento linear continuo e detectado como sinal`() {
        // 2 MiB por minuto = 120 MiB/h, muito acima do limiar de 48 MiB/h
        val s = GrowthAnalyzer.analyze(
            samples = series(30, 2.0),
            minSamples = 20,
            windowSamples = 60,
            minMiBPerHour = 48.0,
        )
        assertTrue(s.detected, s.reason)
        assertEquals(30, s.samples)
        assertTrue(s.slopeMiBPerHour > 100.0, "taxa esperada ~120 MiB/h: ${s.slopeMiBPerHour}")
        assertTrue(s.r2 > 0.99, "reta perfeita deve ter R² ~1: ${s.r2}")
        assertTrue(s.deltaMiB > 0.0)
        assertTrue(s.reason.contains("R²"), s.reason)
        // A evidência precisa citar a necessidade de profiling externo no motor,
        // mas o próprio sinal já se declara compatível com outros fenômenos:
        assertTrue(s.reason.contains("crescimento contínuo"), s.reason)
    }

    @Test
    fun `crescimento abaixo do limiar nao dispara`() {
        // 0,01 MiB/min = 0,6 MiB/h < 48 MiB/h
        val s = GrowthAnalyzer.analyze(
            samples = series(30, 0.01),
            minSamples = 20,
            windowSamples = 60,
            minMiBPerHour = 48.0,
        )
        assertFalse(s.detected)
        assertTrue(s.reason.contains("abaixo do limiar"), s.reason)
        assertEquals(0.6, s.slopeMiBPerHour, 0.05)
    }

    @Test
    fun `crescimento irregular com picos nao conclusivo`() {
        // Tendência clara (+5 MiB/min) mas picos de 400 MiB alternados:
        // taxa alta com R² baixo = compatível com picos normais pós-GC.
        val s = GrowthAnalyzer.analyze(
            samples = series(30, 5.0) { i -> if (i % 2 == 1) 400.0 else 0.0 },
            minSamples = 20,
            windowSamples = 60,
            minMiBPerHour = 48.0,
        )
        assertFalse(s.detected, "picos não devem ser lidos como vazamento: ${s.reason}")
        assertTrue(s.slopeMiBPerHour >= 48.0, "a taxa bruta é alta: ${s.slopeMiBPerHour}")
        assertTrue(s.r2 < 0.4, "aderência linear deve ser baixa: ${s.r2}")
        assertTrue(s.reason.contains("irregular"), s.reason)
        assertTrue(s.reason.contains("não conclusivo"), s.reason)
    }

    @Test
    fun `queda liquida nao e crescimento`() {
        val s = GrowthAnalyzer.analyze(
            samples = series(30, -3.0),
            minSamples = 20,
            windowSamples = 60,
            minMiBPerHour = 48.0,
        )
        assertFalse(s.detected)
        assertTrue(s.reason.contains("sem crescimento líquido"), s.reason)
        assertTrue(s.deltaMiB < 0.0)
    }

    @Test
    fun `series plana nao dispara`() {
        val s = GrowthAnalyzer.analyze(
            samples = series(30, 0.0),
            minSamples = 20,
            windowSamples = 60,
            minMiBPerHour = 48.0,
        )
        assertFalse(s.detected)
        assertEquals(0.0, s.slopeMiBPerHour, 1e-6)
        assertEquals(0.0, s.deltaMiB, 1e-6)
    }

    @Test
    fun `janela usa apenas as amostras mais recentes`() {
        // Crescimento rápido só no fim da série: a janela recente deve capturá-lo.
        val head = series(40, 0.0)
        val tail = (40 until 70).map { i -> TimedSample(i * 60_000L, (100.0 + (i - 40) * 5.0) * mib) }
        val s = GrowthAnalyzer.analyze(
            samples = head + tail,
            minSamples = 20,
            windowSamples = 30,
            minMiBPerHour = 48.0,
        )
        assertTrue(s.detected, s.reason)
        assertEquals(30, s.samples, "só a janela recente deve ser analisada")
        assertEquals(69 * 60_000L, (head + tail).last().timeMillis)
    }
}
