package com.zinnia.regression.schedule;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SchedulerTicker {

    private final ScheduleService schedules;

    public SchedulerTicker(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(fixedDelayString = "${regression.scheduler-tick-ms:30000}", initialDelayString = "${regression.scheduler-tick-ms:30000}")
    void tick() {
        schedules.tick(Instant.now());
    }
}
