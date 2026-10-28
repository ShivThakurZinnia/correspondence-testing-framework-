package com.zinnia.regression.schedule;

/** maxInFlight / eventsPerSecond are optional throttles for the runs this schedule starts (null = service default). */
public record ScheduleRequest(String name, String module, Integer versionNo, String env, String tags, String cron,
                              String timezone, String overlapPolicy, String notifyTarget,
                              Integer maxInFlight, Double eventsPerSecond) {

    public ScheduleRequest(String name, String module, Integer versionNo, String env, String tags, String cron,
                           String timezone, String overlapPolicy, String notifyTarget) {
        this(name, module, versionNo, env, tags, cron, timezone, overlapPolicy, notifyTarget, null, null);
    }
}
