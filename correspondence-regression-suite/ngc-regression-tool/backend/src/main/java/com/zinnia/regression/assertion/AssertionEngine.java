package com.zinnia.regression.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import com.zinnia.regression.collector.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Compares a test case's expected_json with what NGC actually did. Every expectation yields an explicit outcome. */
@Component
public class AssertionEngine {

    private static final List<String> FAILURE_STEPS = List.of("requestInvalid", "requestTerminated", "caseStatusFailed");

    private static final Set<String> NOT_SETTLED = Set.of("NEW_EVENT", "PROCESSING", "SCHEDULED");

    public List<AssertionOutcome> evaluate(JsonNode expected, NgcView view) {
        List<AssertionOutcome> out = new ArrayList<>();
        IncomingEventView incoming = latest(view);

        String wantStatus = expected.path("incomingEvent").path("status").asText();
        out.add(incoming == null
                ? AssertionOutcome.fail("INCOMING_EVENT", "incomingEvent.status", wantStatus, "no incoming_events record",
                        "NGC never recorded the event")
                : compare("INCOMING_EVENT", "incomingEvent.status", wantStatus, incoming.status()));

        if (expected.has("targets")) {
            targets(expected.get("targets"), incoming, out);
        }
        if (expected.has("cds")) {
            cds(expected.get("cds"), view, out);
        }
        names(expected.get("journey"), "JOURNEY", "journey", view, false, out);
        List<String> failed = FAILURE_STEPS.stream().filter(step -> seen(step, view, false)).toList();
        out.add(failed.isEmpty()
                ? AssertionOutcome.pass("JOURNEY", "journey.noFailureSteps", "none of " + FAILURE_STEPS, "none seen")
                : AssertionOutcome.fail("JOURNEY", "journey.noFailureSteps", "none of " + FAILURE_STEPS, String.valueOf(failed),
                        "NGC recorded failure step(s): " + failed));
        names(expected.get("callbacks"), "CALLBACK", "callbacks", view, true, out);
        for (JsonNode f : expected.path("noFailures")) {
            int count = switch (f.asText()) {
                case "technical_failures" -> view.technicalFailureCount();
                case "dead_letter_events" -> view.deadLetterCount();
                default -> -1;
            };
            out.add(count < 0
                    ? AssertionOutcome.fail("NO_FAILURE", "noFailures." + f.asText(), "0", "unknown collection",
                            "Unsupported noFailures entry")
                    : compare("NO_FAILURE", "noFailures." + f.asText(), "0", String.valueOf(count)));
        }
        return out;
    }

    /** True once NGC has finished with the event and every expected callback has been seen. Journey steps are checked afterwards, not waited for. */
    public boolean settled(JsonNode expected, NgcView view) {
        IncomingEventView incoming = latest(view);
        if (incoming == null || NOT_SETTLED.contains(incoming.status())) {
            return false;
        }
        for (JsonNode n : expected.path("callbacks")) {
            if (!seen(n.asText(), view, true)) {
                return false;
            }
        }
        return true;
    }

    private void targets(JsonNode wanted, IncomingEventView incoming, List<AssertionOutcome> out) {
        List<TargetView> actual = incoming == null ? List.of() : incoming.targets();
        Set<String> wantedRoles = new java.util.HashSet<>();
        for (JsonNode w : wanted) {
            String role = w.path("role").asText();
            wantedRoles.add(role);
            String base = "targets[" + role + "]";
            TargetView t = actual.stream().filter(a -> role.equals(a.role())).findFirst().orElse(null);
            if (t == null) {
                out.add(AssertionOutcome.fail("TARGET", base, "target present", "missing", "No target for " + role));
                continue;
            }
            field(w, "status", t.status(), base, out);
            field(w, "deliveryType", t.deliveryType(), base, out);
            field(w, "emailNotification", t.emailNotification(), base, out);
            field(w, "agentType", t.agentType(), base, out);
            field(w, "documentType", t.documentType(), base, out);
            if ("present".equals(w.path("xmlFileName").asText(null))) {
                out.add(t.xmlFileName() != null && !t.xmlFileName().isBlank()
                        ? AssertionOutcome.pass("TARGET", base + ".xmlFileName", "present", "present")
                        : AssertionOutcome.fail("TARGET", base + ".xmlFileName", "present", "missing",
                                "No XML file recorded"));
            }
            if (w.has("tangoJobIdPresent")) {
                boolean has = t.tangoJobId() != null;
                out.add(has == w.get("tangoJobIdPresent").asBoolean()
                        ? AssertionOutcome.pass("TARGET", base + ".tangoJobIdPresent", w.get("tangoJobIdPresent").asText(),
                                String.valueOf(has))
                        : AssertionOutcome.fail("TARGET", base + ".tangoJobIdPresent",
                                w.get("tangoJobIdPresent").asText(), String.valueOf(has), "tangoJobId mismatch"));
            }
        }
        for (TargetView a : actual) {
            if (!wantedRoles.contains(a.role())) {
                out.add(AssertionOutcome.fail("TARGET", "targets[" + a.role() + "]", "not expected", "present",
                        "Unexpected target " + a.role()));
            }
        }
    }

