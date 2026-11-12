package com.zinnia.regression.connector.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayEventPublisherTest {

    HttpServer server;
    final AtomicReference<String> uri = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> appHeader = new AtomicReference<>();
    final AtomicReference<String> cookie = new AtomicReference<>();
    int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            uri.set(ex.getRequestURI().toString());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            appHeader.set(ex.getRequestHeaders().getFirst("X-Application-Header"));
            cookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GatewayEventPublisher publisher(String cookieValue) {
        return new GatewayEventPublisher("http://127.0.0.1:" + server.getAddress().getPort() + "/apigateway",
                "nextgen-correspondence-service", cookieValue, 2000, 5000);
    }

    @Test
    void postsThePayloadToTheTopicWithTheApplicationHeaderAndNoCookieByDefault() {
        var result = publisher("").publish("zinnia.correspondence.sor", "key", "{\"id\":\"1\"}");

        assertThat(uri.get()).isEqualTo("/apigateway/api/v1/events/publish?topicName=zinnia.correspondence.sor");
        assertThat(body.get()).isEqualTo("{\"id\":\"1\"}");
        assertThat(appHeader.get()).isEqualTo("nextgen-correspondence-service");
        assertThat(cookie.get()).isNull();
        assertThat(result.partition()).isNegative();
    }

    @Test
    void sendsTheCookieWhenOneIsConfigured() {
        publisher("AWSALB=abc").publish("t", "k", "{}");
        assertThat(cookie.get()).isEqualTo("AWSALB=abc");
    }

    @Test
    void aRefusedPublishFailsWithTheStatusAndNeverTheCookie() {
        status = 403;
        assertThatThrownBy(() -> publisher("AWSALB=secret-value").publish("t", "k", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 403").hasMessageNotContaining("secret-value");
        status = 500;
        assertThatThrownBy(() -> publisher("").publish("t", "k", "{}")).hasMessageContaining("HTTP 500");
    }

    @Test
    void plainHttpIsOnlyAllowedForLocalhost() {
        assertThatThrownBy(() -> new GatewayEventPublisher("http://correspondence-dev.zinnia.com/apigateway", "h", "", 1000, 1000))
                .hasMessageContaining("https");
        assertThat(GatewayEventPublisher.normalize("https://correspondence-dev.zinnia.com/apigateway/"))
                .isEqualTo("https://correspondence-dev.zinnia.com/apigateway");
    }
}
