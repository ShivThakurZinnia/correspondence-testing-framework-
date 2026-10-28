package com.zinnia.regression.health;

import com.zinnia.regression.health.HealthReport.ByEvent;
import com.zinnia.regression.health.HealthReport.DeadLetters;
import com.zinnia.regression.health.HealthReport.Failures;
import com.zinnia.regression.health.HealthReport.Funnel;
import com.zinnia.regression.health.HealthReport.Issue;
import com.zinnia.regression.health.HealthReport.Retries;
import com.zinnia.regression.health.HealthReport.Stuck;
import com.zinnia.regression.health.HealthSource.EventStatusCount;
import com.zinnia.regression.health.HealthSource.FunnelData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Turns raw aggregates into a health report with a level (ok / warn / bad) per area and a plain-English issue list. */
@Service
public class HealthService {

    static final Set<String> FAILED = Set.of("TECHNICAL_FAILURE", "BUSINESS_FAILURE", "FAILED");
    static final Duration STALE_AFTER = Duration.ofDays(7);
    static final long MIN_EVENTS_FOR_RATE = 5;

    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    private static <T> CompletableFuture<T> async(Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, POOL);
    }

    private static <T> T await(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }

    /** The source calls are independent round trips to a remote database, so they run side by side. */
    public HealthReport report(HealthSource source, Instant now, int windowHours, int stuckMinutes) {
        Instant since = now.minus(Duration.ofHours(windowHours));
        List<Issue> issues = new ArrayList<>();

        var stuckF = async(() -> source.stuck(now, Duration.ofMinutes(stuckMinutes), STALE_AFTER));
        var countsF = async(() -> source.statusCounts(since));
        var workersF = async(() -> source.topFailureWorkers(since, 8));
        var retriesF = async(source::retryStatuses);
        var deadF = async(source::deadLetters);
        var funnelF = async(() -> source.funnel(since));
        // baseline for "new": the period before the window, at least two weeks and as long as the window itself
        var newTypesF = async(() -> source.newEventTypes(since, since.minus(Duration.ofDays(Math.max(14, windowHours / 24)))));
        var lastF = async(source::lastIncoming);

        var stuckData = await(stuckF);
        String stuckLevel = stuckData.incomingStuck() >= 10 ? "bad"
                : stuckData.incomingStuck() > 0 || stuckData.internalStuck() > 0 ? "warn" : "ok";
        Long oldest = stuckData.oldestIncomingStuck() == null ? null
                : Duration.between(stuckData.oldestIncomingStuck(), now).toMinutes();
        if (stuckData.incomingStuck() > 0) {
            issues.add(new Issue(stuckLevel, stuckData.incomingStuck() + " incoming event(s) have been PROCESSING for more than "
                    + stuckMinutes + " minutes (oldest " + describe(oldest) + ")"));
        }
        if (stuckData.internalStuck() > 0) {
            issues.add(new Issue("warn", stuckData.internalStuck() + " CDS callback(s) stuck in PROCESSING"));
        }
        if (stuckData.timedOverdueRecent() > 0) {
            issues.add(new Issue("warn", stuckData.timedOverdueRecent() + " reminder(s) overdue in the last 7 days and still unprocessed"));
        }
        Stuck stuck = new Stuck(stuckData.timedOverdueRecent() > 0 && stuckLevel.equals("ok") ? "warn" : stuckLevel, stuckMinutes,
                stuckData.incomingStuck(), oldest, stuckData.incomingStale(), stuckData.internalStuck(),
                stuckData.timedOverdueRecent(), stuckData.timedOverdueOlder());

        List<EventStatusCount> counts = await(countsF);
        Failures failures = failures(counts, issues);

        var retryMap = await(retriesF);
        long successful = retryMap.getOrDefault("RETRY_SUCCESSFUL", 0L);
        long exhausted = retryMap.getOrDefault("MAX_ATTEMPTS_EXCEEDED", 0L);
        long retrying = retryMap.entrySet().stream().filter(e -> e.getKey().startsWith("RETRY_ATTEMPT"))
                .mapToLong(Map.Entry::getValue).sum();
        String retryLevel = exhausted >= 5 ? "bad" : exhausted > 0 ? "warn" : "ok";
        if (exhausted > 0) {
            issues.add(new Issue(retryLevel, exhausted + " event(s) used up all automatic retries and need manual attention"));
        }
        Retries retries = new Retries(retryLevel, successful, exhausted, retrying);

        var dl = await(deadF);
        long pending = dl.byStatus().entrySet().stream()
                .filter(e -> e.getKey().equals("PENDING_REPLAY") || e.getKey().startsWith("FAILED_REPLAY_ATTEMPT"))
                .mapToLong(Map.Entry::getValue).sum();
        Long oldestPending = dl.oldestPending() == null ? null : Duration.between(dl.oldestPending(), now).toMinutes();
        String dlLevel = pending == 0 ? "ok" : oldestPending != null && oldestPending > 360 ? "bad" : "warn";
        if (pending > 0) {
            issues.add(new Issue(dlLevel, pending + " dead-lettered event(s) waiting for replay (oldest " + describe(oldestPending) + ")"));
        }
        DeadLetters deadLetters = new DeadLetters(dlLevel, pending, dl.byStatus().getOrDefault("MAX_ATTEMPTS_EXCEEDED", 0L),
                dl.byStatus().getOrDefault("REPLAY_SUCCESSFUL", 0L), oldestPending);

        Funnel funnel = funnel(await(funnelF), issues);
        var newTypes = await(newTypesF);
        if (!newTypes.isEmpty()) {
            issues.add(new Issue("warn", newTypes.size() + " event type(s) seen for the first time in this window"));
        }

        issues.sort(Comparator.comparing((Issue i) -> rank(i.level())).reversed());
        String level = worst(stuck.level(), failures.level(), retries.level(), deadLetters.level(), funnel.level());
        long eventsInWindow = counts.stream().mapToLong(EventStatusCount::count).sum();
        if (eventsInWindow == 0) {
            issues.add(new Issue("info", "No events arrived in the last " + windowHours + " hours, so rates are not meaningful"));
        }
        return new HealthReport(now, windowHours, level, await(lastF), eventsInWindow, issues, stuck, failures,
                await(workersF), retries, deadLetters, funnel, newTypes);
    }

    private Failures failures(List<EventStatusCount> counts, List<Issue> issues) {
        Map<String, long[]> byKey = new LinkedHashMap<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        long total = 0;
        long failed = 0;
        long technical = 0;
        long business = 0;
        for (EventStatusCount c : counts) {
            String key = c.carrier() + "|" + c.eventType();
            long[] a = byKey.computeIfAbsent(key, k -> new long[6]);
            names.putIfAbsent(key, new String[]{c.carrier(), c.eventType()});
            a[0] += c.count();
            total += c.count();
            if (FAILED.contains(c.status())) {
                a[1] += c.count();
                failed += c.count();
            }
            // parent status FAILED carries the reason in errorCategory; mixed failures count as technical
            boolean tech = c.status().equals("TECHNICAL_FAILURE") || "TECHNICAL_FAILURE".equals(c.errorCategory())
                    || "BUSINESS_AND_TECHNICAL_FAILURE".equals(c.errorCategory());
            boolean biz = !tech && (c.status().equals("BUSINESS_FAILURE") || "BUSINESS_FAILURE".equals(c.errorCategory()));
            if (FAILED.contains(c.status()) && tech) {
                a[2] += c.count();
                technical += c.count();
            }
            if (FAILED.contains(c.status()) && biz) {
                a[3] += c.count();
                business += c.count();
            }
            if (c.status().equals("SENT_TO_CDS")) {
                a[4] += c.count();
            }
            if (c.status().equals("PROCESSED")) {
                a[5] += c.count();
            }
        }
        List<ByEvent> rows = new ArrayList<>();
        byKey.forEach((k, a) -> rows.add(new ByEvent(names.get(k)[0], names.get(k)[1], a[0], a[1], a[2], a[3], a[4], a[5],
                rate(a[1], a[0]))));
        rows.sort(Comparator.comparingLong(ByEvent::failed).reversed().thenComparing(Comparator.comparingLong(ByEvent::total).reversed()));
        Double rate = rate(failed, total);
        String level = total < MIN_EVENTS_FOR_RATE || rate == null ? "ok" : rate >= 0.25 ? "bad" : rate >= 0.10 ? "warn" : "ok";
        if (!level.equals("ok")) {
            issues.add(new Issue(level, Math.round(rate * 100) + "% of events failed (" + failed + " of " + total + ")"));
        }
        for (ByEvent r : rows) {
            if (r.total() >= MIN_EVENTS_FOR_RATE && r.failureRate() != null && r.failureRate() >= 0.25) {
                issues.add(new Issue("warn", r.carrier() + " " + r.eventType() + ": " + Math.round(r.failureRate() * 100)
                        + "% failing (" + r.failed() + " of " + r.total() + ")"));
            }
        }
        return new Failures(level, total, failed, technical, business, rate, rows);
    }

    private Funnel funnel(FunnelData f, List<Issue> issues) {
        Double started = rate(f.pdfStarted(), f.sentToCds());
        Double completed = rate(f.pdfCompleted(), f.sentToCds());
        List<Long> sorted = f.secondsToPdfStarted().stream().sorted().toList();
        Long median = sorted.isEmpty() ? null : sorted.get(sorted.size() / 2);
        Long p90 = sorted.isEmpty() ? null : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1));
        // callbacks do not arrive for every event in every environment, so a low rate is a warning, never an alarm
        String level = f.sentToCds() >= MIN_EVENTS_FOR_RATE && completed != null && completed < 0.5 ? "warn" : "ok";
        if (level.equals("warn")) {
            issues.add(new Issue("warn", "Only " + Math.round(completed * 100) + "% of events sent to CDS got a pdf.generation.completed callback ("
                    + f.pdfCompleted() + " of " + f.sentToCds() + ")"));
        }
        return new Funnel(level, f.sentToCds(), f.pdfStarted(), f.pdfCompleted(), f.delivery(), started, completed, median, p90);
    }

    static Double rate(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 1000.0;
    }

    private static String describe(Long minutes) {
        if (minutes == null) {
            return "unknown age";
        }
        return minutes >= 1440 ? minutes / 1440 + " days" : minutes >= 60 ? minutes / 60 + " h" : minutes + " min";
    }

    private static int rank(String level) {
        return switch (level) {
            case "bad" -> 3;
            case "warn" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    private static String worst(String... levels) {
        String w = "ok";
        for (String l : levels) {
            if (rank(l) > rank(w)) {
                w = l;
            }
        }
        return w;
    }
}
