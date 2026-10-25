package dev.miguel.otimizador.memory

import dev.miguel.otimizador.core.Format
import dev.miguel.otimizador.core.TimedSample
import dev.miguel.otimizador.core.Trend

/** Resultado da análise de crescimento sustentado de uma série. */
data class GrowthSignal(
    val detected: Boolean,
    val slopeMiBPerHour: Double,
    val r2: Double,
    val samples: Int,
    val windowMinutes: Double,
    val deltaMiB: Double,
    val firstMiB: Double,
    val lastMiB: Double,
    val reason: String,
) {
    companion object {
        fun insufficient(available: Int, required: Int) = GrowthSignal(
            detected = false,
            slopeMiBPerHour = 0.0,
            r2 = 0.0,
            samples = available,
            windowMinutes = 0.0,
            deltaMiB = 0.0,
            firstMiB = 0.0,
            lastMiB = 0.0,
            reason = "amostras insuficientes ($available de $required mínimas) — sem conclusão",
        )
    }
}

/**
 * Detector de crescimento persistente (sinais de possível vazamento).
 *
 * IMPORTANTE: isto é um SINAL, não uma prova. RAM que sobe não significa
 * vazamento — pode ser aquecimento normal, cache legítimo ou retenção
 * temporária. Por isso exigimos:
 *  - número mínimo de amostras (evita alarme no aquecimento);
 *  - taxa de crescimento mínima configurada;
 *  - aderência linear (R²) mínima, para separar crescimento CONTÍNUO de
 *    picos intermitentes (que são normais após GC).
 *
 * Mesmo quando detectado, o relatório orienta profiling externo: a API
 * pública do Paper não permite atribuir o consumo a classes ou plugins.
 */
object GrowthAnalyzer {

    fun analyze(
        samples: List<TimedSample>,
        minSamples: Int,
        windowSamples: Int,
        minMiBPerHour: Double,
        minR2: Double = 0.4,
    ): GrowthSignal {
        if (samples.size < minSamples) return GrowthSignal.insufficient(samples.size, minSamples)

        val window = if (samples.size > windowSamples) samples.takeLast(windowSamples) else samples
        if (window.size < minSamples) return GrowthSignal.insufficient(window.size, minSamples)

        val fit = Trend.fit(window) ?: return GrowthSignal.insufficient(window.size, minSamples + 1)

        val mib = 1024.0 * 1024.0
        val slopeMiB = fit.slopePerHour / mib
        val firstMiB = window.first().value / mib
        val lastMiB = window.last().value / mib
        val deltaMiB = lastMiB - firstMiB

        val fastEnough = slopeMiB >= minMiBPerHour
        val linearEnough = fit.r2 >= minR2
        val actuallyGrowing = deltaMiB > 0.0

        val (detected, reason) = when {
            !actuallyGrowing -> false to "sem crescimento líquido na janela (${Trend.absoluteDelta(window)?.div(mib)} MiB)"
            fastEnough && linearEnough -> true to
                "crescimento contínuo de ${slopeMiB} MiB/h com aderência linear R²=${Format.dec(fit.r2, 2)}"
            fastEnough && !linearEnough -> false to
                "taxa alta (${slopeMiB} MiB/h) mas crescimento irregular (R²=${Format.dec(fit.r2, 2)}) — compatível com picos normais, não conclusivo"
            else -> false to
                "crescimento de ${slopeMiB} MiB/h abaixo do limiar configurado (${minMiBPerHour} MiB/h)"
        }

        return GrowthSignal(
            detected = detected,
            slopeMiBPerHour = slopeMiB,
            r2 = fit.r2,
            samples = window.size,
            windowMinutes = fit.windowHours * 60.0,
            deltaMiB = deltaMiB,
            firstMiB = firstMiB,
            lastMiB = lastMiB,
            reason = reason,
        )
    }
}
