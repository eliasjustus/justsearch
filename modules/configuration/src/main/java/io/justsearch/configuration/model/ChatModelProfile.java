/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chat-model profile — a named, atomic <em>(model, mmproj)</em> bundle for the llama-server engine
 * (tempdoc 842 §2.1).
 *
 * <p>The pair is one unit on purpose: a half-swap (new model, stale projector) degrades the runtime
 * to text-only with no error anywhere, which is exactly the failure the per-file
 * {@link EnvRegistry#VLM_MODEL}/{@link EnvRegistry#MMPROJ_MODEL} overrides allow. Selecting a bundle
 * by name makes that state unrepresentable.
 *
 * <p>Profile is a fourth axis, orthogonal to the three that already exist: {@link InstallIntent}
 * (product shape), {@link DownloadProfile} (precision × execution provider), and
 * {@link CapabilityTier} (package classification). It answers "which chat model", which no other
 * axis can express — {@code VariantSelector} short-circuits {@link ExecutionProvider#LLAMA_SERVER},
 * so size selection cannot ride the hardware-variant seam.
 *
 * <ul>
 *   <li>{@link #STANDARD} — the user-facing Qwen3.5-9B pair. The default when unset, so an
 *       unconfigured runtime is unchanged.
 *   <li>{@link #COMPACT} — the dev-tier small sibling, installed under the {@code chat-compact}
 *       package's {@code compact/} target dir.
 *   <li>{@link #PADDLE_OCR_VL} — the F-009 extraction pilot pair (eval-gated, not a default).
 * </ul>
 *
 * <p>File names are RELATIVE to the models directory, matching each package's registry
 * {@code targetDir}.
 */
public enum ChatModelProfile {
  STANDARD("standard", "Qwen_Qwen3.5-9B-Q4_K_M.gguf", "mmproj-F16.gguf"),
  COMPACT("compact", "compact/Qwen3.5-4B-Q4_K_M.gguf", "compact/mmproj-F16.gguf"),
  PADDLE_OCR_VL("paddle-ocr-vl", "PaddleOCR-VL-1.6-Q4_K_M.gguf", "PaddleOCR-VL-1.6-mmproj-F16.gguf");

  private static final Logger log = LoggerFactory.getLogger(ChatModelProfile.class);

  /** The default profile when {@code -Djustsearch.chat.profile} / {@code JUSTSEARCH_CHAT_PROFILE} is unset. */
  public static final ChatModelProfile DEFAULT = STANDARD;

  /**
   * Legacy id for {@link #STANDARD}: the pre-842 {@code justsearch.vlm.profile} key named this exact
   * pair {@code qwen-vl}. Accepted so an existing configuration keeps selecting the same two files
   * rather than warn-falling-back to them by accident.
   */
  private static final String LEGACY_STANDARD_ID = "qwen-vl";

  private final String id;
  private final String modelFile;
  private final String mmprojFile;

  ChatModelProfile(String id, String modelFile, String mmprojFile) {
    this.id = id;
    this.modelFile = modelFile;
    this.mmprojFile = mmprojFile;
  }

  /** The kebab-case identifier used on the wire ({@code -Djustsearch.chat.profile}). */
  public String id() {
    return id;
  }

  /** Chat/vision model filename, relative to the models directory. */
  public String modelFile() {
    return modelFile;
  }

  /** Multimodal projector filename that belongs to {@link #modelFile()}, relative to the models directory. */
  public String mmprojFile() {
    return mmprojFile;
  }

  /**
   * Resolves a profile from its config string (kebab-case; also tolerates underscores, the enum
   * name, and case). Returns {@link #DEFAULT} for a null/blank/unrecognized value — a bad launch
   * flag must not brick the chat engine, only fall back to the standard pair. A non-blank but
   * unrecognized value (a likely typo) is WARN-logged so the silent fallback is discoverable.
   */
  public static ChatModelProfile resolve(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    String norm = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    if (LEGACY_STANDARD_ID.equals(norm)) {
      return STANDARD;
    }
    for (ChatModelProfile p : values()) {
      if (p.id.equals(norm) || p.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(norm)) {
        return p;
      }
    }
    log.warn(
        "Unknown chat model profile '{}' — falling back to the '{}' default. Known profiles: {}.",
        raw,
        DEFAULT.id,
        Arrays.stream(values()).map(ChatModelProfile::id).toList());
    return DEFAULT;
  }
}
