package com.hmdp.ai.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RBucketAsync;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RSetAsync;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskRepositoryTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> taskBucket;

    @Mock
    private RSet<String> runningIndex;

    @Mock
    private RSet<String> pendingIndex;

    @Mock
    private RLock executionLock;

    @Mock
    private RBatch batch;

    @Mock
    private RBucketAsync<String> batchTaskBucket;

    @Mock
    private RSetAsync<String> batchRunningIndex;

    @Mock
    private RSetAsync<String> batchPendingIndex;

    private AiTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AiTaskRepository();
        ReflectionTestUtils.setField(repository, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(repository, "bucketPrefix", "hmdp:ai:task:");
        ReflectionTestUtils.setField(repository, "resultTtlHours", 24L);
        lenient().when(redissonClient.createBatch(any(BatchOptions.class))).thenReturn(batch);
        lenient().when(batch.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(batchTaskBucket);
        lenient().when(batch.<String>getSet("hmdp:ai:task:index:status:RUNNING"))
                .thenReturn(batchRunningIndex);
        lenient().when(batch.<String>getSet("hmdp:ai:task:index:status:PENDING"))
                .thenReturn(batchPendingIndex);
    }

    @Test
    void findShouldExposeRedisFailureInsteadOfPretendingTaskIsMissing() {
        when(redissonClient.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(taskBucket);
        when(taskBucket.get()).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> repository.find("task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Read AI task failed");
    }

    @Test
    void findShouldTreatCorruptTaskJsonAsUnrecoverableInsteadOfRedisFailure() {
        when(redissonClient.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(taskBucket);
        when(taskBucket.get()).thenReturn("{not-json");

        assertThat(repository.find("task-1")).isEmpty();
    }

    @Test
    void findByStatusShouldRemoveCorruptTaskFromRunningIndex() {
        when(redissonClient.<String>getSet("hmdp:ai:task:index:status:RUNNING")).thenReturn(runningIndex);
        when(runningIndex.iterator()).thenReturn(List.of("task-1").iterator());
        when(redissonClient.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(taskBucket);
        when(taskBucket.get()).thenReturn("{not-json");

        assertThat(repository.findByStatus(AiTaskStatus.RUNNING, 10)).isEmpty();
        verify(runningIndex).remove("task-1");
    }

    @Test
    void terminalTaskShouldBeRemovedFromRunningIndexWithoutCreatingTerminalIndex() throws Exception {
        AiTask running = task(AiTaskStatus.RUNNING);
        AiTask success = task(AiTaskStatus.SUCCESS);
        String runningJson = new ObjectMapper().writeValueAsString(running);
        when(redissonClient.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(taskBucket);
        when(taskBucket.get()).thenReturn(null, runningJson);

        repository.save(running);
        repository.save(success);

        verify(batchRunningIndex).addAsync("task-1");
        verify(batchRunningIndex).removeAsync("task-1");
        verify(batch, times(2)).execute();
        verify(batch, never()).getSet("hmdp:ai:task:index:status:SUCCESS");
    }

    @Test
    void pendingTaskShouldBeAddedToRecoverableStatusIndex() {
        AiTask pending = task(AiTaskStatus.PENDING);
        when(redissonClient.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(taskBucket);
        when(taskBucket.get()).thenReturn(null);

        repository.save(pending);

        verify(batchTaskBucket).setAsync(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(24L),
                org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.HOURS));
        verify(batchPendingIndex).addAsync("task-1");
        verify(batch).execute();
    }

    @Test
    void clearInflightShouldDeleteOnlyMatchingTaskRegistration() {
        when(redissonClient.<String>getBucket("hmdp:ai:task:inflight:dedup-1")).thenReturn(taskBucket);
        when(taskBucket.compareAndSet("task-1", null)).thenReturn(true);

        assertThat(repository.clearInflight("dedup-1", "task-1")).isTrue();

        verify(taskBucket).compareAndSet("task-1", null);
        verify(taskBucket, never()).delete();
    }

    @Test
    void tryRegisterInflightShouldRetryWhenPreviousMarkerDisappears() {
        ReflectionTestUtils.setField(repository, "resultTtlHours", 0L);
        when(redissonClient.<String>getBucket("hmdp:ai:task:inflight:dedup-1")).thenReturn(taskBucket);
        when(taskBucket.trySet("task-1", 1L, java.util.concurrent.TimeUnit.HOURS))
                .thenReturn(false, true);
        when(taskBucket.get()).thenReturn(null);

        assertThat(repository.tryRegisterInflight("dedup-1", "task-1")).isEmpty();

        verify(taskBucket, times(2)).trySet("task-1", 1L, java.util.concurrent.TimeUnit.HOURS);
    }

    @Test
    void tryRegisterInflightShouldFailClosedAfterRepeatedDisappearingMarkers() {
        when(redissonClient.<String>getBucket("hmdp:ai:task:inflight:dedup-1")).thenReturn(taskBucket);
        when(taskBucket.trySet("task-1", 24L, java.util.concurrent.TimeUnit.HOURS)).thenReturn(false);
        when(taskBucket.get()).thenReturn(null);

        assertThatThrownBy(() -> repository.tryRegisterInflight("dedup-1", "task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concurrent updates");

        verify(taskBucket, times(3)).trySet("task-1", 24L, java.util.concurrent.TimeUnit.HOURS);
    }

    @Test
    void clearInflightShouldKeepNewerTaskRegistration() {
        when(redissonClient.<String>getBucket("hmdp:ai:task:inflight:dedup-1")).thenReturn(taskBucket);
        when(taskBucket.compareAndSet("task-1", null)).thenReturn(false);

        assertThat(repository.clearInflight("dedup-1", "task-1")).isFalse();

        verify(taskBucket).compareAndSet("task-1", null);
        verify(taskBucket, never()).delete();
    }

    @Test
    void executionLockShouldUseTaskScopedKey() {
        when(redissonClient.getLock("hmdp:ai:task:lock:task-1")).thenReturn(executionLock);

        repository.executionLock("task-1");

        verify(redissonClient).getLock("hmdp:ai:task:lock:task-1");
    }

    private AiTask task(AiTaskStatus status) {
        return AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.RAG_REBUILD_SHOP)
                .status(status)
                .dedupKey("dedup-1")
                .createdAtEpochMillis(1L)
                .updatedAtEpochMillis(2L)
                .build();
    }
}
