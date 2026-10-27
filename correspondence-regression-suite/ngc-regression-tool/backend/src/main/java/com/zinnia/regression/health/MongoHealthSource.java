package com.zinnia.regression.health;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Aggregation queries over NGC's MongoDB. Read-only; every query is bounded by a time window or a small collection. */
@Component
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoHealthSource implements HealthSource {

    private static final List<String> OPEN = List.of("PROCESSING", "NEW_EVENT");
    private static final int FUNNEL_SAMPLE = 5000;

    private final MongoDatabase db;

    public MongoHealthSource(MongoClient client, @Value("${ngc.mongo.database}") String database) {
        this.db = client.getDatabase(database);
    }

    @Override
    public StuckData stuck(Instant now, Duration threshold, Duration staleAfter) {
        Date stuckBefore = Date.from(now.minus(threshold));
        Date staleBefore = Date.from(now.minus(staleAfter));
        // age reference: when processing started, else last update, else creation (older rows have no processingStartedAt)
        Document ref = new Document("$ifNull", List.of("$processingStartedAt", new Document("$ifNull", List.of("$updatedAt", "$createdAt"))));
        Document row = first(db.getCollection("incoming_events").aggregate(List.of(
                new Document("$match", new Document("status", new Document("$in", OPEN))),
                new Document("$addFields", new Document("ref", ref)),
                new Document("$group", new Document("_id", null)
                        .append("stuck", cond(new Document("$and", List.of(
                                new Document("$lt", List.of("$ref", stuckBefore)), new Document("$gte", List.of("$ref", staleBefore))))))
                        .append("stale", cond(new Document("$lt", List.of("$ref", staleBefore))))
                        .append("oldestStuck", new Document("$min", new Document("$cond", java.util.Arrays.asList(
                                new Document("$and", List.of(new Document("$lt", List.of("$ref", stuckBefore)),
                                        new Document("$gte", List.of("$ref", staleBefore)))), "$ref", null))))))));
        long internal = db.getCollection("internal_events").countDocuments(new Document("status", "PROCESSING")
                .append("processingStartedAt", new Document("$lt", stuckBefore).append("$gte", staleBefore)));

        // followupDateTime is stored as an ISO-8601 UTC string, which sorts correctly as text
        String nowIso = iso(now);
        String sevenDaysAgo = iso(now.minus(Duration.ofDays(7)));
        long recent = db.getCollection("timed_corro").countDocuments(new Document("status", "UNPROCESSED")
                .append("followupDateTime", new Document("$lt", nowIso).append("$gte", sevenDaysAgo)));
        long older = db.getCollection("timed_corro").countDocuments(new Document("status", "UNPROCESSED")
                .append("followupDateTime", new Document("$lt", sevenDaysAgo)));
        return new StuckData(row == null ? 0 : num(row, "stuck"), row == null ? null : instant(row.get("oldestStuck")),
                row == null ? 0 : num(row, "stale"), internal, recent, older);
    }

    @Override
    public List<EventStatusCount> statusCounts(Instant since) {
        List<EventStatusCount> out = new ArrayList<>();
        db.getCollection("incoming_events").aggregate(List.of(
                new Document("$match", new Document("createdAt", new Document("$gte", Date.from(since)))),
                new Document("$group", new Document("_id", new Document("c", "$carrier").append("e", "$eventType")
                        .append("s", "$status").append("x", "$errorCategory")).append("n", new Document("$sum", 1)))))
                .forEach(d -> {
                    Document id = (Document) d.get("_id");
                    out.add(new EventStatusCount(str(id, "c"), str(id, "e"), str(id, "s"),
                            id.get("x") == null ? null : id.get("x").toString(), num(d, "n")));
                });
        return out;
    }

    @Override
    public List<NameCount> topFailureWorkers(Instant since, int limit) {
        List<NameCount> out = new ArrayList<>();
        db.getCollection("technical_failures").aggregate(List.of(
                new Document("$match", new Document("createdAt", new Document("$gte", Date.from(since)))),
                new Document("$group", new Document("_id", new Document("$ifNull", List.of("$workerName", "$eventType")))
                        .append("n", new Document("$sum", 1))),
                new Document("$sort", new Document("n", -1)), new Document("$limit", limit)))
                .forEach(d -> out.add(new NameCount(String.valueOf(d.get("_id")), num(d, "n"))));
        return out;
    }

    @Override
    public Map<String, Long> retryStatuses() {
        Map<String, Long> out = new LinkedHashMap<>();
        db.getCollection("technical_failure_retries").aggregate(List.of(
                new Document("$group", new Document("_id", "$status").append("n", new Document("$sum", 1)))))
                .forEach(d -> out.put(String.valueOf(d.get("_id")), num(d, "n")));
        return out;
    }

    @Override
    public DeadLetterData deadLetters() {
        Map<String, Long> by = new LinkedHashMap<>();
        db.getCollection("dead_letter_events").aggregate(List.of(
                new Document("$group", new Document("_id", "$status").append("n", new Document("$sum", 1)))))
                .forEach(d -> by.put(String.valueOf(d.get("_id")), num(d, "n")));
        Document oldestOpen = db.getCollection("dead_letter_events")
                .find(new Document("status", new Document("$regex", "^(PENDING_REPLAY|FAILED_REPLAY_ATTEMPT)")))
                .sort(new Document("createdAt", 1)).limit(1).first();
        return new DeadLetterData(by, oldestOpen == null ? null : instant(oldestOpen.get("createdAt")));
    }

    @Override
    public FunnelData funnel(Instant since) {
        Date from = Date.from(since);
        Map<String, Instant> cds = new HashMap<>();
        db.getCollection("cds_events").find(new Document("createdAt", new Document("$gte", from)))
                .projection(new Document("correlationId", 1).append("createdAt", 1)).limit(FUNNEL_SAMPLE)
                .forEach(d -> {
                    Instant t = instant(d.get("createdAt"));
                    cds.merge(str(d, "correlationId"), t, (a, b) -> a.isBefore(b) ? a : b);
                });
        Set<String> started = new HashSet<>();
        Set<String> completed = new HashSet<>();
        Set<String> delivery = new HashSet<>();
        List<Long> seconds = new ArrayList<>();
        db.getCollection("internal_events").find(new Document("createdAt", new Document("$gte", from))
                .append("eventType", new Document("$regex", "^(pdf\\.generation|delivery\\.)")))
                .projection(new Document("correlationId", 1).append("eventType", 1).append("createdAt", 1)).limit(FUNNEL_SAMPLE * 4)
                .forEach(d -> {
                    String c = str(d, "correlationId");
                    if (!cds.containsKey(c)) {
                        return;
                    }
                    String type = str(d, "eventType");
                    if (type.equals("pdf.generation.started") && started.add(c)) {
                        seconds.add(Math.max(0, Duration.between(cds.get(c), instant(d.get("createdAt"))).getSeconds()));
                    } else if (type.equals("pdf.generation.completed")) {
                        completed.add(c);
                    } else if (type.startsWith("delivery.")) {
                        delivery.add(c);
                    }
                });
        return new FunnelData(cds.size(), started.size(), completed.size(), delivery.size(), seconds);
    }

    @Override
    public List<NewEventType> newEventTypes(Instant since, Instant priorFrom) {
        Set<String> before = new HashSet<>();
        db.getCollection("incoming_events").aggregate(List.of(
                new Document("$match", new Document("createdAt", new Document("$gte", Date.from(priorFrom)).append("$lt", Date.from(since)))),
                new Document("$group", new Document("_id", new Document("c", "$carrier").append("e", "$eventType")))))
                .forEach(d -> before.add(str((Document) d.get("_id"), "c") + "|" + str((Document) d.get("_id"), "e")));
        List<NewEventType> out = new ArrayList<>();
        db.getCollection("incoming_events").aggregate(List.of(
                new Document("$match", new Document("createdAt", new Document("$gte", Date.from(since)))),
                new Document("$group", new Document("_id", new Document("c", "$carrier").append("e", "$eventType"))
                        .append("first", new Document("$min", "$createdAt")))))
                .forEach(d -> {
                    Document id = (Document) d.get("_id");
                    if (!before.contains(str(id, "c") + "|" + str(id, "e"))) {
                        out.add(new NewEventType(str(id, "c"), str(id, "e"), instant(d.get("first"))));
                    }
                });
        return out;
    }

    @Override
    public Instant lastIncoming() {
        Document d = db.getCollection("incoming_events").find().projection(new Document("createdAt", 1))
                .sort(new Document("createdAt", -1)).limit(1).first();
        return d == null ? null : instant(d.get("createdAt"));
    }

    private static Document cond(Document test) {
        return new Document("$sum", new Document("$cond", List.of(test, 1, 0)));
    }

    private static Document first(Iterable<Document> docs) {
        for (Document d : docs) {
            return d;
        }
        return null;
    }

    private static long num(Document d, String key) {
        Object v = d.get(key);
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static String str(Document d, String key) {
        Object v = d.get(key);
        return v == null ? "(none)" : v.toString();
    }

    private static Instant instant(Object v) {
        return v instanceof Date d ? d.toInstant() : null;
    }

    private static String iso(Instant i) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(i);
    }
}
