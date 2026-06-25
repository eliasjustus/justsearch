/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence model for installed AI Packs.
 *
 * <p>Extracted from nested classes in {@code io.justsearch.ui.ai.pack.InstalledPacksStore} as
 * part of tempdoc 519 §9 Block B2. {@link AiPackImportService#getInstalledPacks} returns this
 * type. Cluster: {@link InstalledPacksRecord} → {@link InstalledPack} → {@link InstalledFile}.
 */
public final class InstalledPacksRecord {
  public int schemaVersion = 1;
  public String updatedAt;
  public List<InstalledPack> packs = new ArrayList<>();

  public static final class InstalledPack {
    public String packId;
    public String packVersion;
    public String kind;
    public String manifestSha256;
    public String installedAt;
    public List<InstalledFile> files = new ArrayList<>();
  }

  public static final class InstalledFile {
    public String role;
    public String variantId;
    public String destPath;
    public String sha256;
    public long sizeBytes;
  }
}
