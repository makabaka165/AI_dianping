package com.hmdp.ai.integration;

import com.hmdp.ai.infrastructure.external.OutboundHttpRequest;
import com.hmdp.ai.infrastructure.external.SafeHttpClient;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("integration")
class CancellationPropagationIntegrationTest {
    @Test
    void cancellingRunClosesActiveHttpStreamAndCancellableInvocation() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            responseStarted.countDown();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        RunCancellationRegistry cancellations = new RunCancellationRegistry();
        cancellations.begin("run-cancel");
        AtomicBoolean customCancelled = new AtomicBoolean();
        cancellations.track("run-cancel", () -> customCancelled.set(true));
        SafeHttpClient client = new SafeHttpClient(cancellations);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OutboundHttpRequest request = new OutboundHttpRequest(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"),
                    "GET", Collections.emptyMap(), null, Duration.ofSeconds(30), 1024 * 1024,
                    Collections.singleton("text/event-stream"), true);
            Future<?> invocation = executor.submit(
                    () -> client.streamLines(request, "run-cancel", ignored -> { }));
            assertTrue(responseStarted.await(3, TimeUnit.SECONDS));

            cancellations.cancel("run-cancel");

            assertTrue(customCancelled.get());
            try {
                invocation.get(3, TimeUnit.SECONDS);
                fail("cancelled HTTP invocation must not complete successfully");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof java.util.concurrent.CancellationException);
            }
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }
}
