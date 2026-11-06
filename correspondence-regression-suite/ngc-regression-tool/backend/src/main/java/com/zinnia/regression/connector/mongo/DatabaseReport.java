package com.zinnia.regression.connector.mongo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatabaseReport(Access access, List<CollectionIndexes> collections, int missingTotal) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Access(boolean checked, boolean readOnly, List<String> writeActions, String note) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CollectionIndexes(String name, boolean exists, Long estimatedCount, List<List<String>> indexes,
                                    List<MissingIndex> missing, String error) {
    }

    public record MissingIndex(List<String> keys, String reason) {
    }
}
