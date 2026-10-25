package dev.miguel.otimizador.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O cache do próprio plugin precisa ter teto de tamanho e expiração — caso
 * contrário o Otimizador seria parte do problema que combate.
 */
class TtlCacheTest {

    @Test
    fun `nunca excede o tamanho maximo`() {
        var now = 0L
        val cache = TtlCache<String, Int>(maxSize = 3, ttlMillis = 1_000, clock = { now })
        for (i in 1..10) cache.put("k$i", i)
        assertEquals(3, cache.size)
        assertNotNull(cache.get("k10"))
    }

    @Test
    fun `evicao e por LRU`() {
        var now = 0L
        val cache = TtlCache<String, Int>(maxSize = 2, ttlMillis = 10_000, clock = { now })
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")          // acessa 'a' -> 'b' vira o menos recente
        cache.put("c", 3)       // estoura: deve evadir 'b'
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `entrada expirada nao e retornada`() {
        var now = 0L
        val cache = TtlCache<String, Int>(maxSize = 5, ttlMillis = 1_000, clock = { now })
        cache.put("x", 42)
        assertEquals(42, cache.get("x"))
        now = 999L
        assertEquals(42, cache.get("x"), "dentro do TTL deve retornar")
        now = 1_001L
        assertNull(cache.get("x"), "após o TTL deve expirar")
        assertEquals(0, cache.size, "get com expiração remove a entrada")
    }

    @Test
    fun `cleanup remove somente expiradas`() {
        var now = 0L
        val cache = TtlCache<String, Int>(maxSize = 10, ttlMillis = 500, clock = { now })
        cache.put("velho1", 1)
        cache.put("velho2", 2)
        now = 400L
        cache.put("novo", 3)
        now = 600L
        // velho1/2 armazenados em t=0 -> 600 > 500 (expirados); novo em 400 -> 200 (ok)
        assertEquals(2, cache.cleanup())
        assertEquals(1, cache.size)
        assertEquals(3, cache.get("novo"))
    }

    @Test
    fun `entries nao inclui expiradas e respeita o teto`() {
        var now = 0L
        val cache = TtlCache<String, Int>(maxSize = 3, ttlMillis = 100, clock = { now })
        cache.put("a", 1)
        now = 50L
        cache.put("b", 2)
        now = 150L
        val entries = cache.entries()
        assertEquals(1, entries.size)
        assertEquals("b", entries.first().first)
        assertTrue(entries.size <= 3)
    }

    @Test
    fun `clear zera tudo`() {
        val cache = TtlCache<String, Int>(maxSize = 3, ttlMillis = 100)
        cache.put("a", 1)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("a"))
        assertFalse(cache.entries().isNotEmpty())
    }

    @Test
    fun `parametros invalidos sao rejeitados`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { TtlCache<String, Int>(0, 100) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { TtlCache<String, Int>(1, 0) }
    }
}
