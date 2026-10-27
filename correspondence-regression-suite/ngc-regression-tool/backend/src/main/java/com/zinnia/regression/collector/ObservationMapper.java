package com.zinnia.regression.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.stereotype.Component;

/** Turns NGC Mongo documents into observations and views. Pure: no database access. */
@Component
public class ObservationMapper {

    private final ObjectMapper json;

    public ObservationMapper(ObjectMapper json) {
        this.json = json;
    }

    public IncomingEventView incomingEvent(Document d) {
        List<TargetView> targets = new ArrayList<>();
        for (Document t : list(d, "targetRoleAndEventStatus")) {
            targets.add(new TargetView(str(t, "targetRole"), str(t, "targetEventStatus"), str(t, "deliveryType"),
                    str(t, "emailNotification"), str(t, "agentType"), str(t, "documentType"),
                    str(t, "xmlFileName"), str(t, "tangoJobId"), str(t, "roleTrackingId")));
        }
        return new IncomingEventView(str(d, "uniqueId"), str(d, "status"), str(d, "errorCategory"),
                str(d, "s3FileBaseLocation"), targets);
    }

    /** Parent row plus one row per target, so the timeline shows each party's status. */
    public List<Observation> fromIncomingEvent(Document d) {
        List<Observation> out = new ArrayList<>();
        String id = id(d);
        OffsetDateTime at = time(d, "updatedAt", "createdAt");
        out.add(new Observation(at, "MONGO", "incoming_events", str(d, "eventType"), str(d, "status"), null,
                str(d, "uniqueId"), null, null, null, id, null));
        for (Document t : list(d, "targetRoleAndEventStatus")) {
            out.add(new Observation(at, "MONGO", "incoming_events", str(t, "targetEvent"),
                    str(t, "targetEventStatus"), str(t, "targetRole"), str(d, "uniqueId"), str(t, "tangoJobId"),
                    str(t, "roleTrackingId"), null, id + "#" + str(t, "targetRole"), null));
        }
        return out;
    }

    public Observation fromCdsEvent(Document d) {
        return new Observation(time(d, "createdAt"), "MONGO", "cds_events", str(d, "eventType"), "SENT",
                str(d, "targetRole"), str(d, "uniqueId"), null, null, null, id(d), null);
    }

    private static final java.util.Set<String> PERSONAL_KEYS = java.util.Set.of("targetpartyname", "targetpartyid");

    /** The CDS message carries XML key/values (ArrayOfXMLOutput); returns them minus personal identifiers. */
    public NgcView.CdsPayload cdsPayload(Document d) {
        // NGC writes keys as TargetPartyRole in some paths and targetPartyRole in others: look them up ignoring case
        java.util.Map<String, String> fields = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            String xml = parse(str(d, "message")).path("data").asText("");
            if (!xml.isBlank()) {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var doc = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
                var items = doc.getElementsByTagName("XMLOutput");
                for (int i = 0; i < items.getLength(); i++) {
                    var item = (org.w3c.dom.Element) items.item(i);
                    var key = item.getElementsByTagName("Key");
                    var value = item.getElementsByTagName("Value");
                    if (key.getLength() > 0 && !PERSONAL_KEYS.contains(key.item(0).getTextContent().toLowerCase())) {
                        fields.put(key.item(0).getTextContent(),
                                value.getLength() > 0 ? value.item(0).getTextContent() : "");
                    }
                }
            }
        } catch (Exception e) {
            // unparseable payload: leave fields empty so assertions on them fail visibly
        }
        String role = fields.getOrDefault("TargetPartyRole", str(d, "targetRole"));
        return new NgcView.CdsPayload(role, fields);
    }

    public Observation fromJourney(Document d) {
        return new Observation(time(d, "createdAt"), "MONGO", "event_journey", str(d, "eventType"), null,
                str(d, "targetRole"), str(d, "uniqueId"), null, null, str(d, "originalEventType"), id(d), null);
    }

    /** CDS/ZL callback: pulls JobId, roleTrackingId, ngcUniqueId, originalTargetEvent out of the message identifiers. */
    public Observation fromInternalEvent(Document d) {
        String tango = null;
        String role = null;
        String ngcId = str(d, "uniqueId");
        String original = null;
        JsonNode identifiers = parse(str(d, "message")).path("identifiers");
        for (JsonNode i : identifiers) {
            String value = i.hasNonNull("value") ? i.get("value").asText() : null;
            switch (i.path("identifier").asText()) {
                case "JobId" -> tango = value;
                case "roleTrackingId" -> role = value;
                case "ngcUniqueId" -> ngcId = value != null ? value : ngcId;
                case "originalTargetEvent" -> original = value;
                default -> { }
            }
        }
        return new Observation(time(d, "updatedAt", "createdAt"), "MONGO", "internal_events", str(d, "eventType"),
                str(d, "status"), str(d, "targetRole"), ngcId, tango, role, original, id(d), null);
    }

    public Observation fromTechnicalFailure(Document d) {
        String worker = str(d, "workerName");
        return new Observation(time(d, "createdAt"), "MONGO", "technical_failures", str(d, "eventType"),
                "TECHNICAL_FAILURE", null, str(d, "uniqueId"), null, null, null, id(d),
                worker == null ? null : "{\"workerName\":\"" + worker.replace("\"", "'") + "\"}");
    }

    public Observation fromDeadLetter(Document d) {
        return new Observation(time(d, "lastAttemptAt", "createdAt"), "MONGO", "dead_letter_events",
                str(d, "eventType"), str(d, "status"), null, str(d, "uniqueId"), null, null, null, id(d),
                "{\"failureExceptionClass\":\"" + String.valueOf(d.get("failureExceptionClass")).replace("\"", "'")
                        + "\"}");
    }

    public Observation fromTargetAudit(Document d) {
        return new Observation(time(d, "createdAt"), "MONGO", "target_role_and_event_status_audit",
                str(d, "status"), str(d, "status"), str(d, "targetPartyRole"), str(d, "uniqueId"), null, null, null, id(d), null);
    }

    private JsonNode parse(String text) {
        try {
            return text == null ? json.nullNode() : json.readTree(text);
        } catch (Exception e) {
            return json.nullNode();
        }
    }

    private static String id(Document d) {
        Object id = d.get("_id");
        return id == null ? null : id.toString();
    }

    private static String str(Document d, String key) {
        Object v = d.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Document> list(Document d, String key) {
        Object v = d.get(key);
        return v instanceof List<?> l ? (List<Document>) l : List.of();
    }

    private static OffsetDateTime time(Document d, String... keys) {
        for (String k : keys) {
            if (d.get(k) instanceof Date date) {
                return date.toInstant().atOffset(ZoneOffset.UTC);
            }
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
