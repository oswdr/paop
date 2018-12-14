package no.nav.paop.routes

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.nav.altinnkanal.avro.ExternalAttachment
import no.nav.emottak.schemas.HentPartnerIDViaOrgnummerRequest
import no.nav.emottak.schemas.PartnerResource
import no.nav.model.dataBatch.DataBatch
import no.nav.model.oppfolgingsplan2016.Oppfoelgingsplan4UtfyllendeInfoM
import no.nav.paop.Environment
import no.nav.paop.client.createDialogmelding
import no.nav.paop.client.createJoarkRequest
import no.nav.paop.client.generatePDF
import no.nav.paop.client.sendDialogmeldingOppfolginsplan
import no.nav.paop.log
import no.nav.paop.mapping.mapFormdataToFagmelding
import no.nav.paop.model.IncomingMetadata
import no.nav.paop.model.IncomingUserInfo
import no.nav.paop.xml.dataBatchUnmarshaller
import no.nav.paop.xml.extractGPFirstName
import no.nav.paop.xml.extractGPFnr
import no.nav.paop.xml.extractGPHprNumber
import no.nav.paop.xml.extractGPLastName
import no.nav.paop.xml.extractGPMiddleName
import no.nav.tjeneste.virksomhet.organisasjon.v4.binding.OrganisasjonV4
import no.nav.tjeneste.virksomhet.organisasjon.v4.meldinger.ValiderOrganisasjonRequest
import no.nav.tjeneste.virksomhet.organisasjonenhet.v2.binding.OrganisasjonEnhetV2
import no.nav.tjeneste.virksomhet.organisasjonenhet.v2.informasjon.Geografi
import no.nav.tjeneste.virksomhet.organisasjonenhet.v2.meldinger.FinnNAVKontorRequest
import no.nav.tjeneste.virksomhet.person.v3.binding.PersonV3
import no.nav.tjeneste.virksomhet.person.v3.informasjon.NorskIdent
import no.nav.tjeneste.virksomhet.person.v3.informasjon.PersonIdent
import no.nav.tjeneste.virksomhet.person.v3.informasjon.Personidenter
import no.nav.tjeneste.virksomhet.person.v3.meldinger.HentGeografiskTilknytningRequest
import no.nav.virksomhet.tjenester.arkiv.journalbehandling.v1.binding.Journalbehandling
import no.nhn.adresseregisteret.ICommunicationPartyService
import no.nhn.schemas.reg.flr.IFlrReadOperations
import no.nhn.schemas.reg.flr.IFlrReadOperationsGetPatientGPDetailsGenericFaultFaultFaultMessage
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.io.StringReader
import javax.jms.MessageProducer
import javax.jms.Session

val xmlMapper: ObjectMapper = XmlMapper(JacksonXmlModule().apply {
    setDefaultUseWrapper(false)
}).registerModule(JaxbAnnotationModule())
        .registerKotlinModule()
        .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)

fun handleAltinnFollowupPlan(
    env: Environment,
    record: ConsumerRecord<String, ExternalAttachment>,
    pdfClient: HttpClient,
    journalbehandling: Journalbehandling,
    fastlegeregisteret: IFlrReadOperations,
    organisasjonV4: OrganisasjonV4,
    adresseRegisterV1: ICommunicationPartyService,
    partnerEmottak: PartnerResource,
    receiptProducer: MessageProducer,
    session: Session,
    personV3: PersonV3,
    organisasjonEnhetV2: OrganisasjonEnhetV2
) {
    val dataBatch = dataBatchUnmarshaller.unmarshal(StringReader(record.value().getBatch())) as DataBatch
    val payload = dataBatch.dataUnits.dataUnit.first().formTask.form.first().formData
    val oppfolgingsplan = xmlMapper.readValue<Oppfoelgingsplan4UtfyllendeInfoM>(payload)
    val skjemainnhold = oppfolgingsplan.skjemainnhold

    val incomingMetadata = IncomingMetadata(
            archiveReference = record.value().getArchiveReference(),
            senderOrgName = skjemainnhold.arbeidsgiver.orgnavn,
            senderOrgId = skjemainnhold.arbeidsgiver.orgnr,
            senderSystemName = skjemainnhold.avsenderSystem.systemNavn,
            senderSystemVersion = skjemainnhold.avsenderSystem.systemVersjon,
            userPersonNumber = skjemainnhold.sykmeldtArbeidstaker.fnr
    )

    val logValues = arrayOf(
            keyValue("archiveReference", record.value().getArchiveReference()),
            keyValue("senderOrganisationNumber", incomingMetadata.senderOrgId),
            keyValue("topic", record.topic())
    )
    val logKeys = logValues.joinToString(",", "(", ")") { "{}" }

    log.info("Received a Altinn oppfølgingsplan $logKeys", *logValues)

    val fagmelding =
                runBlocking {
                    pdfClient.generatePDF(env, mapFormdataToFagmelding(skjemainnhold, incomingMetadata))
                }

    val validOrganizationNumber = organisasjonV4.validerOrganisasjon(ValiderOrganisasjonRequest().apply {
            orgnummer = incomingMetadata.senderOrgId
        }).isGyldigOrgnummer
    if (!validOrganizationNumber) {
        log.error("Failed because the incoming organization ${incomingMetadata.senderOrgId} was invalid $logKeys", *logValues)
        throw RuntimeException("Failed because the incoming organization ${incomingMetadata.senderOrgId} was invalid")
    }

    val geografiskTilknytning = personV3.hentGeografiskTilknytning(HentGeografiskTilknytningRequest().withAktoer(PersonIdent().withIdent(
            NorskIdent()
                    .withIdent(incomingMetadata.userPersonNumber)
                    .withType(Personidenter().withValue("FNR"))))).geografiskTilknytning

    val navKontor = organisasjonEnhetV2.finnNAVKontor(FinnNAVKontorRequest().apply {
        this.geografiskTilknytning = Geografi().apply {
            this.value = geografiskTilknytning?.geografiskTilknytning ?: "0"
        }
    }).navKontor

    log.info("Personen sitt nav kontor: ${navKontor.enhetId}")

    val incomingPersonInfo = IncomingUserInfo(
            userFamilyName = skjemainnhold.sykmeldtArbeidstaker?.fornavn,
            userGivenName = skjemainnhold.sykmeldtArbeidstaker?.etternavn,
            userPersonNumber = skjemainnhold.sykmeldtArbeidstaker.fnr
    )

    if (skjemainnhold.mottaksInformasjon.isOppfolgingsplanSendesTiNav) {
        val joarkRequest = createJoarkRequest(incomingMetadata, fagmelding)
        journalbehandling.lagreDokumentOgOpprettJournalpost(joarkRequest)
        // TODO SEND TO KAFKA TOPIC
    }
    if (skjemainnhold.mottaksInformasjon.isOppfolgingsplanSendesTilFastlege) {
        handleDoctorFollowupPlanAltinn(fastlegeregisteret, adresseRegisterV1,
                partnerEmottak, receiptProducer, session, incomingMetadata, incomingPersonInfo, fagmelding, logKeys, logValues)
    }
}

