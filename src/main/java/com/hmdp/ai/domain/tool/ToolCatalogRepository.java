package com.hmdp.ai.domain.tool;

import java.util.List;

public interface ToolCatalogRepository {
    ToolCatalogEntry create(ToolCatalogEntry tool, ToolVersionDraft version, String actorId);
    List<ToolCatalogEntry> findPage(String tenantId, String workspaceId, int offset, int limit);
    long count(String tenantId, String workspaceId);
}
