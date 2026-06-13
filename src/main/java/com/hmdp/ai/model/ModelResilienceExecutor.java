package com.hmdp.ai.model;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ModelResilienceExecutor {

    @Value("${hmdp.ai.model.timeout-seconds:30}")
    private long timeoutSeconds = 30L;

    @Value("${hmdp.ai.resilience.max-concurrent-calls:8}")
    private int maxConcurrentCalls = 8;

    @Value("${hmdp.ai.resilience.rate-limit-period-seconds:1}")
    private long rateLimitPeriodSeconds = 1L;

    @Value("${hmdp.ai.resilience.rate-limit-permits:2}")
    private int rateLimitPermits = 2;

    @Value("${hmdp.ai.resilience.executor-core-size:0}")
    private int executorCoreSize;

    @Value("${hmdp.ai.resilience.executor-max-size:0}")
    private int executorMaxSize;

    @Value("${hmdp.ai.resilience.executor-queue-capacity:16}")
    private int executorQueueCapacity = 16;

    private volatile CircuitBreaker circuitBreaker;
    private volatile Bulkhead bulkhead;
    private volatile RateLimiter rateLimiter;
    private volatile TimeLimiter timeLimiter;
    private volatile ExecutorService executorService;

    @PostConstruct
    void init() {
        ensureInitialized();
    }

    @PreDestroy
    void shutdown() {
        ExecutorService executor = executorService;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public <T> T execute(String operation, Callable<T> callable) throws Exception {
        ensureInitialized();
        Callable<T> decorated = Bulkhead.decorateCallable(bulkhead,
                CircuitBreaker.decorateCallable(circuitBreaker,
                        RateLimiter.decorateCallable(rateLimiter, callable)));
        return timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(() -> {
            try {
                return decorated.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ModelGatewayException(operation, e);
            }
        }, executorService));
    }

    public Flux<String> decorateStream(Flux<String> source) {
        ensureInitialized();
        return source
                .timeout(timeout())
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RateLimiterOperator.of(rateLimiter));
    }

    private void ensureInitialized() {
        if (circuitBreaker != null) {
            return;
        }
        synchronized (this) {
            if (circuitBreaker != null) {
                return;
            }
            Duration timeout = timeout();
            CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(5)
                    .waitDurationInOpenState(timeout.multipliedBy(2))
                    .build();
            BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                    .maxConcurrentCalls(Math.max(1, maxConcurrentCalls))
                    .maxWaitDuration(Duration.ZERO)
                    .build();
            RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                    .limitRefreshPeriod(Duration.ofSeconds(Math.max(1, rateLimitPeriodSeconds)))
                    .limitForPeriod(Math.max(1, rateLimitPermits))
                    .timeoutDuration(Duration.ZERO)
                    .build();
            TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                    .timeoutDuration(timeout)
                    .cancelRunningFuture(true)
                    .build();
            circuitBreaker = CircuitBreaker.of("shop-ai-model", circuitBreakerConfig);
            bulkhead = Bulkhead.of("shop-ai-model", bulkheadConfig);
            rateLimiter = RateLimiter.of("shop-ai-model", rateLimiterConfig);
            timeLimiter = TimeLimiter.of("shop-ai-model", timeLimiterConfig);
            executorService = boundedExecutor();
        }
    }

    private ExecutorService boundedExecutor() {
        int defaultSize = Math.max(1, maxConcurrentCalls);
        int core = executorCoreSize > 0 ? executorCoreSize : defaultSize;
        int max = executorMaxSize > 0 ? executorMaxSize : defaultSize;
        if (max < core) {
            max = core;
        }
        int queueCapacity = Math.max(0, executorQueueCapacity);
        BlockingQueue<Runnable> workQueue = queueCapacity == 0
                ? new SynchronousQueue<>()
                : new LinkedBlockingQueue<>(queueCapacity);
        return new ThreadPoolExecutor(
                core,
                max,
                30L,
                TimeUnit.SECONDS,
                workQueue,
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "shop-ai-model-gateway-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }
}
