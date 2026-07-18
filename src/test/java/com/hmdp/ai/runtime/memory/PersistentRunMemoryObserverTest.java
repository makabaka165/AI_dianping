package com.hmdp.ai.runtime.memory;
import com.fasterxml.jackson.databind.ObjectMapper;import com.hmdp.ai.domain.memory.*;import com.hmdp.ai.domain.run.*;
import com.hmdp.ai.guard.PiiDetectionService;import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;
import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
class PersistentRunMemoryObserverTest {
    @Test void persistsOnlyExplicitNonSensitiveUserFact()throws Exception{MemoryRepository repository=mock(MemoryRepository.class);
        WorkingMemoryPort working=mock(WorkingMemoryPort.class);ObjectMapper mapper=new ObjectMapper();
        PersistentRunMemoryObserver observer=new PersistentRunMemoryObserver(repository,working,new PiiDetectionService(),mapper);
        AgentRunRecord run=run("{\"text\":\"请记住我喜欢安静的餐厅\"}");observer.onCompleted(run,output(mapper));
        verify(repository).recordCompletedRun(eq(run),contains("请记住"),anyString(),eq("done"),anyString(),anyString(),
                eq("我喜欢安静的餐厅"),any(Instant.class));verify(working).put(eq("tenant"),eq("workspace"),eq("run"),anyString(),eq(Duration.ofHours(24)));}
    @Test void rejectsPiiFromLongTermFactCandidate()throws Exception{MemoryRepository repository=mock(MemoryRepository.class);
        ObjectMapper mapper=new ObjectMapper();PersistentRunMemoryObserver observer=new PersistentRunMemoryObserver(repository,
                mock(WorkingMemoryPort.class),new PiiDetectionService(),mapper);AgentRunRecord run=run("{\"text\":\"请记住我的手机号是13800138000\"}");
        observer.onCompleted(run,output(mapper));verify(repository).recordCompletedRun(eq(run),anyString(),anyString(),anyString(),anyString(),anyString(),isNull(),isNull());}
    private AgentRunRecord run(String input){return new AgentRunRecord("run","tenant","workspace","user","session","conversation","agent",1,
            RunStatus.RUNNING,"BLOCKING",input,null,"{}","{}","{}","{}","trace",null,1,null,null,Instant.now(),Instant.now(),null,Instant.now().plusSeconds(60),Instant.now());}
    private String output(ObjectMapper mapper)throws Exception{return mapper.writeValueAsString(new AgentRunOutput("done",Collections.emptyList(),Collections.emptyList(),
            Collections.emptyList(),UsageSummary.empty(10),Collections.emptyList(),RunStatus.COMPLETED));}}
