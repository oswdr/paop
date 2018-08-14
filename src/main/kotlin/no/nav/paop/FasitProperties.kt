package no.nav.paop

data class FasitProperties(
    val srvPaopUsername: String = getEnvVar("SRVPAOP_USERNAME"),
    val srvPaopPassword: String = getEnvVar("SRVPAOP_PASSWORD"),
    val aaregHentOrganisasjonEndpointURL: String = getEnvVar("AAREG_HENT_ORGANISASJON_ENDPOINTURL"),
    val aaregWSUsername: String = getEnvVar("AAREGPOLICYUSER_USERNAME"),
    val aaregWSPassword: String = getEnvVar("AAREGPOLICYUSER_PASSWORD"),
    val journalbehandlingEndpointURL: String = getEnvVar("JOARK_JOURNALBEHANDLING_WS_ENDPOINT_URL"),
    val securityTokenServiceUrl: String = getEnvVar("SECURITYTOKENSERVICE_URL"),
    val appName: String = getEnvVar("APP_NAME"),
    val appVersion: String = getEnvVar("APP_VERSION"),
    val arenaIAQueue: String = getEnvVar("EIA_QUEUE_ARENA_IA_QUEUENAME"),
    val mqHostname: String = getEnvVar("MQGATEWAY03_HOSTNAME"),
    val mqPort: Int = getEnvVar("MQGATEWAY03_PORT").toInt(),
    val mqQueueManagerName: String = getEnvVar("MQGATEWAY03_NAME"),
    val mqChannelName: String = getEnvVar("PAOP_CHANNEL_NAME"),
    val mqUsername: String = getEnvVar("SRVAPPSERVER_USERNAME", "srvappserver"),
    val mqPassword: String = getEnvVar("SRVAPPSERVER_PASSWORD", ""),
    val fastlegeregiserHdirURL: String = getEnvVar("FASTLEGEREGISTER_HDIR_ENDPOINTURL"),
    val pdfGeneratorURL: String = getEnvVar("PDF_GENERATOR_URL", "http://pdf-gen/api"),
    val kuhrSarApiURL: String = getEnvVar("KUHR_SAR_API_URL", "http://kuhr-sar-api")
)

fun getEnvVar(name: String, default: String? = null): String =
        System.getenv(name) ?: default ?: throw RuntimeException("Missing variable $name")
