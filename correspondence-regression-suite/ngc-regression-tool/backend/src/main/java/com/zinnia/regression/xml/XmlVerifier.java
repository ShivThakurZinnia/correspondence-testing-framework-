package com.zinnia.regression.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.zinnia.regression.assertion.AssertionOutcome;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.NgcView.CdsPayload;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/** Checks the XML NGC generated for one target: it exists, is well formed, matches its XSD and the golden file. */
@Component
public class XmlVerifier {

    public record Result(List<AssertionOutcome> outcomes, String role, String xml) {
    }

    private final XmlComparator comparator;

    public XmlVerifier(XmlComparator comparator) {
        this.comparator = comparator;
    }

    public Result verify(JsonNode expected, NgcView view, String golden, XmlStore store) {
        List<AssertionOutcome> out = new ArrayList<>();
        IncomingEventView incoming = view.incomingEvents().isEmpty() ? null
                : view.incomingEvents().get(view.incomingEvents().size() - 1);
        if (incoming == null) {
            out.add(fail("xml.exists", "XML generated", "no incoming_events record", "NGC never recorded the event"));
            return new Result(out, null, null);
        }
        String role = expected.path("role").asText(null);
        if (role == null) {
            if (incoming.targets().size() != 1) {
                out.add(fail("xml.role", "one target", incoming.targets().size() + " targets",
                        "Set xml.role when the event has several targets"));
                return new Result(out, null, null);
            }
            role = incoming.targets().get(0).role();
        }
        final String targetRole = role;
        TargetView target = incoming.targets().stream().filter(t -> targetRole.equals(t.role())).findFirst().orElse(null);
        CdsPayload cds = view.cdsPayloads().stream().filter(p -> targetRole.equals(p.targetRole())).findFirst().orElse(null);

        Located located;
        try {
            located = locate(incoming, target, cds, store);
        } catch (RuntimeException e) {
            out.add(fail("xml.exists", "XML in S3", "unreadable", e.getMessage()));
            return new Result(out, role, null);
        }
        boolean wantExists = !expected.has("exists") || expected.get("exists").asBoolean();
        if (located.xml == null) {
            out.add(wantExists ? fail("xml.exists", "XML in S3", "not found",
                    "Tried: " + String.join(", ", located.tried)) : pass("xml.exists", "absent", "absent"));
            skipped(expected, out);
            return new Result(out, role, null);
        }
        out.add(wantExists ? pass("xml.exists", "XML in S3", located.where)
                : fail("xml.exists", "absent", "present", "XML exists but the test expects none"));

        boolean wellFormed = wellFormed(located.xml);
        out.add(wellFormed ? pass("xml.wellFormed", "well formed", "well formed")
                : fail("xml.wellFormed", "well formed", "malformed", "Generated XML cannot be parsed"));
        if (wellFormed && expected.path("xsdValid").asBoolean(false)) {
            out.add(xsd(cds, located.xml, store));
        }
        if (wellFormed && expected.path("goldenCompare").asBoolean(false)) {
            if (golden == null) {
                out.add(fail("xml.golden", "golden XML", "none attached", "Attach a golden XML to this test case"));
            } else {
                Set<String> ignore = new HashSet<>();
                expected.path("ignore").forEach(i -> ignore.add(i.asText()));
                List<String> diffs = comparator.diff(golden, located.xml, ignore);
                out.add(diffs.isEmpty() ? pass("xml.golden", "matches golden", "matches golden")
                        : fail("xml.golden", "matches golden", diffs.size() + " difference(s)",
                                String.join("; ", diffs.subList(0, Math.min(diffs.size(), XmlComparator.MAX_REPORTED)))));
            }
        }
        return new Result(out, role, located.xml);
    }

    private record Located(String xml, String where, List<String> tried) {
    }

