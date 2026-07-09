package io.justsearch.indexerworker.services.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.disambiguation.ClusterEntry;
import io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code EntityClusterSnapshot.expandCanonical} is keyed by canonical form only, so
 * {@code SearchInputCapture.expandFilterList} must resolve a raw/non-canonical alias to its
 * canonical form ({@code getCanonical}) before expanding it, or a non-canonical filter value
 * never expands to its sibling aliases.
 */
final class SearchInputCaptureExpandFilterListTest {

  private static final String TYPE = "PERSON";

  private static EntityClusterSnapshot johnSmithCluster() {
    long now = System.currentTimeMillis();
    return EntityClusterSnapshot.fromEntries(
        List.of(
            // Founding cluster row: rawForm == canonicalForm (per EntityClusterSnapshot's
            // documented invariant), so the canonical form is itself a key in rawToCanonical.
            new ClusterEntry("John Smith", TYPE, "cluster-1", "John Smith", 1.0, now, now),
            new ClusterEntry("J. Smith", TYPE, "cluster-1", "John Smith", 0.9, now, now),
            new ClusterEntry("Smith, John", TYPE, "cluster-1", "John Smith", 0.85, now, now)));
  }

  @Test
  void expandsNonCanonicalAliasToTheFullClusterAfterTheFix() {
    EntityClusterSnapshot snapshot = johnSmithCluster();

    List<String> expanded =
        SearchInputCapture.expandFilterList(List.of("J. Smith"), TYPE, snapshot);

    assertEquals(
        Set.of("John Smith", "J. Smith", "Smith, John"),
        Set.copyOf(expanded),
        "A raw/non-canonical filter value must expand to every sibling alias in its cluster");
  }

  @Test
  void oldBehaviorWithoutCanonicalResolutionWouldOnlyReturnTheSingleton() {
    // Pins the defect this fix addresses: expandCanonical() alone (the pre-fix call, without the
    // getCanonical() resolution step) is keyed by canonical form only, so a raw alias misses and
    // expandCanonical returns just the singleton — proving expandFilterList's improvement isn't
    // a no-op.
    EntityClusterSnapshot snapshot = johnSmithCluster();

    Set<String> oldBehaviorResult = snapshot.expandCanonical(TYPE, "J. Smith");

    assertEquals(Set.of("J. Smith"), oldBehaviorResult);
    assertTrue(
        oldBehaviorResult.size()
            < SearchInputCapture.expandFilterList(List.of("J. Smith"), TYPE, snapshot).size(),
        "The fixed expandFilterList must expand strictly more than the unresolved expandCanonical call");
  }

  @Test
  void canonicalInputRemainsIdempotentAndUnknownInputIsUnaffected() {
    EntityClusterSnapshot snapshot = johnSmithCluster();

    List<String> canonicalInput =
        SearchInputCapture.expandFilterList(List.of("John Smith"), TYPE, snapshot);
    assertEquals(Set.of("John Smith", "J. Smith", "Smith, John"), Set.copyOf(canonicalInput));

    List<String> unknownInput =
        SearchInputCapture.expandFilterList(List.of("Someone Else"), TYPE, snapshot);
    assertEquals(List.of("Someone Else"), unknownInput);
  }
}
