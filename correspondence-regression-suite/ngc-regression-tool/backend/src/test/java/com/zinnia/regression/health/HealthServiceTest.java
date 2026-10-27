package com.zinnia.regression.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.zinnia.regression.health.HealthSource.DeadLetterData;
import com.zinnia.regression.health.HealthSource.EventStatusCount;
import com.zinnia.regression.health.HealthSource.FunnelData;
import com.zinnia.regression.health.HealthSource.NameCount;
import com.zinnia.regression.health.HealthSource.NewEventType;
import com.zinnia.regression.health.HealthSource.StuckData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    final HealthService service = new HealthService();

    static class Fake implements HealthSource {
        StuckData stuck = new StuckData(0, null, 0, 0, 0, 0);
        List<EventStatusCount> counts = List.of();
        Map<String, Long> retries = Map.of();
        DeadLetterData dead = new DeadLetterData(Map.of(), null);
        FunnelData funnel = new FunnelData(0, 0, 0, 0, List.of());
        List<NewEventType> newTypes = List.of();
        Instant last;

        public StuckData stuck(Instant now, Duration t, Duration s) { return stuck; }
        public List<EventStatusCount> statusCounts(Instant since) { return counts; }
        public List<NameCount> topFailureWorkers(Instant since, int limit) { return List.of(new NameCount("SearchDocumentWorker", 3)); }
        public Map<String, Long> retryStatuses() { return retries; }
        public DeadLetterData deadLetters() { return dead; }
        public FunnelData funnel(Instant since) { return funnel; }
        Instant priorSeen;
        public List<NewEventType> newEventTypes(Instant since, Instant prior) { priorSeen = prior; return newTypes; }
        public Instant lastIncoming() { return last; }
    }

    EventStatusCount c(String event, String status, long n) {
        return new EventStatusCount("GILI", event, status, n);
    }

    HealthReport run(Fake f) {
        return service.report(f, NOW, 24, 30);
    }

    @Test
    void quietHealthyEnvironmentIsOkButSaysThereWasNoTraffic() {
        HealthReport r = run(new Fake());

        assertThat(r.level()).isEqualTo("ok");
        assertThat(r.eventsInWindow()).isZero();
        assertThat(r.failures().failureRate()).isNull();
        assertThat(r.issues()).singleElement().satisfies(i -> {
            assertThat(i.level()).isEqualTo("info");
            assertThat(i.text()).contains("No events arrived in the last 24 hours");
        });
    }

    @Test
    void failureRateIsBandedAndNeedsAMinimumNumberOfEvents() {
        Fake f = new Fake();
        f.counts = List.of(c("policy.activation.completed", "SENT_TO_CDS", 3), c("policy.activation.completed", "TECHNICAL_FAILURE", 2));
        assertThat(run(f).failures().level()).as("5 events, 40% failed: enough to judge").isEqualTo("bad");

        f.counts = List.of(c("e", "SENT_TO_CDS", 3), c("e", "FAILED", 1));
        assertThat(run(f).failures().level()).as("only 4 events: too few to judge").isEqualTo("ok");

        f.counts = List.of(c("e", "SENT_TO_CDS", 17), c("e", "BUSINESS_FAILURE", 3));
        assertThat(run(f).failures().level()).as("15%").isEqualTo("warn");

        f.counts = List.of(c("e", "SENT_TO_CDS", 95), c("e", "TECHNICAL_FAILURE", 5));
        HealthReport ok = run(f);
        assertThat(ok.failures().level()).as("5%").isEqualTo("ok");
        assertThat(ok.failures().technical()).isEqualTo(5);
        assertThat(ok.failures().failureRate()).isEqualTo(0.05);
    }

    @Test
    void failuresAreBrokenDownPerEventWorstFirstAndOnlyBadEventsRaiseIssues() {
        Fake f = new Fake();
        f.counts = List.of(c("good.event", "SENT_TO_CDS", 50), c("bad.event", "SENT_TO_CDS", 5), c("bad.event", "TECHNICAL_FAILURE", 5),
                c("bad.event", "BUSINESS_FAILURE", 1));

        HealthReport r = run(f);

        assertThat(r.failures().byEvent()).extracting(HealthReport.ByEvent::eventType).containsExactly("bad.event", "good.event");
        assertThat(r.failures().byEvent().get(0)).satisfies(e -> {
            assertThat(e.total()).isEqualTo(11);
            assertThat(e.failed()).isEqualTo(6);
            assertThat(e.technical()).isEqualTo(5);
            assertThat(e.business()).isEqualTo(1);
            assertThat(e.sentToCds()).isEqualTo(5);
        });
        assertThat(r.issues()).anyMatch(i -> i.text().contains("bad.event") && i.text().contains("55%"));
        assertThat(r.issues()).noneMatch(i -> i.text().contains("good.event"));
    }

    @Test
    void failedParentsAreSplitIntoTechnicalAndBusinessByTheirErrorCategory() {
        Fake f = new Fake();
        f.counts = List.of(new EventStatusCount("GILI", "e", "FAILED", "TECHNICAL_FAILURE", 4),
                new EventStatusCount("GILI", "e", "FAILED", "BUSINESS_FAILURE", 3),
                new EventStatusCount("GILI", "e", "FAILED", "BUSINESS_AND_TECHNICAL_FAILURE", 2),
                new EventStatusCount("GILI", "e", "FAILED", null, 1),
                new EventStatusCount("GILI", "e", "SENT_TO_CDS", null, 40));

        HealthReport.Failures r = run(f).failures();

        assertThat(r.failed()).isEqualTo(10);
        assertThat(r.technical()).as("technical plus mixed").isEqualTo(6);
        assertThat(r.business()).isEqualTo(3);
        assertThat(r.byEvent().get(0).technical()).isEqualTo(6);
    }

    @Test
    void stuckWorkEscalatesWithCountAndReportsTheOldest() {
        Fake f = new Fake();
        f.stuck = new StuckData(3, NOW.minus(Duration.ofMinutes(95)), 1247, 0, 0, 2300);

        HealthReport warn = run(f);
        assertThat(warn.stuck().level()).isEqualTo("warn");
        assertThat(warn.stuck().oldestIncomingMinutes()).isEqualTo(95);
        assertThat(warn.stuck().incomingStale()).as("old leftovers are shown but do not drive the level").isEqualTo(1247);

        f.stuck = new StuckData(12, NOW.minus(Duration.ofHours(3)), 0, 0, 0, 0);
        assertThat(run(f).level()).isEqualTo("bad");

        f.stuck = new StuckData(0, null, 5000, 0, 0, 2300);
        assertThat(run(f).stuck().level()).as("only ancient leftovers").isEqualTo("ok");

        f.stuck = new StuckData(0, null, 0, 2, 0, 0);
        assertThat(run(f).stuck().level()).as("stuck callbacks").isEqualTo("warn");

        f.stuck = new StuckData(0, null, 0, 0, 4, 0);
        assertThat(run(f).stuck().level()).as("recently overdue reminders").isEqualTo("warn");
    }

    @Test
    void retriesAndDeadLettersAreCountedAndEscalate() {
        Fake f = new Fake();
        f.retries = Map.of("RETRY_SUCCESSFUL", 25L, "MAX_ATTEMPTS_EXCEEDED", 3L, "RETRY_ATTEMPT_29", 3L, "RETRY_ATTEMPT_22", 2L);
        f.dead = new DeadLetterData(Map.of("PENDING_REPLAY", 10L, "REPLAY_SUCCESSFUL", 4L, "FAILED_REPLAY_ATTEMPT_2", 1L), NOW.minus(Duration.ofDays(3)));

        HealthReport r = run(f);

        assertThat(r.retries().successful()).isEqualTo(25);
        assertThat(r.retries().exhausted()).isEqualTo(3);
        assertThat(r.retries().retrying()).isEqualTo(5);
        assertThat(r.retries().level()).isEqualTo("warn");
        assertThat(r.deadLetters().pending()).isEqualTo(11);
        assertThat(r.deadLetters().replayed()).isEqualTo(4);
        assertThat(r.deadLetters().level()).as("older than 6 hours").isEqualTo("bad");
        assertThat(r.deadLetters().oldestPendingMinutes()).isEqualTo(3 * 24 * 60);
        assertThat(r.level()).isEqualTo("bad");
        assertThat(r.issues().get(0).level()).as("worst issue first").isEqualTo("bad");

        f.dead = new DeadLetterData(Map.of("PENDING_REPLAY", 1L), NOW.minus(Duration.ofMinutes(20)));
        assertThat(run(f).deadLetters().level()).isEqualTo("warn");
    }

    @Test
    void funnelRatesAndLatencyPercentilesAreComputedFromTheSample() {
        Fake f = new Fake();
        f.funnel = new FunnelData(10, 8, 6, 3, List.of(10L, 30L, 20L, 600L, 40L, 50L, 60L, 70L));

        HealthSource.FunnelData d = f.funnel;
        HealthReport.Funnel r = run(f).funnel();

        assertThat(d.sentToCds()).isEqualTo(10);
        assertThat(r.pdfStartedRate()).isEqualTo(0.8);
        assertThat(r.pdfCompletedRate()).isEqualTo(0.6);
        assertThat(r.medianSecondsToPdfStarted()).isEqualTo(50);
        assertThat(r.p90SecondsToPdfStarted()).isEqualTo(600);
        assertThat(r.level()).isEqualTo("ok");
    }

    @Test
    void lowCallbackConversionIsOnlyEverAWarning() {
        Fake f = new Fake();
        f.funnel = new FunnelData(20, 2, 1, 0, List.of(5L, 6L));

        HealthReport r = run(f);

        assertThat(r.funnel().level()).isEqualTo("warn");
        assertThat(r.level()).isEqualTo("warn");
        assertThat(r.issues()).anyMatch(i -> i.text().contains("5%") && i.text().contains("pdf.generation.completed"));

        f.funnel = new FunnelData(3, 0, 0, 0, List.of());
        assertThat(run(f).funnel().level()).as("too few events to judge").isEqualTo("ok");
    }

    @Test
    void newEventTypesAreListedAndRaiseAWarning() {
        Fake f = new Fake();
        f.newTypes = List.of(new NewEventType("GILI", "brand.new.event", NOW.minus(Duration.ofHours(2))));
        f.last = NOW.minus(Duration.ofMinutes(5));

        HealthReport r = run(f);

        assertThat(r.newEventTypes()).hasSize(1);
        assertThat(r.issues()).anyMatch(i -> i.level().equals("warn") && i.text().contains("first time"));
        assertThat(r.lastEventAt()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(r.workers()).extracting(NameCount::name).containsExactly("SearchDocumentWorker");
    }

    @Test
    void newEventTypeBaselineIsTwoWeeksOrAsLongAsTheWindow() {
        Fake f = new Fake();

        service.report(f, NOW, 24, 30);
        assertThat(f.priorSeen).isEqualTo(NOW.minus(Duration.ofHours(24)).minus(Duration.ofDays(14)));

        service.report(f, NOW, 24 * 60, 30);
        assertThat(f.priorSeen).isEqualTo(NOW.minus(Duration.ofDays(60)).minus(Duration.ofDays(60)));
    }

    @Test
    void sourceCallsRunSideBySideNotOneAfterAnother() {
        Fake slow = new Fake() {
            private void nap() { try { Thread.sleep(250); } catch (InterruptedException e) { throw new IllegalStateException(e); } }
            public StuckData stuck(Instant now, Duration t, Duration s) { nap(); return super.stuck(now, t, s); }
            public List<EventStatusCount> statusCounts(Instant since) { nap(); return super.statusCounts(since); }
            public List<NameCount> topFailureWorkers(Instant since, int limit) { nap(); return super.topFailureWorkers(since, limit); }
            public Map<String, Long> retryStatuses() { nap(); return super.retryStatuses(); }
            public DeadLetterData deadLetters() { nap(); return super.deadLetters(); }
            public FunnelData funnel(Instant since) { nap(); return super.funnel(since); }
            public List<NewEventType> newEventTypes(Instant since, Instant prior) { nap(); return super.newEventTypes(since, prior); }
            public Instant lastIncoming() { nap(); return super.lastIncoming(); }
        };

        long start = System.nanoTime();
        service.report(slow, NOW, 24, 30);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("8 calls of 250ms each would take 2000ms one after another").isLessThan(1200);
    }

    @Test
    void aFailingSourceCallSurfacesItsOriginalException() {
        Fake broken = new Fake() {
            public Map<String, Long> retryStatuses() { throw new IllegalStateException("mongo down"); }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.report(broken, NOW, 24, 30))
                .isInstanceOf(IllegalStateException.class).hasMessage("mongo down");
    }
}
