package dev.miguel.otimizador.core

/**
 * Amostra serializada no tempo (métrica escalar + horário em epoch millis).
 */
data class TimedSample(val timeMillis: Long, val value: Double)

/**
 * Buffer circular de tamanho fixo.
 *
 * Propriedade essencial deste plugin: a capacidade é CONSTANTE. O buffer nunca
 * cresce — quando fica cheio, a amostra mais antiga é sobrescrita. Isso garante
 * que o próprio histórico do Otimizador não se transforme em fonte de consumo
 * de memória.
 *
 * Todas as operações são thread-safe (a coleta é assíncrona e os comandos
 * rodam na thread principal).
 */
class RingBuffer(capacity: Int) {

    init {
        require(capacity > 0) { "capacidade do RingBuffer deve ser > 0 (recebido: $capacity)" }
    }

    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private var count = 0
    private var writeIndex = 0

    /** Capacidade máxima (fixa). */
    val capacity: Int get() = times.size

    /** Número de amostras atualmente retidas (<= capacity). */
    val size: Int get() = count

    /** Adiciona uma amostra; descarta a mais antiga se estiver cheio. */
    @Synchronized
    fun add(timeMillis: Long, value: Double) {
        times[writeIndex] = timeMillis
        values[writeIndex] = value
        writeIndex = (writeIndex + 1) % times.size
        if (count < times.size) count++
    }

    /** Última amostra adicionada, ou null se vazio. */
    @Synchronized
    fun latest(): TimedSample? {
        if (count == 0) return null
        val idx = (writeIndex - 1 + times.size) % times.size
        return TimedSample(times[idx], values[idx])
    }

    /** Primeira amostra retida, ou null se vazio. */
    @Synchronized
    fun oldest(): TimedSample? {
        if (count == 0) return null
        val idx = (writeIndex - count + times.size) % times.size
        return TimedSample(times[idx], values[idx])
    }

    /**
     * Retorna as últimas [n] amostras em ordem cronológica (mais antiga primeiro).
     * A lista retornada é limitada por [n] e por [capacity].
     */
    @Synchronized
    fun last(n: Int): List<TimedSample> {
        val wanted = n.coerceIn(0, count)
        if (wanted == 0) return emptyList()
        val out = ArrayList<TimedSample>(wanted)
        val start = (writeIndex - wanted + times.size) % times.size
        for (i in 0 until wanted) {
            val idx = (start + i) % times.size
            out.add(TimedSample(times[idx], values[idx]))
        }
        return out
    }

    /** Snapshot completo em ordem cronológica (limitado à capacidade). */
    @Synchronized
    fun all(): List<TimedSample> = last(count)

    /** Valor máximo das últimas [n] amostras (ou null se vazio). */
    @Synchronized
    fun maxOfLast(n: Int): Double? {
        val samples = last(n)
        if (samples.isEmpty()) return null
        var m = Double.NEGATIVE_INFINITY
        for (s in samples) if (s.value > m) m = s.value
        return m
    }

    /** Valor mínimo das últimas [n] amostras (ou null se vazio). */
    @Synchronized
    fun minOfLast(n: Int): Double? {
        val samples = last(n)
        if (samples.isEmpty()) return null
        var m = Double.POSITIVE_INFINITY
        for (s in samples) if (s.value < m) m = s.value
        return m
    }

    @Synchronized
    fun clear() {
        count = 0
        writeIndex = 0
    }
}
