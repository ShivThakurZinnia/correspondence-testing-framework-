package com.zinnia.regression.connector.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.zinnia.regression.connector.Connector;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

class MongoConnectorTest {

    MongoDatabase db = mock(MongoDatabase.class);
    MongoClient client = mock(MongoClient.class);

    MongoConnector connector() {
        when(client.getDatabase("ngc")).thenReturn(db);
        return new MongoConnector(client, "ngc");
    }

    @Test
    void reportsOkWhenThePingAnswers() {
        MongoConnector c = connector();
        when(db.runCommand(any(Bson.class))).thenReturn(new Document("ok", 1));

        assertThat(c.id()).isEqualTo("mongo");
        assertThat(c.check().state()).isEqualTo(Connector.State.OK);
    }

    @Test
    void reportsFailedWithoutTheDriverMessage() {
        MongoConnector c = connector();
        when(db.runCommand(any(Bson.class))).thenThrow(new IllegalStateException("mongodb://user:pw@host"));

        Connector.Status s = c.check();

        assertThat(s.state()).isEqualTo(Connector.State.FAILED);
        assertThat(s.detail()).contains("IllegalStateException").doesNotContain("pw");
    }

    @Test
    void namesATimeoutAsSuch() {
        MongoConnector c = connector();
        when(db.runCommand(any(Bson.class))).thenThrow(new MongoExecutionTimeoutException(50, "slow"));

        assertThat(c.check().detail()).contains("timed out");
    }
}
