package com.zinnia.regression.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.zinnia.regression.config.WiredEnvironment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectorControllerTest {

    record Fake(String id, Connector.Status status, boolean throwing) implements Connector {
        @Override
        public Status check() {
            if (throwing) {
                throw new IllegalStateException("secret detail");
            }
            return status;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, String>> rows(Map<String, Object> body) {
        return (List<Map<String, String>>) body.get("connectors");
    }

    @Test
    void everyKnownConnectorAppearsAndMissingOnesAreNotConfigured() {
        ConnectorController controller = new ConnectorController(List.of(
                new Fake("mongo", new Connector.Status(Connector.State.OK, "up"), false),
                new Fake("s3", new Connector.Status(Connector.State.FAILED, "down"), false)), new WiredEnvironment("dev"));

        Map<String, Object> body = controller.list();

        assertThat(body.get("environment")).isEqualTo("dev");
        assertThat(rows(body)).extracting(r -> r.get("id")).containsExactly("mongo", "s3", "eds", "kafka", "gateway");
        assertThat(rows(body)).extracting(r -> r.get("state")).containsExactly("ok", "failed", "not_configured", "not_configured", "not_configured");
        assertThat(rows(body).get(0).get("detail")).isEqualTo("up");
    }

    @Test
    void aNewConnectorIsListedAndAThrowingCheckBecomesFailedWithoutItsMessage() {
        ConnectorController controller = new ConnectorController(List.of(
                new Fake("sftp", null, true),
                new Fake("kafka", new Connector.Status(Connector.State.NOT_PROBED, "configured"), false)), new WiredEnvironment(""));

        Map<String, Object> body = controller.list();

        assertThat(body.get("environment")).isEqualTo("");
        assertThat(rows(body)).extracting(r -> r.get("id")).containsExactly("mongo", "s3", "eds", "kafka", "gateway", "sftp");
        Map<String, String> sftp = rows(body).get(5);
        assertThat(sftp.get("state")).isEqualTo("failed");
        assertThat(sftp.get("detail")).contains("IllegalStateException").doesNotContain("secret");
        assertThat(rows(body).get(3).get("state")).isEqualTo("not_probed");
    }
}
