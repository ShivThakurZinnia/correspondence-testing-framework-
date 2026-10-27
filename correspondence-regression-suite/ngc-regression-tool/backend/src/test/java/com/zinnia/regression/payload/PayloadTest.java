package com.zinnia.regression.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class PayloadTest {

    public static final String ORIGINAL = "11111111-2222-4333-8444-555555555555";

    public static final String PAYLOAD = """
            {"id":"a","source":"zahara","specversion":"V1","type":"policy.activation.completed",
             "correlationid":"%s","carrier":"GILI",
             "identifiers":[{"identifier":"PLAN_CODE","value":"GCFIA903"},
                            {"identifier":"transactionId","value":"%s"}]}
            """.formatted(ORIGINAL, ORIGINAL.toUpperCase());

    final EventPayloadValidator validator = new EventPayloadValidator(new ObjectMapper());
    final CorrelationIdRewriter rewriter = new CorrelationIdRewriter();

    @Test
    void validPayloadExposesCorrelationIdCarrierTypeAndPlan() {
        PayloadInfo info = validator.validate(PAYLOAD);

        assertThat(info.valid()).isTrue();
        assertThat(info.correlationId()).isEqualTo(ORIGINAL);
        assertThat(info.carrier()).isEqualTo("GILI");
        assertThat(info.eventType()).isEqualTo("policy.activation.completed");
        assertThat(info.planCode()).isEqualTo("GCFIA903");
    }

    @Test
    void reportsEveryMissingRequiredField() {
        PayloadInfo info = validator.validate("{\"correlationid\":\"not-a-uuid\"}");

        assertThat(info.errors()).containsExactlyInAnyOrder(
                "correlationid must be a UUID", "source is required", "carrier is required",
                "type is required", "identifiers must be a non-empty array");
    }

    @Test
    void rejectsNonJson() {
        assertThat(validator.validate("nope").errors()).containsExactly("Payload is not valid JSON");
    }

    @Test
    void rewriteReplacesEveryOccurrenceIncludingEchoesInOtherCase() {
        String fresh = rewriter.newCorrelationId();

        String out = rewriter.rewrite(PAYLOAD, ORIGINAL, fresh);

        assertThat(out).doesNotContainIgnoringCase(ORIGINAL).contains(fresh);
        assertThat(out.split(fresh, -1)).hasSize(3);
        assertThat(validator.validate(out).correlationId()).isEqualTo(fresh);
    }

    @Test
    void newIdIsAlwaysAValidDistinctUuid() {
        String a = rewriter.newCorrelationId();
        assertThat(UUID.fromString(a)).isNotNull();
        assertThat(a).isNotEqualTo(rewriter.newCorrelationId());
    }

    @Test
    void rewriteRefusesToReuseTheOriginalId() {
        assertThatThrownBy(() -> rewriter.rewrite(PAYLOAD, ORIGINAL, ORIGINAL.toUpperCase()))
                .isInstanceOf(IllegalStateException.class);
    }
}
