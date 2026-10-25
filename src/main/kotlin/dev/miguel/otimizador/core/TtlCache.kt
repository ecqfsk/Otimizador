package dev.miguel.otimizador.core

/**
 * Cache limitado por tamanho e por tempo de vida (TTL).
 *
 * Garantias:
 *  - nunca mantém mais de [maxSize] entradas (evição LRU ao estourar);
 *  - entradas expiradas são ignoradas na leitura e removidas na varredura;
 *  - [entries] devolve um snapshot limitado — nada aqui pode crescer sem teto.
 *
 * Usado pelo próprio Otimizador para históricos curtos (ex.: contagem de
 * tarefas pendentes por plugin). Não é um mecanismo para limpar caches de
 * terceiros: isso exigiria API oficial do plugin alvo.
 */
class TtlCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(maxSize > 0) { "maxSize deve ser > 0 (recebido: $maxSize)" }
        require(ttlMillis > 0) { "ttlMillis deve ser > 0 (recebido: $ttlMillis)" }
    }

    private class Entry<V>(val value: V, val storedAt: Long)

    // accessOrder = true para LRU; removeEldestEntry mantém o teto rígido.
    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean =
            size > maxSize
    }

    val size: Int get() = synchronized(this) { map.size }

    fun put(key: K, value: V) {
        synchronized(this) { map[key] = Entry(value, clock()) }
    }

    /** Retorna o valor se existir e não estiver expirado (remove se estiver). */
    fun get(key: K): V? {
        synchronized(this) {
            val e = map[key] ?: return null
            if (clock() - e.storedAt > ttlMillis) {
                map.remove(key)
                return null
            }
            return e.value
        }
    }

    /** Remove entradas expiradas. Retorna quantas foram removidas. */
    fun cleanup(): Int {
        val now = clock()
        synchronized(this) {
            val it = map.entries.iterator()
            var removed = 0
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.storedAt > ttlMillis) {
                    it.remove()
                    removed++
                }
            }
            return removed
        }
    }

    /** Snapshot limitado a [maxSize] entradas válidas, em ordem de acesso (LRU). */
    fun entries(): List<Pair<K, V>> {
        val now = clock()
        synchronized(this) {
            val out = ArrayList<Pair<K, V>>(map.size)
            for ((k, e) in map) {
                if (now - e.storedAt <= ttlMillis) out.add(k to e.value)
            }
            return out
        }
    }

    fun clear() {
        synchronized(this) { map.clear() }
    }
}
