package com.zinnia.regression.connector.eds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.connector.Connector;
import com.zinnia.regression.connector.ConnectorException;
import com.zinnia.regression.connector.ConnectorException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fetches a stored document through the API Gateway: resolve a pre-signed URL, then download it. Read-only by
 * construction: there is no update, replace or delete here. The pre-signed URL is a credential, so it is never
 * logged or put in an error message, and the gateway headers are never sent to it.
 */
@Component
@ConditionalOnExpression("!'${ngc.eds.base-url:}'.isEmpty()")
public class EdsClient implements Connector {

    private static final int MESSAGE_LIMIT = 200;

    private final RestClient gateway;
    private final RestClient storage;
    private final URI downloadUrlEndpoint;
    private final long maxBytes;
    private final ObjectMapper json;

    public EdsClient(@Value("${ngc.eds.base-url}") String baseUrl,
                     @Value("${ngc.eds.application-header:nextgen-correspondence-service}") String applicationHeader,
                     @Value("${ngc.eds.cookie:}") String cookie,
                     @Value("${ngc.eds.connect-timeout-ms:10000}") long connectTimeoutMs,
                     @Value("${ngc.eds.read-timeout-ms:60000}") long readTimeoutMs,
                     @Value("${ngc.eds.max-bytes:52428800}") long maxBytes,
                     ObjectMapper json) {
        this.downloadUrlEndpoint = endpoint(baseUrl);
        this.maxBytes = maxBytes;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient.Builder gatewayBuilder = RestClient.builder().requestFactory(factory)
                .defaultHeader("X-Application-Header", applicationHeader);
        if (cookie != null && !cookie.isBlank()) {
            gatewayBuilder.defaultHeader("Cookie", cookie);
        }
        this.gateway = gatewayBuilder.build();
        this.storage = RestClient.builder().requestFactory(factory).build();
    }

    static URI endpoint(String baseUrl) {
        String trimmed = baseUrl.strip();
        if (trimmed.contains("{") || trimmed.contains("}")) {
            throw new IllegalStateException("ngc.eds.base-url contains an unresolved placeholder");
        }
        URI uri = URI.create(trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
        if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme()) && !"localhost".equals(uri.getHost())) {
            throw new IllegalStateException("ngc.eds.base-url must be an https URL");
        }
        return URI.create(uri + "/eds/downloadUrl");
    }

    @Override
    public String id() {
        return "eds";
    }

    @Override
    public Status check() {
        return new Status(State.NOT_PROBED, "Gateway " + downloadUrlEndpoint.getHost()
                + " configured; reachability is only known when a document is fetched");
    }

    public EdsDocument fetch(String documentId) {
        EdsDocument downloaded = download(resolveDownloadUrl(documentId));
        return new EdsDocument(documentId, downloaded.contentType(), downloaded.filename(), downloaded.content());
    }

    URI resolveDownloadUrl(String documentId) {
        String body;
        try {
            body = gateway.post().uri(downloadUrlEndpoint).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("documentId", documentId, "correlationId", UUID.randomUUID().toString()))
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw rejected(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new ConnectorException(Kind.UNAVAILABLE, "Unable to reach the document service", e);
        }
        JsonNode payload = parse(body);
        String url = text(payload, "preSignedUrl", "url");
        if (url == null) {
            String reason = text(payload, "message");
            throw new ConnectorException(Kind.NOT_FOUND, "No download URL for document " + documentId
                    + (reason == null ? "" : ": " + shorten(reason)));
        }
        return secure(url);
    }

    EdsDocument download(URI url) {
        try {
            return storage.get().uri(url).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.isError()) {
                    throw rejected(status, null);
                }
                byte[] content = response.getBody().readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                if (content.length > maxBytes) {
                    throw new ConnectorException(Kind.TOO_LARGE, "Document is larger than " + maxBytes + " bytes");
                }
                MediaType type = response.getHeaders().getContentType();
                return new EdsDocument(null, type == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : type.toString(),
                        filename(response.getHeaders().getFirst("Content-Disposition"), url), content);
            });
        } catch (ConnectorException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ConnectorException(Kind.UNAVAILABLE, "Unable to download the document", e);
        }
    }

    private static URI secure(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(Kind.UNAVAILABLE, "The document service returned an invalid download URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ConnectorException(Kind.UNAVAILABLE, "The document service returned a download URL that is not https");
        }
        return uri;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new ConnectorException(Kind.UNAVAILABLE, "The document service returned an empty response");
        }
        try {
            JsonNode node = json.readTree(body);
            if (node != null && node.isTextual()) {
                node = json.readTree(node.textValue());
            }
            if (node == null || !node.isObject()) {
                throw new ConnectorException(Kind.UNAVAILABLE, "The document service returned an invalid response");
            }
            return node;
        } catch (IOException e) {
            throw new ConnectorException(Kind.UNAVAILABLE, "The document service returned an invalid response");
        }
    }

    private ConnectorException rejected(HttpStatusCode status, String body) {
        int code = status.value();
        Kind kind = code == 404 ? Kind.NOT_FOUND : code == 401 || code == 403 ? Kind.DENIED : Kind.UNAVAILABLE;
        String reason = null;
        try {
            reason = body == null || body.isBlank() ? null : text(parse(body), "message", "error", "detail");
        } catch (ConnectorException ignored) {
            reason = null;
        }
        return new ConnectorException(kind, "The document service answered " + code + (reason == null ? "" : ": " + shorten(reason)));
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MESSAGE_LIMIT ? oneLine.substring(0, MESSAGE_LIMIT) + "…" : oneLine;
    }

    static String filename(String contentDisposition, URI url) {
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            try {
                String name = ContentDisposition.parse(contentDisposition).getFilename();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (IllegalArgumentException ignored) {
                return fromPath(url);
            }
        }
        return fromPath(url);
    }

    private static String fromPath(URI url) {
        String path = url.getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? null : URLDecoder.decode(name, StandardCharsets.UTF_8);
    }
}
