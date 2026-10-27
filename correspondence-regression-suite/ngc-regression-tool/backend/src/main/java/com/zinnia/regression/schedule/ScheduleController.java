package com.zinnia.regression.schedule;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ScheduleRequest request,
                                                      java.security.Principal principal) {
        return ResponseEntity.status(201).body(Map.of("id", service.create(request, principal.getName())));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> runNow(@PathVariable long id, java.security.Principal principal) {
        return ResponseEntity.status(202).body(Map.of("runKey", service.runNow(id, principal.getName())));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody ScheduleRequest request,
                                      java.security.Principal principal) {
        service.update(id, request, principal.getName());
        return Map.of("id", id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, java.security.Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/enabled")
    public Map<String, Object> enabled(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        service.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("id", id, "enabled", Boolean.TRUE.equals(body.get("enabled")));
    }
}
