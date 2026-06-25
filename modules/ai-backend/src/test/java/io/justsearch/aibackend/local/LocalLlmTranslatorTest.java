package io.justsearch.aibackend.local;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalLlmTranslatorTest {

  @Test
  void defaultLifecycleIsNoopAndReady() throws Exception {
    LocalLlmTranslator translator =
        new LocalLlmTranslator() {
          @Override
          public String translateIntent(String text, String locale) {
            return text + ":" + locale;
          }

          @Override
          public String summarize(String content, String language) {
            return content + "/" + language;
          }
        };

    assertTrue(translator.isReady(), "default isReady should return true");
    translator.close();
    assertEquals("hello:en", translator.translateIntent("hello", "en"));
    assertEquals("body/en", translator.summarize("body", "en"));
  }

  @Test
  void translatorExceptionConstructorsExposeMessages() {
    LocalLlmTranslator.TranslatorException withCause =
        new LocalLlmTranslator.TranslatorException("boom", new IllegalStateException("bad"));
    assertEquals("boom", withCause.getMessage());
    assertNotNull(withCause.getCause());

    LocalLlmTranslator.TranslatorException withoutCause =
        new LocalLlmTranslator.TranslatorException("solo");
    assertEquals("solo", withoutCause.getMessage());
    assertNull(withoutCause.getCause());
  }
}
