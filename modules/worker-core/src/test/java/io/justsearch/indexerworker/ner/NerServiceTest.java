package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NerService")
class NerServiceTest {

  @Nested
  @DisplayName("isAvailable()")
  class IsAvailable {

    @Test
    @DisplayName("returns false when config is DISABLED")
    void returnsFalse_whenDisabled() {
      NerService service = new NerService(NerConfig.DISABLED);
      assertFalse(service.isAvailable());
    }
  }

  @Nested
  @DisplayName("extractEntities()")
  class ExtractEntities {

    @Test
    @DisplayName("returns EMPTY when not available")
    void returnsEmpty_whenNotAvailable() {
      NerService service = new NerService(NerConfig.DISABLED);
      NerResult result = service.extractEntities("Some text about John Smith");
      assertSame(NerResult.EMPTY, result);
    }

    @Test
    @DisplayName("returns EMPTY for null content")
    void returnsEmpty_forNullContent() {
      NerService service = new NerService(NerConfig.DISABLED);
      NerResult result = service.extractEntities(null);
      assertSame(NerResult.EMPTY, result);
    }

    @Test
    @DisplayName("returns EMPTY for blank content")
    void returnsEmpty_forBlankContent() {
      NerService service = new NerService(NerConfig.DISABLED);
      NerResult result = service.extractEntities("   ");
      assertSame(NerResult.EMPTY, result);
    }
  }

  @Nested
  @DisplayName("close()")
  class Close {

    @Test
    @DisplayName("is idempotent — double close does not throw")
    void isIdempotent() {
      NerService service = new NerService(NerConfig.DISABLED);
      assertDoesNotThrow(() -> {
        service.close();
        service.close();
      });
    }
  }
}
