package com.zinnia.regression.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class ObservationMapperTest {

    final ObservationMapper mapper = new ObservationMapper(new ObjectMapper());

    @Test
    void callbackIdentifiersAreExtractedAndBodyIsNotStored() {
        Document internal = new Document("_id", "i1").append("eventType", "pdf.generation.started")
                .append("status", "PROCESSED").append("targetRole", "OWNER").append("uniqueId", "internal-uid")
                .append("createdAt", new Date())
                .append("message", """
                        {"data":"{\\"targetPartyName\\":\\"SECRET NAME\\"}","identifiers":[
                          {"identifier":"JobId","value":"22219_1"},
                          {"identifier":"roleTrackingId","value":"rt-1"},
                          {"identifier":"ngcUniqueId","value":"ngc-1"},
                          {"identifier":"originalTargetEvent","value":"policy.activation.completed"},
                          {"identifier":"destination","value":null}]}
                        """);

        Observation o = mapper.fromInternalEvent(internal);

        assertThat(o.tangoJobId()).isEqualTo("22219_1");
        assertThat(o.roleTrackingId()).isEqualTo("rt-1");
        assertThat(o.ngcUniqueId()).isEqualTo("ngc-1");
        assertThat(o.originalTargetEvent()).isEqualTo("policy.activation.completed");
        assertThat(o.collection()).isEqualTo("internal_events");
        assertThat(o.toString()).doesNotContain("SECRET NAME");
    }

    @Test
    void unparseableCallbackMessageStillProducesAnObservation() {
        Observation o = mapper.fromInternalEvent(new Document("_id", "i2").append("eventType", "delivery.completed")
                .append("status", "TECHNICAL_FAILURE").append("message", "not json"));

        assertThat(o.eventType()).isEqualTo("delivery.completed");
        assertThat(o.status()).isEqualTo("TECHNICAL_FAILURE");
        assertThat(o.tangoJobId()).isNull();
    }

    @Test
    void incomingEventYieldsParentAndPerTargetRowsAndView() {
        Document d = new Document("_id", "e1").append("uniqueId", "u1").append("eventType", "policy.activation.completed")
                .append("status", "SENT_TO_CDS").append("s3FileBaseLocation", "base")
                .append("targetRoleAndEventStatus", List.of(new Document("targetRole", "OWNER")
                        .append("targetEvent", "policy.activation.completed").append("targetEventStatus", "SENT_TO_CDS")
                        .append("deliveryType", "PRINT").append("emailNotification", "YES")
                        .append("agentType", "PRIMARYSERVICINGAGENT").append("documentType", "POLPG")
                        .append("xmlFileName", "x.XML")));

        assertThat(mapper.fromIncomingEvent(d)).extracting(Observation::sourceDocumentId)
                .containsExactly("e1", "e1#OWNER");
        NgcView.TargetView t = mapper.incomingEvent(d).targets().get(0);
        assertThat(t.deliveryType()).isEqualTo("PRINT");
        assertThat(t.documentType()).isEqualTo("POLPG");
        assertThat(t.tangoJobId()).isNull();
    }

    @Test
    void cdsPayloadKeepsTheKeyValuesButDropsPartyNameAndId() {
        String data = "<?xml version=\"1.0\"?><ArrayOfXMLOutput><XMLOutput><Key>ClientCode</Key><Value>GILI</Value></XMLOutput>"
                + "<XMLOutput><Key>OutputFilePath</Key><Value>s3://b/base/f.xml</Value></XMLOutput>"
                + "<XMLOutput><Key>TargetPartyRole</Key><Value>OWNER</Value></XMLOutput>"
                + "<XMLOutput><Key>TargetPartyName</Key><Value>SECRET NAME</Value></XMLOutput>"
                + "<XMLOutput><Key>targetPartyId</Key><Value>secret-id</Value></XMLOutput>"
                + "<XMLOutput><Key>documentType</Key><Value>POLPG</Value></XMLOutput>"
                + "<XMLOutput><Key>SubType</Key><Value></Value></XMLOutput></ArrayOfXMLOutput>";
        String message = new ObjectMapper().valueToTree(java.util.Map.of("data", data)).toString();

        NgcView.CdsPayload p = mapper.cdsPayload(new Document("_id", "c").append("message", message));

        assertThat(p.targetRole()).isEqualTo("OWNER");
        assertThat(p.fields()).containsEntry("ClientCode", "GILI").containsEntry("OutputFilePath", "s3://b/base/f.xml")
                .containsEntry("SubType", "").doesNotContainKeys("TargetPartyName", "TargetPartyId", "targetPartyName");
        assertThat(p.fields().get("DOCUMENTTYPE")).as("keys are looked up ignoring case").isEqualTo("POLPG");
    }

    @Test
    void unreadableCdsMessageGivesAnEmptyPayloadNotAnError() {
        assertThat(mapper.cdsPayload(new Document("_id", "c").append("message", "garbage")
                .append("targetRole", "OWNER")).fields()).isEmpty();
    }

    @Test
    void journeyCdsAuditAndFailureRowsMapTheirKeyFields() {
        assertThat(mapper.fromJourney(new Document("_id", "j").append("eventType",
                "correspondence.generation.requestSentToCDS").append("targetRole", "OWNER")).targetRole())
                .isEqualTo("OWNER");
        assertThat(mapper.fromCdsEvent(new Document("_id", "c").append("eventType", "x")).status()).isEqualTo("SENT");
        assertThat(mapper.fromTechnicalFailure(new Document("_id", "t").append("workerName", "SearchDocumentWorker"))
                .detailJson()).contains("SearchDocumentWorker");
        assertThat(mapper.fromDeadLetter(new Document("_id", "d").append("status", "PENDING_REPLAY")).status())
                .isEqualTo("PENDING_REPLAY");
        assertThat(mapper.fromTargetAudit(new Document("_id", "a").append("targetPartyRole", "OWNER")
                .append("status", "PROCESSED")).targetRole()).isEqualTo("OWNER");
    }
}
