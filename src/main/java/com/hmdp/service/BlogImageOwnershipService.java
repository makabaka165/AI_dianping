package com.hmdp.service;

public interface BlogImageOwnershipService {

    void registerOwner(String normalizedPath, Long userId);

    boolean canDelete(String normalizedPath, Long userId);

    String validateAndNormalizeUserImages(String images, Long userId);

    void refreshOwnerTtlForUserImages(String images, Long userId);

    void clearOwner(String normalizedPath);
}
