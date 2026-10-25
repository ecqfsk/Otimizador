package dev.miguel.otimizador.core

import kotlin.math.abs

/**
 * Análise de tendência (regressão linear mínimos quadrados) sobre amostras
 * temporais. Pura — sem dependência de Bukkit — para permitir testes unitários.
 */
object Trend {

    /** Resultado de um ajuste linear: y = slope * x + intercept, com x em horas. */
    data class LinearFit(
        /** Taxa de variação por hora (unidade da métrica / hora). */
        val slopePerHour: Double,
        val intercept: Double,
        /** Coeficiente de determinação (0..1). Quanto mais perto de 1, mais linear. */
        val r2: Double,
        /** Quantidade de amostras usadas. */
        val samples: Int,
        /** Janela coberta em horas. */
        val windowHours: Double,
    )

    /**
     * Ajusta y = a*x + b com x = tempo em horas desde a primeira amostra.
     * Retorna null quando há menos de 2 amostras ou quando todas têm o mesmo
     * instante (reta indefinida).
     */
    fun fit(samples: List<TimedSample>): LinearFit? {
        val n = samples.size
        if (n < 2) return null
        val t0 = samples.first().timeMillis
        val spanMs = samples.last().timeMillis - t0
        if (spanMs <= 0) return null

        // X em horas desde o início; normaliza para evitar overflow de soma grande.
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = (samples[i].timeMillis - t0) / 3_600_000.0
            ys[i] = samples[i].value
        }

        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until n) {
            sumX += xs[i]
            sumY += ys[i]
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        if (abs(sxx) < 1e-12) return null

        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val r2 = if (abs(syy) < 1e-12) {
            // Y constante: ajuste perfeito apenas se a inclinação também for ~0.
            if (abs(slope) < 1e-9) 1.0 else 0.0
        } else {
            val r = sxy / kotlin.math.sqrt(sxx * syy)
            (r * r).coerceIn(0.0, 1.0)
        }

        return LinearFit(
            slopePerHour = slope,
            intercept = intercept,
            r2 = r2,
            samples = n,
            windowHours = spanMs / 3_600_000.0,
        )
    }

    /**
     * Primeira diferença observada entre o início e o fim da série
     * (útil como evidência simples de crescimento).
     */
    fun absoluteDelta(samples: List<TimedSample>): Double? {
        if (samples.size < 2) return null
        return samples.last().value - samples.first().value
    }
}
