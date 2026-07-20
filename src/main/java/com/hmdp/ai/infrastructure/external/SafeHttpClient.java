package com.hmdp.ai.infrastructure.external;

import com.hmdp.ai.runtime.cancellation.CancellableInvocation;
import com.hmdp.ai.runtime.cancellation.CancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class SafeHttpClient {
    private final HttpClient client;
    private final RunCancellationRegistry cancellations;

    public SafeHttpClient() {
        this(defaultClient(), null);
    }

    @Autowired
    public SafeHttpClient(RunCancellationRegistry cancellations) {
        this(defaultClient(), cancellations);
    }

    SafeHttpClient(HttpClient client) {
        this(client, null);
    }

    SafeHttpClient(HttpClient client, RunCancellationRegistry cancellations) {
        this.client = client;
        this.cancellations = cancellations;
    }

    public OutboundHttpResponse execute(OutboundHttpRequest request) {
        return execute(request, null);
    }

    public OutboundHttpResponse execute(OutboundHttpRequest request, String runId) {
        try {
            HttpResponse<InputStream> response = send(request, runId);
            validateResponse(request, response);
            InputStream body = response.body();
            CancellableInvocation stream = close(body);
            track(runId, stream);
            try {
                byte[] bytes = readLimited(body, request.getMaxResponseBytes(), runId);
                return new OutboundHttpResponse(response.statusCode(), contentType(response), bytes,
                        response.headers().map());
            } finally {
                untrack(runId, stream);
            }
        } catch (IllegalArgumentException | java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throwIfCancelled(runId);
            throw new IllegalStateException("HTTP_REQUEST_FAILED", e);
        }
    }

    public OutboundHttpResponse streamLines(OutboundHttpRequest request, String runId, Consumer<String> consumer) {
        try {
            HttpResponse<InputStream> response = send(request, runId);
            validateResponse(request, response);
            InputStream body = response.body();
            CancellableInvocation stream = close(body);
            track(runId, stream);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                int total = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    throwIfCancelled(runId);
                    total += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (total > request.getMaxResponseBytes()) {
                        throw new IllegalArgumentException("HTTP_RESPONSE_TOO_LARGE");
                    }
                    consumer.accept(line);
                }
                return new OutboundHttpResponse(response.statusCode(), contentType(response), new byte[0],
                        response.headers().map());
            } finally {
                untrack(runId, stream);
            }
        } catch (IllegalArgumentException | java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throwIfCancelled(runId);
            throw new IllegalStateException("HTTP_REQUEST_FAILED", e);
        }
    }

    void validateUri(URI uri, boolean allowPrivateNetwork) {
        if (uri == null || uri.getHost() == null) throw new IllegalArgumentException("HTTP_URL_INVALID");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("HTTP_SCHEME_NOT_ALLOWED");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("HTTP_USERINFO_NOT_ALLOWED");
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!allowPrivateNetwork && (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress())) {
                    throw new IllegalArgumentException("HTTP_PRIVATE_ADDRESS_NOT_ALLOWED");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("HTTP_HOST_RESOLUTION_FAILED", e);
        }
    }

    private HttpResponse<InputStream> send(OutboundHttpRequest request, String runId) throws Exception {
        validateUri(request.getUri(), request.isAllowPrivateNetwork());
        throwIfCancelled(runId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.getUri()).timeout(request.getTimeout());
        request.getHeaders().forEach(builder::header);
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher body = request.getBody().length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.getBody());
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(
                builder.method(method, body).build(), HttpResponse.BodyHandlers.ofInputStream());
        track(runId, future);
        try {
            return future.get(Math.max(1, request.getTimeout().toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
        } finally {
            untrack(runId, future);
        }
    }

    private void validateResponse(OutboundHttpRequest request, HttpResponse<?> response) {
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new IllegalArgumentException("HTTP_REDIRECT_NOT_ALLOWED");
        }
        String contentType = contentType(response);
        if (!request.getAllowedContentTypes().isEmpty()
                && request.getAllowedContentTypes().stream().map(value -> value.toLowerCase(Locale.ROOT))
                .noneMatch(contentType::equals)) {
            throw new IllegalArgumentException("HTTP_CONTENT_TYPE_NOT_ALLOWED");
        }
    }

    private String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("application/octet-stream")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private byte[] readLimited(InputStream input, int max, String runId) throws Exception {
        int limit = Math.max(1, max);
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                throwIfCancelled(runId);
                total += read;
                if (total > limit) throw new IllegalArgumentException("HTTP_RESPONSE_TOO_LARGE");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private CancellableInvocation close(InputStream stream) {
        return () -> {
            try {
                stream.close();
            } catch (Exception ignored) {
                // Closing is best effort during cancellation.
            }
        };
    }

    private void throwIfCancelled(String runId) {
        CancellationToken token = cancellations == null || runId == null ? null : cancellations.token(runId);
        if (token != null) token.throwIfCancelled();
    }

    private void track(String runId, CompletableFuture<?> future) {
        if (cancellations != null && runId != null) cancellations.track(runId, future);
    }

    private void untrack(String runId, CompletableFuture<?> future) {
        if (cancellations != null && runId != null) cancellations.untrack(runId, future);
    }

    private void track(String runId, CancellableInvocation invocation) {
        if (cancellations != null && runId != null) cancellations.track(runId, invocation);
    }

    private void untrack(String runId, CancellableInvocation invocation) {
        if (cancellations != null && runId != null) cancellations.untrack(runId, invocation);
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
