package io.justsearch.testsupport.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SampleDocsTest {

  @Test
  void loadsCatalogSmokeSet() {
    SampleDocs.SampleDocSet set = SampleDocs.catalogSmoke();
    assertEquals("catalog-smoke", set.name());
    assertFalse(set.docs().isEmpty());
    assertEquals("test-support/sample-docs/catalog-smoke.json", set.resourcePath());
  }

  @Test
  void cachesLoadedDocSets() {
    SampleDocs.SampleDocSet first = SampleDocs.byName("catalog-smoke");
    SampleDocs.SampleDocSet second = SampleDocs.byName("catalog-smoke");
    assertSame(first, second);
  }

  @Test
  void throwsForMissingResource() {
    assertThrows(IllegalStateException.class, () -> SampleDocs.byName("missing-sample-set"));
  }
}
