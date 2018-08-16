package no.nav.paop

import no.nav.model.dataBatch.DataBatch
import no.nav.model.navOppfPlan.OppfolgingsplanMetadata
import no.nav.model.oppfolgingsplan2012.Oppfoelgingsplan2M as Oppfoelgingsplan2M2012
import no.nav.model.oppfolgingsplan2014.Oppfoelgingsplan2M as Oppfoelgingsplan2M2014
import no.nav.model.oppfolgingsplan2016.Oppfoelgingsplan4UtfyllendeInfoM
import java.io.StringReader
import javax.xml.bind.JAXBContext
import javax.xml.bind.Unmarshaller
import javax.xml.datatype.DatatypeFactory
import javax.xml.transform.stream.StreamSource

val newInstance: DatatypeFactory = DatatypeFactory.newInstance()

val dataBatchJaxBContext: JAXBContext = JAXBContext.newInstance(DataBatch::class.java)
val dataBatchUnmarshaller: Unmarshaller = dataBatchJaxBContext.createUnmarshaller()

val skjemainnholdJaxBContext: JAXBContext = JAXBContext.newInstance(Oppfoelgingsplan4UtfyllendeInfoM::class.java)
val skjemainnholdUnmarshaller: Unmarshaller = skjemainnholdJaxBContext.createUnmarshaller()

val oppfoelgingsplan2014JaxBContext: JAXBContext = JAXBContext.newInstance(Oppfoelgingsplan2M2014::class.java)
val oppfoelgingsplan2014Unmarshaller: Unmarshaller = oppfoelgingsplan2014JaxBContext.createUnmarshaller()

val oppfoelgingsplan2012JaxBContext: JAXBContext = JAXBContext.newInstance(Oppfoelgingsplan2M2012::class.java)
val oppfoelgingsplan2012Unmarshaller: Unmarshaller = oppfoelgingsplan2012JaxBContext.createUnmarshaller()

val oppfolgingsplanMetadataJaxBContext: JAXBContext = JAXBContext.newInstance(OppfolgingsplanMetadata::class.java)
val oppfolgingsplanMetadataUnmarshaller: Unmarshaller = oppfolgingsplanMetadataJaxBContext.createUnmarshaller()

fun extractDataBatch(dataBatchString: String): DataBatch =
        dataBatchUnmarshaller.unmarshal(StringReader(dataBatchString)) as DataBatch

fun extractOppfolginsplan2016(formdataString: String): Oppfoelgingsplan4UtfyllendeInfoM =
        skjemainnholdUnmarshaller.unmarshal(StreamSource(StringReader(formdataString)), Oppfoelgingsplan4UtfyllendeInfoM::class.java).value

fun extractOppfolginsplan2014(formdataString: String): Oppfoelgingsplan2M2014 =
        oppfoelgingsplan2014Unmarshaller.unmarshal(StreamSource(StringReader(formdataString)), Oppfoelgingsplan2M2014::class.java).value

fun extractOppfolginsplan2012(formdataString: String): no.nav.model.oppfolgingsplan2012.Oppfoelgingsplan2M {
    return oppfoelgingsplan2012Unmarshaller.unmarshal(StreamSource(StringReader(formdataString)), no.nav.model.oppfolgingsplan2012.Oppfoelgingsplan2M::class.java).value
}

fun extractNavOppfPlan(formdataString: String): OppfolgingsplanMetadata {
    return oppfolgingsplanMetadataUnmarshaller.unmarshal(StringReader(formdataString)) as OppfolgingsplanMetadata
}
