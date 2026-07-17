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
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
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

    private AiTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AiTaskRepository();
        ReflectionTestUtils.setField(repository, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(repository, "bucketPrefix", "hmdp:ai:task:");
        ReflectionTestUtils.setField(repository, "resultTtlHours", 24L);
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
        when(redissonClient.<String>getSet("hmdp:ai:task:index:status:RUNNING")).thenReturn(runningIndex);

        repository.save(running);
        repository.save(success);

        verify(runningIndex).add("task-1");
        verify(runningIndex).remove("task-1");
        verify(redissonClient, never()).getSet("hmdp:ai:task:index:status:SUCCESS");
    }

    @Test
    void pendingTaskShouldBeAddedToRecoverableStatusIndex() {
        AiTask pending = task(AiTaskStatus.PENDING);
        when(redissonClient.<String>getBucket("hmdp:ai:task:task-1")).thenReturn(taskBucket);
        when(taskBucket.get()).thenReturn(null);
        when(redissonClient.<String>getSet("hmdp:ai:task:index:status:PENDING")).thenReturn(pendingIndex);

        repository.save(pending);

        verify(pendingIndex).add("task-1");
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
