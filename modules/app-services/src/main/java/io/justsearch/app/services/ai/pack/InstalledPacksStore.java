/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.justsearch.app.api.InstalledPacksRecord;
import io.justsearch.app.api.InstalledPacksRecord.InstalledPack;
import io.justsearch.app.api.InstalledPacksRecord.InstalledFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists {@code installed-packs.v1.json} under AI Home. */
public final class InstalledPacksStore {
  private static final Logger log = LoggerFactory.getLogger(InstalledPacksStore.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private final Path recordPath;

  public InstalledPacksStore(Path aiHome) {
    this.recordPath = aiHome.resolve("installed-packs.v1.json");
  }

  public InstalledPacksRecord load() {
    if (!Files.exists(recordPath)) {
      return new InstalledPacksRecord();
    }
    try {
      InstalledPacksRecord r = MAPPER.readValue(recordPath.toFile(), InstalledPacksRecord.class);
      return r == null ? new InstalledPacksRecord() : r;
    } catch (Exception e) {
      log.warn("Failed to read installed packs record (treating as empty): {}", recordPath, e);
      return new InstalledPacksRecord();
    }
  }

  public void save(InstalledPacksRecord r) {
    if (r == null) return;
    try {
      Files.createDirectories(recordPath.getParent());
      r.schemaVersion = 1;
      r.updatedAt = Instant.now().toString();
      MAPPER.writeValue(recordPath.toFile(), r);
    } catch (IOException e) {
      log.warn("Failed to persist installed packs record: {}", recordPath, e);
    }
  }

  /**
   * Upserts a pack record, preventing silent downgrade unless allowDowngrade=true.
   *
   * @throws IllegalStateException when a downgrade is attempted without allowDowngrade
   */
  public void upsertPack(InstalledPack pack, boolean allowDowngrade) {
    InstalledPacksRecord r = load();
    if (r.packs == null) {
      r.packs = new ArrayList<>();
    }

    InstalledPack existing = null;
    int existingIdx = -1;
    for (int i = 0; i < r.packs.size(); i++) {
      InstalledPack p = r.packs.get(i);
      if (p != null && safe(p.packId).equals(safe(pack.packId))) {
        existing = p;
        existingIdx = i;
        break;
      }
    }
    if (existing != null && !allowDowngrade) {
      int cmp = compareSemverLike(pack.packVersion, existing.packVersion);
      if (cmp < 0) {
        throw new IllegalStateException(
            "Refusing silent downgrade for packId="
                + safe(pack.packId)
                + " from "
                + safe(existing.packVersion)
                + " to "
                + safe(pack.packVersion));
      }
    }

    pack.installedAt = Instant.now().toString();
    if (existingIdx >= 0) {
      r.packs.set(existingIdx, pack);
    } else {
      r.packs.add(pack);
    }
    save(r);
  }

  public Path recordPath() {
    return recordPath;
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  /**
   * Best-effort semver-like comparison:
   *
   * <ul>
   *   <li>Parses the numeric dotted prefix (e.g., 2.0.0 from 2.0.0-alpha)</li>
   *   <li>Compares lexicographically by numeric segments</li>
   *   <li>If parsing fails for either, falls back to case-insensitive string compare</li>
   * </ul>
   */
  static int compareSemverLike(String a, String b) {
    int[] av = parseNumericDottedPrefix(a);
    int[] bv = parseNumericDottedPrefix(b);
    if (av == null || bv == null) {
      String as = safe(a).trim().toLowerCase(Locale.ROOT);
      String bs = safe(b).trim().toLowerCase(Locale.ROOT);
      return as.compareTo(bs);
    }
    int n = Math.max(av.length, bv.length);
    for (int i = 0; i < n; i++) {
      int ai = i < av.length ? av[i] : 0;
      int bi = i < bv.length ? bv[i] : 0;
      if (ai != bi) {
        return Integer.compare(ai, bi);
      }
    }
    return 0;
  }

  private static int[] parseNumericDottedPrefix(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isBlank()) return null;
    // Extract a leading dotted numeric prefix: 1.2.3 (stop at first non-digit/non-dot).
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= '0' && c <= '9') || c == '.') {
        sb.append(c);
      } else {
        break;
      }
    }
    String prefix = sb.toString();
    if (prefix.isBlank() || prefix.startsWith(".") || prefix.endsWith(".")) return null;
    String[] parts = prefix.split("\\.");
    if (parts.length == 0) return null;
    int[] out = new int[parts.length];
    try {
      for (int i = 0; i < parts.length; i++) {
        out[i] = Integer.parseInt(parts[i]);
      }
      return out;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // JSON model lives in io.justsearch.app.api.InstalledPacksRecord (with nested
  // InstalledPack + InstalledFile) - moved as part of tempdoc 519 §9 Block B2 so
  // AiPackImportService can return it from app-api.
}
