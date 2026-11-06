package com.zinnia.regression.forensics;

import static org.assertj.core.api.Assertions.assertThat;

import com.zinnia.regression.forensics.ForensicsModel.CarrierNode;
import com.zinnia.regression.forensics.ForensicsModel.RejectedCount;
import com.zinnia.regression.forensics.ForensicsModel.Tree;
import com.zinnia.regression.health.HealthSource.EventStatusCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeBuilderTest {

    final TreeBuilder builder = new TreeBuilder();

    @Test
    void groupsByCarrierThenEventTypeAndCountsOutcomes() {
        Tree tree = builder.build(24, List.of(
                new EventStatusCount("GILI", "policy.activation.completed", "SENT_TO_CDS", null, 226),
                new EventStatusCount("GILI", "policy.activation.completed", "FAILED", "TECHNICAL_FAILURE", 4),
                new EventStatusCount("GILI", "policy.anniversary.completed", "FAILED", "BUSINESS_FAILURE", 2),
                new EventStatusCount("GILI", "policy.anniversary.completed", "PROCESSING", null, 3),
                new EventStatusCount("ELIC", "policy.activation.completed", "PROCESSED", null, 5)), List.of());

        assertThat(tree.windowHours()).isEqualTo(24);
        assertThat(tree.carriers()).extracting(CarrierNode::carrier).containsExactly("GILI", "ELIC");
        CarrierNode gili = tree.carriers().get(0);
        assertThat(gili.total()).isEqualTo(235);
        assertThat(gili.failed()).isEqualTo(6);
        var activation = gili.eventTypes().get(0);
        assertThat(activation.eventType()).isEqualTo("policy.activation.completed");
        assertThat(activation.ok()).isEqualTo(226);
        assertThat(activation.technical()).isEqualTo(4);
        var anniversary = gili.eventTypes().get(1);
        assertThat(anniversary.business()).isEqualTo(2);
        assertThat(anniversary.stuck()).isEqualTo(3);
    }

    @Test
    void aMixedFailureCountsAsBothTechnicalAndBusinessButOnceAsFailed() {
        Tree tree = builder.build(24, List.of(
                new EventStatusCount("GILI", "e", "FAILED", "BUSINESS_AND_TECHNICAL_FAILURE", 3)), List.of());

        var node = tree.carriers().get(0).eventTypes().get(0);
        assertThat(node.failed()).isEqualTo(3);
        assertThat(node.technical()).isEqualTo(3);
        assertThat(node.business()).isEqualTo(3);
    }

    @Test
    void rejectedEventsAppearUnderTheirCarrierEvenWithoutPersistedEvents() {
        Tree tree = builder.build(24, List.of(new EventStatusCount("GILI", "e", "PROCESSED", null, 1)),
                List.of(new RejectedCount("GILI", "DEAD_LETTER", 2), new RejectedCount("GILI", "INVALID", 5),
                        new RejectedCount("NEWCO", "INVALID", 1)));

        CarrierNode gili = tree.carriers().stream().filter(c -> c.carrier().equals("GILI")).findFirst().orElseThrow();
        assertThat(gili.rejectedDeadLetters()).isEqualTo(2);
        assertThat(gili.rejectedInvalid()).isEqualTo(5);
        CarrierNode newco = tree.carriers().stream().filter(c -> c.carrier().equals("NEWCO")).findFirst().orElseThrow();
        assertThat(newco.total()).isZero();
        assertThat(newco.eventTypes()).isEmpty();
        assertThat(newco.rejectedInvalid()).isEqualTo(1);
    }
}