    /** Prefer the exact path NGC handed to CDS; fall back to s3FileBaseLocation + xmlFileName. */
    private Located locate(IncomingEventView incoming, TargetView target, CdsPayload cds, XmlStore store) {
        List<S3Location> candidates = new ArrayList<>();
        if (cds != null) {
            S3Location.parse(cds.fields().get("OutputFilePath")).ifPresent(candidates::add);
        }
        if (target != null && target.xmlFileName() != null && incoming.s3FileBaseLocation() != null) {
            S3Location fallback = new S3Location(store.defaultBucket(), incoming.s3FileBaseLocation() + "/" + target.xmlFileName());
            if (!candidates.contains(fallback)) {
                candidates.add(fallback);
            }
        }
        List<String> tried = new ArrayList<>();
        for (S3Location c : candidates) {
            tried.add("s3://" + c.bucket() + "/" + c.key());
            Optional<String> xml = store.read(c.bucket(), c.key());
            if (xml.isPresent() && !xml.get().isBlank()) {
                return new Located(xml.get(), tried.get(tried.size() - 1), tried);
            }
        }
        if (candidates.isEmpty()) {
            tried.add("no XML location recorded by NGC");
        }
        return new Located(null, null, tried);
    }

    private AssertionOutcome xsd(CdsPayload cds, String xml, XmlStore store) {
        Optional<S3Location> loc = cds == null ? Optional.empty() : S3Location.parse(cds.fields().get("XSDFilePath"));
        if (loc.isEmpty()) {
            return fail("xml.xsdValid", "valid against XSD", "no XSDFilePath", "CDS payload has no XSD path");
        }
        try {
            String xsd = store.read(loc.get().bucket(), loc.get().key()).orElse(null);
            if (xsd == null) {
                return fail("xml.xsdValid", "valid against XSD", "XSD not found", "s3://" + loc.get().bucket() + "/" + loc.get().key());
            }
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);
            sf.setResourceResolver((type, ns, publicId, systemId, base) -> {
                if (systemId == null || systemId.startsWith("http")) {
                    return null;
                }
                String dir = loc.get().key().contains("/") ? loc.get().key().substring(0, loc.get().key().lastIndexOf('/') + 1) : "";
                String text = store.read(loc.get().bucket(), java.net.URI.create(dir).resolve(systemId).normalize().toString())
                        .orElse(null);
                return text == null ? null : new StringLsInput(publicId, systemId, text);
            });
            var validator = sf.newSchema(new StreamSource(new StringReader(xsd))).newValidator();
            List<String> errors = new ArrayList<>();
            validator.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(SAXParseException e) {
                    errors.add("line " + e.getLineNumber() + ": " + e.getMessage());
                }

                @Override
                public void fatalError(SAXParseException e) {
                    errors.add("line " + e.getLineNumber() + ": " + e.getMessage());
                }
            });
            validator.validate(new StreamSource(new StringReader(xml)));
            return errors.isEmpty() ? pass("xml.xsdValid", "valid against XSD", "valid")
                    : fail("xml.xsdValid", "valid against XSD", errors.size() + " error(s)",
                            String.join("; ", errors.subList(0, Math.min(errors.size(), 10))));
        } catch (Exception e) {
            return fail("xml.xsdValid", "valid against XSD", "could not validate", e.getMessage());
        }
    }

    private void skipped(JsonNode expected, List<AssertionOutcome> out) {
        if (expected.path("xsdValid").asBoolean(false)) {
            out.add(fail("xml.xsdValid", "valid against XSD", "not evaluated", "XML was not found"));
        }
        if (expected.path("goldenCompare").asBoolean(false)) {
            out.add(fail("xml.golden", "matches golden", "not evaluated", "XML was not found"));
        }
    }

    private static boolean wellFormed(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static AssertionOutcome pass(String path, String expected, String actual) {
        return new AssertionOutcome("XML", path, expected, actual, true, null);
    }

    private static AssertionOutcome fail(String path, String expected, String actual, String message) {
        return new AssertionOutcome("XML", path, expected, actual, false, message);
    }
}
