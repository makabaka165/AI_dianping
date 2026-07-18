package com.hmdp.ai.runtime.knowledge.chunking;
import com.hmdp.ai.domain.knowledge.ChunkFragment;import com.hmdp.ai.domain.knowledge.ChunkingPolicy;import com.hmdp.ai.domain.knowledge.parsing.ParsedDocument;import java.util.List;
public interface ChunkingStrategy {String code();List<ChunkFragment> chunk(ParsedDocument document,ChunkingPolicy policy);}
