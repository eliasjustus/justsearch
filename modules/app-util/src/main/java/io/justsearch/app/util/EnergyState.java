/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

/**
 * The OS energy-intent host capability (tempdoc 630). Answers "does the OS want background work
 * reduced right now?" — the modern, OS-authoritative signal (Windows Energy Saver / macOS Low Power
 * Mode / Linux power-saver profile) rather than a hand-rolled battery-vs-AC heuristic.
 *
 * <p>Mirrors the single-probe host-capability shape of {@code GpuCapabilities.Cuda} (tempdoc 587):
 * a value carrying provenance, where {@link Intent#UNKNOWN} encodes "no confidence". Policy MUST be
 * conservative under UNKNOWN — never throttle on a signal we could not read (so a host where the
 * probe is unavailable behaves exactly as before this feature).
 *
 * @param intent whether the OS wants background work reduced
 * @param source the power source, used only for user-facing wording (battery vs energy-saver)
 */
public record EnergyState(Intent intent, Source source) {

  /** Whether the OS is asking apps to reduce background activity. */
  public enum Intent {
    /** Run normally — no energy-conservation request in effect. */
    FULL,
    /** The OS wants reduced background activity (energy saver / low power / power-saver profile). */
    REDUCED,
    /** The energy intent could not be determined — treat as {@link #FULL} (never throttle). */
    UNKNOWN
  }

  /** Power source, for wording only ("Energy saver is on" vs "on battery"). */
  public enum Source {
    AC,
    BATTERY,
    UNKNOWN
  }

  /** The not-probed sentinel: unknown intent + source (⇒ no throttling). */
  public static EnergyState unknown() {
    return new EnergyState(Intent.UNKNOWN, Source.UNKNOWN);
  }

  /** True iff the OS is actively requesting reduced background work (REDUCED only; UNKNOWN is not). */
  public boolean reduced() {
    return intent == Intent.REDUCED;
  }
}
