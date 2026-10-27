package com.zinnia.regression.schedule;

import com.zinnia.regression.common.Keys;
import com.zinnia.regression.common.Rows;
import com.zinnia.regression.config.WiredEnvironment;
import com.zinnia.regression.run.RunExecutor;
import com.zinnia.regression.run.RunRequest;
import com.zinnia.regression.run.RunService;
import com.zinnia.regression.testcase.ConflictException;
import com.zinnia.regression.testcase.ValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time-based runs. Cron is Spring's 6-field form (sec min hour day month weekday), evaluated in the schedule's
 * timezone. A schedule fires at most once per tick; occurrences missed while the service was down are skipped.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final JdbcClient jdbc;
    private final RunService runs;
    private final RunExecutor executor;
    private final WiredEnvironment wired;

    public ScheduleService(JdbcClient jdbc, RunService runs, RunExecutor executor, WiredEnvironment wired) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.executor = executor;
        this.wired = wired;
    }

    @Transactional
    public long create(ScheduleRequest r, String user) {
        Resolved x = validate(r, null);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO schedule (name, module_id, module_version_id, environment_id, tag_expression,
                  cron_expression, timezone, overlap_policy, notify_target, created_by, max_in_flight, events_per_second)
                VALUES (:n, :m, :v, :e, :t, :c, :z, :o, :notify, :u, :mif, :eps)
                """).param("mif", r.maxInFlight()).param("eps", r.eventsPerSecond()).param("n", r.name()).param("m", x.moduleId).param("v", x.versionId).param("e", x.envId)
                .param("t", r.tags()).param("c", r.cron()).param("z", x.zone).param("o", x.overlap)
                .param("notify", r.notifyTarget()).param("u", user).update(keys);
        long id = Keys.idOf(keys);
        audit(id, "CREATE", user, r.name());
        return id;
    }

    /** Only while the schedule has no queued or running run. Re-bases the next fire time to after this edit. */
    @Transactional
    public void update(long id, ScheduleRequest r, String user) {
        requireIdle(id);
        Resolved x = validate(r, id);
        jdbc.sql("""
                UPDATE schedule SET name = :n, module_id = :m, module_version_id = :v, environment_id = :e,
                  tag_expression = :t, cron_expression = :c, timezone = :z, overlap_policy = :o, notify_target = :notify,
                  max_in_flight = :mif, events_per_second = :eps, last_fired_at = CURRENT_TIMESTAMP
                WHERE id = :i
                """).param("mif", r.maxInFlight()).param("eps", r.eventsPerSecond()).param("n", r.name()).param("m", x.moduleId).param("v", x.versionId).param("e", x.envId)
                .param("t", r.tags()).param("c", r.cron()).param("z", x.zone).param("o", x.overlap)
                .param("notify", r.notifyTarget()).param("i", id).update();
        audit(id, "UPDATE", user, r.name());
    }

    /** Only while idle. Past runs stay in history (they keep the schedule name) but lose the link. */
    @Transactional
    public void delete(long id, String user) {
        requireIdle(id);
        String name = jdbc.sql("SELECT name FROM schedule WHERE id = :i").param("i", id).query(String.class).single();
        jdbc.sql("UPDATE test_run SET schedule_id = NULL WHERE schedule_id = :i").param("i", id).update();
        jdbc.sql("DELETE FROM schedule WHERE id = :i").param("i", id).update();
        audit(id, "DELETE", user, name);
    }

    private void requireIdle(long id) {
        if (jdbc.sql("SELECT COUNT(*) FROM schedule WHERE id = :i").param("i", id).query(Integer.class).single() == 0) {
            throw new ValidationException(List.of("Unknown schedule " + id));
        }
        if (jdbc.sql("SELECT COUNT(*) FROM test_run WHERE schedule_id = :i AND status IN ('QUEUED','RUNNING')")
                .param("i", id).query(Integer.class).single() > 0) {
            throw new ConflictException("Schedule is executing right now; wait for the run to finish");
        }
    }

    private void audit(long id, String action, String user, String name) {
        jdbc.sql("""
                INSERT INTO audit_log (entity_type, entity_id, action, actor, detail_json)
                VALUES ('SCHEDULE', :i, :a, :u, :d)
                """).param("i", id).param("a", action).param("u", user)
                .param("d", "{\"name\":\"" + name.replace("\"", "'") + "\"}").update();
    }

    private record Resolved(Long moduleId, long envId, Long versionId, String zone, String overlap) {
    }

    private Resolved validate(ScheduleRequest r, Long selfId) {
        List<String> errors = new ArrayList<>();
        if (r.name() == null || r.name().isBlank()) {
            errors.add("name is required");
        } else if (jdbc.sql("SELECT COUNT(*) FROM schedule WHERE name = :n AND (:self IS NULL OR id <> :self)")
                .param("n", r.name()).param("self", selfId).query(Integer.class).single() > 0) {
            errors.add("A schedule named '" + r.name() + "' already exists");
        }
        if (r.cron() == null || !CronExpression.isValidExpression(r.cron())) {
            errors.add("cron must be a valid 6-field expression, e.g. '0 0 6 * * *' for daily 06:00");
        }
        String zone = r.timezone() == null || r.timezone().isBlank() ? "UTC" : r.timezone();
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            errors.add("Unknown timezone: " + zone);
        }
        String overlap = r.overlapPolicy() == null ? "SKIP" : r.overlapPolicy().toUpperCase();
        if (!List.of("SKIP", "QUEUE").contains(overlap)) {
            errors.add("overlapPolicy must be SKIP or QUEUE");
        }
        Long envId = jdbc.sql("SELECT id FROM environment WHERE code = :c AND active = TRUE").param("c", r.env())
                .query(Long.class).optional().orElse(null);
        if (envId == null) {
            errors.add("Unknown or inactive environment: " + r.env());
        } else if (!wired.allows(r.env())) {
            errors.add("This instance is connected to " + wired.code().orElse("?") + ", not " + r.env());
        }
        Long moduleId = null;
        if (r.module() != null && !r.module().isBlank()) {
            moduleId = jdbc.sql("SELECT id FROM test_module WHERE code = :c").param("c", r.module())
                    .query(Long.class).optional().orElse(null);
            if (moduleId == null) {
                errors.add("Unknown module: " + r.module());
            }
        }
        Long versionId = null;
        if (moduleId != null && r.versionNo() != null) {
            versionId = jdbc.sql("SELECT id FROM module_version WHERE module_id = :m AND version_no = :n")
                    .param("m", moduleId).param("n", r.versionNo()).query(Long.class).optional().orElse(null);
            if (versionId == null) {
                errors.add("Module " + r.module() + " has no version " + r.versionNo());
            }
        }
        for (String t : r.tags() == null || r.tags().isBlank() ? new String[0] : r.tags().split(",")) {
            if (t.trim().split(":", 2).length != 2) {
                errors.add("Bad tag expression: " + t);
            }
        }
        if (r.maxInFlight() != null && r.maxInFlight() < 1) {
            errors.add("maxInFlight must be at least 1");
        }
        if (r.eventsPerSecond() != null && r.eventsPerSecond() <= 0) {
            errors.add("eventsPerSecond must be greater than 0");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Resolved(moduleId, envId, versionId, zone, overlap);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = Rows.list(jdbc.sql("""
                SELECT s.id, s.name, m.code AS module, v.version_no AS pinned_version_no, e.code AS env,
                       s.tag_expression, s.cron_expression, s.timezone, s.overlap_policy, s.enabled, s.last_fired_at,
                       s.created_at, s.max_in_flight, s.events_per_second,
                       (SELECT COUNT(*) FROM test_run r WHERE r.schedule_id = s.id
                          AND r.status IN ('QUEUED','RUNNING')) > 0 AS executing
                FROM schedule s LEFT JOIN test_module m ON m.id = s.module_id JOIN environment e ON e.id = s.environment_id
                LEFT JOIN module_version v ON v.id = s.module_version_id ORDER BY s.name
                """));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
            copy.put("next_fire_at", next(row));
            out.add(copy);
        }
        return out;
    }

    @Transactional
    public void setEnabled(long id, boolean enabled) {
        if (jdbc.sql("UPDATE schedule SET enabled = :e WHERE id = :i").param("e", enabled).param("i", id).update() == 0) {
            throw new ValidationException(List.of("Unknown schedule " + id));
        }
    }

    /** Fires every schedule that is due at {@code now}. Returns the run keys it started. */
    public List<String> tick(Instant now) {
        List<String> started = new ArrayList<>();
        for (Map<String, Object> s : Rows.list(jdbc.sql("""
                SELECT s.id, s.name, m.code AS module, s.module_version_id, e.code AS env, s.tag_expression,
                       s.cron_expression, s.timezone, s.overlap_policy, s.last_fired_at, s.created_at,
                       s.max_in_flight, s.events_per_second
                FROM schedule s LEFT JOIN test_module m ON m.id = s.module_id JOIN environment e ON e.id = s.environment_id
                WHERE s.enabled = TRUE ORDER BY s.id"""))) {
            Instant due = nextInstant(s);
            if (due == null || due.isAfter(now)) {
                continue;
            }
            long id = ((Number) s.get("id")).longValue();
            boolean busy = jdbc.sql("""
                    SELECT COUNT(*) FROM test_run WHERE schedule_id = :i AND status IN ('QUEUED','RUNNING')
                    """).param("i", id).query(Integer.class).single() > 0;
            if (busy) {
                if ("SKIP".equals(s.get("overlap_policy"))) {
                    log.warn("Schedule {} skipped: previous run still active", s.get("name"));
                    markFired(id, now);
                }
                continue;
            }
            try {
                String key = startRun(s, "scheduler");
                if (key != null) {
                    started.add(key);
                }
            } catch (RuntimeException e) {
                log.error("Schedule {} failed to start a run: {}", s.get("name"), e.getMessage());
            }
            markFired(id, now);
        }
        return started;
    }

    /** Starts a run of the schedule right now, outside its cron times. Refused while the schedule is already executing. */
    public String runNow(long id, String user) {
        Map<String, Object> s = Rows.list(jdbc.sql("""
                SELECT s.id, s.name, m.code AS module, s.module_version_id, e.code AS env, s.tag_expression,
                       s.max_in_flight, s.events_per_second
                FROM schedule s LEFT JOIN test_module m ON m.id = s.module_id JOIN environment e ON e.id = s.environment_id
                WHERE s.id = :i""").param("i", id)).stream().findFirst()
                .orElseThrow(() -> new ValidationException(List.of("Unknown schedule")));
        if (jdbc.sql("SELECT COUNT(*) FROM test_run WHERE schedule_id = :i AND status IN ('QUEUED','RUNNING')")
                .param("i", id).query(Integer.class).single() > 0) {
            throw new ConflictException("Schedule is executing right now; wait for the run to finish");
        }
        String key = startRun(s, user);
        if (key == null) {
            throw new ValidationException(List.of("Module " + s.get("module") + " has no test cases yet"));
        }
        return key;
    }

    private String startRun(Map<String, Object> s, String user) {
        Integer versionNo = s.get("module") == null ? null : versionNo(s);
        if (s.get("module") != null && versionNo == null) {
            log.warn("Schedule {} skipped: module {} has no test cases yet", s.get("name"), s.get("module"));
            return null;
        }
        String key = runs.create(new RunRequest((String) s.get("module"), versionNo, (String) s.get("env"),
                (String) s.get("tag_expression"), "SCHEDULE",
                s.get("max_in_flight") == null ? null : ((Number) s.get("max_in_flight")).intValue(),
                s.get("events_per_second") == null ? null : ((Number) s.get("events_per_second")).doubleValue(), null),
                user, ((Number) s.get("id")).longValue());
        executor.executeAsync(key);
        return key;
    }

    /** A pinned version if the schedule has one, otherwise the module's newest version: schedules run the latest tests. */
    private Integer versionNo(Map<String, Object> s) {
        if (s.get("module_version_id") != null) {
            return jdbc.sql("SELECT version_no FROM module_version WHERE id = :i")
                    .param("i", ((Number) s.get("module_version_id")).longValue()).query(Integer.class).single();
        }
        return jdbc.sql("""
                SELECT v.version_no FROM module_version v JOIN test_module m ON m.id = v.module_id
                WHERE m.code = :m ORDER BY v.version_no DESC LIMIT 1
                """).param("m", s.get("module")).query(Integer.class).optional().orElse(null);
    }

    private void markFired(long id, Instant now) {
        jdbc.sql("UPDATE schedule SET last_fired_at = :t WHERE id = :i")
                .param("t", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)).param("i", id).update();
    }

    private Instant nextInstant(Map<String, Object> s) {
        Object base = s.get("last_fired_at") != null ? s.get("last_fired_at") : s.get("created_at");
        ZoneId zone = ZoneId.of((String) s.get("timezone"));
        ZonedDateTime from = toOffset(base).atZoneSameInstant(zone);
        ZonedDateTime next = CronExpression.parse((String) s.get("cron_expression")).next(from);
        return next == null ? null : next.toInstant();
    }

    private Object next(Map<String, Object> s) {
        Instant i = nextInstant(s);
        return i == null ? null : OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    private static OffsetDateTime toOffset(Object o) {
        if (o instanceof OffsetDateTime odt) {
            return odt;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return ((java.time.ZonedDateTime) o).toOffsetDateTime();
    }
}
