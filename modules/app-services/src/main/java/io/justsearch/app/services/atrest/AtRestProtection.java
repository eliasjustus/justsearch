/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.atrest;

/**
 * Tempdoc 629 (FLOOR) — the sensed at-rest protection of the volume that holds the data dir.
 *
 * <p>This is the domain record the {@link DiskEncryptionProbe} produces and the {@code
 * AtRestHealthTap} reads; the Head-side status handler maps it to the wire {@code
 * AtRestProtectionView}. It mirrors the GPU-capability pattern (a confidence-carrying effective
 * view, tempdoc 587): a coarse OS-disk-encryption state plus its provenance + confidence.
 *
 * <p>Fidelity (629 §R1 / confidence-probe P1): a non-elevated process can read only the coarse
 * on/off state (the Explorer {@code System.Volume.BitLockerProtection} shell property); the
 * configuration-quality distinction (weak TPM-only vs a secure pre-boot PIN) and cloud-escrow
 * status require admin elevation and are therefore NOT carried here. The honest status surface
 * renders configuration quality as "unknown — needs admin".
 */
public record AtRestProtection(State state, String source, Confidence confidence) {

  /** Coarse OS-disk-encryption state of the data-dir volume (the only fidelity available un-elevated). */
  public enum State {
    /** Volume is encrypted by the OS (BitLocker / FileVault / LUKS). */
    ENCRYPTED,
    /** Volume is not encrypted — authored data has no at-rest protection. */
    NOT_ENCRYPTED,
    /** Encryption or decryption is in progress. */
    ENCRYPTING,
    /** Could not be determined (non-Windows, probe failure, or an indeterminate shell-property value). */
    UNKNOWN
  }

  /** Provenance confidence, mirroring the GPU resolver's confidence axis (tempdoc 587). */
  public enum Confidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
  }

  /** The sentinel used when the state cannot be determined. */
  public static AtRestProtection unknown() {
    return new AtRestProtection(State.UNKNOWN, "none", Confidence.UNKNOWN);
  }
}
