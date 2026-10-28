package com.zinnia.regression.run;

/**
 * @param maxInFlight    most unfinished events at once (null = service default)
 * @param eventsPerSecond publish rate (null = service default)
 * @param confirmEvents  for large manual runs: must equal the number of events the run will send
 */
public record RunRequest(String module, Integer versionNo, String env, String tags, String trigger,
                         Integer maxInFlight, Double eventsPerSecond, Integer confirmEvents) {

    public RunRequest(String module, Integer versionNo, String env, String tags, String trigger) {
        this(module, versionNo, env, tags, trigger, null, null, null);
    }
}
