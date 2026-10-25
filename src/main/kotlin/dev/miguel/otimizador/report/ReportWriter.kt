package dev.miguel.otimizador.report

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.Logger

/**
 * Grava relatórios em arquivo com rotação rígida: no máximo [maxFiles]
 * relatórios são mantidos; os mais antigos são apagados. Assim o diretório de
 * relatórios nunca cresce indefinidamente.
 */
class ReportWriter(
    private val directory: File,
    private val maxFiles: Int,
    private val logger: Logger,
) {

    /** Grava o conteúdo e devolve o arquivo gerado. */
    fun write(content: String): File {
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val file = File(directory, "otimizador-$stamp.txt")
        file.writeText(content, StandardCharsets.UTF_8)
        rotate()
        return file
    }

    private fun rotate() {
        val files = directory.listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".txt")
        } ?: return
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxFiles)
            .forEach { victim ->
                if (!victim.delete()) {
                    logger.warning("Não foi possível apagar relatório antigo: ${victim.name}")
                }
            }
    }

    /** Aplica a rotação imediatamente (mantém no máximo [maxFiles] arquivos). */
    fun trim() = rotate()

    fun currentCount(): Int {
        val files = directory.listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".txt")
        } ?: return 0
        return files.size
    }

    companion object {
        private const val PREFIX = "otimizador-"
    }
}
