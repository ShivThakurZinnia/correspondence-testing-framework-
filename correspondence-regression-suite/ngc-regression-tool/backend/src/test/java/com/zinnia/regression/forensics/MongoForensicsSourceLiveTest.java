package com.zinnia.regression.forensics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.zinnia.regression.connector.mongo.MongoLimits;
import com.zinnia.regression.forensics.ForensicsModel.Criteria;
import com.zinnia.regression.forensics.ForensicsModel.EventSummary;
import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.forensics.ForensicsModel.SearchResult;
import com.zinnia.regression.health.MongoHealthSource;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Read-only check against the real NGC database; prints counts and structure only. Runs when MONGODB_URI is set. */
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
class MongoForensicsSourceLiveTest {

    @Test
    void treeSearchAndHistoryWorkOnTheRealCollections() {
        try (MongoClient client = MongoClients.create(System.getenv("MONGODB_URI"))) {
            String database = System.getenv("MONGODB_DATABASE");
            var source = new MongoForensicsSource(client, database, new HistoryBuilder(), MongoLimits.DEFAULT);
            var health = new MongoHealthSource(client, database, MongoLimits.DEFAULT);
            Instant since = Instant.now().minus(Duration.ofDays(60));

            var tree = new TreeBuilder().build(1440, health.statusCounts(since), source.rejectedCounts(since));
            tree.carriers().forEach(c -> System.out.println("LIVE tree " + c.carrier() + " total=" + c.total() + " failed=" + c.failed()
                    + " deadLetters=" + c.rejectedDeadLetters() + " invalid=" + c.rejectedInvalid() + " eventTypes=" + c.eventTypes().size()));

            for (String outcome : new String[]{"any", "failed", "technical", "business", "stuck", "ok"}) {
                SearchResult r = source.search(new Criteria(null, null, null, null, null, outcome, since, null, 10));
                System.out.println("LIVE search outcome=" + outcome + " events=" + r.events().size() + " hasMore=" + r.hasMore()
                        + " rejected=" + r.rejected().size());
            }

            SearchResult page = source.search(new Criteria(null, null, null, null, null, "any", since, null, 5));
            assertThat(page.events()).isNotEmpty();
            SearchResult next = source.search(new Criteria(null, null, null, null, null, "any", since, page.nextBefore(), 5));
            System.out.println("LIVE paging first=" + page.events().size() + " hasMore=" + page.hasMore() + " next=" + next.events().size());

            EventSummary e = page.events().get(0);
            SearchResult byPolicy = e.policyNumber() == null ? null
                    : source.search(new Criteria(null, null, e.policyNumber(), null, null, "any", since, null, 10));
            SearchResult byUnique = source.search(new Criteria(null, e.uniqueId(), null, null, null, "any", since, null, 10));
            System.out.println("LIVE exact byPolicy=" + (byPolicy == null ? "n/a" : byPolicy.events().size()) + " byUniqueId=" + byUnique.events().size());

            History h = source.history(e.correlationId(), null);
            assertThat(h).isNotNull();
            System.out.println("LIVE history attempts=" + h.attempts().size() + " timeline=" + h.timeline().size() + " retries=" + h.retries().size() + " selected=" + (h.selectedAttempt() != null) + " attemptCount=" + h.attemptCount()
                    + " stages=" + h.stages().stream().map(s -> s.code() + ":" + s.state()).toList() + " markers=" + h.markers().size());
            h.timeline().stream().map(t -> t.collection()).distinct().forEach(c -> System.out.println("LIVE   collection in timeline: " + c));
            assertThat(source.history("00000000-0000-0000-0000-000000000000", null)).isNull();
        }
    }
}
