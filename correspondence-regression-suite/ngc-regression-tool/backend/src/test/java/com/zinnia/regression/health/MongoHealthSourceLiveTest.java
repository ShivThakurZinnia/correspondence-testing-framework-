package com.zinnia.regression.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Read-only smoke test against the real NGC database. Runs only when MONGODB_URI and MONGODB_DATABASE are set. */
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
class MongoHealthSourceLiveTest {

    @Test
    void buildsAFullReportFromTheRealCollections() {
        try (MongoClient client = MongoClients.create(System.getenv("MONGODB_URI"))) {
            var source = new MongoHealthSource(client, System.getenv("MONGODB_DATABASE"));

            for (int hours : new int[]{24, 168, 24 * 60}) {
                HealthReport r = new HealthService().report(source, Instant.now(), hours, 30);
                System.out.println("LIVE window=" + hours + "h level=" + r.level() + " events=" + r.eventsInWindow()
                        + " failed=" + r.failures().failed() + " rate=" + r.failures().failureRate()
                        + " lastEvent=" + r.lastEventAt()
                        + " | stuck=" + r.stuck().incomingStuck() + " stale=" + r.stuck().incomingStale()
                        + " internalStuck=" + r.stuck().internalStuck() + " timedRecent=" + r.stuck().timedOverdueRecent()
                        + " timedOlder=" + r.stuck().timedOverdueOlder()
                        + " | retries ok/exh/retrying=" + r.retries().successful() + "/" + r.retries().exhausted() + "/" + r.retries().retrying()
                        + " | deadPending=" + r.deadLetters().pending() + " oldestMin=" + r.deadLetters().oldestPendingMinutes()
                        + " | cds=" + r.funnel().sentToCds() + " started=" + r.funnel().pdfStarted() + " completed=" + r.funnel().pdfCompleted()
                        + " median=" + r.funnel().medianSecondsToPdfStarted()
                        + " | newTypes=" + r.newEventTypes().size() + " workers=" + r.workers());
                r.issues().forEach(i -> System.out.println("LIVE   issue [" + i.level() + "] " + i.text()));
                r.failures().byEvent().stream().limit(4).forEach(e -> System.out.println("LIVE   event " + e.carrier() + " "
                        + e.eventType() + " total=" + e.total() + " failed=" + e.failed() + " rate=" + e.failureRate()));
                assertThat(r.level()).isIn("ok", "warn", "bad");
            }
            assertThat(source.lastIncoming()).isNotNull();
        }
    }
}
