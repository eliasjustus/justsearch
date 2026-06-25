/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.util.Map;
import java.util.regex.Pattern;

/** Minimal validator that enforces required keys and hex-format constraints. */
public final class RequiredFieldsCommitMetadataValidator implements CommitMetadataValidator {
  private static final String[] REQUIRED = new String[] {
      "schema_ver",
      "schema_fp",
      "field_catalog_hash",
      "synonyms_hash",
      "grammar_ver",
      "grammar_hash",
      "template_ver",
      "prompt_pack_hash",
      "analyzer_fp"
  };

  private static final Pattern HEX64 = Pattern.compile("^[a-f0-9]{64}$");

  @Override
  public void validate(Map<String, Object> metadata) {
    for (String k : REQUIRED) {
      if (!metadata.containsKey(k)) {
        throw new IllegalStateException("Missing required commit metadata field: " + k);
      }
    }
    checkHex(metadata, "schema_fp");
    checkHex(metadata, "field_catalog_hash");
    checkHex(metadata, "synonyms_hash");
    checkHex(metadata, "grammar_hash");
    checkHex(metadata, "prompt_pack_hash");
    checkHex(metadata, "analyzer_fp");
    checkInt(metadata, "template_ver");
  }

  private static void checkHex(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof String value) || !HEX64.matcher(value).matches()) {
      throw new IllegalStateException(key + " must be 64-char lowercase hex string");
    }
  }

  private static void checkInt(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof Number)) {
      throw new IllegalStateException(key + " must be an integer");
    }
  }
}
