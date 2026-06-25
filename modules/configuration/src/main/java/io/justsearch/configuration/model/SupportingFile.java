/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * A non-model file required by a model package (tokenizer, config, pooling config, vocab, etc.).
 *
 * <p>Supporting files are always downloaded regardless of hardware profile.
 *
 * @param filename the file name (e.g., "tokenizer.json"). For archives ({@code extract == true}),
 *     this is the archive filename — its extracted contents land in the same {@code targetDir}.
 * @param sha256 uppercase hex SHA-256 hash
 * @param sizeBytes file size in bytes
 * @param downloadUrl HTTPS URL to download the file
 * @param extract when true, the file is treated as a zip archive and its entries are extracted
 *     into the package's {@code targetDir} after download + SHA verification. The archive itself
 *     is kept on disk so the install planner's {@code isAlreadyInstalled} check (which looks at
 *     the archive's filename) skips re-download on subsequent installs. Used by the alpha.15
 *     CUDA-runtime package (tempdoc 374) — bundled DLLs are too large for the NSIS installer
 *     payload, so they ship via Install AI as a downloaded + extracted archive instead.
 */
public record SupportingFile(
    String filename, String sha256, long sizeBytes, String downloadUrl, boolean extract) {

  /** Backwards-compat constructor — non-extracted file (existing behavior). */
  public SupportingFile(String filename, String sha256, long sizeBytes, String downloadUrl) {
    this(filename, sha256, sizeBytes, downloadUrl, false);
  }
}
