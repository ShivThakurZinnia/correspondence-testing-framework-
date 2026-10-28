package com.zinnia.regression.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line client for the central service, so runs started from a terminal are stored with everyone else's.
 *
 * <pre>
 * java -jar ngc-regression.jar run --env qa [--tags mapper:policy-page,client:GILI] [--module policy-page]
 *                                  [--version 3] [--hub http://host:8085] [--no-wait] [--timeout-minutes 30]
 * Login: REGRESSION_USER (default admin) and REGRESSION_PASSWORD, or --user / --password.
 *                                  [--yes]  confirm a run above the large-run threshold
 *                                  [--max-in-flight 25] [--rate 5]  pacing
 * java -jar ngc-regression.jar status &lt;runKey&gt; [--hub ...]
 * java -jar ngc-regression.jar cancel &lt;runKey&gt;
 * java -jar ngc-regression.jar rerun-failed &lt;runKey&gt;
 * java -jar ngc-regression.jar runs [--env qa] [--status FAILED] [--hub ...]
 * </pre>
 * Exit codes: 0 passed, 1 failed, 2 error or bad request, 3 timed out waiting.
 */
public final class RegressionCli {

    static final Set<String> COMMANDS = Set.of("run", "status", "runs", "cancel", "rerun-failed");
    private static final Set<String> TERMINAL = Set.of("PASSED", "FAILED", "ERROR", "CANCELLED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private RegressionCli() {
    }

    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0]);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Duration.ofSeconds(5));
    }

    static int run(String[] args, PrintStream out, PrintStream err, Duration pollInterval) {
        Map<String, String> opts = new HashMap<>();
        String positional = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                boolean flag = key.equals("no-wait") || i + 1 >= args.length || args[i + 1].startsWith("--");
                opts.put(key, flag ? "true" : args[++i]);
            } else if (positional == null) {
                positional = args[i];
            }
        }
        String hub = opts.getOrDefault("hub", System.getenv().getOrDefault("REGRESSION_HUB", "http://localhost:8085"));
        String user = opts.getOrDefault("user", System.getenv().getOrDefault("REGRESSION_USER", "admin"));
        String password = opts.getOrDefault("password", System.getenv("REGRESSION_PASSWORD"));
        if (password == null || password.isBlank()) {
            return usage(err, "Set REGRESSION_PASSWORD (or pass --password) to log in");
        }
        Client http = new Client(hub, user, password);
        try {
            return switch (args[0]) {
                case "run" -> start(http, opts, out, err, pollInterval);
                case "status" -> positional == null ? usage(err, "status needs a run key") : show(http, positional, out);
                case "cancel" -> positional == null ? usage(err, "cancel needs a run key") : cancel(http, positional, out, err);
                case "rerun-failed" -> positional == null ? usage(err, "rerun-failed needs a run key")
                        : rerunFailed(http, positional, opts, out, err, pollInterval);
                default -> list(http, opts, out);
            };
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static int start(Client http, Map<String, String> o, PrintStream out, PrintStream err, Duration poll)
            throws Exception {
        if (!o.containsKey("env")) {
            return usage(err, "run needs --env");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("module", o.get("module"));
        body.put("env", o.get("env"));
        body.put("tags", o.get("tags"));
        body.put("versionNo", o.containsKey("version") ? Integer.valueOf(o.get("version")) : null);
        body.put("trigger", "CLI");
        body.put("maxInFlight", o.containsKey("max-in-flight") ? Integer.valueOf(o.get("max-in-flight")) : null);
        body.put("eventsPerSecond", o.containsKey("rate") ? Double.valueOf(o.get("rate")) : null);
        Response preview = http.post("/api/runs/preview", JSON.writeValueAsString(body));
        if (preview.status != 200) {
            err.println("Run rejected: " + (preview.json.has("errors") ? preview.json.get("errors") : preview.body));
            return 2;
        }
        int events = preview.json.path("events").asInt();
        out.println(events + " event(s) from " + preview.json.path("cases").asInt() + " test case(s) will be sent to " + o.get("env"));
        if (preview.json.path("requiresConfirmation").asBoolean()) {
            if (!o.containsKey("yes")) {
                err.println("That is more than " + preview.json.path("confirmAbove").asInt() + " events. Re-run with --yes to confirm.");
                return 2;
            }
            body.put("confirmEvents", events);
        }
        Response r = http.post("/api/runs", JSON.writeValueAsString(body));
        if (r.status != 202) {
            err.println("Run rejected: " + (r.json.has("errors") ? r.json.get("errors") : r.body));
            return 2;
        }
        String key = r.json.get("runKey").asText();
        out.println("Run " + key + " started on " + o.get("env"));
        if (o.containsKey("no-wait")) {
            return 0;
        }
        return follow(http, key, o, out, err, poll);
    }

    private static int cancel(Client http, String key, PrintStream out, PrintStream err) throws Exception {
        Response r = http.post("/api/runs/" + key + "/cancel", "{}");
        if (r.status != 200) {
            err.println("Cannot cancel: " + (r.json.has("errors") ? r.json.get("errors") : r.body));
            return 2;
        }
        out.println("Cancel requested for run " + key + ". Unsent events will be skipped.");
        return 0;
    }

    private static int rerunFailed(Client http, String key, Map<String, String> o, PrintStream out, PrintStream err, Duration poll)
            throws Exception {
        Response r = http.post("/api/runs/" + key + "/rerun-failed", "{}");
        if (r.status != 202) {
            err.println("Cannot re-run: " + (r.json.has("errors") ? r.json.get("errors") : r.body));
            return 2;
        }
        String newKey = r.json.get("runKey").asText();
        out.println("Re-running failed events of " + key + " as run " + newKey);
        return o.containsKey("no-wait") ? 0 : follow(http, newKey, o, out, err, poll);
    }

    /** Prints progress until the run ends. Only changes are printed, and large runs are summarised, not listed. */
    private static int follow(Client http, String key, Map<String, String> o, PrintStream out, PrintStream err, Duration poll)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(Long.parseLong(o.getOrDefault("timeout-minutes", "120"))).toNanos();
        Map<String, String> printed = new HashMap<>();
        String lastProgress = "";
        while (System.nanoTime() < deadline) {
            JsonNode run = http.get("/api/runs/" + key + "?size=500").json;
            boolean big = run.path("total_cases").asInt() > 25;
            if (!big) {
                for (JsonNode c : run.path("cases")) {
                    String line = caseLine(c);
                    if (!line.equals(printed.put(c.path("correlation_id").asText(), line))) {
                        out.println(line);
                    }
                }
            } else {
                JsonNode sum = run.path("summary");
                String progress = "  " + (run.path("passed_cases").asInt() + run.path("failed_cases").asInt()) + "/" + run.path("total_cases").asInt()
                        + " done, " + run.path("passed_cases").asInt() + " passed, " + run.path("failed_cases").asInt() + " failed, "
                        + sum.path("RUNNING").asInt() + " in flight, " + sum.path("PENDING").asInt() + " waiting";
                if (!progress.equals(lastProgress)) {
                    out.println(progress);
                    lastProgress = progress;
                }
            }
            if (TERMINAL.contains(run.path("status").asText())) {
                if (big) {
                    for (JsonNode c : http.get("/api/runs/" + key + "?size=25&status=FAILED,TIMEOUT,ERROR").json.path("cases")) {
                        out.println(caseLine(c));
                    }
                }
                return summary(run, out);
            }
            Thread.sleep(poll.toMillis());
        }
        err.println("Gave up waiting; run " + key + " is still going. Check: status " + key);
        return 3;
    }

    private static int show(Client http, String key, PrintStream out) throws Exception {
        Response r = http.get("/api/runs/" + key);
        if (r.status == 404) {
            out.println("Unknown run " + key);
            return 2;
        }
        r.json.path("cases").forEach(c -> out.println(caseLine(c)));
        return summary(r.json, out);
    }

    private static int list(Client http, Map<String, String> o, PrintStream out) throws Exception {
        StringBuilder q = new StringBuilder("/api/runs?size=" + o.getOrDefault("size", "15"));
        for (String k : List.of("env", "module", "status", "trigger")) {
            if (o.containsKey(k)) {
                q.append('&').append(k).append('=').append(URLEncoder.encode(o.get(k), StandardCharsets.UTF_8));
            }
        }
        for (JsonNode r : http.get(q.toString()).json) {
            out.printf("%-8s %-4s %s %-30s %d/%d passed  %s%n", r.path("status").asText(),
                    r.path("env").asText(), r.path("trigger_source").asText(),
                    r.path("tag_expression").isMissingNode() || r.path("tag_expression").isNull() ? "all tests" : r.path("tag_expression").asText(),
                    r.path("passed_cases").asInt(), r.path("total_cases").asInt(), r.path("run_key").asText());
        }
        return 0;
    }

    private static String caseLine(JsonNode c) {
        long reached = 0;
        long total = 0;
        for (JsonNode s : c.path("progress")) {
            total++;
            reached += s.path("reached").asBoolean() ? 1 : 0;
        }
        String line = String.format("  [%-7s] %s  stages %d/%d", c.path("status").asText(),
                c.path("scenario_key").asText(), reached, total);
        return c.hasNonNull("failure_summary") ? line + "  - " + c.get("failure_summary").asText() : line;
    }

    private static int summary(JsonNode run, PrintStream out) {
        String status = run.path("status").asText();
        out.printf("Run %s: %s (%d passed, %d failed of %d)%n", run.path("run_key").asText(), status,
                run.path("passed_cases").asInt(), run.path("failed_cases").asInt(), run.path("total_cases").asInt());
        return switch (status) {
            case "PASSED" -> 0;
            case "FAILED" -> 1;
            default -> 2;
        };
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println("Usage: run --env <e> [--tags a:b,c:d] [--module <m> [--version n]] [--yes] [--max-in-flight n] [--rate n] [--hub url] [--no-wait]"
                + " | status <runKey> | cancel <runKey> | rerun-failed <runKey> | runs [--env e] [--status s]");
        return 2;
    }

    private record Response(int status, String body, JsonNode json) {
    }

    private static final class Client {
        private final String hub;
        private final String authorization;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        Client(String hub, String user, String password) {
            this.hub = hub.endsWith("/") ? hub.substring(0, hub.length() - 1) : hub;
            this.authorization = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        Response get(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(hub + path)).GET());
        }

        Response post(String path, String body) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(hub + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
        }

        private Response send(HttpRequest.Builder b) throws Exception {
            HttpResponse<String> r = http.send(b.header("Authorization", authorization).timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) {
                throw new IllegalStateException("Login failed: wrong user or password");
            }
            JsonNode json;
            try {
                json = JSON.readTree(r.body());
            } catch (Exception e) {
                json = JSON.nullNode();
            }
            return new Response(r.statusCode(), r.body(), json == null ? JSON.nullNode() : json);
        }
    }
}
