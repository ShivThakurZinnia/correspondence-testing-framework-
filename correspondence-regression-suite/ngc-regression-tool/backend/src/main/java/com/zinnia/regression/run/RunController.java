package com.zinnia.regression.run;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService service;
    private final RunExecutor executor;

    public RunController(RunService service, RunExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    /** How many events a run would send and roughly how long it takes, so the caller can confirm a large run. */
    @PostMapping("/preview")
    public RunService.Preview preview(@RequestBody RunRequest request) {
        return service.preview(request);
    }

    /** Starts a run in the background and returns its key; follow it with GET /api/runs/{runKey}. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody RunRequest request, java.security.Principal principal) {
        String runKey = service.create(request, principal.getName());
        executor.executeAsync(runKey);
        return ResponseEntity.accepted().body(Map.of("runKey", runKey));
    }

    /** Stops publishing, marks everything unfinished as skipped. */
    @PostMapping("/{runKey}/cancel")
    public Map<String, Object> cancel(@PathVariable String runKey) {
        service.cancel(runKey);
        return Map.of("runKey", runKey, "cancelRequested", true);
    }

    /** New run with only the events that failed, timed out or errored in this one. */
    @PostMapping("/{runKey}/rerun-failed")
    public ResponseEntity<Map<String, Object>> rerunFailed(@PathVariable String runKey, java.security.Principal principal) {
        String newKey = service.rerunFailed(runKey, principal.getName());
        executor.executeAsync(newKey);
        return ResponseEntity.accepted().body(Map.of("runKey", newKey));
    }
}
