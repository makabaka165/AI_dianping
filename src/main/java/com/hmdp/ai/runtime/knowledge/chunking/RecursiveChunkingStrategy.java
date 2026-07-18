package com.hmdp.ai.runtime.knowledge.chunking;
import com.hmdp.ai.domain.knowledge.ChunkFragment;import com.hmdp.ai.domain.knowledge.ChunkingPolicy;import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;import org.springframework.stereotype.Component;import java.util.List;
@Component public class RecursiveChunkingStrategy implements ChunkingStrategy {public String code(){return "RECURSIVE";}public List<ChunkFragment> chunk(ParsedDocument d,ChunkingPolicy p){return ChunkingSupport.windows(d.getPlainText(),p,null,d.getTitle(),null,0);}}
