package no.nav.paop

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.prometheus.client.hotspot.DefaultExports
import kotlinx.coroutines.experimental.runBlocking
import no.nav.model.dataBatch.DataBatch
import no.nav.model.navOppfPlan.OppfolgingsplanMetadata
import no.nav.model.oppfolgingsplan2014.Oppfoelgingsplan2M
import no.nav.model.oppfolgingsplan2016.Oppfoelgingsplan4UtfyllendeInfoM
import no.nav.paop.sts.configureSTSFor
import no.nav.tjeneste.virksomhet.organisasjonenhet.v2.binding.OrganisasjonEnhetV2
import no.nav.virksomhet.tjenester.arkiv.journalbehandling.v1.binding.Journalbehandling
import org.apache.cxf.ext.logging.LoggingFeature
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor
import org.apache.wss4j.common.ext.WSPasswordCallback
import org.apache.wss4j.dom.WSConstants
import org.apache.wss4j.dom.handler.WSHandlerConstants
import org.slf4j.LoggerFactory
import java.io.StringReader
import javax.security.auth.callback.CallbackHandler
import javax.xml.bind.JAXBContext
import javax.xml.bind.Unmarshaller
import javax.xml.transform.stream.StreamSource

val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

private val log = LoggerFactory.getLogger("nav.paop-application")

class PaopApplication

fun main(args: Array<String>) = runBlocking {
    DefaultExports.initialize()
    val fasitProperties = FasitProperties()
    createHttpServer(applicationVersion = fasitProperties.appVersion)

    // TODO read for kafak topic
    // aapen-altinn-oppfolgingsplan-Mottatt
    // all of the diffrent types of oppfolgingsplan comes throw here
    val dataBatch = extractDataBatch(args.first())
    val op2016 = extractOppfolginsplan2016(dataBatch.dataUnits.dataUnit.first().formTask.form.first().formData)

    // calls Old arreg
    val orgnaisasjonEnhet = JaxWsProxyFactoryBean().apply {
        address = fasitProperties.organisasjonEnhetV2EndpointURL
        features.add(LoggingFeature())
        serviceClass = OrganisasjonEnhetV2::class.java
    }.create() as OrganisasjonEnhetV2
    configureSTSFor(orgnaisasjonEnhet, fasitProperties.srvPaopUsername,
            fasitProperties.srvPaopPassword, fasitProperties.securityTokenServiceUrl)

    val interceptorProperties = mapOf(
            WSHandlerConstants.USER to fasitProperties.srvPaopUsername,
            WSHandlerConstants.ACTION to WSHandlerConstants.USERNAME_TOKEN,
            WSHandlerConstants.PASSWORD_TYPE to WSConstants.PW_TEXT,
            WSHandlerConstants.PW_CALLBACK_REF to CallbackHandler {
                (it[0] as WSPasswordCallback).password = fasitProperties.srvPaopPassword
            }
    )

    val journalbehandling = JaxWsProxyFactoryBean().apply {
        address = fasitProperties.journalbehandlingEndpointURL
        features.add(LoggingFeature())
        outInterceptors.add(WSS4JOutInterceptor(interceptorProperties))
        serviceClass = Journalbehandling::class.java
    }.create() as Journalbehandling
}

fun extractDataBatch(dataBatchString: String): DataBatch {
    val dataBatchJaxBContext: JAXBContext = JAXBContext.newInstance(DataBatch::class.java)
    val dataBatchUnmarshaller: Unmarshaller = dataBatchJaxBContext.createUnmarshaller()
    return dataBatchUnmarshaller.unmarshal(StringReader(dataBatchString)) as DataBatch
}

fun extractOppfolginsplan2016(formdataString: String): Oppfoelgingsplan4UtfyllendeInfoM {
    val skjemainnholdJaxBContext: JAXBContext = JAXBContext.newInstance(Oppfoelgingsplan4UtfyllendeInfoM::class.java)
    val skjemainnholdUnmarshaller: Unmarshaller = skjemainnholdJaxBContext.createUnmarshaller()
    return skjemainnholdUnmarshaller.unmarshal(
            StreamSource(StringReader(formdataString)), Oppfoelgingsplan4UtfyllendeInfoM::class.java).value
}

fun extractOppfolginsplan2014(formdataString: String): Oppfoelgingsplan2M {
    val oppfoelgingsplan2MJaxBContext: JAXBContext = JAXBContext.newInstance(Oppfoelgingsplan2M::class.java)
    val oppfoelgingsplan2MUnmarshaller: Unmarshaller = oppfoelgingsplan2MJaxBContext.createUnmarshaller()
    return oppfoelgingsplan2MUnmarshaller.unmarshal(
            StreamSource(StringReader(formdataString)), Oppfoelgingsplan2M::class.java).value
}

fun extractOppfolginsplan2012(formdataString: String): no.nav.model.oppfolgingsplan2012.Oppfoelgingsplan2M {
    val oppfoelgingsplan2MJaxBContext: JAXBContext = JAXBContext.newInstance(no.nav.model.oppfolgingsplan2012.Oppfoelgingsplan2M::class.java)
    val oppfoelgingsplan2MUnmarshaller: Unmarshaller = oppfoelgingsplan2MJaxBContext.createUnmarshaller()
    return oppfoelgingsplan2MUnmarshaller.unmarshal(
            StreamSource(StringReader(formdataString)), no.nav.model.oppfolgingsplan2012.Oppfoelgingsplan2M::class.java).value
}

fun extractNavOppfPlan(formdataString: String): OppfolgingsplanMetadata {
    val oppfolgingsplanMetadataJaxBContext: JAXBContext = JAXBContext.newInstance(OppfolgingsplanMetadata::class.java)
    val oppfolgingsplanMetadataUnmarshaller: Unmarshaller = oppfolgingsplanMetadataJaxBContext.createUnmarshaller()
    return oppfolgingsplanMetadataUnmarshaller.unmarshal(StringReader(formdataString)) as OppfolgingsplanMetadata
}