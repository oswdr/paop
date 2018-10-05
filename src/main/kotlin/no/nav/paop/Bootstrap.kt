package no.nav.paop

import io.prometheus.client.hotspot.DefaultExports
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import no.altinn.services.serviceengine.correspondence._2009._10.ICorrespondenceAgencyExternalBasic
import no.nav.altinnkanal.avro.ExternalAttachment
import no.nav.emottak.schemas.PartnerResource
import no.nav.paop.client.PdfClient
import no.nav.paop.ws.configureBasicAuthFor
import no.nav.paop.ws.configureSTSFor
import no.nav.tjeneste.virksomhet.dokumentproduksjon.v3.DokumentproduksjonV3
import no.nav.tjeneste.virksomhet.organisasjon.v4.binding.OrganisasjonV4
import no.nav.virksomhet.tjenester.arkiv.journalbehandling.v1.binding.Journalbehandling
import no.nhn.adresseregisteret.ICommunicationPartyService
import no.nhn.schemas.reg.flr.IFlrReadOperations
import org.apache.cxf.ext.logging.LoggingFeature
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean
import org.apache.cxf.ws.addressing.WSAddressingFeature
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.wss4j.common.ext.WSPasswordCallback
import org.apache.wss4j.dom.WSConstants
import org.apache.wss4j.dom.handler.WSHandlerConstants
import java.time.Duration
import javax.jms.MessageProducer
import javax.jms.Session
import javax.security.auth.callback.CallbackHandler

fun main(args: Array<String>) = runBlocking {
    DefaultExports.initialize()
    val env = Environment()
    createHttpServer(applicationVersion = env.appVersion)

    val consumerProperties = readConsumerConfig(env)
    val consumer = KafkaConsumer<String, ExternalAttachment>(consumerProperties)
    consumer.subscribe(listOf(env.kafkaTopicOppfolginsplan))

    connectionFactory(env).createConnection(env.mqUsername, env.mqPassword).use {
        connection ->
        connection.start()

        val session = connection.createSession()
        val arenaQueue = session.createQueue(env.arenaIAQueue)
        val receiptQueue = session.createQueue(env.receiptQueueName)

        val interceptorProperties = mapOf(
                WSHandlerConstants.USER to env.srvPaopUsername,
                WSHandlerConstants.ACTION to WSHandlerConstants.USERNAME_TOKEN,
                WSHandlerConstants.PASSWORD_TYPE to WSConstants.PW_TEXT,
                WSHandlerConstants.PW_CALLBACK_REF to CallbackHandler {
                    (it[0] as WSPasswordCallback).password = env.srvPaopPassword
                }
        )

        val fastlegeregisteret = JaxWsProxyFactoryBean().apply {
            address = env.fastlegeregiserHdirURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            serviceClass = IFlrReadOperations::class.java
        }.create() as IFlrReadOperations
        configureSTSFor(fastlegeregisteret, env.srvPaopUsername,
                env.srvPaopPassword, env.securityTokenServiceUrl)

        val organisasjonV4 = JaxWsProxyFactoryBean().apply {
            address = env.organisasjonV4EndpointURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            serviceClass = OrganisasjonV4::class.java
        }.create() as OrganisasjonV4
        configureSTSFor(organisasjonV4, env.srvPaopUsername,
                env.srvPaopPassword, env.securityTokenServiceUrl)

        val journalbehandling = JaxWsProxyFactoryBean().apply {
            address = env.journalbehandlingEndpointURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            outInterceptors.add(WSS4JOutInterceptor(interceptorProperties))
            serviceClass = Journalbehandling::class.java
        }.create() as Journalbehandling

        val dokumentProduksjonV3 = JaxWsProxyFactoryBean().apply {
            address = env.dokumentproduksjonV3EndpointURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            serviceClass = DokumentproduksjonV3::class.java
        }.create() as DokumentproduksjonV3
        configureSTSFor(dokumentProduksjonV3, env.srvPaopUsername,
                env.srvPaopPassword, env.securityTokenServiceUrl)

        val adresseRegisterV1 = JaxWsProxyFactoryBean().apply {
            address = env.adresseregisteretV1EmottakEndpointURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            serviceClass = ICommunicationPartyService::class.java
        }.create() as ICommunicationPartyService
        configureSTSFor(adresseRegisterV1, env.srvPaopUsername,
                env.srvPaopPassword, env.securityTokenServiceUrl)

        val partnerEmottak = JaxWsProxyFactoryBean().apply {
            address = env.partnerEmottakEndpointURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            serviceClass = PartnerResource::class.java
        }.create() as PartnerResource
        configureBasicAuthFor(partnerEmottak, env.srvPaopUsername, env.srvPaopPassword)

        val iCorrespondenceAgencyExternalBasic = JaxWsProxyFactoryBean().apply {
            address = env.behandlealtinnmeldingV1EndpointURL
            features.add(LoggingFeature())
            features.add(WSAddressingFeature())
            serviceClass = ICorrespondenceAgencyExternalBasic::class.java
        }.create() as ICorrespondenceAgencyExternalBasic
        configureSTSFor(iCorrespondenceAgencyExternalBasic, env.srvPaopUsername,
                env.srvPaopPassword, env.securityTokenServiceUrl)

        val arenaProducer = session.createProducer(arenaQueue)
        val receiptProducer = session.createProducer(receiptQueue)

        listen(PdfClient(env.pdfGeneratorURL), journalbehandling, fastlegeregisteret, organisasjonV4,
                dokumentProduksjonV3, adresseRegisterV1, partnerEmottak, iCorrespondenceAgencyExternalBasic, arenaProducer, receiptProducer, session, consumer, env.altinnUserUsername, env.altinnUserPassword).join()
    }
}

fun listen(
    pdfClient: PdfClient,
    journalbehandling: Journalbehandling,
    fastlegeregisteret: IFlrReadOperations,
    organisasjonV4: OrganisasjonV4,
    dokumentProduksjonV3: DokumentproduksjonV3,
    adresseRegisterV1: ICommunicationPartyService,
    partnerEmottak: PartnerResource,
    iCorrespondenceAgencyExternalBasic: ICorrespondenceAgencyExternalBasic,
    arenaProducer: MessageProducer,
    receiptProducer: MessageProducer,
    session: Session,
    consumer: KafkaConsumer<String, ExternalAttachment>,
    altinnUserUsername: String,
    altinnUserPassword: String
) = launch {
    while (true) {
        consumer.poll(Duration.ofMillis(0)).forEach {
            log.info("Recived a kafka message")

            handleOppfoelgingsplan(it, pdfClient, journalbehandling, fastlegeregisteret, organisasjonV4,
                    dokumentProduksjonV3, adresseRegisterV1, partnerEmottak, iCorrespondenceAgencyExternalBasic,
                    arenaProducer, receiptProducer, session, altinnUserUsername, altinnUserPassword)
        }
        delay(100)
    }
}