package com.hmdp.ai.api.knowledge;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.knowledge.CreateKnowledgeBaseRequest;
import com.hmdp.ai.application.dto.knowledge.CreateKnowledgeBaseVersionRequest;
import com.hmdp.ai.application.dto.knowledge.IngestionCreatedResponse;
import com.hmdp.ai.application.dto.knowledge.IngestionJobResponse;
import com.hmdp.ai.application.dto.knowledge.KnowledgeBaseResponse;
import com.hmdp.ai.application.dto.knowledge.KnowledgeBaseVersionResponse;
import com.hmdp.ai.application.dto.knowledge.KnowledgeSearchRequest;
import com.hmdp.ai.application.dto.knowledge.KnowledgeSearchResponse;
import com.hmdp.ai.application.knowledge.KnowledgeIngestionApplicationService;
import com.hmdp.ai.application.knowledge.KnowledgeSearchApplicationService;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.IOException;

@RestController
@Validated
public class KnowledgeController {
    private final KnowledgeIngestionApplicationService knowledge;
    private final KnowledgeSearchApplicationService search;

    public KnowledgeController(KnowledgeIngestionApplicationService knowledge,
                               KnowledgeSearchApplicationService search) {
        this.knowledge = knowledge;
        this.search = search;
    }

    @PostMapping("/api/v1/knowledge-bases")
    @RequireAiPermission(AiPermission.KNOWLEDGE_WRITE)
    public KnowledgeBaseResponse create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return knowledge.createKnowledgeBase(request);
    }

    @GetMapping("/api/v1/knowledge-bases")
    @RequireAiPermission(AiPermission.KNOWLEDGE_READ)
    public PageResponse<KnowledgeBaseResponse> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return knowledge.list(page, size);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/versions")
    @RequireAiPermission(AiPermission.KNOWLEDGE_WRITE)
    public KnowledgeBaseVersionResponse createVersion(@PathVariable @Size(max = 64) String id,
                                                      @Valid @RequestBody CreateKnowledgeBaseVersionRequest request) {
        return knowledge.createVersion(id, request);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/versions/{version}/publish")
    @RequireAiPermission(AiPermission.KNOWLEDGE_PUBLISH)
    public KnowledgeBaseVersionResponse publish(@PathVariable @Size(max = 64) String id,
                                                @PathVariable @Min(1) int version) {
        return knowledge.publishVersion(id, version);
    }

    @PostMapping(value = "/api/v1/knowledge-bases/{id}/documents", consumes = "multipart/form-data")
    @RequireAiPermission(AiPermission.KNOWLEDGE_WRITE)
    public IngestionCreatedResponse upload(@PathVariable @Size(max = 64) String id,
                                           @RequestParam(required = false) @Min(1) Integer knowledgeBaseVersion,
                                           @RequestParam(required = false) @Size(max = 512) String title,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        return knowledge.upload(id, knowledgeBaseVersion, title, fileName(file), file.getContentType(),
                file.getBytes());
    }

    @PostMapping(value = "/api/v1/documents/{id}/versions", consumes = "multipart/form-data")
    @RequireAiPermission(AiPermission.KNOWLEDGE_WRITE)
    public IngestionCreatedResponse uploadVersion(@PathVariable @Size(max = 64) String id,
                                                  @RequestParam(required = false) @Min(1) Integer knowledgeBaseVersion,
                                                  @RequestParam(required = false) @Size(max = 512) String title,
                                                  @RequestParam @NotBlank @Size(max = 1000) String changeNote,
                                                  @RequestPart("file") MultipartFile file) throws IOException {
        return knowledge.uploadDocumentVersion(id, knowledgeBaseVersion, title, changeNote, fileName(file),
                file.getContentType(), file.getBytes());
    }

    @PostMapping("/api/v1/documents/{id}/reindex")
    @RequireAiPermission(AiPermission.KNOWLEDGE_WRITE)
    public IngestionCreatedResponse reindex(@PathVariable @Size(max = 64) String id,
                                            @RequestParam(required = false) @Min(1) Integer knowledgeBaseVersion) {
        return knowledge.reindex(id, knowledgeBaseVersion);
    }

    @GetMapping("/api/v1/ingestion-jobs/{jobId}")
    @RequireAiPermission(AiPermission.KNOWLEDGE_READ)
    public IngestionJobResponse job(@PathVariable @Size(max = 64) String jobId) {
        return knowledge.job(jobId);
    }

    @DeleteMapping("/api/v1/documents/{id}")
    @RequireAiPermission(AiPermission.KNOWLEDGE_WRITE)
    public void delete(@PathVariable @Size(max = 64) String id) {
        knowledge.deleteDocument(id);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/search")
    @RequireAiPermission(AiPermission.KNOWLEDGE_READ)
    public KnowledgeSearchResponse search(@PathVariable @Size(max = 64) String id,
                                          @Valid @RequestBody KnowledgeSearchRequest request) {
        return search.search(id, request);
    }

    private String fileName(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file must not be empty");
        String name = file.getOriginalFilename();
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("file name is required");
        return name;
    }
}
