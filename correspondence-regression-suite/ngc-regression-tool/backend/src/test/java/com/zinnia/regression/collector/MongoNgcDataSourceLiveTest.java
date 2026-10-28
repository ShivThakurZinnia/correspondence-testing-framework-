package com.zinnia.regression.collector;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Read-only smoke test against a real NGC database. Runs only when MONGODB_URI and MONGODB_DATABASE are set. */
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
class MongoNgcDataSourceLiveTest {

    @Test
    void collectsTheFullChainForARecentGiliPolicyPage() {
        try (MongoClient client = MongoClients.create(System.getenv("MONGODB_URI"))) {
            String database = System.getenv("MONGODB_DATABASE");
            Document recent = client.getDatabase(database).getCollection("incoming_events")
                    .find(and(eq("carrier", "GILI"), eq("eventType", "policy.activation.completed"),
                            eq("status", "SENT_TO_CDS")))
                    .sort(Sorts.descending("createdAt")).first();
            assertThat(recent).as("a recent GILI policy page in the database").isNotNull();
            String correlationId = recent.getString("correlationId");

            MongoNgcDataSource source = new MongoNgcDataSource(client, database, new ObservationMapper(new ObjectMapper()));
            NgcView view = source.collect(correlationId);

            System.out.println("LIVE correlation found=" + source.incomingEventExists(correlationId)
                    + " incoming=" + view.incomingEvents().size() + " cds=" + view.cdsEventCount()
                    + " techFailures=" + view.technicalFailureCount() + " deadLetters=" + view.deadLetterCount());
            view.incomingEvents().forEach(e -> System.out.println("LIVE incoming status=" + e.status() + " targets="
                    + e.targets().stream().map(t -> t.role() + ":" + t.status() + ":" + t.deliveryType() + ":"
                            + t.documentType() + ":xml=" + (t.xmlFileName() != null)).toList()));
            view.observations().forEach(o -> System.out.println("LIVE obs " + o.collection() + " "
                    + o.eventType() + " " + o.status() + " " + o.targetRole() + " job=" + (o.tangoJobId() != null)));

            view.cdsPayloads().forEach(p -> System.out.println("LIVE cds role=" + p.targetRole() + " keys="
                    + p.fields().keySet() + " output=" + p.fields().getOrDefault("OutputFilePath", "-")
                    .replaceAll("[0-9]", "9").replaceAll("^(s3://[^/]+/).*(/[^/]*)$", "$1...$2")
                    + " xsd=" + p.fields().getOrDefault("XSDFilePath", "-").replaceAll("^(s3://[^/]+/).*", "$1...")));
            assertThat(view.cdsPayloads()).isNotEmpty();
            assertThat(view.cdsPayloads().get(0).fields()).containsKeys("OutputFilePath", "XSDFilePath")
                    .doesNotContainKeys("TargetPartyName", "TargetPartyId");
            assertThat(view.cdsPayloads().get(0).fields().get("OutputFilePath")).startsWith("s3://");
            // the batch path must give the same answer for this id and an empty view for an unknown one
            String unknown = "00000000-0000-4000-8000-000000000001";
            var batch = source.collectBatch(java.util.List.of(correlationId, unknown));
            assertThat(batch.keySet()).containsExactly(correlationId, unknown);
            assertThat(batch.get(correlationId).incomingEvents()).hasSameSizeAs(view.incomingEvents());
            assertThat(batch.get(correlationId).cdsEventCount()).isEqualTo(view.cdsEventCount());
            assertThat(batch.get(unknown).incomingEvents()).isEmpty();
            assertThat(batch.get(unknown).observations()).isEmpty();
            assertThat(source.incomingEventExists(correlationId)).isTrue();
            assertThat(view.incomingEvents()).isNotEmpty();
            assertThat(view.cdsEventCount()).isPositive();
            assertThat(source.incomingEventExists("00000000-0000-4000-8000-000000000000")).isFalse();
        }
    }
}
