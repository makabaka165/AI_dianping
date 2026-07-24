package com.hmdp.ai.infrastructure.vector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The single naming seam for physical Redis knowledge indexes.
 *
 * <p>v1 replaced every non-ASCII identifier character with an underscore. That mapping is not
 * injective ({@code a-b} and {@code a_b} collide), so new indexes use a stable hash-based v2 name.
 * The legacy name remains derivable for read fallback and rollback; changing the name does not
 * require copying the HASH keys because both index versions use the same key prefix.
 */
public final class RedisKnowledgeIndexNaming {
  // '~' is deliberately outside the v1 normalization alphabet, so v1 and v2 namespaces cannot
  // alias solely because an old version string happens to look like a v2 hash.
  private static final String V2_PREFIX = "ai_kb_v2~";
  private static final String LEGACY_PREFIX = "ai_kb_";
  private static final int HASH_HEX_LENGTH = 32;

  private RedisKnowledgeIndexNaming() {}

  /** Returns the current (collision-safe) physical index name. */
  public static String indexName(String indexVersion) {
    return v2IndexName(indexVersion);
  }

  /** Returns the collision-safe v2 name used for new writes and reads. */
  public static String v2IndexName(String indexVersion) {
    Objects.requireNonNull(indexVersion, "indexVersion");
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA-256")
              .digest(indexVersion.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      hex.append(Character.forDigit(value & 0x0f, 16));
    }
    return V2_PREFIX + hex.substring(0, HASH_HEX_LENGTH);
  }

  /** Returns the deployed v1 name so an existing index can be read or rolled back safely. */
  public static String legacyIndexName(String indexVersion) {
    Objects.requireNonNull(indexVersion, "indexVersion");
    return LEGACY_PREFIX + indexVersion.replaceAll("[^A-Za-z0-9_]", "_");
  }

  /** Alias that makes the version of the legacy format explicit at call sites and in tests. */
  public static String v1IndexName(String indexVersion) {
    return legacyIndexName(indexVersion);
  }

  /** Returns v2 first, followed by the v1 fallback when the names differ. */
  public static List<String> candidateIndexNames(String indexVersion) {
    List<String> names = new ArrayList<>(2);
    names.add(v2IndexName(indexVersion));
    String legacy = legacyIndexName(indexVersion);
    if (!names.contains(legacy)) names.add(legacy);
    return Collections.unmodifiableList(names);
  }

  public static boolean isV2Name(String value) {
    return value != null && value.startsWith(V2_PREFIX);
  }
}
