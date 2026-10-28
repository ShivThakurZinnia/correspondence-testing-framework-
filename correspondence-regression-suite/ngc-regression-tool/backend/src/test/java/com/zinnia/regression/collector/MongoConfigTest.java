package com.zinnia.regression.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ReadPreference;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MongoConfigTest {

    @Test
    void clientGetsTheConfiguredTimeoutsAndReadPreference() {
        MongoClientSettings s = MongoConfig.settings("mongodb://user:pw@localhost/", 1500, 20000, 7000, "secondaryPreferred");

        assertThat(s.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1500);
        assertThat(s.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(20000);
        assertThat(s.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(7000);
        assertThat(s.getReadPreference()).isEqualTo(ReadPreference.secondaryPreferred());
    }

    @Test
    void credentialsAndHostFromTheUriAreKept() {
        MongoClientSettings s = MongoConfig.settings("mongodb://user:pw@db.example.test:27018/", 1, 1, 1, "primary");

        assertThat(s.getCredential().getUserName()).isEqualTo("user");
        assertThat(s.getClusterSettings().getHosts().get(0).getHost()).isEqualTo("db.example.test");
        assertThat(s.getReadPreference()).isEqualTo(ReadPreference.primary());
    }

    @Test
    void anUnknownReadPreferenceIsRefused() {
        assertThatThrownBy(() -> MongoConfig.settings("mongodb://localhost/", 1, 1, 1, "nearly"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeoutsAreRecognisedEvenWhenWrapped() {
        assertThat(MongoLimits.isTimeout(new MongoExecutionTimeoutException(50, "operation exceeded time limit"))).isTrue();
        assertThat(MongoLimits.isTimeout(new MongoTimeoutException("no server"))).isTrue();
        assertThat(MongoLimits.isTimeout(new CompletionException(new MongoExecutionTimeoutException(50, "x")))).isTrue();
        assertThat(MongoLimits.isTimeout(new IllegalStateException("boom"))).isFalse();
        assertThat(MongoLimits.isTimeout(null)).isFalse();
    }
}
