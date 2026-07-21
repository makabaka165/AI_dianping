package com.hmdp.ai.api.artifact;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.artifact.ArtifactApplicationService;
import com.hmdp.ai.domain.artifact.ArtifactContent;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/artifacts")
@RequireAiPermission(AiPermission.ARTIFACT_READ)
public class ArtifactController {
    private final ArtifactApplicationService service;

    public ArtifactController(ArtifactApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id) {
        ArtifactContent value = service.download(id);
        String safeName = value.getRecord().getName().replaceAll("[\\r\\n\"]", "_");
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(value.getRecord().getContentType()))
                .contentLength(value.getRecord().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(value.getContent()));
    }
}
