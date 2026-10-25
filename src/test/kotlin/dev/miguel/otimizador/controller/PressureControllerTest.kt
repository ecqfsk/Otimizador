package dev.miguel.otimizador.controller

import dev.miguel.otimizador.config.Acoes
import dev.miguel.otimizador.config.Chunks
import dev.miguel.otimizador.config.Controle
import dev.miguel.otimizador.config.Desativacao
import dev.miguel.otimizador.config.Entidades
import dev.miguel.otimizador.config.ItensNoChao
import dev.miguel.otimizador.config.Remocao
import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.core.PressureClassifier
import dev.miguel.otimizador.memorySnapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Limite de heap usado nos snapshots de teste (4 GiB). */
private const val TEST_HEAP_MAX = 4L * 1024 * 1024 * 1024

/**
 * Controlador adaptativo: separa DECISÃO de AÇÃO. O teste prova que nenhuma
 * ação destrutiva é autorizada sem estado crítico + autorização explícita na
 * configuração, e que o alerta respeita o rate-limiter.
 */
class PressureControllerTest {

    /** Controlador com relógio compartilhado (mesmo instante dos snapshots). */
    private class Harness(val settings: Settings) {
        var now = 0L
        val alerts = AlertManager(minIntervalMs = 600_000L, maxPerMinute = 10, clock = { now })
        val classifier = PressureClassifier(PressureController.thresholdsFrom(settings), initialAtMillis = 0L)
        val controller = PressureController(settings, classifier, alerts)

        fun eval(timeMillis: Long, heapUsedRatio: Double = 0.25, gcOverheadRatio: Double = 0.0): ControllerDecision {
            now = timeMillis
            val used = (TEST_HEAP_MAX * heapUsedRatio).toLong()
            return controller.evaluate(
                memorySnapshot(timeMillis, heapUsed = used, heapMax = TEST_HEAP_MAX),
                gcOverheadRatio,
            )
        }
    }

    @Test
    fun `thresholds refletem a configuracao padrao`() {
        val t = PressureController.thresholdsFrom(Settings())
        assertContentEquals(doubleArrayOf(70.0, 85.0, 95.0), t.heapPercent)
        assertContentEquals(doubleArrayOf(5.0, 10.0, 25.0), t.gcOverheadPercent)
        assertEquals(0L, t.rssLimitBytes, "sem teto de RSS por padrão")
        assertEquals(5.0, t.hysteresisPoints)
        assertEquals(30_000L, t.persistUpMs)
        assertEquals(60_000L, t.persistDownMs)
    }

    @Test
    fun `controle desabilitado nunca transiciona nem autoriza nada`() {
        val h = Harness(Settings(controle = Controle(habilitado = false)))
        repeat(5) { i ->
            val d = h.eval(i * 30_000L, heapUsedRatio = 0.96)
            assertNull(d.transition, "com controle desligado não há transição")
            assertEquals(MemoryState.NORMAL, d.state)
            assertFalse(d.suspendOptionalOps)
            assertFalse(d.autoReportAllowed)
            assertFalse(d.chunkReclaimAllowed)
            assertFalse(d.entityControlAllowed)
            assertTrue(d.alerts.isEmpty())
        }
    }

