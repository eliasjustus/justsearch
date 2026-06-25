package io.justsearch.aibackend.local;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalIntentTranslatorV2Test {

  private static final class NoopTranslator implements LocalIntentTranslatorV2 {
    @Override
    public String translateIntent(String text, String locale) {
      return text;
    }

    @Override
    public String summarize(String content, String language) {
      return content;
    }
  }

  @Test
  void readyDefaultsToParentImplementation() {
    NoopTranslator translator = new NoopTranslator();
    assertTrue(translator.ready(), "default ready should defer to LocalLlmTranslator.isReady()");
    assertEquals(LocalIntentTranslatorV2.Provenance.empty(), translator.provenance());
  }

  @Test
  void provenanceNormalisesNullsAndNegatives() {
    LocalIntentTranslatorV2.Provenance provenance =
        new LocalIntentTranslatorV2.Provenance(null, null, -5);
    assertEquals("", provenance.modelFileSha256());
    assertEquals("", provenance.backend());
    assertEquals(0, provenance.gpuLayers());

    LocalIntentTranslatorV2.Provenance empty = LocalIntentTranslatorV2.Provenance.empty();
    assertEquals("unknown", empty.modelFileSha256());
    assertEquals("unknown", empty.backend());
    assertEquals(0, empty.gpuLayers());
  }
}
