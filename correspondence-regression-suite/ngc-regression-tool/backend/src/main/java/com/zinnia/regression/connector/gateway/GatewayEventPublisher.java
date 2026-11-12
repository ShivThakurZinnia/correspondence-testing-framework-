package com.zinnia.regression.connector.gateway;

import com.zinnia.regression.connector.Connector;
import com.zinnia.regression.connector.kafka.EventPublisher;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Publishes events through the environment's API Gateway ({@code POST /api/v1/events/publish?topicName=...}) instead of
 * talking to Kafka directly, so a developer machine only needs HTTPS access to the gateway. When a gateway base URL is
 * set it replaces the direct Kafka publisher. The optional cookie is only a load-balancer stickiness value; it is sent
 * to the gateway and never logged or put in an error message.
 */
@Component
@ConditionalOnExpression("!'${ngc.gateway.publish-base-url:}'.isEmpty()")
public class GatewayEventPublisher implements EventPublisher, Connector {

    static final String PATH = "/api/v1/events/publish";

    private final String base;
    private final String applicationHeader;
    private final String cookie;
    private final Duration readTimeout;
    private final HttpClient http;

    public GatewayEventPublisher(@Value("${ngc.gateway.publish-base-url}") String baseUrl,
                                 @Value("${ngc.gateway.application-header:nextgen-correspondence-service}") String applicationHeader,
                                 @Value("${ngc.gateway.cookie:}") String cookie,
                                 @Value("${ngc.gateway.connect-timeout-ms:10000}") long connectTimeoutMs,
                                 @Value("${ngc.gateway.read-timeout-ms:60000}") long readTimeoutMs) {
        this.base = normalize(baseUrl);
        this.applicationHeader = applicationHeader;
        this.cookie = cookie == null || cookie.isBlank() ? null : cookie.strip();
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    }

    /** https only (plain http is allowed for localhost so tests and local stubs work); trailing slash dropped. */
    static String normalize(String baseUrl) {
        String trimmed = baseUrl.strip();
        if (trimmed.contains("${")) {
            throw new IllegalStateException("ngc.gateway.publish-base-url contains an unresolved placeholder");
        }
        URI uri = URI.create(trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(local && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException("ngc.gateway.publish-base-url must be an https URL");
        }
        return uri.toString();
    }

    URI endpoint(String topic) {
        return URI.create(base + PATH + "?topicName=" + URLEncoder.encode(topic, StandardCharsets.UTF_8));
    }

    @Override
    public String id() {
        return "gateway";
    }

    @Override
    public Status check() {
        return new Status(State.NOT_PROBED, "Publishes through " + URI.create(base).getHost()
                + "; verified only when a run publishes, because a probe would send an event");
    }

    @Override
    public PublishResult publish(String topic, String key, String value) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(topic)).timeout(readTimeout)
                .header("Content-Type", "application/json").header("X-Application-Header", applicationHeader)
                .POST(HttpRequest.BodyPublishers.ofString(value));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Gateway publish to " + topic + " failed: HTTP " + response.statusCode()
                        + (response.statusCode() == 401 || response.statusCode() == 403 ? " (the gateway refused the call; it may need the cookie)" : ""));
            }
            // The gateway does not report a partition or offset.
            return new PublishResult(-1, -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gateway publish to " + topic + " was interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Gateway publish to " + topic + " failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
