package com.zinnia.regression.forensics;

import com.zinnia.regression.forensics.ForensicsModel.CarrierNode;
import com.zinnia.regression.forensics.ForensicsModel.EventTypeNode;
import com.zinnia.regression.forensics.ForensicsModel.RejectedCount;
import com.zinnia.regression.forensics.ForensicsModel.Tree;
import com.zinnia.regression.health.HealthSource.EventStatusCount;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Carrier > event type > outcome counts. Mixed business-and-technical failures count in both buckets. */
@Component
public class TreeBuilder {

    private static final class Counts {
        long total;
        long failed;
        long technical;
        long business;
        long stuck;
        long ok;
    }

    public Tree build(int windowHours, List<EventStatusCount> rows, List<RejectedCount> rejected) {
        Map<String, Map<String, Counts>> byCarrier = new TreeMap<>();
        for (EventStatusCount r : rows) {
            Counts c = byCarrier.computeIfAbsent(r.carrier(), k -> new LinkedHashMap<>()).computeIfAbsent(r.eventType(), k -> new Counts());
            c.total += r.count();
            if (Outcomes.failed(r.status())) {
                c.failed += r.count();
            }
            if (Outcomes.technical(r.status(), r.errorCategory())) {
                c.technical += r.count();
            }
            if (Outcomes.business(r.status(), r.errorCategory())) {
                c.business += r.count();
            }
            if (Outcomes.stuck(r.status())) {
                c.stuck += r.count();
            }
            if (Outcomes.ok(r.status())) {
                c.ok += r.count();
            }
        }
        for (RejectedCount r : rejected) {
            byCarrier.computeIfAbsent(r.carrier(), k -> new LinkedHashMap<>());
        }
        List<CarrierNode> carriers = byCarrier.entrySet().stream().map(e -> {
            List<EventTypeNode> types = e.getValue().entrySet().stream()
                    .map(t -> new EventTypeNode(t.getKey(), t.getValue().total, t.getValue().failed, t.getValue().technical,
                            t.getValue().business, t.getValue().stuck, t.getValue().ok))
                    .sorted(Comparator.comparingLong(EventTypeNode::total).reversed()).toList();
            return new CarrierNode(e.getKey(), types.stream().mapToLong(EventTypeNode::total).sum(),
                    types.stream().mapToLong(EventTypeNode::failed).sum(), rejectedOf(rejected, e.getKey(), "DEAD_LETTER"),
                    rejectedOf(rejected, e.getKey(), "INVALID"), types);
        }).sorted(Comparator.comparingLong(CarrierNode::total).reversed()).toList();
        return new Tree(windowHours, carriers);
    }

    private static long rejectedOf(List<RejectedCount> rejected, String carrier, String kind) {
        return rejected.stream().filter(r -> r.carrier().equals(carrier) && r.kind().equals(kind)).mapToLong(RejectedCount::count).sum();
    }
}
