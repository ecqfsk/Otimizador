package dev.miguel.otimizador.memory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Parser de /proc/self/status: precisa tolerar campos ausentes e malformados
 * sem lançar exceções — em SO sem procfs o plugin reporta "n/d".
 */
class NativeMemoryParseTest {

    private val sample = """
        Name:	java
        Umask:	0022
        State:	S (sleeping)
        Tgid:	12345
        Threads:	87
        VmPeak:	 5242880 kB
        VmSize:	 5177344 kB
        VmHWM:	  3145728 kB
        VmRSS:	  2883584 kB
        RssAnon:	  2097152 kB
        RssFile:	   786432 kB
        VmSwap:	        0 kB
        SigQ:	0/12345
        CapEff:	0000000000000000
    """.trimIndent()

    @Test
    fun `campos conhecidos sao convertidos de kB para bytes`() {
        val p = NativeMemory.parse(sample)
        assertEquals(2883584L * 1024, p.rssBytes, "VmRSS em kB → bytes")
        assertEquals(5177344L * 1024, p.vmSizeBytes, "VmSize em kB → bytes")
        assertEquals(3145728L * 1024, p.peakRssBytes, "VmHWM em kB → bytes")
        assertEquals(0L, p.swapBytes, "VmSwap zerado continua sendo 0")
        assertEquals(87L, p.threadCount, "Threads é contagem, não kB")
    }

    @Test
    fun `conteudo malformado devolve nulos sem excecao`() {
        val p = NativeMemory.parse("apenas um texto qualquer\nsem separador\n\n")
        assertNull(p.rssBytes)
        assertNull(p.vmSizeBytes)
        assertNull(p.peakRssBytes)
        assertNull(p.swapBytes)
        assertNull(p.threadCount)
    }

    @Test
    fun `valores nao numericos ficam nulos`() {
        val p = NativeMemory.parse("VmRSS:\tABC kB\nThreads:\tmuitas\n")
        assertNull(p.rssBytes)
        assertNull(p.threadCount)
    }

    @Test
    fun `linha sem dois pontos e ignorada`() {
        // "VmRSS" sem valor e com dois-pontos no início da linha
        val p = NativeMemory.parse(":VmRSS 100 kB\n::\n")
        assertNull(p.rssBytes)
    }

    @Test
    fun `parseKb aceita kb sem unidade e rejeita unidade desconhecida`() {
        assertEquals(1024L, NativeMemory.parseKb("1 kB"))
        assertEquals(42L, NativeMemory.parseKb("42"))
        assertEquals(0L, NativeMemory.parseKb("0 kB"))
        assertNull(NativeMemory.parseKb(""))
        assertNull(NativeMemory.parseKb("abc kB"))
        assertNull(NativeMemory.parseKb("1kb"), "sem separador a unidade não é interpretada → n/d")
        assertNull(NativeMemory.parseKb("12 MB"), "unidade desconhecida não é convertida às cegas")
    }

    @Test
    fun `read nao lanca excecao`() {
        val p = NativeMemory.read()
        assertNotNull(p, "read() sempre devolve uma estrutura")
        if (Files.isReadable(Path.of(NativeMemory.STATUS_PATH))) {
            // Em Linux o arquivo existe: os campos principais precisam ser lidos.
            assertNotNull(p.rssBytes, "procfs legível deve produzir VmRSS")
            assertNotNull(p.threadCount)
        } else {
            assertNull(p.rssBytes, "sem procfs o valor é n/d, nunca inventado")
        }
    }
}
