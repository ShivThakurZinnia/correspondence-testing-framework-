package com.zinnia.regression.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlanCodeRewriterTest {

    final ObjectMapper mapper = new ObjectMapper();
    final PlanCodeRewriter rewriter = new PlanCodeRewriter(mapper);

    String plan(String json) throws Exception {
        for (var i : mapper.readTree(json).get("identifiers")) {
            if (i.get("identifier").asText().equalsIgnoreCase("PLAN_CODE") || i.get("identifier").asText().equals("planCode")) {
                return i.get("value").asText();
            }
        }
        return null;
    }

    @Test
    void replacesTheExistingPlanCodeIdentifierAndLeavesTheRestAlone() throws Exception {
        String out = rewriter.apply(PayloadTest.PAYLOAD, "GCFIA905");

        assertThat(plan(out)).isEqualTo("GCFIA905");
        assertThat(mapper.readTree(out).get("carrier").asText()).isEqualTo("GILI");
        assertThat(mapper.readTree(out).get("identifiers")).hasSize(2);
        assertThat(out).contains(PayloadTest.ORIGINAL);
    }

    @Test
    void matchesTheIdentifierNameIgnoringCase() throws Exception {
        String json = "{\"identifiers\":[{\"identifier\":\"planCode\",\"value\":\"OLD\"},{\"identifier\":\"policyNumber\",\"value\":\"P1\"}]}";

        assertThat(plan(rewriter.apply(json, "NEW"))).isEqualTo("NEW");
    }

    @Test
    void addsTheIdentifierWhenThePayloadHasNone() throws Exception {
        String out = rewriter.apply("{\"identifiers\":[{\"identifier\":\"policyNumber\",\"value\":\"P1\"}]}", "GCFIA901");

        assertThat(plan(out)).isEqualTo("GCFIA901");
        assertThat(mapper.readTree(out).get("identifiers")).hasSize(2);
    }

    @Test
    void refusesPayloadsItCannotChange() {
        assertThatThrownBy(() -> rewriter.apply("{\"type\":\"x\"}", "A")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rewriter.apply("nope", "A")).isInstanceOf(IllegalStateException.class);
    }

    static final String REAL_SHAPE = """
            {"id":"a","source":"zahara","origin":"DAML","specversion":"1","type":"policy.anniversary.completed",
             "correlationid":"11111111-2222-4333-8444-555555555555","carrier":"GILI","datacontenttype":"application/json",
             "data":"{\\"policyNumber\\":\\"P123456789\\",\\"transactionId\\":\\"7\\",\\"planCode\\":\\"GCFIA901\\",\\"status\\":\\"Completed\\"}",
             "identifiers":[{"identifier":"transactionId","value":"7"},{"identifier":"planCode","value":"GCFIA901"},
                            {"identifier":"policyNumber","value":"P123456789"}]}
            """;

    @Test
    void alsoUpdatesThePlanCodeInsideTheDataStringOfRealNgcEvents() throws Exception {
        String out = rewriter.apply(REAL_SHAPE, "GCFIA905");

        var root = mapper.readTree(out);
        assertThat(mapper.readTree(root.get("data").asText()).get("planCode").asText()).isEqualTo("GCFIA905");
        assertThat(mapper.readTree(root.get("data").asText()).get("policyNumber").asText()).isEqualTo("P123456789");
        assertThat(plan(out)).isEqualTo("GCFIA905");
    }

    @Test
    void updatesPlanCodeWhenDataIsAnObjectAndLeavesNonJsonDataAlone() throws Exception {
        String asObject = "{\"data\":{\"planCode\":\"OLD\",\"x\":1},\"identifiers\":[{\"identifier\":\"planCode\",\"value\":\"OLD\"}]}";
        assertThat(mapper.readTree(rewriter.apply(asObject, "NEW")).get("data").get("planCode").asText()).isEqualTo("NEW");

        String xmlData = "{\"data\":\"<xml planCode='OLD'/>\",\"identifiers\":[{\"identifier\":\"planCode\",\"value\":\"OLD\"}]}";
        assertThat(mapper.readTree(rewriter.apply(xmlData, "NEW")).get("data").asText()).isEqualTo("<xml planCode='OLD'/>");

        String noPlanInData = "{\"data\":\"{\\\"a\\\":1}\",\"identifiers\":[]}";
        assertThat(mapper.readTree(rewriter.apply(noPlanInData, "NEW")).get("data").asText()).isEqualTo("{\"a\":1}");
    }

    @Test
    void validatorReadsTheRealIdentifierSpellingsForPlanAndPolicy() {
        var info = new EventPayloadValidator(mapper).validate(REAL_SHAPE);

        assertThat(info.valid()).isTrue();
        assertThat(info.planCode()).isEqualTo("GCFIA901");
        assertThat(info.policyNumber()).isEqualTo("P123456789");
        var upper = new EventPayloadValidator(mapper).validate(PayloadTest.PAYLOAD);
        assertThat(upper.planCode()).as("PLAN_CODE spelling still works").isEqualTo("GCFIA903");
        assertThat(upper.policyNumber()).isNull();
    }
}
