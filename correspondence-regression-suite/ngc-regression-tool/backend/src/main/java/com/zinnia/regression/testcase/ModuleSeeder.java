package com.zinnia.regression.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.artifact.ArtifactRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Starter content: the four test modules (Policy Page, Anniversary, New Business, Policy) with test cases built from
 * what NGC really does in Dev. Payloads are placeholders (REPLACE_ME) for the tester to edit.
 * <p>
 * It only fills a module that has no test cases yet, so it never overwrites or duplicates anyone's work, and it goes
 * through the normal save path, so versions, hashes and validation are exactly as if a tester had typed it.
 */
@Component
public class ModuleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModuleSeeder.class);

    public record Result(List<String> seededModules, List<String> skippedModules, int cases) {
    }

    private final JdbcClient jdbc;
    private final TestCaseService service;
    private final ArtifactRepository artifacts;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public ModuleSeeder(JdbcClient jdbc, TestCaseService service, ArtifactRepository artifacts, ObjectMapper mapper,
                        @Value("${regression.seed-modules:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.service = service;
        this.artifacts = artifacts;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            Result r = seed();
            log.info("Seeded modules {} ({} test cases); left alone {}", r.seededModules(), r.cases(), r.skippedModules());
        } catch (RuntimeException | IOException e) {
            log.warn("Could not seed starter test modules: {}", e.getMessage());
        }
    }

    public Result seed() throws IOException {
        List<String> seeded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int cases = 0;
        try (InputStream in = new ClassPathResource("seed/test-modules.json").getInputStream()) {
            for (JsonNode m : mapper.readTree(in)) {
                String code = m.get("code").asText();
                jdbc.sql("INSERT INTO test_module (code, name, description) SELECT :c, :n, :d WHERE NOT EXISTS (SELECT 1 FROM test_module WHERE code = :c)")
                        .param("c", code).param("n", m.get("name").asText()).param("d", m.get("description").asText()).update();
                boolean hasWork = jdbc.sql("SELECT COUNT(*) FROM module_version v JOIN test_module t ON t.id = v.module_id WHERE t.code = :c")
                        .param("c", code).query(Integer.class).single() > 0;
                if (hasWork) {
                    skipped.add(code);
                    continue;
                }
                for (JsonNode c : m.get("cases")) {
                    String payload = new String(new ClassPathResource("seed/payloads/" + c.get("payload").asText()).getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8);
                    long payloadId = artifacts.saveInDb("EVENT_PAYLOAD", c.get("payload").asText(), "application/json", payload, "seed");
                    service.save(code, c.get("key").asText(), new TestCaseRequest(c.get("name").asText(), c.get("description").asText(),
                            c.get("kafkaKind").asText(), c.get("topic").asText(), c.get("eventType").asText(), c.get("carrier").asText(),
                            strings(c.get("planCodes")), payloadId, null, mapper.writeValueAsString(c.get("expected")),
                            c.get("timeout").asInt(), true, strings(c.get("tags")), "Starter test case"), "seed");
                    cases++;
                }
                seeded.add(code);
            }
        }
        return new Result(seeded, skipped, cases);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
