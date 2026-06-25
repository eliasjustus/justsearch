package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

final class TemplateResourcePresenceTest {

  @Test
  void bundledTemplatesArePresentOnClasspath() {
    ClassLoader loader = getClass().getClassLoader();
    assertNotNull(loader.getResourceAsStream("templates/translate.jinja"), "translate.jinja missing");
    assertNotNull(loader.getResourceAsStream("templates/summary_chunk.jinja"), "summary_chunk.jinja missing");
    assertNotNull(loader.getResourceAsStream("templates/summary_reduce.jinja"), "summary_reduce.jinja missing");
  }
}
