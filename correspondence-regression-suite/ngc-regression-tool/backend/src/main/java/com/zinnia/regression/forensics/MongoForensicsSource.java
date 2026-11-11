package com.zinnia.regression.forensics;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.zinnia.regression.connector.mongo.MongoLimits;
import com.zinnia.regression.forensics.ForensicsModel.Criteria;
import com.zinnia.regression.forensics.ForensicsModel.EventSummary;
import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.forensics.ForensicsModel.RejectedCount;
import com.zinnia.regression.forensics.ForensicsModel.Rejection;
import com.zinnia.regression.forensics.ForensicsModel.SearchResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Read-only queries for event search and history. Allow-listed projections only: message bodies are never fetched. */
@Component
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoForensicsSource implements ForensicsSource {

    static final int REJECTED_LIMIT = 20;
    static final Map<String, List<String>> HISTORY_FIELDS = new LinkedHashMap<>();

    static {
        HISTORY_FIELDS.put("incoming_events", List.of("uniqueId", "correlationId", "carrier", "eventType", "policyNumber",
                "planCode", "source", "status", "errorCategory", "errorMessage", "retryAttemptInProgress", "createdAt",
                "updatedAt", "targetRoleAndEventStatus.targetRole", "targetRoleAndEventStatus.targetEvent",
                "targetRoleAndEventStatus.targetEventStatus", "targetRoleAndEventStatus.deliveryType",
                "targetRoleAndEventStatus.errorMessage"));
        HISTORY_FIELDS.put("target_role_and_event_status_audit", List.of("status", "targetPartyRole", "originalUniqueId", "uniqueId", "createdAt"));
        HISTORY_FIELDS.put("event_journey", List.of("eventType", "publishStatus", "targetRole", "documentType", "originalUniqueId", "uniqueId", "createdAt"));
        HISTORY_FIELDS.put("cds_events", List.of("targetRole", "originalUniqueId", "uniqueId", "createdAt"));
        HISTORY_FIELDS.put("internal_events", List.of("eventType", "status", "targetRole", "errorMessage", "uniqueId", "createdAt", "updatedAt"));
        HISTORY_FIELDS.put("technical_failures", List.of("workerName", "errorMessage", "eventType", "uniqueId", "createdAt"));
        HISTORY_FIELDS.put("technical_failure_retries", List.of("workerName", "status", "attemptCount", "uniqueId", "createdAt", "lastAttemptAt", "nextEligibleAt"));
        HISTORY_FIELDS.put("dead_letter_events", List.of("uniqueId", "originalUniqueId", "status", "failureExceptionClass", "failureReason", "attemptCount", "createdAt", "lastAttemptAt"));
        HISTORY_FIELDS.put("timed_corro", List.of("eventType", "status", "followupDateTime", "originalUniqueId", "uniqueId", "createdAt"));
        HISTORY_FIELDS.put("incoming_event_execution_sequence", List.of("uniqueId", "createdAt", "updatedAt",
                "incomingEventExecutionSequence.ruleName", "incomingEventExecutionSequence.apiEndpoint", "incomingEventExecutionSequence.status"));
        HISTORY_FIELDS.put("incoming_events_xml_validation_result", List.of("uniqueId", "createdAt",
                "rule_execution_fail_results.ruleId", "rule_execution_fail_results.reason",
                "rule_execution_error_results.ruleId", "rule_execution_error_results.reason"));
    }

    private final MongoDatabase db;
    private final HistoryBuilder builder;
    private final MongoLimits limits;

    public MongoForensicsSource(MongoClient client, @Value("${ngc.mongo.database}") String database, HistoryBuilder builder,
                                MongoLimits limits) {
        this.db = client.getDatabase(database);
        this.builder = builder;
        this.limits = limits;
    }

    @Override
    public SearchResult search(Criteria c) {
        List<Bson> filters = new ArrayList<>();
        add(filters, "correlationId", c.correlationId());
        add(filters, "uniqueId", c.uniqueId());
        add(filters, "policyNumber", c.policyNumber());
        add(filters, "carrier", c.carrier());
        add(filters, "eventType", c.eventType());
        Bson outcome = outcomeFilter(c.outcome());
        if (outcome != null) {
            filters.add(outcome);
        }
        if (!c.exactId() && c.from() != null) {
            filters.add(gte("createdAt", Date.from(c.from())));
        }
        if (c.before() != null) {
            filters.add(lt("createdAt", Date.from(c.before())));
        }
        List<EventSummary> found = new ArrayList<>();
        limits.apply(db.getCollection("incoming_events").find(filters.isEmpty() ? new Document() : and(filters))
                        .projection(projection("incoming_events")).sort(Sorts.descending("createdAt")).limit(c.limit() + 1))
                .forEach(d -> found.add(builder.summary(d)));
        boolean more = found.size() > c.limit();
        List<EventSummary> page = more ? found.subList(0, c.limit()) : found;
        Instant next = more ? page.get(page.size() - 1).createdAt() : null;
        List<Rejection> rejected = c.before() == null ? rejected(c) : List.of();
        return new SearchResult(List.copyOf(page), rejected, more, next);
    }

    private List<Rejection> rejected(Criteria c) {
        String o = c.outcome();
        List<Rejection> out = new ArrayList<>();
        if (c.uniqueId() != null || !List.of("any", "failed", "technical", "business").contains(o)) {
            return out;
        }
        List<Bson> base = new ArrayList<>();
        add(base, "correlationId", c.correlationId());
        add(base, "policyNumber", c.policyNumber());
        add(base, "carrier", c.carrier());
        if (!c.exactId() && c.from() != null) {
            base.add(gte("createdAt", Date.from(c.from())));
        }
        if (!o.equals("business")) {
            List<Bson> f = new ArrayList<>(base);
            add(f, "eventType", c.eventType());
            limits.apply(db.getCollection("dead_letter_events").find(f.isEmpty() ? new Document() : and(f))
                            .projection(Projections.fields(Projections.include("correlationId", "carrier", "eventType", "policyNumber",
                                    "status", "failureExceptionClass", "failureReason", "createdAt", "lastAttemptAt"), Projections.excludeId()))
                            .sort(Sorts.descending("createdAt")).limit(REJECTED_LIMIT))
                    .forEach(d -> out.add(builder.deadLetter(d)));
        }
        if (!o.equals("technical")) {
            List<Bson> f = new ArrayList<>(base);
            f.add(eq("eventType", HistoryBuilder.INVALID_JOURNEY));
            add(f, "originalEventType", c.eventType());
            limits.apply(db.getCollection("event_journey").find(and(f))
                            .projection(Projections.fields(Projections.include("correlationId", "carrier", "originalEventType",
                                    "policyNumber", "createdAt"), Projections.excludeId()))
                            .sort(Sorts.descending("createdAt")).limit(REJECTED_LIMIT))
                    .forEach(d -> out.add(builder.invalid(d)));
        }
        out.sort(Comparator.comparing(Rejection::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Override
    public History history(String correlationId, String attempt) {
        Map<String, List<Document>> raw = new LinkedHashMap<>();
        HISTORY_FIELDS.forEach((collection, fields) -> {
            List<Document> docs = new ArrayList<>();
            limits.apply(db.getCollection(collection).find(eq("correlationId", correlationId))
                    .projection(Projections.fields(Projections.include(fields), Projections.excludeId()))
                    .sort(Sorts.descending("createdAt")).limit(HistoryBuilder.ROW_LIMIT))
                    .forEach(docs::add);
            raw.put(collection, docs);
        });
        return builder.isEmpty(raw) ? null : builder.build(correlationId, attempt, raw);
    }

    @Override
    public List<RejectedCount> rejectedCounts(Instant since) {
        List<RejectedCount> out = new ArrayList<>();
        Date from = Date.from(since);
        limits.apply(db.getCollection("dead_letter_events").aggregate(List.of(
                new Document("$match", new Document("createdAt", new Document("$gte", from))),
                new Document("$group", new Document("_id", "$carrier").append("n", new Document("$sum", 1))))))
                .forEach(d -> out.add(new RejectedCount(String.valueOf(d.get("_id")), "DEAD_LETTER", count(d))));
        limits.apply(db.getCollection("event_journey").aggregate(List.of(
                new Document("$match", new Document("eventType", HistoryBuilder.INVALID_JOURNEY).append("createdAt", new Document("$gte", from))),
                new Document("$group", new Document("_id", "$carrier").append("n", new Document("$sum", 1))))))
                .forEach(d -> out.add(new RejectedCount(String.valueOf(d.get("_id")), "INVALID", count(d))));
        return out;
    }

    static Bson outcomeFilter(String outcome) {
        return switch (outcome) {
            case "failed" -> eq("status", "FAILED");
            case "technical" -> and(eq("status", "FAILED"), in("errorCategory", Outcomes.TECHNICAL_CATEGORIES));
            case "business" -> and(eq("status", "FAILED"), in("errorCategory", Outcomes.BUSINESS_CATEGORIES));
            case "stuck" -> in("status", Outcomes.STUCK_STATUSES);
            case "ok" -> in("status", Outcomes.OK_STATUSES);
            default -> null;
        };
    }

    private static Bson projection(String collection) {
        return Projections.fields(Projections.include(HISTORY_FIELDS.get(collection)), Projections.excludeId());
    }

    private static void add(List<Bson> filters, String field, String value) {
        if (value != null) {
            filters.add(eq(field, value));
        }
    }

    private static long count(Document d) {
        return d.get("n") instanceof Number n ? n.longValue() : 0;
    }
}