fun handleDoctorFollowupPlanAltinn(
    fastlegeregisteret: IFlrReadOperations,
    adresseRegisterV1: ICommunicationPartyService,
    partnerEmottak: PartnerResource,
    receiptProducer: MessageProducer,
    session: Session,
    incomingMetadata: IncomingMetadata,
    incomingPersonInfo: IncomingUserInfo,
    fagmelding: ByteArray,
    logKeys: String,
    logValues: Array<StructuredArgument>
) {
    try {
            log.info("Calling fastlegeregistert $logKeys", *logValues)
            val patientToGPContractAssociation = fastlegeregisteret.getPatientGPDetails(incomingMetadata.userPersonNumber)
            val gpFirstName = patientToGPContractAssociation.extractGPFirstName()!!
            val gpMiddleName = patientToGPContractAssociation.extractGPMiddleName()
            val gpLastName = patientToGPContractAssociation.extractGPLastName()!!
            val gpFnr = patientToGPContractAssociation.extractGPFnr()
            val gpHprNumber = patientToGPContractAssociation.extractGPHprNumber()

            val gpHerIdFlr = patientToGPContractAssociation.gpHerId

            log.info("Calling adresseregisteret $logKeys", *logValues)
            val getCommunicationPartyDetailsResponse = adresseRegisterV1.getOrganizationPersonDetails(gpHerIdFlr)

            // Should only return one org
            val herIDAdresseregister = getCommunicationPartyDetailsResponse.organizations.organization.first().herId
            val gpOfficeOrganizationNumber = getCommunicationPartyDetailsResponse.organizations.organization.first().organizationNumber.toString()
            val gpOfficeOrganizationName = getCommunicationPartyDetailsResponse.organizations.organization.first().name

            log.info("Calling emottak $logKeys", *logValues)
            val partner = partnerEmottak.hentPartnerIDViaOrgnummer(HentPartnerIDViaOrgnummerRequest().apply {
                orgnr = gpOfficeOrganizationNumber
            }).partnerInformasjon

            val canReceiveDialogMessage = partner.firstOrNull {
                it.heRid.toInt() == herIDAdresseregister
            }
            if (canReceiveDialogMessage != null) {
                val fellesformat = createDialogmelding(incomingMetadata, incomingPersonInfo, gpOfficeOrganizationName,
                        gpOfficeOrganizationNumber, herIDAdresseregister, fagmelding,
                        canReceiveDialogMessage, gpFirstName, gpMiddleName, gpLastName, gpHerIdFlr, gpFnr, gpHprNumber)

                sendDialogmeldingOppfolginsplan(receiptProducer, session, fellesformat)
                log.info("Dialogmessage sendt to GP $logKeys", *logValues)
            } else {
                // TODO TMP
                log.info("Could not send Dialogmessage to GP $logKeys", *logValues)
            }
        } catch (e: IFlrReadOperationsGetPatientGPDetailsGenericFaultFaultFaultMessage) {
                log.error("Oppfølginsplan kunne ikkje sendes, feil fra fastelegeregisteret $logKeys", *logValues, e)
        } catch (e: Exception) {
                log.error("Oppfølginsplan kunne ikkje sendes $logKeys", *logValues, e)
        }
}
