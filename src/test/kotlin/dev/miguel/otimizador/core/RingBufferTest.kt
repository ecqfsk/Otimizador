package dev.miguel.otimizador.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Garante que o histórico é LIMITADO: o buffer nunca cresce além da capacidade
 * e sempre devolve amostras em ordem cronológica.
 */
class RingBufferTest {

    @Test
    fun `capacidade nunca e excedida`() {
        val buffer = RingBuffer(10)
        repeat(100) { buffer.add(it * 1000L, it.toDouble()) }
        assertEquals(10, buffer.size)
        assertEquals(10, buffer.capacity)
    }

    @Test
    fun `amostras mais antigas sao sobrescritas`() {
        val buffer = RingBuffer(5)
        for (i in 0 until 8) buffer.add(i * 1000L, i.toDouble())
        assertEquals(5, buffer.size)
        val all = buffer.all()
        assertEquals(5, all.size)
        assertEquals(3.0, all.first().value)
        assertEquals(7.0, all.last().value)
        assertEquals(3000L, all.first().timeMillis)
    }

    @Test
    fun `ordem cronologica preservada em last`() {
        val buffer = RingBuffer(4)
        for (i in 0 until 6) buffer.add(i * 10L, i.toDouble())
        val last3 = buffer.last(3)
        assertEquals(listOf(3.0, 4.0, 5.0), last3.map { it.value })
    }

    @Test
    fun `buffer vazio devolve nulos`() {
        val buffer = RingBuffer(3)
        assertNull(buffer.latest())
        assertNull(buffer.oldest())
        assertNull(buffer.maxOfLast(3))
        assertNull(buffer.minOfLast(3))
        assertTrue(buffer.all().isEmpty())
    }

    @Test
    fun `latest reflete a ultima amostra`() {
        val buffer = RingBuffer(3)
        buffer.add(1000L, 42.0)
        val latest = buffer.latest()
        assertNotNull(latest)
        assertEquals(42.0, latest.value)
        assertEquals(1000L, latest.timeMillis)
    }

    @Test
    fun `max e min respeitam janela`() {
        val buffer = RingBuffer(10)
        val values = listOf(5.0, 1.0, 9.0, 3.0, 7.0)
        values.forEachIndexed { i, v -> buffer.add(i.toLong(), v) }
        assertEquals(9.0, buffer.maxOfLast(10))
        assertEquals(1.0, buffer.minOfLast(10))
        assertEquals(9.0, buffer.maxOfLast(3))
        assertEquals(3.0, buffer.minOfLast(3))
    }

    @Test
    fun `capacidade invalida e rejeitada`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { RingBuffer(0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { RingBuffer(-1) }
    }

    @Test
    fun `last pedindo mais do que existe devolve tudo`() {
        val buffer = RingBuffer(100)
        buffer.add(1L, 1.0)
        buffer.add(2L, 2.0)
        assertEquals(2, buffer.last(50).size)
    }
}
