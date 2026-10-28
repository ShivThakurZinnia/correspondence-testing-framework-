package com.zinnia.regression.health;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.zinnia.regression.collector.MongoLimits;
import com.zinnia.regression.health.DatabaseReport.Access;
import com.zinnia.regression.health.DatabaseReport.CollectionIndexes;
import com.zinnia.regression.health.DatabaseReport.MissingIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Read-only checks on the NGC database: what the Mongo user may do and which indexes our queries rely on. */
@Component
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(MongoDiagnostics.class);

    record Want(List<String> keys, String reason) {
    }

    static final Set<String> WRITE_ACTIONS = Set.of("insert", "update", "remove", "createCollection", "dropCollection",
            "createIndex", "dropIndex", "dropDatabase", "renameCollectionSameDB", "collMod", "convertToCapped");

    static final Map<String, List<Want>> WANTED = new LinkedHashMap<>();

    static {
        WANTED.put("incoming_events", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId"),
                new Want(List.of("uniqueId"), "joining history rows to one event"),
                new Want(List.of("policyNumber"), "search by policy number"),
                new Want(List.of("createdAt"), "time-window health and search queries")));
        WANTED.put("cds_events", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId"),
                new Want(List.of("originalUniqueId"), "joining to the incoming event"),
                new Want(List.of("createdAt"), "CDS funnel time window")));
        WANTED.put("event_journey", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId"),
                new Want(List.of("originalUniqueId"), "joining to the incoming event")));
        WANTED.put("internal_events", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId"),
                new Want(List.of("createdAt"), "callback funnel time window")));
        WANTED.put("target_role_and_event_status_audit", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId"),
                new Want(List.of("originalUniqueId"), "status timeline of one event")));
        WANTED.put("incoming_event_execution_sequence", List.of(
                new Want(List.of("uniqueId"), "execution steps of one event"),
                new Want(List.of("correlationId"), "event lookup by correlationId")));
        WANTED.put("technical_failures", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId"),
                new Want(List.of("uniqueId"), "failures of one event"),
                new Want(List.of("createdAt"), "top failing workers time window")));
        WANTED.put("technical_failure_retries", List.of(
                new Want(List.of("uniqueId"), "retry state of one event")));
        WANTED.put("dead_letter_events", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId")));
        WANTED.put("timed_corro", List.of(
                new Want(List.of("correlationId"), "event lookup by correlationId")));
    }

    private final MongoClient client;
    private final MongoDatabase db;
    private final String database;
    private final MongoLimits limits;

    public MongoDiagnostics(MongoClient client, @Value("${ngc.mongo.database}") String database, MongoLimits limits) {
        this.client = client;
        this.db = client.getDatabase(database);
        this.database = database;
        this.limits = limits;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfNotReadOnly() {
        Thread.ofVirtual().start(() -> {
            Access access = access();
            if (access.checked() && !access.readOnly()) {
                log.warn("The NGC Mongo user can write ({}); use a read-only user for this tool", access.writeActions());
            }
        });
    }

    public DatabaseReport report() {
        Set<String> existing = new TreeSet<>();
        limits.apply(db.listCollectionNames()).forEach(existing::add);
        List<CollectionIndexes> collections = new ArrayList<>();
        int missingTotal = 0;
        for (Map.Entry<String, List<Want>> entry : WANTED.entrySet()) {
            CollectionIndexes c = inspect(entry.getKey(), entry.getValue(), existing.contains(entry.getKey()));
            missingTotal += c.missing().size();
            collections.add(c);
        }
        return new DatabaseReport(access(), collections, missingTotal);
    }

    private CollectionIndexes inspect(String name, List<Want> wanted, boolean exists) {
        if (!exists) {
            return new CollectionIndexes(name, false, null, List.of(), List.of(), null);
        }
        try {
            var collection = db.getCollection(name);
            List<List<String>> indexes = new ArrayList<>();
            limits.apply(collection.listIndexes()).forEach(i -> indexes.add(keys(i)));
            return new CollectionIndexes(name, true, limits.estimatedCount(collection), indexes, missing(indexes, wanted), null);
        } catch (RuntimeException e) {
            return new CollectionIndexes(name, true, null, List.of(), List.of(),
                    MongoLimits.isTimeout(e) ? "timed out" : e.getClass().getSimpleName());
        }
    }

    Access access() {
        try {
            Document status = client.getDatabase("admin")
                    .runCommand(new Document("connectionStatus", 1).append("showPrivileges", true));
            return writeAccess(status, database);
        } catch (RuntimeException e) {
            return new Access(false, false, List.of(), "Could not check privileges (" + e.getClass().getSimpleName() + ")");
        }
    }

    static List<String> keys(Document index) {
        Document key = index.get("key", Document.class);
        return key == null ? List.of() : new ArrayList<>(key.keySet());
    }

    static List<MissingIndex> missing(List<List<String>> existing, List<Want> wanted) {
        List<MissingIndex> out = new ArrayList<>();
        for (Want w : wanted) {
            boolean covered = existing.stream().anyMatch(e -> e.size() >= w.keys().size()
                    && e.subList(0, w.keys().size()).equals(w.keys()));
            if (!covered) {
                out.add(new MissingIndex(w.keys(), w.reason()));
            }
        }
        return out;
    }

    static Access writeAccess(Document status, String database) {
        Document auth = status.get("authInfo", Document.class);
        List<?> users = auth == null ? null : auth.get("authenticatedUsers", List.class);
        if (users == null || users.isEmpty()) {
            return new Access(false, false, List.of(), "The connection is not authenticated, so privileges are unknown");
        }
        Set<String> writes = new TreeSet<>();
        List<?> privileges = auth.get("authenticatedUserPrivileges", List.class);
        if (privileges != null) {
            for (Object p : privileges) {
                Document privilege = (Document) p;
                Document resource = privilege.get("resource", Document.class);
                if (resource == null || !appliesTo(resource, database)) {
                    continue;
                }
                List<?> actions = privilege.get("actions", List.class);
                if (actions != null) {
                    actions.stream().map(String::valueOf).filter(WRITE_ACTIONS::contains).forEach(writes::add);
                }
            }
        }
        return new Access(true, writes.isEmpty(), List.copyOf(writes), null);
    }

    private static boolean appliesTo(Document resource, String database) {
        if (resource.getBoolean("cluster", false) || resource.getBoolean("anyResource", false)) {
            return true;
        }
        String db = resource.getString("db");
        return db != null && (db.isEmpty() || db.equals(database));
    }
}
