package com.zinnia.regression.connector.eds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zinnia.regression.connector.Connector;
import com.zinnia.regression.connector.ConnectorException;
import com.zinnia.regression.connector.ConnectorException.Kind;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EdsClientTest {

    static final String SIGNED = "https://bucket.s3.amazonaws.com/docs/a.pdf?X-Amz-Signature=secret";

    final AtomicReference<HttpHandler> gatewayHandler = new AtomicReference<>();
    final AtomicReference<HttpHandler> storageHandler = new AtomicReference<>();
    HttpServer server;
    String base;
    final AtomicReference<String> gatewayBody = new AtomicReference<>();
    final AtomicReference<String> gatewayApp = new AtomicReference<>();
    final AtomicReference<String> gatewayCookie = new AtomicReference<>();
    final AtomicReference<String> storageCookie = new AtomicReference<>("untouched");
    final AtomicReference<String> storageApp = new AtomicReference<>("untouched");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/apigateway/api/v1/eds/downloadUrl", ex -> gatewayHandler.get().handle(ex));
        server.createContext("/docs", ex -> storageHandler.get().handle(ex));
        server.start();
        base = "http://localhost:" + server.getAddress().getPort() + "/apigateway/api/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    EdsClient client(long maxBytes) {
        return new EdsClient(base, "test-app", "sid=1", 2000, 2000, maxBytes, new ObjectMapper());
    }

    void gateway(int status, String body) {
        gatewayHandler.set(ex -> {
            gatewayBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            gatewayApp.set(ex.getRequestHeaders().getFirst("X-Application-Header"));
            gatewayCookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            reply(ex, status, "application/json", body.getBytes(StandardCharsets.UTF_8), null);
        });
    }

    void storage(int status, byte[] body, String disposition) {
        storageHandler.set(ex -> {
            storageCookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            storageApp.set(ex.getRequestHeaders().getFirst("X-Application-Header"));
            reply(ex, status, "application/pdf", body, disposition);
        });
    }

    static void reply(HttpExchange ex, int status, String type, byte[] body, String disposition) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        if (disposition != null) {
            ex.getResponseHeaders().add("Content-Disposition", disposition);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    URI local(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    @Test
    void endpointAppendsTheDownloadPathAndInsistsOnHttps() {
        assertThat(EdsClient.endpoint("https://h.example/api/v1/")).hasToString("https://h.example/api/v1/eds/downloadUrl");
        assertThatThrownBy(() -> EdsClient.endpoint("http://h.example/api")).hasMessageContaining("https");
        assertThatThrownBy(() -> EdsClient.endpoint("https://${HOST}/api")).hasMessageContaining("placeholder");
        assertThatThrownBy(() -> EdsClient.endpoint("not a url")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void resolvesAPreSignedUrlSendingTheGatewayHeadersAndAFreshCorrelationId() {
        gateway(200, "{\"preSignedUrl\":\"" + SIGNED + "\",\"success\":true}");

        URI url = client(1000).resolveDownloadUrl("DOC-1");

        assertThat(url).hasToString(SIGNED);
        assertThat(gatewayApp.get()).isEqualTo("test-app");
        assertThat(gatewayCookie.get()).isEqualTo("sid=1");
        assertThat(gatewayBody.get()).contains("\"documentId\":\"DOC-1\"");
        String correlation = gatewayBody.get().replaceAll(".*\"correlationId\":\"([^\"]+)\".*", "$1");
        assertThat(UUID.fromString(correlation)).isNotNull();
    }

    @Test
    void acceptsTheUrlAliasAndAJsonStringWrappedResponse() {
        gateway(200, new ObjectMapper().valueToTree("{\"url\":\"" + SIGNED + "\"}").toString());

        assertThat(client(1000).resolveDownloadUrl("DOC-1")).hasToString(SIGNED);
    }

    @Test
    void anAnswerWithoutAUrlIsNotFoundAndKeepsTheReason() {
        gateway(200, "{\"success\":false,\"message\":\"Document\\n not   found\"}");

        assertThatThrownBy(() -> client(1000).resolveDownloadUrl("DOC-9"))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND);
                    assertThat(e.getMessage()).contains("DOC-9", "Document not found");
                });
    }

    @Test
    void gatewayStatusesMapToKinds() {
        for (Object[] c : new Object[][] {{404, Kind.NOT_FOUND}, {401, Kind.DENIED}, {403, Kind.DENIED}, {500, Kind.UNAVAILABLE}}) {
            gateway((int) c[0], "{\"message\":\"nope\"}");
            assertThatThrownBy(() -> client(1000).resolveDownloadUrl("D"))
                    .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(c[1]))
                    .hasMessageContaining(String.valueOf(c[0]));
        }
    }

    @Test
    void anEmptyOrUnstructuredAnswerIsUnavailable() {
        gateway(200, "");
        assertThatThrownBy(() -> client(1000).resolveDownloadUrl("D"))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        gateway(200, "[1,2]");
        assertThatThrownBy(() -> client(1000).resolveDownloadUrl("D")).hasMessageContaining("invalid response");
        gateway(200, "not json");
        assertThatThrownBy(() -> client(1000).resolveDownloadUrl("D")).hasMessageContaining("invalid response");
    }

    @Test
    void refusesADownloadUrlThatIsNotHttps() {
        gateway(200, "{\"preSignedUrl\":\"http://bucket.example/a.pdf?sig=secret\"}");

        assertThatThrownBy(() -> client(1000).resolveDownloadUrl("D"))
                .hasMessageContaining("not https").hasMessageNotContaining("secret");
        gateway(200, "{\"preSignedUrl\":\"https://bad host/a\"}");
        assertThatThrownBy(() -> client(1000).resolveDownloadUrl("D")).hasMessageContaining("invalid download URL");
    }

    @Test
    void downloadsBytesWithoutSendingGatewayHeadersToStorage() {
        storage(200, "%PDF-1.4 body".getBytes(StandardCharsets.UTF_8), "attachment; filename=\"letter.pdf\"");

        EdsDocument d = client(1000).download(local("/docs/ignored.pdf"));

        assertThat(new String(d.content(), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4 body");
        assertThat(d.contentType()).isEqualTo("application/pdf");
        assertThat(d.filename()).isEqualTo("letter.pdf");
        assertThat(storageCookie.get()).isNull();
        assertThat(storageApp.get()).isNull();
    }

    @Test
    void theFileNameFallsBackToTheUrlPath() {
        assertThat(EdsClient.filename(null, URI.create("https://h/docs/My%20Letter.pdf?sig=1"))).isEqualTo("My Letter.pdf");
        assertThat(EdsClient.filename("garbage;;;", URI.create("https://h/docs/x.pdf"))).isEqualTo("x.pdf");
        assertThat(EdsClient.filename("attachment", URI.create("https://h/"))).isNull();
        assertThat(EdsClient.filename("", URI.create("https://h"))).isNull();
    }

    @Test
    void aDocumentOverTheLimitIsRejectedAndOneAtTheLimitIsKept() {
        storage(200, new byte[11], null);
        assertThatThrownBy(() -> client(10).download(local("/docs/a.pdf")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TOO_LARGE));
        storage(200, new byte[10], null);
        assertThat(client(10).download(local("/docs/a.pdf")).content()).hasSize(10);
    }

    @Test
    void storageErrorsMapToKindsWithoutLeakingTheUrl() {
        storage(403, new byte[0], null);
        assertThatThrownBy(() -> client(10).download(local("/docs/a.pdf?X-Amz-Signature=secret")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DENIED))
                .hasMessageNotContaining("secret");
    }

    @Test
    void anUnreachableServiceIsUnavailable() {
        server.stop(0);
        assertThatThrownBy(() -> client(10).download(local("/docs/a.pdf")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        assertThatThrownBy(() -> client(10).resolveDownloadUrl("D"))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    @Test
    void fetchResolvesThenDownloadsAndReportsItself() {
        gateway(200, "{\"preSignedUrl\":\"http://localhost:" + server.getAddress().getPort() + "/docs/a.pdf\"}");
        assertThatThrownBy(() -> client(10).fetch("D")).hasMessageContaining("not https");

        EdsClient c = client(10);
        assertThat(c.id()).isEqualTo("eds");
        assertThat(c.check().state()).isEqualTo(Connector.State.NOT_PROBED);
        assertThat(c.check().detail()).contains("localhost");
    }
}
