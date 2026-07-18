package com.hmdp.ai.domain.artifact;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ArtifactReference {
    private final String artifactId;
    private final String name;
    private final String contentType;
    private final long sizeBytes;
    private final String downloadPath;

    @JsonCreator
    public ArtifactReference(@JsonProperty("artifactId") String artifactId,
                             @JsonProperty("name") String name,
                             @JsonProperty("contentType") String contentType,
                             @JsonProperty("sizeBytes") long sizeBytes,
                             @JsonProperty("downloadPath") String downloadPath) {
        this.artifactId = artifactId;
        this.name = name;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.downloadPath = downloadPath;
    }

    public String getArtifactId() { return artifactId; }
    public String getName() { return name; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getDownloadPath() { return downloadPath; }
}
