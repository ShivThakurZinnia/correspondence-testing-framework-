package com.zinnia.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegressionCliTest {

    HttpServer server;
    String hub;
    final List<String> requests = new ArrayList<>();
    final List<String> auth = new ArrayList<>();
    volatile int authStatus = 0;
    final AtomicInteger polls = new AtomicInteger();
    volatile String finalStatus = "PASSED";
    volatile int postStatus = 202;
    volatile int cancelStatus = 200;
    volatile int previewEvents = 3;
    volatile int previewCases = 3;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/runs", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (authStatus == 401) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + body);
            String response;
            int code = 200;
            String path = ex.getRequestURI().getPath();
            if (ex.getRequestMethod().equals("POST") && path.equals("/api/runs/preview")) {
                code = postStatus == 202 ? 200 : postStatus;
                response = postStatus == 202 ? "{\"cases\":" + previewCases + ",\"events\":" + previewEvents + ",\"minimumSeconds\":3,\"confirmAbove\":100,\"requiresConfirmation\":" + (previewEvents > 100) + "}"
                        : "{\"valid\":false,\"errors\":[\"No enabled test cases match\"]}";
            } else if (ex.getRequestMethod().equals("POST") && path.endsWith("/cancel")) {
                code = cancelStatus;
                response = cancelStatus == 200 ? "{\"cancelRequested\":true}" : "{\"errors\":[\"Run run-1 is not running, so it cannot be cancelled\"]}";
            } else if (ex.getRequestMethod().equals("POST") && path.endsWith("/rerun-failed")) {
                code = 202;
                response = "{\"runKey\":\"run-2\"}";
            } else if (ex.getRequestMethod().equals("POST")) {
                code = postStatus;
                response = postStatus == 202 ? "{\"runKey\":\"run-1\"}" : "{\"valid\":false,\"errors\":[\"No enabled test cases match\"]}";
            } else if (ex.getRequestURI().getPath().equals("/api/runs/missing")) {
                code = 404;
                response = "{}";
            } else if (ex.getRequestURI().getPath().startsWith("/api/runs/")) {
                boolean done = polls.incrementAndGet() >= 2;
                if (previewEvents > 25) {
                    response = "{\"run_key\":\"run-1\",\"status\":\"" + (done ? finalStatus : "RUNNING") + "\",\"total_cases\":" + previewEvents + ",\"passed_cases\":"
                            + (done ? previewEvents - 2 : 10) + ",\"failed_cases\":" + (done ? 2 : 0) + ",\"summary\":{\"RUNNING\":5,\"PENDING\":" + (done ? 0 : 20) + "},\"cases\":"
                            + (done ? "[{\"correlation_id\":\"c9\",\"scenario_key\":\"bad-one\",\"status\":\"FAILED\",\"failure_summary\":\"1 assertion(s) failed: cds.count\",\"progress\":[]}]" : "[]") + "}";
                    byte[] big = response.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, big.length);
                    ex.getResponseBody().write(big);
                    ex.close();
                    return;
                }
                String status = done ? finalStatus : "RUNNING";
                response = "{\"run_key\":\"run-1\",\"status\":\"" + status + "\",\"total_cases\":1,\"passed_cases\":"
                        + (status.equals("PASSED") ? 1 : 0) + ",\"failed_cases\":" + (status.equals("FAILED") ? 1 : 0)
                        + ",\"cases\":[{\"correlation_id\":\"c1\",\"scenario_key\":\"gili-owner-print\",\"status\":\""
                        + (done ? finalStatus : "RUNNING") + "\",\"failure_summary\":"
                        + (finalStatus.equals("FAILED") && done ? "\"1 assertion(s) failed: cds.count\"" : "null")
                        + ",\"progress\":[{\"reached\":true},{\"reached\":" + done + "}]}]}";
            } else {
                response = "[{\"status\":\"PASSED\",\"module\":\"policy-page\",\"env\":\"qa\",\"trigger_source\":\"CLI\","
                        + "\"module_version\":\"1.0.0\",\"passed_cases\":1,\"total_cases\":1,\"run_key\":\"run-1\"}]";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        hub = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    record Result(int code, String out, String err) {
    }

    Result cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        List<String> all = new ArrayList<>(List.of(args));
        if (!all.contains("--password")) {
            all.addAll(List.of("--password", "pw"));
        }
        int code = RegressionCli.run(all.toArray(new String[0]), new PrintStream(o), new PrintStream(e), Duration.ofMillis(10));
        return new Result(code, o.toString(), e.toString());
    }

    @Test
    void runStartsWaitsPrintsProgressAndExitsZeroOnPass() {
        Result r = cli("run", "--module", "policy-page", "--env", "qa", "--tags", "client:GILI", "--hub", hub);

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("Run run-1 started on qa", "[RUNNING]", "[PASSED ]", "Run run-1: PASSED (1 passed, 0 failed of 1)");
        assertThat(requests.get(0)).startsWith("POST /api/runs")
                .contains("\"module\":\"policy-page\"", "\"tags\":\"client:GILI\"", "\"trigger\":\"CLI\"");
    }

    @Test
    void failedRunShowsTheFailureReasonAndExitsOne() {
        finalStatus = "FAILED";

        Result r = cli("run", "--module", "policy-page", "--env", "qa", "--hub", hub);

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("1 assertion(s) failed: cds.count", "Run run-1: FAILED");
    }

    @Test
    void errorRunExitsTwoAndNoWaitReturnsImmediately() {
        finalStatus = "ERROR";
        assertThat(cli("run", "--module", "m", "--env", "qa", "--hub", hub).code()).isEqualTo(2);

        requests.clear();
        Result r = cli("run", "--module", "m", "--env", "qa", "--no-wait", "--hub", hub);
        assertThat(r.code()).isZero();
        assertThat(requests).as("preview, then the run").hasSize(2);
    }

    @Test
    void rejectedRunPrintsServerErrorsAndExitsTwo() {
        postStatus = 400;

        Result r = cli("run", "--module", "m", "--env", "qa", "--hub", hub);

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("No enabled test cases match");
    }

    @Test
    void missingArgumentsAndUnreachableHubAreReportedNotThrown() {
        assertThat(cli("run", "--hub", hub).err()).contains("run needs --env");
        Result down = cli("runs", "--hub", "http://127.0.0.1:1");
        assertThat(down.code()).isEqualTo(2);
        assertThat(down.err()).startsWith("Error:");
    }

    @Test
    void statusAndRunsCommands() {
        assertThat(cli("status", "run-1", "--hub", hub).out()).contains("gili-owner-print");
        assertThat(cli("status", "missing", "--hub", hub).code()).isEqualTo(2);
        Result list = cli("runs", "--env", "qa", "--hub", hub);
        assertThat(list.out()).contains("all tests", "run-1");
        assertThat(requests).anyMatch(s -> s.contains("env=qa"));
    }

    @Test
    void sendsBasicAuthFromUserAndPasswordOptions() {
        cli("runs", "--hub", hub, "--user", "tester", "--password", "s3cret");

        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("tester:s3cret".getBytes());
        assertThat(auth).containsOnly(expected);
    }

    @Test
    void wrongPasswordIsReportedAsALoginFailure() {
        authStatus = 401;

        Result r = cli("runs", "--hub", hub);

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("Login failed");
    }

    @Test
    void missingPasswordStopsBeforeAnyRequest() {
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = RegressionCli.run(new String[]{"runs", "--hub", hub}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(e), Duration.ofMillis(10));

        if (System.getenv("REGRESSION_PASSWORD") == null) {
            assertThat(code).isEqualTo(2);
            assertThat(e.toString()).contains("REGRESSION_PASSWORD");
            assertThat(requests).isEmpty();
        }
    }

    @Test
    void aLargeRunNeedsYesAndSendsTheCountBackAsConfirmation() {
        previewEvents = 400;
        previewCases = 120;

        Result refused = cli("run", "--module", "m", "--env", "dev", "--hub", hub);
        assertThat(refused.code()).isEqualTo(2);
        assertThat(refused.err()).contains("more than 100 events", "--yes");
        assertThat(refused.out()).contains("400 event(s) from 120 test case(s) will be sent to dev");
        assertThat(requests).noneMatch(s -> s.startsWith("POST /api/runs ") || s.equals("POST /api/runs"));

        requests.clear();
        Result ok = cli("run", "--module", "m", "--env", "dev", "--yes", "--max-in-flight", "10", "--rate", "2.5", "--no-wait", "--hub", hub);
        assertThat(ok.code()).isZero();
        assertThat(requests.stream().filter(s -> s.startsWith("POST /api/runs ") && !s.contains("preview")).findFirst().orElse(""))
                .contains("\"confirmEvents\":400", "\"maxInFlight\":10", "\"eventsPerSecond\":2.5");
    }

    @Test
    void aSmallRunNeedsNoConfirmation() {
        Result r = cli("run", "--module", "m", "--env", "dev", "--no-wait", "--hub", hub);

        assertThat(r.code()).isZero();
        assertThat(requests.stream().filter(s -> s.startsWith("POST /api/runs ") && !s.contains("preview")).findFirst().orElse(""))
                .doesNotContain("\"confirmEvents\":3");
    }

    @Test
    void bigRunsAreSummarisedAsCountersAndOnlyFailuresAreListed() {
        previewEvents = 40;
        previewCases = 40;
        finalStatus = "FAILED";

        Result r = cli("run", "--module", "m", "--env", "dev", "--yes", "--hub", hub);

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("10/40 done", "40/40 done", "in flight", "waiting", "bad-one", "cds.count");
        assertThat(r.out()).doesNotContain("[PASSED");
    }

    @Test
    void cancelAndRerunFailedCommands() {
        Result cancel = cli("cancel", "run-1", "--hub", hub);
        assertThat(cancel.code()).isZero();
        assertThat(cancel.out()).contains("Cancel requested for run run-1");

        cancelStatus = 400;
        Result notRunning = cli("cancel", "run-1", "--hub", hub);
        assertThat(notRunning.code()).isEqualTo(2);
        assertThat(notRunning.err()).contains("not running");

        Result rerun = cli("rerun-failed", "run-1", "--no-wait", "--hub", hub);
        assertThat(rerun.code()).isZero();
        assertThat(rerun.out()).contains("as run run-2");
        assertThat(cli("cancel", "--hub", hub).err()).contains("cancel needs a run key");
    }

    @Test
    void onlyKnownCommandsAreHandledSoTheServerStillStarts() {
        assertThat(RegressionCli.handles(new String[]{"run"})).isTrue();
        assertThat(RegressionCli.handles(new String[]{"cancel"})).isTrue();
        assertThat(RegressionCli.handles(new String[]{"rerun-failed"})).isTrue();
        assertThat(RegressionCli.handles(new String[]{})).isFalse();
        assertThat(RegressionCli.handles(new String[]{"--spring.profiles.active=x"})).isFalse();
    }
}
