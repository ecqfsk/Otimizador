package dev.miguel.otimizador.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Formatação determinística usada em relatórios: bytes, percentuais e durações
 * com unidades claras e Locale.ROOT (reprodutível em qualquer máquina).
 */
class FormatTest {

    @Test
    fun `bytes com unidades binarias`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("1023 B", Format.bytes(1023))
        assertEquals("1.0 KiB", Format.bytes(1024))
        assertEquals("1.5 KiB", Format.bytes(1536))
        assertEquals("1.0 MiB", Format.bytes(1024L * 1024))
        assertEquals("1.00 GiB", Format.bytes(1024L * 1024 * 1024))
        assertEquals("2.50 GiB", Format.bytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `valores invalidos viram n_d`() {
        assertEquals("n/d", Format.bytes(null))
        assertEquals("n/d", Format.bytes(-1L))
    }

    @Test
    fun `percentual a partir de razao`() {
        assertEquals("50.0%", Format.percent(0.5))
        assertEquals("0.0%", Format.percent(0.0))
        assertEquals("100.0%", Format.percent(1.0))
        assertEquals("33.33%", Format.percent(1.0 / 3.0, 2))
    }

    @Test
    fun `duracoes legiveis`() {
        assertEquals("500 ms", Format.duration(500))
        assertEquals("1s", Format.duration(1_000))
        assertEquals("1s", Format.duration(1_500))
        assertEquals("1m 05s", Format.duration(65_000))
        assertEquals("1h 0m", Format.duration(3_600_000))
        assertEquals("2h 30m", Format.duration(2 * 3_600_000 + 30 * 60_000))
        assertEquals("1d 0h 0m", Format.duration(86_400_000))
        assertEquals("n/d", Format.duration(-5))
    }

    @Test
    fun `mib com dois decimais`() {
        assertEquals("0.50 MiB", Format.mib(0.5 * 1024 * 1024))
        assertEquals("0.00 MiB", Format.mib(0.0))
    }

    @Test
    fun `taxa com sinal`() {
        assertEquals("+12.50 MiB/h", Format.rateMiBPerHour(12.5))
        assertEquals("-3.00 MiB/h", Format.rateMiBPerHour(-3.0))
        assertEquals("+0.00 MiB/h", Format.rateMiBPerHour(0.0))
    }

    @Test
    fun `contagem com separador de milhar`() {
        assertEquals("1,000", Format.count(1000))
        assertEquals("12,345", Format.count(12345))
    }

    @Test
    fun `timestamp nao vazio`() {
        val t = Format.timestamp(0L)
        assertTrue(t.isNotBlank())
        assertTrue(t.contains("1970") || t.contains("/"))
    }

    @Test
    fun `age relativa`() {
        assertEquals("agora", Format.age(1_000L, 1_500L))
        assertEquals("há 10s", Format.age(1_000L, 11_000L))
    }

    @Test
    fun `dec usa ponto decimal fixo`() {
        assertEquals("3.1", Format.dec(3.14, 1))
        assertEquals("3.14", Format.dec(3.14, 2))
        assertEquals("10.0", Format.dec(10.0))
    }
}
