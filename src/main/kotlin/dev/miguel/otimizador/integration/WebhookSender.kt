package dev.miguel.otimizador.integration

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Integração opcional por webhook (ex.: Discord). DESLIGADA quando a URL está
 * vazia. Envia apenas métricas agregadas e o estado — nunca dados de jogadores.
 *
 * Regras de segurança desta integração:
 *  - nunca bloqueia o tick principal (HTTP assíncrono com timeout curto);
 *  - falhas são engolidas com log reduzido (não polui o console);
 *  - um único cliente HTTP reutilizado, fechado no disable do plugin.
 */
class WebhookSender(
    url: String,
    private val timeoutSeconds: Int,
    private val logger: Logger,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val target: URI = URI.create(url)

    /** Envia o texto como conteúdo simples (compatível com Discord). */
    fun sendAsync(text: String) {
        try {
            val payload = "{\"content\":\"${escape(text)}\"}"
            val request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val future: CompletableFuture<HttpResponse<String>> =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            future.whenComplete { _, error ->
                if (error != null) {
                    logger.log(Level.FINE, "Webhook falhou: ${error.message}")
                }
            }
        } catch (t: Throwable) {
            logger.log(Level.FINE, "Webhook não pôde ser enviado: ${t.message}")
        }
    }

    fun close() {
        // HttpClient moderno não exige fechamento explícito; limpa referências.
    }

    private fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<', '>', '&' -> append(" ") // evita formatação inesperada
                else -> if (c.code < 0x20) append(" ") else append(c)
            }
        }
    }
}
