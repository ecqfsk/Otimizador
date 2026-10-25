package dev.miguel.otimizador.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Limitador de taxa: o mesmo alerta não pode inundar o log e o volume global
 * tem teto por minuto — com relógio injetável para testes determinísticos.
 */
class AlertManagerTest {

    @Test
    fun `primeiro alerta da chave e permitido e o proximo respeita o intervalo`() {
        var now = 0L
        val am = AlertManager(minIntervalMs = 60_000L, maxPerMinute = 10, clock = { now })

        assertTrue(am.tryAcquire("estado:PRESSAO_ALTA"))
        now = 30_000L
        assertFalse(am.tryAcquire("estado:PRESSAO_ALTA"), "30 s < intervalo mínimo de 60 s")
        now = 59_999L
        assertFalse(am.tryAcquire("estado:PRESSAO_ALTA"))
        now = 60_000L
        assertTrue(am.tryAcquire("estado:PRESSAO_ALTA"), "intervalo completo: liberado")
    }

    @Test
    fun `chaves diferentes sao independentes`() {
        var now = 0L
        val am = AlertManager(minIntervalMs = 60_000L, maxPerMinute = 10, clock = { now })

        assertTrue(am.tryAcquire("estado:PRESSAO_ALTA"))
        assertTrue(am.tryAcquire("estado:PRESSAO_CRITICA"), "outra chave não é afetada")
        assertFalse(am.tryAcquire("estado:PRESSAO_ALTA"))
    }

    @Test
    fun `teto global por minuto evita inundacao`() {
        var now = 0L
        val am = AlertManager(minIntervalMs = 0L, maxPerMinute = 3, clock = { now })

        repeat(3) { i -> assertTrue(am.tryAcquire("chave-$i")) }
        assertFalse(am.tryAcquire("chave-quarta"), "3 alertas já emitidos neste minuto")

        now = 60_000L
        assertTrue(am.tryAcquire("chave-quarta"), "nova janela de 1 minuto")
    }

    @Test
    fun `janela deslizante zera ao cruzar o minuto`() {
        var now = 0L
        val am = AlertManager(minIntervalMs = 0L, maxPerMinute = 2, clock = { now })

        assertTrue(am.tryAcquire("a"))
        assertTrue(am.tryAcquire("b"))
        assertFalse(am.tryAcquire("c"))
        now = 60_000L
        assertTrue(am.tryAcquire("c"))
        assertTrue(am.tryAcquire("d"))
        assertFalse(am.tryAcquire("e"))
    }

    @Test
    fun `reset libera a chave imediatamente`() {
        val now = 0L
        val am = AlertManager(minIntervalMs = 300_000L, maxPerMinute = 5, clock = { now })

        assertTrue(am.tryAcquire("estado:X"))
        assertFalse(am.tryAcquire("estado:X"))
        am.reset()
        assertTrue(am.tryAcquire("estado:X"), "reset limpa histórico e janela")
    }

    @Test
    fun `chaves novas nao consomem a janela quando negadas`() {
        var now = 0L
        val am = AlertManager(minIntervalMs = 60_000L, maxPerMinute = 2, clock = { now })

        assertTrue(am.tryAcquire("k1"))
        // repetição da mesma chave é negada ANTES de consumir a janela
        assertFalse(am.tryAcquire("k1"))
        assertFalse(am.tryAcquire("k1"))
        assertTrue(am.tryAcquire("k2"), "as negações por intervalo não consumiram a janela")
        assertFalse(am.tryAcquire("k3"), "2 emissões é o teto do minuto")
    }
}
