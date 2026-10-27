package com.zinnia.regression.collector;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Queries NGC's MongoDB by correlationId. Never writes. */
@Component
@ConditionalOnExpression("!'${ngc.mongo.uri:}'.isEmpty()")
public class MongoNgcDataSource implements NgcDataSource {

    /** Keeps each $in list and result set a sensible size. */
    static final int CHUNK = 250;

    private static final List<String> COLLECTIONS = List.of("incoming_events", "cds_events", "event_journey",
            "internal_events", "technical_failures", "dead_letter_events", "target_role_and_event_status_audit");

    private final MongoDatabase db;
    private final ObservationMapper mapper;

    public MongoNgcDataSource(MongoClient client, @Value("${ngc.mongo.database}") String database,
                              ObservationMapper mapper) {
        this.db = client.getDatabase(database);
        this.mapper = mapper;
    }

    @Override
    public NgcView collect(String correlationId) {
        return collectBatch(List.of(correlationId)).get(correlationId);
    }

    /** One query per collection per chunk of ids, however many events are in flight. */
    @Override
    public Map<String, NgcView> collectBatch(Collection<String> correlationIds) {
        List<String> ids = new ArrayList<>(correlationIds);
        Map<String, Map<String, List<Document>>> byId = new LinkedHashMap<>();
        ids.forEach(id -> byId.put(id, new LinkedHashMap<>()));
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            for (String collection : COLLECTIONS) {
                db.getCollection(collection).find(in("correlationId", chunk)).forEach(d -> {
                    Map<String, List<Document>> forId = byId.get(String.valueOf(d.get("correlationId")));
                    if (forId != null) {
                        forId.computeIfAbsent(collection, c -> new ArrayList<>()).add(d);
                    }
                });
            }
        }
        Map<String, NgcView> out = new LinkedHashMap<>();
        byId.forEach((id, docs) -> out.put(id, view(id, docs)));
        return out;
    }

    private NgcView view(String correlationId, Map<String, List<Document>> docs) {
        List<Observation> observations = new ArrayList<>();
        List<IncomingEventView> incoming = new ArrayList<>();
        for (Document d : docs.getOrDefault("incoming_events", List.of())) {
            incoming.add(mapper.incomingEvent(d));
            observations.addAll(mapper.fromIncomingEvent(d));
        }
        List<Document> cds = docs.getOrDefault("cds_events", List.of());
        cds.forEach(d -> observations.add(mapper.fromCdsEvent(d)));
        List<NgcView.CdsPayload> payloads = cds.stream().map(mapper::cdsPayload).toList();
        docs.getOrDefault("event_journey", List.of()).forEach(d -> observations.add(mapper.fromJourney(d)));
        docs.getOrDefault("internal_events", List.of()).forEach(d -> observations.add(mapper.fromInternalEvent(d)));
        List<Document> failures = docs.getOrDefault("technical_failures", List.of());
        failures.forEach(d -> observations.add(mapper.fromTechnicalFailure(d)));
        List<Document> deadLetters = docs.getOrDefault("dead_letter_events", List.of());
        deadLetters.forEach(d -> observations.add(mapper.fromDeadLetter(d)));
        docs.getOrDefault("target_role_and_event_status_audit", List.of())
                .forEach(d -> observations.add(mapper.fromTargetAudit(d)));
        observations.sort(Comparator.comparing(Observation::occurredAt));
        return new NgcView(correlationId, incoming, cds.size(), failures.size(), deadLetters.size(), observations,
                payloads);
    }

    @Override
    public boolean incomingEventExists(String correlationId) {
        return db.getCollection("incoming_events").countDocuments(eq("correlationId", correlationId)) > 0;
    }
}
