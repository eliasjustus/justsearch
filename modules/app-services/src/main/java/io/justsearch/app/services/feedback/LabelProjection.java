/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.app.services.gpl.GplTrainingTripleStore;
import io.justsearch.app.services.gpl.GplTrainingTripleStore.FeaturePayload;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 580 §17.5 (Track C P5) — the LABEL projection: joins {@link ResultDisposition}s to the
 * per-query {@link FeatureSnapshot} that ranked them (by {@code interactionId} plus stable UID, or
 * the retained path key for legacy rows — the §17.4 join) and writes real-label training triples in
 * the existing trainer's NDJSON format.
 *
 * <p>This is the structural answer to F-021: the learned layer's missing piece was <em>real labels</em>
 * (a data problem, not a feature-count problem). The projection derives them from actual outcomes, so
 * the GPL <em>synthetic</em> store demotes to a cold-start prior. It is a governed projection of the
 * one canonical disposition stream (§17.5), not a second authority.
 *
 * <p>A disposition with no joinable snapshot is dropped (it cannot become a <em>featured</em> example)
 * — the honest limit of §16's recall-blindness, surfaced rather than papered over.
 */
public final class LabelProjection {

  private static final Logger log = LoggerFactory.getLogger(LabelProjection.class);

  private LabelProjection() {}

  /**
   * Projects (disposition ⋈ snapshot) → real-label triples appended to {@code realLabelStore}.
   *
   * <p>Two passes. (1) Every explicit disposition that joins a snapshot hit becomes a labelled
   * triple (the §17.4 join). (2) For every query the user actually engaged with (≥1 <em>positive</em>
   * disposition), each shown-but-not-disposed hit becomes a derived {@code SHOWN} <em>negative</em>:
   * it was displayed and passed over. This is the contrast LambdaMART needs — a lone {@code OPENED}
   * gives a single-document group with no pairwise signal, and the trainer would adopt a degenerate
   * model that scores a trivially-perfect nDCG on it (the F-021 recurrence this guards against). The
   * {@link FeatureSnapshot} already holds every shown hit, so deriving {@code SHOWN} from it is a pure
   * projection — no new FE event, no second authority. (v0 = the all-shown model; a click model
   * (skip-above / cascade) is the future refinement that drops the position-bias assumption.)
   *
   * @return triples written + the count of <em>contrast groups</em> (queries with ≥1 positive AND ≥1
   *     negative) — the Fix-C gate input deciding whether real-label training is non-degenerate.
   */
  public static Result project(
      List<ResultDisposition> dispositions,
      List<FeatureSnapshot> snapshots,
      GplTrainingTripleStore realLabelStore) {
    // Index each interaction twice: the primary map deduplicates the persisted identity (UID for
    // new rows, path for legacy rows), while the alias map also accepts the path-oriented source id
    // emitted by unchanged UI/agent surfaces. A conflicting alias is removed and stays unusable;
    // arbitrary first-wins identity selection would attach a label to the wrong logical document.
    Map<String, Map<String, FeatureSnapshot.HitFeatures>> primaryByInteraction = new HashMap<>();
    Map<String, Map<String, FeatureSnapshot.HitFeatures>> aliasesByInteraction = new HashMap<>();
    Set<String> conflictingAliases = new HashSet<>();
    for (FeatureSnapshot snap : snapshots) {
      if (snap == null || snap.interactionId() == null || snap.hits() == null) {
        continue;
      }
      Map<String, FeatureSnapshot.HitFeatures> primary =
          primaryByInteraction.computeIfAbsent(snap.interactionId(), k -> new HashMap<>());
      Map<String, FeatureSnapshot.HitFeatures> aliases =
          aliasesByInteraction.computeIfAbsent(snap.interactionId(), k -> new HashMap<>());
      for (FeatureSnapshot.HitFeatures h : snap.hits()) {
        if (h == null || h.docId() == null || h.docId().isBlank()) {
          continue;
        }
        FeatureSnapshot.HitFeatures canonical = primary.putIfAbsent(h.docId(), h);
        if (canonical == null) {
          canonical = h;
        }
        aliases.putIfAbsent(canonical.docId(), canonical);
        String sourceDocId = h.sourceDocId();
        if (sourceDocId == null || sourceDocId.isBlank() || sourceDocId.equals(h.docId())) {
          continue;
        }
        String aliasKey = disposedKey(snap.interactionId(), sourceDocId);
        if (conflictingAliases.contains(aliasKey)) {
          continue;
        }
        FeatureSnapshot.HitFeatures previous = aliases.putIfAbsent(sourceDocId, canonical);
        if (previous != null && !previous.docId().equals(canonical.docId())) {
          aliases.remove(sourceDocId);
          conflictingAliases.add(aliasKey);
        }
      }
    }

    // Which queries carry a JOINED, successfully-written explicit positive, and which stable
    // (interactionId, document key) pairs are already disposed so the derived pass cannot write the
    // same UID again under a path alias.
    Set<String> interactionsWithPositive = new HashSet<>();
    Set<String> explicitlyDisposed = new HashSet<>();

    // Per-group [hasPositive, hasNegative] for the contrast-group count.
    Map<String, boolean[]> groupFlags = new HashMap<>();
    int written = 0;

    // Resolve and coalesce explicit dispositions before writing. One stable UID may appear under
    // multiple source-path aliases during an agent run or rename. Persisting each alias separately
    // would create duplicate positives or, worse, both a positive and a negative for one document.
    // A positive dominates a negative, and the strongest positive grade wins.
    Map<String, ResolvedDisposition> resolvedDispositions = new LinkedHashMap<>();
    for (ResultDisposition d : dispositions) {
      Map<String, FeatureSnapshot.HitFeatures> byDoc = aliasesByInteraction.get(d.interactionId());
      if (byDoc == null) {
        continue; // no snapshot for this query → cannot form a featured label
      }
      FeatureSnapshot.HitFeatures hf = byDoc.get(d.docId());
      if (hf == null) {
        continue;
      }
      Label label = labelFor(d.kind());
      String stableKey = disposedKey(d.interactionId(), hf.docId());
      resolvedDispositions.merge(
          stableKey,
          new ResolvedDisposition(d.interactionId(), hf, label),
          LabelProjection::strongerDisposition);
    }

    // Pass 1 — coalesced explicit dispositions.
    for (ResolvedDisposition resolved : resolvedDispositions.values()) {
      FeatureSnapshot.HitFeatures hf = resolved.features();
      Label label = resolved.label();
      explicitlyDisposed.add(disposedKey(resolved.interactionId(), hf.docId()));
      if (append(realLabelStore, resolved.interactionId(), hf.docId(), label, hf)) {
        written++;
        mark(groupFlags, resolved.interactionId(), label.isNegative());
        if (!label.isNegative()) {
          interactionsWithPositive.add(resolved.interactionId());
        }
      }
    }

    // Pass 2 — derived SHOWN negatives for queries with a positive (the contrast).
    Label shown = labelFor(ResultDisposition.Kind.SHOWN);
    for (var interaction : primaryByInteraction.entrySet()) {
      if (!interactionsWithPositive.contains(interaction.getKey())) {
        continue; // no positive → an all-negative group; the trainer drops it anyway
      }
      for (FeatureSnapshot.HitFeatures hf : interaction.getValue().values()) {
        if (explicitlyDisposed.contains(disposedKey(interaction.getKey(), hf.docId()))) {
          continue; // already labelled explicitly (positive or negative)
        }
        if (append(realLabelStore, interaction.getKey(), hf.docId(), shown, hf)) {
          written++;
          mark(groupFlags, interaction.getKey(), shown.isNegative());
        }
      }
    }

    int contrastGroups = 0;
    for (boolean[] f : groupFlags.values()) {
      if (f[0] && f[1]) {
        contrastGroups++;
      }
    }
    return new Result(written, contrastGroups);
  }

