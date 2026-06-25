/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.substrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The ONE transactional contribution composer + the four shared substrates (tempdoc 560 §4.2/§4.3),
 * reused by <em>every</em> axis and <em>both</em> processes — the Head's {@code ContributionRegistry}
 * over registry declarations and the Worker's {@code ExtractorContributionRegistry} over content
 * extractors — with ZERO per-axis/per-process re-derivation.
 *
 * <p>Generic over the contribution key {@code K} and value {@code V}. Trust and boundary specifics are
 * reduced by each caller to two booleans on the {@link Installation} ({@code ownerIsCore},
 * {@code boundaryAdmissible}), so this module stays pure JDK — it never sees {@code TrustTier},
 * {@code IsolationPolicy}, {@code RegistryRef}, Lucene, or any framework. The Head and the Worker each
 * compute those booleans from their own trust enum and delegate the actual composition here.
 *
 * <p>The four substrates, all reified in this one class:
 *
 * <ul>
 *   <li><b>Lifecycle</b> — atomic install (validate-before-commit; a rejected install leaves the
 *       composer unchanged) + ownership-keyed {@link #uninstall} that revokes exactly the owner's keys.
 *   <li><b>Boundary</b> — isolation proportional to trust: an installation whose {@code boundaryAdmissible}
 *       is false is refused, never silently downgraded.
 *   <li><b>Trust</b> — host owns truth: a non-core owner ({@code ownerIsCore == false}) may not mint a
 *       key in the reserved {@code core.} namespace (decided via {@code keyId}).
 *   <li><b>Dispatch</b> — the composed view ({@link #values} / {@link #get} / {@link #keys}) the
 *       consumer routes over.
 * </ul>
 */
public final class ContributionComposer<K, V> {

  /** One owner's atomic contribution set: identity, the two admission booleans, and its keyed entries. */
  public record Installation<K, V>(
      Object ownerId,
      String ownerLabel,
      boolean ownerIsCore,
      boolean boundaryAdmissible,
      String boundaryDenialDetail,
      Map<K, V> entries) {
    public Installation {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(ownerLabel, "ownerLabel");
      entries = entries == null ? Map.of() : new LinkedHashMap<>(entries);
    }
  }

  /** Outcome of an {@link #uninstall}: whether the owner was present, and the keys it owned (now revoked). */
  public record UninstallResult<K>(boolean wasInstalled, List<K> removedKeys) {
    public UninstallResult {
      removedKeys = removedKeys == null ? List.of() : List.copyOf(removedKeys);
    }
  }

  private final Function<K, String> keyId;
  private final Map<Object, String> owners = new LinkedHashMap<>();
  private final Map<K, V> contributions = new LinkedHashMap<>();
  private final Map<K, Object> owner = new LinkedHashMap<>();

  /**
   * @param keyId projects a key to its namespaced id string — the Trust substrate reads its {@code
   *     core.} prefix from this (an {@code OperationRef.value()} for the Head; the extractor id for the
   *     Worker).
   */
  public ContributionComposer(Function<K, String> keyId) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
  }

  /**
   * Atomically install one owner's whole contribution set across all axes.
   *
   * @throws IllegalStateException if the owner is already installed (Lifecycle), the boundary refuses it
   *     (Boundary), a key collides with an installed contribution (Lifecycle), or a non-core owner mints
   *     a {@code core.*} key (Trust) — the composer is left unchanged in every case.
   */
  public synchronized void install(Installation<K, V> inst) {
    Objects.requireNonNull(inst, "inst");
    // Lifecycle: one owner installs once.
    if (owners.containsKey(inst.ownerId())) {
      throw new IllegalStateException("Already installed: " + inst.ownerLabel());
    }
    // Boundary: isolation proportional to trust — refused, never downgraded.
    if (!inst.boundaryAdmissible()) {
      throw new IllegalStateException(
          "Boundary refused "
              + inst.ownerLabel()
              + (inst.boundaryDenialDetail() == null ? "" : ": " + inst.boundaryDenialDetail()));
    }
    // Trust (host-owns-truth) + collision — validated over EVERY key before any commit.
    for (K key : inst.entries().keySet()) {
      if (contributions.containsKey(key)) {
        throw new IllegalStateException(
            "Contribution already present: " + keyId.apply(key) + " (by " + owner.get(key) + ")");
      }
      if (!inst.ownerIsCore() && keyId.apply(key).startsWith("core.")) {
        throw new IllegalStateException(
            "Host owns truth (tempdoc 560 §4.5): non-core "
                + inst.ownerLabel()
                + " may not contribute a core.* key: "
                + keyId.apply(key));
      }
    }
    // All validation passed — commit (pure map puts, no failure path).
    owners.put(inst.ownerId(), inst.ownerLabel());
    for (Map.Entry<K, V> e : inst.entries().entrySet()) {
      contributions.put(e.getKey(), e.getValue());
      owner.put(e.getKey(), inst.ownerId());
    }
  }

  /** Revoke an owner and every contribution it owns. The result names the removed keys for the caller. */
  public synchronized UninstallResult<K> uninstall(Object ownerId) {
    Objects.requireNonNull(ownerId, "ownerId");
    if (owners.remove(ownerId) == null) {
      return new UninstallResult<>(false, List.of());
    }
    List<K> owned = new ArrayList<>();
    owner.forEach(
        (k, o) -> {
          if (o.equals(ownerId)) {
            owned.add(k);
          }
        });
    for (K k : owned) {
      contributions.remove(k);
      owner.remove(k);
    }
    return new UninstallResult<>(true, owned);
  }

  public synchronized boolean isInstalled(Object ownerId) {
    return owners.containsKey(ownerId);
  }

  /** The installed owner ids, in install order. */
  public synchronized List<Object> ownerIds() {
    return List.copyOf(owners.keySet());
  }

  /** Dispatch substrate: look up a single contribution by key. */
  public synchronized Optional<V> get(K key) {
    return Optional.ofNullable(contributions.get(key));
  }

  /** Dispatch substrate: every composed contribution value, in install order. */
  public synchronized List<V> values() {
    return List.copyOf(contributions.values());
  }

  /** Every composed contribution key, in install order. */
  public synchronized List<K> keys() {
    return List.copyOf(contributions.keySet());
  }

  /** Which owner contributed a key (the ownership index revocation keys on). */
  public synchronized Optional<Object> ownerOf(K key) {
    return Optional.ofNullable(owner.get(key));
  }
}
