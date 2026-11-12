package com.zinnia.regression.connector;

import com.zinnia.regression.config.WiredEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    static final List<String> KNOWN = List.of("mongo", "s3", "eds", "kafka", "gateway");

    private final List<Connector> connectors;
    private final WiredEnvironment wired;

    public ConnectorController(List<Connector> connectors, WiredEnvironment wired) {
        this.connectors = connectors;
        this.wired = wired;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, CompletableFuture<Connector.Status>> checks = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connectors.forEach(c -> checks.put(c.id(), CompletableFuture.supplyAsync(() -> run(c), executor)));
        }
        List<String> ids = new ArrayList<>(KNOWN);
        checks.keySet().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        List<Map<String, String>> rows = ids.stream().map(id -> {
            CompletableFuture<Connector.Status> check = checks.get(id);
            return check == null ? row(id, "not_configured", "Not configured on this instance")
                    : row(id, check.join().state().name().toLowerCase(), check.join().detail());
        }).toList();
        return Map.of("environment", wired.code().orElse(""), "connectors", rows);
    }

    private static Connector.Status run(Connector connector) {
        try {
            return connector.check();
        } catch (RuntimeException e) {
            return new Connector.Status(Connector.State.FAILED, "Check failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Map<String, String> row(String id, String state, String detail) {
        return Map.of("id", id, "state", state, "detail", detail);
    }
}
