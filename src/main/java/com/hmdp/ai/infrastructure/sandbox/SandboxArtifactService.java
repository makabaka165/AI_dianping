package com.hmdp.ai.infrastructure.sandbox;

import com.hmdp.ai.domain.artifact.ArtifactReference;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class SandboxArtifactService {
    private final ObjectStoragePort storage;
    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;

    public SandboxArtifactService(ObjectStoragePort storage, JdbcTemplate jdbc, AiIdGenerator ids) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.ids = ids;
    }

    public List<ArtifactReference> store(ExecutionContext context, Path workspace, long maxBytes) {
        return store(context, workspace, maxBytes, 20);
    }

    public List<ArtifactReference> store(ExecutionContext context, Path workspace, long maxBytes, int maxCount) {
        List<ArtifactReference> result = new ArrayList<>();
        long total = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(workspace)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (result.size() >= maxCount) {
                    throw new IllegalArgumentException("SANDBOX_ARTIFACT_COUNT_EXCEEDED");
                }
                byte[] bytes = Files.readAllBytes(file);
                total += bytes.length;
                if (total > maxBytes) throw new IllegalArgumentException("SANDBOX_ARTIFACT_BUDGET_EXCEEDED");
                String name = workspace.relativize(file).toString().replace('\\', '/');
                String sha = sha(bytes);
                String contentType = Files.probeContentType(file);
                if (contentType == null) contentType = "application/octet-stream";
                StoredObject object = storage.put(context.getTenantId(), "artifacts-" + context.getRunId(),
                        name, contentType, bytes, sha);
                String id = ids.nextId();
                jdbc.update("insert into ai_artifact (id,tenant_id,workspace_id,run_id,node_run_id,artifact_type," +
                                "name,content_type,object_key,size_bytes,sha256,metadata_json,expires_at,status," +
                                "created_by,updated_by) values (?,?,?,?,?,'FILE',?,?,?,?,?,'{}',?,'ACTIVE',?,?)",
                        id, context.getTenantId(), context.getWorkspaceId(), context.getRunId(), null, name,
                        contentType, object.getObjectKey(), bytes.length, sha,
                        Timestamp.from(Instant.now().plusSeconds(86400)), context.getUserId(), context.getUserId());
                result.add(new ArtifactReference(id, name, contentType, bytes.length,
                        "/api/v1/artifacts/" + id));
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SANDBOX_ARTIFACT_STORE_FAILED", e);
        }
    }

    private String sha(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
