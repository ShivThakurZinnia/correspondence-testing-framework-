package com.zinnia.regression.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.zinnia.regression.collector.MongoLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Read-only check against the real NGC database. Runs only when MONGODB_URI and MONGODB_DATABASE are set. */
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
class MongoDiagnosticsLiveTest {

    @Test
    void reportsAccessAndIndexesOfTheRealCollections() {
        try (MongoClient client = MongoClients.create(System.getenv("MONGODB_URI"))) {
            DatabaseReport r = new MongoDiagnostics(client, System.getenv("MONGODB_DATABASE"), MongoLimits.DEFAULT).report();

            System.out.println("LIVE access checked=" + r.access().checked() + " readOnly=" + r.access().readOnly()
                    + " writeActions=" + r.access().writeActions() + " note=" + r.access().note());
            r.collections().forEach(c -> System.out.println("LIVE " + c.name() + " exists=" + c.exists() + " docs=" + c.estimatedCount()
                    + " indexes=" + c.indexes() + " missing=" + c.missing().stream().map(m -> m.keys().toString()).toList()
                    + (c.error() == null ? "" : " error=" + c.error())));
            assertThat(r.collections()).isNotEmpty();
        }
    }
}
