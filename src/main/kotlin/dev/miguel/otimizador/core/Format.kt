package dev.miguel.otimizador.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Formatação determinística de valores para relatórios e comandos.
 *
 * Todas as strings usam Locale.ROOT (ponto decimal) para que relatórios e
 * testes sejam reprodutíveis em qualquer máquina.
 */
object Format {

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withLocale(Locale.ROOT)

    private fun num(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%,.${decimals}f", value)

    /** Número com ponto decimal fixo e [decimals] casas (Locale.ROOT). */
    fun dec(value: Double, decimals: Int = 1): String = num(value, decimals)

    /** Formata bytes com unidades binárias (KiB/MiB/GiB). */
    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "n/d"
        val b = value.toDouble()
        return when {
            b < 1024.0 -> "$value B"
            b < 1024.0 * 1024 -> "${num(b / 1024.0, 1)} KiB"
            b < 1024.0 * 1024 * 1024 -> "${num(b / (1024.0 * 1024), 1)} MiB"
            b < 1024.0 * 1024 * 1024 * 1024 -> "${num(b / (1024.0 * 1024 * 1024), 2)} GiB"
            else -> "${num(b / (1024.0 * 1024 * 1024 * 1024), 2)} TiB"
        }
    }

    /** Formata bytes com dois decimais em MiB fixo (para séries temporais). */
    fun mib(value: Double): String = "${num(value / (1024.0 * 1024), 2)} MiB"

    /** Percentual 0..1 → "12,3%" (Locale.ROOT → ponto decimal: "12.3%"). */
    fun percent(ratio: Double, decimals: Int = 1): String =
        "${num(ratio * 100.0, decimals)}%"

    /** Percentual já em pontos (0..100) → "12.3%". */
    fun percentPoints(points: Double, decimals: Int = 1): String =
        "${num(points, decimals)}%"

    /** Duração em ms → "1h 23m", "4m 05s", "850 ms" etc. */
    fun duration(millis: Long): String {
        if (millis < 0) return "n/d"
        val ms = millis % 1000
        val s = (millis / 1000) % 60
        val m = (millis / 60_000) % 60
        val h = (millis / 3_600_000) % 24
        val d = millis / 86_400_000
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${String.format(Locale.ROOT, "%02d", s)}s"
            s > 0 -> "${s}s"
            else -> "${ms} ms"
        }
    }

    /** Horário local do sistema a partir de epoch millis. */
    fun timestamp(epochMillis: Long): String =
        timeFmt.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    /** Idade relativa: "há 12s", "há 3m", "agora". */
    fun age(epochMillis: Long, nowMillis: Long): String {
        val diff = nowMillis - epochMillis
        return when {
            diff < 2_000 -> "agora"
            else -> "há ${duration(diff.coerceAtLeast(0))}"
        }
    }

    /** Número com separador de milhar e decimais fixos (Locale.ROOT). */
    fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

    fun count(value: Int): String = String.format(Locale.ROOT, "%,d", value)

    /** Taxa em MiB/hora sempre com sinal explícito (crescimento positivo, "0.00" = estável). */
    fun rateMiBPerHour(value: Double): String {
        val sign = if (value < 0) "-" else "+"
        return "$sign${num(abs(value), 2)} MiB/h"
    }
}
