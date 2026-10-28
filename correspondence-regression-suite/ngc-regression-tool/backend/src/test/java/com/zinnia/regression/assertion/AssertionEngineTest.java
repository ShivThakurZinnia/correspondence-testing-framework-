package com.zinnia.regression.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import com.zinnia.regression.collector.Observation;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssertionEngineTest {

    final AssertionEngine engine = new AssertionEngine();
    final ObjectMapper mapper = new ObjectMapper();

    static final String EXPECTED = """
            {"incomingEvent":{"status":"SENT_TO_CDS"},
             "targets":[{"role":"OWNER","status":"SENT_TO_CDS","deliveryType":"PRINT","documentType":"POLPG",
                         "xmlFileName":"present","tangoJobIdPresent":false}],
             "cds":{"published":true,"count":1},
             "journey":["requestValid","requestSentToCDS"],
             "callbacks":["pdf.generation.started|pdf.generation.completed"],
             "noFailures":["technical_failures","dead_letter_events"]}
            """;

    JsonNode expected(String json) throws Exception {
        return mapper.readTree(json);
    }

    Observation obs(String collection, String type) {
        return new Observation(OffsetDateTime.now(), "MONGO", collection, type, null, null, null, null, null, null,
                collection + type, null);
    }

    NgcView view(String status, String delivery, int cds, int tech, List<Observation> obs, TargetView... targets) {
        return new NgcView("c", List.of(new IncomingEventView("u", status, null, "b", List.of(targets))), cds, tech, 0,
                obs);
    }

    TargetView owner(String delivery, String xml) {
        return new TargetView("OWNER", "SENT_TO_CDS", delivery, "YES", "PRIMARYSERVICINGAGENT", "POLPG", xml, null, null);
    }

    List<Observation> fullChain() {
        return List.of(obs("event_journey", "correspondence.generation.requestValid"),
                obs("event_journey", "correspondence.generation.requestSentToCDS"),
                obs("internal_events", "pdf.generation.started"));
    }

    @Test
    void everythingMatchingPasses() throws Exception {
        var out = engine.evaluate(expected(EXPECTED), view("SENT_TO_CDS", "PRINT", 1, 0, fullChain(), owner("PRINT", "x.XML")));

        assertThat(out).allMatch(AssertionOutcome::passed);
        assertThat(out).extracting(AssertionOutcome::path).contains("incomingEvent.status", "targets[OWNER].deliveryType",
                "targets[OWNER].xmlFileName", "cds.count", "journey.requestValid", "noFailures.technical_failures");
    }

    @Test
    void reportsEachMismatchWithExpectedAndActual() throws Exception {
        var out = engine.evaluate(expected(EXPECTED), view("FAILED", "EMAIL", 2, 1, List.of(), owner("EMAIL", null)));

        assertThat(out).filteredOn(o -> !o.passed()).extracting(AssertionOutcome::path).contains(
                "incomingEvent.status", "targets[OWNER].deliveryType", "targets[OWNER].xmlFileName", "cds.count",
                "journey.requestValid", "journey.requestSentToCDS", "callbacks.pdf.generation.started|pdf.generation.completed",
                "noFailures.technical_failures");
        assertThat(out).filteredOn(o -> o.path().equals("targets[OWNER].deliveryType")).singleElement()
                .satisfies(o -> assertThat(o.message()).isEqualTo("Expected PRINT but was EMAIL"));
    }

    @Test
    void missingAndUnexpectedTargetsFail() throws Exception {
        TargetView joint = new TargetView("JOINTOWNER", "SENT_TO_CDS", "PRINT", null, null, "POLPG", "y", null, null);
        var out = engine.evaluate(expected(EXPECTED), view("SENT_TO_CDS", "PRINT", 1, 0, fullChain(), joint));

        assertThat(out).filteredOn(o -> !o.passed()).extracting(AssertionOutcome::path)
                .contains("targets[OWNER]", "targets[JOINTOWNER]");
    }

    @Test
    void noIncomingEventFailsInsteadOfPassingSilently() throws Exception {
        var out = engine.evaluate(expected(EXPECTED), new NgcView("c", List.of(), 0, 0, 0, List.of()));

        assertThat(out).anyMatch(o -> o.path().equals("incomingEvent.status") && !o.passed()
                && o.actual().equals("no incoming_events record"));
    }

    @Test
    void cdsFieldsAreCheckedOnTheOnlyPayloadWithExactAndContainsMatching() throws Exception {
        var payload = new NgcView.CdsPayload("OWNER", java.util.Map.of("ClientCode", "GILI", "TargetPartyRole", "OWNER",
                "OutputFilePath", "s3://bucket/base/file.xml"));
        NgcView view = new NgcView("c", List.of(), 1, 0, 0, List.of(), List.of(payload));
        JsonNode e = expected("{\"incomingEvent\":{\"status\":\"X\"},\"cds\":{\"fields\":{\"ClientCode\":\"GILI\","
                + "\"OutputFilePath~\":\"/base/\",\"TargetPartyRole\":\"JOINTOWNER\",\"XSDFilePath\":\"x\"}}}");

        var out = engine.evaluate(e, view);

        assertThat(out).filteredOn(o -> o.path().startsWith("cds.fields.")).extracting(AssertionOutcome::path,
                AssertionOutcome::passed).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("cds.fields.ClientCode", true),
                org.assertj.core.groups.Tuple.tuple("cds.fields.OutputFilePath", true),
                org.assertj.core.groups.Tuple.tuple("cds.fields.TargetPartyRole", false),
                org.assertj.core.groups.Tuple.tuple("cds.fields.XSDFilePath", false));
        assertThat(out).filteredOn(o -> o.path().equals("cds.fields.XSDFilePath")).singleElement()
                .satisfies(o -> assertThat(o.message()).isEqualTo("XSDFilePath not sent to CDS"));
    }

    @Test
    void cdsFieldsNeedARoleWhenSeveralPayloadsWereSent() throws Exception {
        var a = new NgcView.CdsPayload("OWNER", java.util.Map.of("ClientCode", "GILI"));
        var b = new NgcView.CdsPayload("JOINTOWNER", java.util.Map.of("ClientCode", "GILI"));
        JsonNode e = expected("{\"incomingEvent\":{\"status\":\"X\"},\"cds\":{\"fields\":{\"ClientCode\":\"GILI\"}}}");
        NgcView view = new NgcView("c", List.of(), 2, 0, 0, List.of(), List.of(a, b));

        assertThat(engine.evaluate(e, view)).anyMatch(o -> o.path().equals("cds.fields") && !o.passed());

        JsonNode withRole = expected("{\"incomingEvent\":{\"status\":\"X\"},\"cds\":{\"role\":\"JOINTOWNER\","
                + "\"fields\":{\"ClientCode\":\"GILI\"}}}");
        assertThat(engine.evaluate(withRole, view)).filteredOn(o -> o.path().equals("cds.fields.ClientCode"))
                .singleElement().satisfies(o -> assertThat(o.passed()).isTrue());
    }

    @Test
    void settledNeedsTerminalStatusAndExpectedCallbacksButNotJourney() throws Exception {
        JsonNode e = expected(EXPECTED);

        assertThat(engine.settled(e, view("PROCESSING", "PRINT", 1, 0, fullChain()))).isFalse();
        assertThat(engine.settled(e, view("SENT_TO_CDS", "PRINT", 1, 0, fullChain().subList(0, 2)))).isFalse();
        assertThat(engine.settled(e, view("SENT_TO_CDS", "PRINT", 1, 0, fullChain().subList(2, 3)))).isTrue();
        assertThat(engine.settled(e, view("SENT_TO_CDS", "PRINT", 1, 0, fullChain()))).isTrue();
        assertThat(engine.settled(e, new NgcView("c", List.of(), 0, 0, 0, List.of()))).isFalse();
    }

    @Test
    void anyFailureJourneyStepFailsTheCase() throws Exception {
        var chain = new java.util.ArrayList<>(fullChain());
        chain.add(obs("event_journey", "correspondence.generation.requestTerminated"));

        var out = engine.evaluate(expected(EXPECTED), view("SENT_TO_CDS", "PRINT", 1, 0, chain, owner("PRINT", "x.XML")));

        assertThat(out).filteredOn(o -> o.path().equals("journey.noFailureSteps")).singleElement()
                .satisfies(o -> assertThat(o.passed()).isFalse());
    }
}
