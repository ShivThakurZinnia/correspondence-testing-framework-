package com.zinnia.regression.run;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Caps unfinished events across all runs together, so overlapping runs cannot flood NGC between them. */
@Component
public class DispatchGate {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final int max;

    public DispatchGate(@Value("${regression.run.global-max-in-flight:50}") int max) {
        this.max = max;
    }

    public boolean tryAcquire() {
        while (true) {
            int current = inFlight.get();
            if (current >= max) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Re-adopts an event that was already in flight before a restart, even if that goes over the cap. */
    public void force() {
        inFlight.incrementAndGet();
    }

    public void release() {
        inFlight.updateAndGet(v -> Math.max(0, v - 1));
    }

    public int inFlight() {
        return inFlight.get();
    }
}
