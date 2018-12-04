package no.nav.paop.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.config
import io.ktor.client.features.auth.basic.BasicAuth
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import no.nav.paop.Environment

val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

fun createHttpClient(env: Environment) = HttpClient(CIO.config {
    maxConnectionsCount = 1000 // Maximum number of socket connections.
    endpoint.apply {
        maxConnectionsPerRoute = 100
        pipelineMaxSize = 20
        keepAliveTime = 5000
        connectTimeout = 5000
        connectRetryAttempts = 5
    }
}) {
    install(JsonFeature) {
        serializer = JacksonSerializer {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        }
    }
}

suspend fun HttpClient.generatePDF(env: Environment, pdfType: PdfType, domainObject: Any): ByteArray = post {
    contentType(ContentType.Application.Json)
    body = objectMapper.writeValueAsBytes(domainObject)
    accept(ContentType.Application.Json)

    url("${env.pdfGeneratorURL}/api/v1/genpdf/paop/${pdfType.pdfGenName()}")
}

enum class PdfType {
    FAGMELDING,
    BEHANDLINGSVEDLEGG
}

fun PdfType.pdfGenName(): String =
        name.toLowerCase()
