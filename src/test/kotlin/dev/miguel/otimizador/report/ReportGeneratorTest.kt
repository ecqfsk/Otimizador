package dev.miguel.otimizador.report

import dev.miguel.otimizador.config.Settings
import dev.miguel.otimizador.core.MemoryState
import dev.miguel.otimizador.diag.Finding
import dev.miguel.otimizador.diag.FindingLevel
import dev.miguel.otimizador.jvm.JvmAnalyzer
import dev.miguel.otimizador.memory.MemoryStore
import dev.miguel.otimizador.memorySnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O relatório é o artefato entregue ao administrador: precisa ter valores com
 * unidades, horários, os conceitos diferenciados (usado × comprometido ×
 * máximo) e a seção de limitações — nunca NaN nem conclusões sem evidência.
 */
class ReportGeneratorTest {

    private val mib = 1024L * 1024

    /** Coletado uma vez por instância de teste: a comparação de determinismo não pode incluir uptime. */
    private val jvm = JvmAnalyzer.collect()

    private fun generate(
        state: MemoryState = MemoryState.NORMAL,
        findings: List<Finding> = emptyList(),
    ): String {
        val store = MemoryStore(historyCapacity = 60, maxEvidences = 10, gcEventTtlMillis = 900_000L)
        store.record(
            memorySnapshot(
                timeMillis = 1_000L,
                gc = listOf(dev.miguel.otimizador.gcReading(count = 5, timeMs = 50)),
            )
        )
        store.record(
            memorySnapshot(
                timeMillis = 61_000L,
                gc = listOf(dev.miguel.otimizador.gcReading(count = 7, timeMs = 90)),
            )
        )

        val generator = ReportGenerator(Settings(), store, jvm)
        val ctx = ReportContext(
            nowMillis = 1_700_000_000_000L,
            pluginVersion = "1.0.0",
            serverVersion = "Paper 26.2",
            tps = listOf(20.0, 20.0, 20.0),
            msptMillis = 45.5,
            onlinePlayers = 2,
            worldCount = 1,
            state = state,
            stateSinceMillis = 1_699_999_000_000L,
            modules = listOf("memoria", "chunks", "entidades"),
        )
        return generator.generate(ctx, findings, JvmAnalyzer.recommendations(jvm, observed()))
    }

    private fun observed() = dev.miguel.otimizador.jvm.ObservedStats(
        peakHeapUsed = 2048L * mib,
        avgHeapUsed = 1536L * mib,
        gcOverheadPercent = 3.5,
        latestRssBytes = 2560L * mib,
        peakRssBytes = 3072L * mib,
        threadCount = 40,
        heapMax = 4096L * mib,
        heapFloorLatest = 768L * mib,
    )

    @Test
    fun `relatorio tem cabecalho com versao e horario`() {
        val text = generate()
        assertTrue(text.contains("RELATÓRIO COMPLETO — OTIMIZADOR 1.0.0"))
        assertTrue(text.contains("Gerado em:"), "horário local presente")
        assertTrue(text.contains("1. AMBIENTE"))
    }

    @Test
    fun `conceitos de memoria sao diferenciados`() {
        val text = generate()
        assertTrue(text.contains("2. RESUMO DE MEMÓRIA"))
        assertTrue(text.contains("Heap comprometido:"), "usado ≠ comprometido")
        assertTrue(text.contains("heap máximo") || text.contains("-Xmx"), "máximo ≠ consumo")
        assertTrue(text.contains("Não-heap:"), "não-heap reportado à parte")
        assertTrue(text.contains("3. HISTÓRICO E TENDÊNCIAS"))
        assertTrue(text.contains("5. GARBAGE COLLECTION"))
        assertTrue(text.contains("8. DIAGNÓSTICO"))
    }

    @Test
    fun `relatorio traz a secao de limitacoes completa`() {
        val text = generate()
        assertTrue(text.contains("10. LIMITAÇÕES DESTE RELATÓRIO"))
        assertTrue(text.contains("Heap comprometido ≠ heap usado"))
        assertTrue(text.contains("/proc/self/status"), "origem do RSS documentada")
        assertTrue(text.contains("resolução do intervalo de coleta"), "pausas de GC são estimativas")
        assertTrue(text.contains("API pública não expõe memória por classe"))
        assertTrue(text.contains("Nenhum System.gc() foi chamado"))
    }

    @Test
    fun `configuracao efetiva mostra protecoes desligadas por padrao`() {
        val text = generate()
        assertTrue(text.contains("9. CONFIGURAÇÃO EFETIVA"))
        assertTrue(text.contains("desligada"), "remoção de chunks/entidades desligada por padrão")
        assertTrue(text.contains("Histerese:"), "histerese e persistências documentadas")
    }

    @Test
    fun `valores sao formatados com unidades e sem NaN`() {
        val text = generate(
            state = MemoryState.PRESSAO_ALTA,
            findings = listOf(
                Finding(
                    id = "exemplo",
                    level = FindingLevel.ALERTA,
                    title = "Achado de exemplo",
                    atMillis = 1_700_000_000_000L,
                    evidence = listOf("heap usado: 1.0 GiB em 25/10/2026 12:00:00"),
                    recommendations = listOf("Recomendação de exemplo"),
                ),
            ),
        )
        assertTrue(text.contains("MiB") || text.contains("GiB"), "unidades binárias presentes")
        assertTrue(text.contains("Achado de exemplo"))
        assertTrue(text.contains("Recomendação de exemplo"))
        assertTrue(text.contains("Pressão elevada"), "estado elevado refletido no relatório")
        assertFalse(text.contains("NaN"), "formato numérico nunca deve vazar NaN")
        assertFalse(text.contains("Infinity"))
    }

    @Test
    fun `relatorio e deterministico para os mesmos dados`() {
        // Mesmos dados de entrada => mesmo texto (sem aleatoriedade nem relógio solto).
        kotlin.test.assertEquals(generate(), generate())
    }
}
