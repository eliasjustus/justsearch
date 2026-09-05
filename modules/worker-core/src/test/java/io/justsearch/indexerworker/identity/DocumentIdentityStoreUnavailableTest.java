package io.justsearch.indexerworker.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.identity.DocumentIdentityStore.ImportRecord;
import io.justsearch.indexerworker.identity.DocumentIdentityStore.ImportedIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §P2.D "fail-closed authority": a composition that did not wire the durable store
 * must not be able to mint, import, re-key or even count identity from a second authority. The
 * {@link DocumentIdentityStore#UNAVAILABLE} default is what {@code InfraContext} substitutes for a
 * missing store, so every method — the read-only ones included — has to refuse loudly; a silent
 * empty {@code lookup} or a zero {@code identityCount} would look exactly like a healthy, empty
 * store and let admission mint fresh uids that orphan every label keyed on the old ones.
 */
final class DocumentIdentityStoreUnavailableTest {

  private static final DocumentIdentityStore STORE = DocumentIdentityStore.UNAVAILABLE;
  private static final String HASH = PathHash.sha256("/any/path.txt");

  @Test
  void mintingIsRefused() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> STORE.resolve(HASH, 1L));
    assertTrue(e.getMessage().contains("unavailable"), e.getMessage());
  }

  @Test
  void singleAndBatchImportAreRefused() {
    assertThrows(IllegalStateException.class, () -> STORE.importExisting(HASH, "uid-1", 1L));
    assertThrows(
        IllegalStateException.class,
        () -> STORE.importExisting(List.of(new ImportedIdentity(HASH, "uid-1")), 1L));
    // An EMPTY batch is refused too: the boot import must learn the store is missing on its first
    // call, not only once it has something to write.
    assertThrows(IllegalStateException.class, () -> STORE.importExisting(List.of(), 1L));
  }

  @Test
  void theReadOnlyQueriesAreRefusedRatherThanAnsweredEmpty() {
    // These are the dangerous ones: "no identity known" and "no import recorded" are the exact
    // answers a fresh install gives, and both would send the caller down the mint/scan path.
    assertThrows(IllegalStateException.class, () -> STORE.lookup(HASH));
    assertThrows(IllegalStateException.class, STORE::identityCount);
    assertThrows(IllegalStateException.class, () -> STORE.hasImportRecord("gen-a"));
  }

  @Test
  void bookkeepingWritesAreRefused() {
    assertThrows(
        IllegalStateException.class,
        () -> STORE.recordImport(new ImportRecord("gen-a", 1L, 0L, 0L, 0L)));
    assertThrows(
        IllegalStateException.class,
        () -> STORE.rekey(HASH, PathHash.sha256("/any/renamed.txt"), 1L));
  }

  @Test
  void theRekeyVocabularyIsClosed() {
    // Consumers switch on this enum; a new member must be a deliberate contract change, not a
    // drive-by.
    assertEquals(
        List.of("MOVED", "ALREADY_AT_DESTINATION", "NOT_FOUND"),
        List.of(DocumentIdentityStore.RekeyResult.values()).stream().map(Enum::name).toList());
  }
}
