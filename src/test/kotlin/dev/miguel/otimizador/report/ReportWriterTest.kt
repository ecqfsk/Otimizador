package dev.miguel.otimizador.report

import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rotação de relatórios: o diretório nunca cresce sem teto e arquivos de
 * terceiros no mesmo diretório não são tocados.
 */
class ReportWriterTest {

    private val dir: File = Files.createTempDirectory("otimizador-report-test").toFile()
    private val logger: Logger = Logger.getLogger("otimizador-test").apply { useParentHandlers = false }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun old(name: String, modifiedAt: Long): File =
        File(dir, name).apply {
            writeText("antigo")
            setLastModified(modifiedAt)
        }

    @Test
    fun `grava o conteudo no arquivo devolvido`() {
        val w = ReportWriter(dir, maxFiles = 3, logger = logger)
        val out = w.write("conteúdo do relatório\nlinha 2")

        assertTrue(out.isFile)
        assertTrue(out.name.startsWith("otimizador-") && out.name.endsWith(".txt"))
        assertTrue(out.readText().contains("conteúdo do relatório"))
        assertEquals(1, w.currentCount())
    }

    @Test
    fun `rotacao mantem no maximo o limite de arquivos`() {
        old("otimizador-20240101-000000.txt", 1_000L)
        old("otimizador-20240102-000000.txt", 2_000L)
        old("otimizador-20240103-000000.txt", 3_000L)

        val w = ReportWriter(dir, maxFiles = 2, logger = logger)
        val out = w.write("atual")

        assertTrue(out.isFile)
        assertEquals(2, w.currentCount(), "teto de arquivos respeitado")
        assertTrue(
            File(dir, "otimizador-20240101-000000.txt").exists().not(),
            "o mais antigo foi apagado",
        )
        assertTrue(File(dir, "otimizador-20240103-000000.txt").exists(), "os recentes permanecem")
    }

    @Test
    fun `trim aplica o teto sem gravar nada`() {
        old("otimizador-20240101-000000.txt", 1_000L)
        old("otimizador-20240102-000000.txt", 2_000L)
        old("otimizador-20240103-000000.txt", 3_000L)
        val unrelated = File(dir, "meu-arquivo.txt").apply { writeText("não me toque") }

        val w = ReportWriter(dir, maxFiles = 1, logger = logger)
        w.trim()

        assertEquals(1, w.currentCount())
        assertTrue(unrelated.isFile, "arquivos que não são relatórios não são apagados")
        assertEquals("não me toque", unrelated.readText())
    }

    @Test
    fun `diretorio vazio nao tem relatorios`() {
        val w = ReportWriter(dir, maxFiles = 2, logger = logger)
        assertEquals(0, w.currentCount())
    }

    @Test
    fun `rotacao nao falha quando nada pode ser apagado`() {
        // limite maior que a quantidade existente: nada a fazer, sem exceção
        old("otimizador-20240101-000000.txt", 1_000L)
        val w = ReportWriter(dir, maxFiles = 10, logger = logger)
        w.trim()
        assertEquals(1, w.currentCount())
        assertFalse(File(dir, "otimizador-20240101-000000.txt").length() == 0L)
    }
}