    @Test
    fun `transicao gera um alerta e o rate-limiter bloqueia a repeticao`() {
        val h = Harness(Settings())

        // 87% ≥ 85%: candidato ALTA, precisa de 30 s de persistência
        assertNull(h.eval(0L, heapUsedRatio = 0.87).transition)
        val subida = h.eval(30_000L, heapUsedRatio = 0.87)
        assertEquals(MemoryState.PRESSAO_ALTA, subida.state)
        assertNotNull(subida.transition)
        assertEquals(1, subida.alerts.size)
        assertTrue(subida.alerts.single().text.contains("→"), subida.alerts.single().text)
        assertTrue(subida.autoReportAllowed, "estado elevado libera relatório automático")
        assertFalse(subida.suspendOptionalOps, "só pressão crítica suspende operações opcionais")
        assertFalse(subida.chunkReclaimAllowed, "sem autorização dupla não há controle de chunks")

        // 60%: abaixo da banda de histerese; descida exige 60 s
        assertNull(h.eval(40_000L, heapUsedRatio = 0.60).transition)
        val volta = h.eval(100_000L, heapUsedRatio = 0.60)
        assertEquals(MemoryState.NORMAL, volta.state)
        assertEquals(1, volta.alerts.size, "voltar ao normal também alerta (chave diferente)")

        // Nova subida para ALTA dentro do intervalo mínimo do alerta (10 min)
        assertNull(h.eval(110_000L, heapUsedRatio = 0.87).transition)
        val segundaSubida = h.eval(140_000L, heapUsedRatio = 0.87)
        assertEquals(MemoryState.PRESSAO_ALTA, segundaSubida.state)
        assertTrue(
            segundaSubida.alerts.isEmpty(),
            "mesmo estado em menos de 10 min não repete o alerta",
        )
    }

    @Test
    fun `overhead de gc conta mesmo com heap folgado`() {
        val h = Harness(Settings())
        assertNull(h.eval(0L, heapUsedRatio = 0.25, gcOverheadRatio = 0.12).transition)
        val d = h.eval(30_000L, heapUsedRatio = 0.25, gcOverheadRatio = 0.12)
        assertEquals(MemoryState.PRESSAO_ALTA, d.state, "12% ≥ limiar de 10%")
        assertTrue(d.reasons.any { it.contains("GC") }, "razões devem citar o GC: ${d.reasons}")
    }

    @Test
    fun `critico autoriza acoes apenas com dupla autorizacao`() {
        val h = Harness(
            Settings(
                chunks = Chunks(desativacao = Desativacao(habilitado = true)),
                entidades = Entidades(itensNoChao = ItensNoChao(remocao = Remocao(habilitado = true))),
                controle = Controle(
                    acoes = Acoes(
                        permitirControleChunksSobPressao = true,
                        permitirControleEntidadesSobPressao = true,
                    ),
                ),
            ),
        )

        assertNull(h.eval(0L, heapUsedRatio = 0.96).transition)
        val critico = h.eval(30_000L, heapUsedRatio = 0.96)
        assertEquals(MemoryState.PRESSAO_CRITICA, critico.state)
        assertTrue(critico.chunkReclaimAllowed, "crítico + habilitado + autorização = liberado")
        assertTrue(critico.entityControlAllowed)
        assertTrue(critico.suspendOptionalOps)
        assertTrue(critico.autoReportAllowed)
        assertTrue(critico.reasons.isNotEmpty())
    }

    @Test
    fun `sem autorizacao explicita o controle permaneca negado mesmo no critico`() {
        // Desativação de chunks ligada, mas a ação correspondente NÃO autorizada.
        val h = Harness(Settings(chunks = Chunks(desativacao = Desativacao(habilitado = true))))

        assertNull(h.eval(0L, heapUsedRatio = 0.96).transition)
        val critico = h.eval(30_000L, heapUsedRatio = 0.96)
        assertEquals(MemoryState.PRESSAO_CRITICA, critico.state)
        assertFalse(critico.chunkReclaimAllowed, "autorização da ação é obrigatória")
        assertFalse(critico.entityControlAllowed, "remoção de entidades segue desligada por padrão")
    }

    @Test
    fun `histerese evita alternancia rapida entre estados`() {
        val h = Harness(Settings())
        h.eval(0L, heapUsedRatio = 0.87)
        h.eval(30_000L, heapUsedRatio = 0.87)
        assertEquals(MemoryState.PRESSAO_ALTA, h.classifier.state)

        // 81% está na banda de histerese (85 - 5 = 80): deve permanecer em ALTA
        repeat(10) { i ->
            val d = h.eval(35_000L + i * 1_000L, heapUsedRatio = 0.81)
            assertNull(d.transition, "flapping na descida")
            assertEquals(MemoryState.PRESSAO_ALTA, d.state)
        }
    }
}
