package com.zinnia.regression.forensics;

import com.zinnia.regression.forensics.ForensicsModel.Criteria;
import com.zinnia.regression.forensics.ForensicsModel.History;
import com.zinnia.regression.forensics.ForensicsModel.RejectedCount;
import com.zinnia.regression.forensics.ForensicsModel.SearchResult;
import java.time.Instant;
import java.util.List;

public interface ForensicsSource {

    SearchResult search(Criteria criteria);

    /** What NGC holds for one correlationId, scoped to one attempt ("all" for every attempt), or null when nothing is found. */
    History history(String correlationId, String attempt);

    List<RejectedCount> rejectedCounts(Instant since);
}