    private void field(JsonNode wanted, String name, String actual, String base, List<AssertionOutcome> out) {
        if (wanted.has(name) && !wanted.get(name).asText().equals("present")) {
            out.add(compare("TARGET", base + "." + name, wanted.get(name).asText(), actual));
        }
    }

    private void cds(JsonNode cds, NgcView view, List<AssertionOutcome> out) {
        if (cds.has("published")) {
            boolean published = view.cdsEventCount() > 0;
            out.add(published == cds.get("published").asBoolean()
                    ? AssertionOutcome.pass("CDS", "cds.published", cds.get("published").asText(), String.valueOf(published))
                    : AssertionOutcome.fail("CDS", "cds.published", cds.get("published").asText(),
                            String.valueOf(published), "CDS publish mismatch"));
        }
        if (cds.has("count")) {
            out.add(compare("CDS", "cds.count", cds.get("count").asText(), String.valueOf(view.cdsEventCount())));
        }
        if (cds.has("fields")) {
            cdsFields(cds, view, out);
        }
    }

    /** Fields are checked on the payload for cds.role, or the only payload. "Key~" means the value contains the text. */
    private void cdsFields(JsonNode cds, NgcView view, List<AssertionOutcome> out) {
        String role = cds.path("role").asText(null);
        List<NgcView.CdsPayload> candidates = view.cdsPayloads().stream()
                .filter(p -> role == null || role.equals(p.targetRole())).toList();
        if (candidates.size() != 1) {
            out.add(AssertionOutcome.fail("CDS", "cds.fields", "one CDS payload" + (role == null ? "" : " for " + role),
                    candidates.size() + " payloads", candidates.isEmpty() ? "No CDS payload to check"
                            : "Set cds.role when several payloads were sent"));
            return;
        }
        var fields = candidates.get(0).fields();
        cds.get("fields").fields().forEachRemaining(f -> {
            boolean contains = f.getKey().endsWith("~");
            String key = contains ? f.getKey().substring(0, f.getKey().length() - 1) : f.getKey();
            String actual = fields.get(key);
            String want = f.getValue().asText();
            boolean ok = actual != null && (contains ? actual.contains(want) : actual.equals(want));
            String path = "cds.fields." + key;
            out.add(ok ? AssertionOutcome.pass("CDS", path, want, actual)
                    : AssertionOutcome.fail("CDS", path, (contains ? "contains " : "") + want, String.valueOf(actual),
                            actual == null ? key + " not sent to CDS" : "Expected " + want + " but was " + actual));
        });
    }

    private void names(JsonNode names, String category, String path, NgcView view, boolean callbacks,
                       List<AssertionOutcome> out) {
        if (names == null) {
            return;
        }
        for (JsonNode n : names) {
            boolean ok = seen(n.asText(), view, callbacks);
            out.add(ok ? AssertionOutcome.pass(category, path + "." + n.asText(), "seen", "seen")
                    : AssertionOutcome.fail(category, path + "." + n.asText(), "seen", "not seen",
                            n.asText() + " was not observed"));
        }
    }

    /** "a|b" accepts either. Journey names match by suffix (requestValid = correspondence.generation.requestValid). */
    private boolean seen(String spec, NgcView view, boolean callbacks) {
        for (String alt : spec.split("\\|")) {
            for (Observation o : view.observations()) {
                String type = o.eventType();
                if (type == null) {
                    continue;
                }
                boolean collection = callbacks
                        ? o.collection().equals("internal_events") || o.collection().equals("event_journey")
                        : o.collection().equals("event_journey");
                if (collection && (type.equals(alt) || type.endsWith("." + alt))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AssertionOutcome compare(String category, String path, String expected, String actual) {
        return expected.equals(actual) ? AssertionOutcome.pass(category, path, expected, actual)
                : AssertionOutcome.fail(category, path, expected, String.valueOf(actual),
                        "Expected " + expected + " but was " + actual);
    }

    private static IncomingEventView latest(NgcView view) {
        List<IncomingEventView> all = view.incomingEvents();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
