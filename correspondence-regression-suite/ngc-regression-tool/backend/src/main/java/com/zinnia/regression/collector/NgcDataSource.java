package com.zinnia.regression.collector;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only window onto NGC's database. */
public interface NgcDataSource {

    NgcView collect(String correlationId);

    /**
     * Everything NGC holds for each id, using as few round trips as the source allows. Ids with no data map to an
     * empty view, so the result always has one entry per id.
     */
    default Map<String, NgcView> collectBatch(Collection<String> correlationIds) {
        Map<String, NgcView> out = new LinkedHashMap<>();
        correlationIds.forEach(id -> out.put(id, collect(id)));
        return out;
    }

    boolean incomingEventExists(String correlationId);
}
