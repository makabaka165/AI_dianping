package com.hmdp.ai.api.memory;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.memory.ConversationResponse;
import com.hmdp.ai.application.dto.memory.DeleteMemoriesResponse;
import com.hmdp.ai.application.dto.memory.MemoryFactResponse;
import com.hmdp.ai.application.dto.memory.MemoryPreferenceRequest;
import com.hmdp.ai.application.dto.memory.UpdateMemoryFactRequest;
import com.hmdp.ai.application.memory.MemoryApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
@Validated
public class MemoryController {
    private final MemoryApplicationService memory;

    public MemoryController(MemoryApplicationService memory) {
        this.memory = memory;
    }

    @GetMapping("/conversations/{id}")
    @RequireAiPermission(AiPermission.MEMORY_READ)
    public ConversationResponse conversation(@PathVariable @Size(max = 64) String id,
                                             @RequestParam(defaultValue = "1") @Min(1) int page,
                                             @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        return memory.conversation(id, page, size);
    }

    @GetMapping("/memories")
    @RequireAiPermission(AiPermission.MEMORY_READ)
    public PageResponse<MemoryFactResponse> facts(@RequestParam(defaultValue = "1") @Min(1) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return memory.facts(page, size);
    }

    @PostMapping("/memories/{id}/confirm")
    @RequireAiPermission(AiPermission.MEMORY_MANAGE)
    public MemoryFactResponse confirm(@PathVariable @Size(max = 64) String id) {
        return memory.confirm(id);
    }

    @PutMapping("/memories/{id}")
    @RequireAiPermission(AiPermission.MEMORY_MANAGE)
    public MemoryFactResponse correct(@PathVariable @Size(max = 64) String id,
                                      @Valid @RequestBody UpdateMemoryFactRequest request) {
        return memory.correct(id, request);
    }

    @DeleteMapping("/memories/{id}")
    @RequireAiPermission(AiPermission.MEMORY_DELETE)
    public void delete(@PathVariable @Size(max = 64) String id) { memory.delete(id); }

    @DeleteMapping("/memories")
    @RequireAiPermission(AiPermission.MEMORY_DELETE)
    public DeleteMemoriesResponse deleteAll() { return new DeleteMemoriesResponse(memory.deleteAll()); }

    @PostMapping("/memories/preferences")
    @RequireAiPermission(AiPermission.MEMORY_MANAGE)
    public void preference(@RequestBody MemoryPreferenceRequest request) {
        memory.setEnabled(request.isEnabled());
    }
}
