package com.zinnia.regression.payload;

import com.zinnia.regression.artifact.ArtifactRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payloads")
public class PayloadController {

    private final EventPayloadValidator validator;
    private final ArtifactRepository artifacts;

    public PayloadController(EventPayloadValidator validator, ArtifactRepository artifacts) {
        this.validator = validator;
        this.artifacts = artifacts;
    }

    /** Stores a golden XML (the expected output of a scenario). It must be well-formed XML. */
    @PostMapping(path = "/golden", consumes = {"application/xml", "text/xml"})
    public ResponseEntity<Map<String, Object>> uploadGolden(
            @RequestBody String xml,
            @RequestParam(defaultValue = "golden.xml") String name,
            java.security.Principal principal) {
        try {
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "errors",
                    java.util.List.of("Golden file is not well-formed XML")));
        }
        return ResponseEntity.ok(Map.of("valid", true, "artifactId",
                artifacts.saveInDb("GOLDEN_XML", name, "application/xml", xml, principal.getName())));
    }

    /** Validates an uploaded event and stores it unmodified; the detected correlationId is returned for the test case. */
    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestBody String json,
            @RequestParam(defaultValue = "event-payload.json") String name,
            java.security.Principal principal) {
        PayloadInfo info = validator.validate(json);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", info.valid());
        body.put("errors", info.errors());
        if (!info.valid()) {
            return ResponseEntity.badRequest().body(body);
        }
        body.put("artifactId", artifacts.saveInDb("EVENT_PAYLOAD", name, "application/json", json, principal.getName()));
        body.put("payloadCorrelationId", info.correlationId());
        body.put("carrier", info.carrier());
        body.put("eventType", info.eventType());
        body.put("planCode", info.planCode());
        return ResponseEntity.ok(body);
    }
}
