package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequiredFieldsCommitMetadataValidatorTest {

  @Test
  void validatesAndCatchesCommonFailures() {
    var v = new RequiredFieldsCommitMetadataValidator();
    Map<String, Object> base = new SsotCommitMetadataSource().build();
    // Valid base
    assertDoesNotThrow(() -> v.validate(base));

    // Bad hex for schema_fp
    Map<String, Object> badHex = new HashMap<>(base);
    badHex.put("schema_fp", "not_hex");
    assertThrows(IllegalStateException.class, () -> v.validate(badHex));

    // Wrong type for template_ver
    Map<String, Object> badInt = new HashMap<>(base);
    badInt.put("template_ver", "x");
    assertThrows(IllegalStateException.class, () -> v.validate(badInt));
  }
}