  /** Projection outcome: total triples written + the number of contrast groups (Fix-C gate input). */
  public record Result(int triples, int contrastGroups) {}

  private record ResolvedDisposition(
      String interactionId, FeatureSnapshot.HitFeatures features, Label label) {}

  private static ResolvedDisposition strongerDisposition(
      ResolvedDisposition current, ResolvedDisposition candidate) {
    if (current.label().isNegative() != candidate.label().isNegative()) {
      return current.label().isNegative() ? candidate : current;
    }
    return candidate.label().score() > current.label().score() ? candidate : current;
  }

  private static boolean append(
      GplTrainingTripleStore store,
      String interactionId,
      String docId,
      Label label,
      FeatureSnapshot.HitFeatures hf) {
    try {
      store.appendWithFeatures(
          interactionId, docId, "", label.score(), label.isNegative(), payload(hf));
      return true;
    } catch (IOException e) {
      log.warn(
          "Failed to append real-label triple for {}#{}: {}", interactionId, docId, e.getMessage());
      return false;
    }
  }

  private static void mark(Map<String, boolean[]> flags, String interactionId, boolean isNegative) {
    boolean[] f = flags.computeIfAbsent(interactionId, k -> new boolean[2]);
    if (isNegative) {
      f[1] = true;
    } else {
      f[0] = true;
    }
  }

  private static String disposedKey(String interactionId, String docId) {
    return interactionId + '\0' + docId;
  }

  /**
   * Maps a graded disposition kind to a learning label. Positive (relevant) = the user/agent acted
   * on it; negative = shown-but-passed, and crucially {@code REFINED_WITHOUT_OPENING} — §16's one
   * recall-failure signal.
   */
  static Label labelFor(ResultDisposition.Kind kind) {
    return switch (kind) {
      case CITED, ACTED_ON -> new Label(false, 1.0f);
      case DWELLED -> new Label(false, 0.8f);
      case OPENED -> new Label(false, 0.6f);
      case SHOWN -> new Label(true, 0.0f);
      case REFINED_WITHOUT_OPENING -> new Label(true, 0.0f);
    };
  }

  /** The trainer's label tuple: (isNegative, graded-confidence score). */
  record Label(boolean isNegative, float score) {}

  private static FeaturePayload payload(FeatureSnapshot.HitFeatures h) {
    return FeaturePayload.builder()
        .sparse(h.sparse())
        .vector(h.dense())
        .wholeSplade(h.splade())
        .wholeCc(h.fused())
        .parentTokenCount(h.parentTokenCount())
        .rankPosition(h.rank())
        .timestampMs(Instant.now().toEpochMilli())
        .build();
  }
}
