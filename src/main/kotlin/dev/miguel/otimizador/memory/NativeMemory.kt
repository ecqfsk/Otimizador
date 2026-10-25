package dev.miguel.otimizador.memory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Leitura da memória nativa do processo.
 *
 * Em Linux, `/proc/self/status` expõe VmRSS (memória residente), VmSize
 * (endereço virtual), VmHWM (pico de RSS) e VmSwap de forma confiável e sem
 * dependências externas. Em outros sistemas o arquivo não existe e o plugin
 * reporta "n/d" — nunca inventa valores.
 *
 * O parser é puro e testável; a leitura de arquivo é isolada em [read].
 */
data class ProcStatus(
    val rssBytes: Long?,
    val vmSizeBytes: Long?,
    val peakRssBytes: Long?,
    val swapBytes: Long?,
    val threadCount: Long?,
    val cmdline: String? = null,
)

object NativeMemory {

    /** Caminho do arquivo lido (para documentação/diagnóstico). */
    const val STATUS_PATH = "/proc/self/status"

    /** Lê o status do processo atual. Retorna estrutura com campos nulos se indisponível. */
    fun read(): ProcStatus {
        val content = readFile(STATUS_PATH) ?: return ProcStatus(null, null, null, null, null)
        return parse(content)
    }

    /**
     * Interpreta o conteúdo de /proc/self/status (Linux).
     * Campos ignorados ou malformados simplesmente ficam nulos.
     */
    internal fun parse(content: String): ProcStatus {
        var rss: Long? = null
        var vmSize: Long? = null
        var hwm: Long? = null
        var swap: Long? = null
        var threads: Long? = null

        for (line in content.lineSequence()) {
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val key = line.substring(0, sep)
            val rest = line.substring(sep + 1).trim()
            when (key) {
                "VmRSS" -> rss = parseKb(rest)
                "VmSize" -> vmSize = parseKb(rest)
                "VmHWM" -> hwm = parseKb(rest)
                "VmSwap" -> swap = parseKb(rest)
                "Threads" -> threads = rest.split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
            }
        }
        return ProcStatus(rss, vmSize, hwm, swap, threads)
    }

    /** "123456 kB" → bytes; "42" (sem unidade) → 42; formatos inesperados → null. */
    internal fun parseKb(text: String): Long? {
        val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val value = parts[0].toLongOrNull() ?: return null
        return when {
            parts.size == 1 -> value
            parts[1].equals("kB", ignoreCase = true) -> value * 1024
            // Unidade desconhecida (ex.: "12 MB"): não inventa conversão.
            else -> null
        }
    }

    private fun readFile(path: String): String? = try {
        val p = Path.of(path)
        if (!Files.isReadable(p)) null
        else String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    } catch (_: InvalidPathException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}
