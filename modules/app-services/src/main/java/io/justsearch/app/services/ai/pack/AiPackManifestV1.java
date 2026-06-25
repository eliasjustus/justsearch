/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AiPackManifestV1 {
  public int schemaVersion = 0;
  public String packId;
  public String packVersion;
  public String kind;
  /** v3: required when kind == "runtime" (e.g., "cuda-12.4"). */
  public String variantId;
  public String createdAt;
  public String requiresAppMin;
  public String requiresAppMax;
  public List<FileEntry> files;
  public List<AssetEntry> assets;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class FileEntry {
    public String id;
    public String pathInPack;
    public String sha256;
    public long sizeBytes;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class AssetEntry {
    public String role;
    public String fileId;
    public String variantId;
  }
}
