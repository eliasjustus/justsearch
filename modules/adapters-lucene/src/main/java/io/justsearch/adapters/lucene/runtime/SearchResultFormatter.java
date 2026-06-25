/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.StoredFieldVisitor;

/**
 * Utility methods for extracting and formatting search result fields.
 *
 * <p>Extracted from LuceneLifecycleManager to improve code organization and testability.
 */
public final class SearchResultFormatter {

  private SearchResultFormatter() {} // utility class

  /**
   * Extracts stored fields from Lucene StoredFields into a String map.
   *
   * <p>Uses a StoredFieldVisitor for efficient selective field loading. Fields are converted to
   * their string representation.
   *
   * @param storedFields the Lucene StoredFields accessor
   * @param docNum the internal document number
   * @param includeContent whether to include the content field (can be large)
   * @param storedFieldAllowlist if non-null, only include fields in this set
   * @return map of field name to string value
   * @throws IOException if field reading fails
   */
  public static Map<String, String> extractFromStoredFields(
      org.apache.lucene.index.StoredFields storedFields,
      int docNum,
      boolean includeContent,
      Set<String> storedFieldAllowlist)
      throws IOException {
    if (storedFields == null) {
      throw new NullPointerException("storedFields must not be null");
    }
    Map<String, String> fields = new HashMap<>();
    storedFields.document(
        docNum,
        new StoredFieldVisitor() {
          @Override
          public Status needsField(FieldInfo fieldInfo) {
            if (!includeContent && SchemaFields.CONTENT.equals(fieldInfo.name)) {
              return Status.NO;
            }
            if (storedFieldAllowlist != null && !storedFieldAllowlist.contains(fieldInfo.name)) {
              return Status.NO;
            }
            return Status.YES;
          }

          @Override
          public void stringField(FieldInfo fieldInfo, String value) {
            if (value != null) {
              fields.merge(fieldInfo.name, value, (existing, newVal) -> existing + " | " + newVal);
            }
          }

          @Override
          public void intField(FieldInfo fieldInfo, int value) {
            fields.put(fieldInfo.name, Integer.toString(value));
          }

          @Override
          public void longField(FieldInfo fieldInfo, long value) {
            fields.put(fieldInfo.name, Long.toString(value));
          }

          @Override
          public void floatField(FieldInfo fieldInfo, float value) {
            fields.put(fieldInfo.name, Float.toString(value));
          }

          @Override
          public void doubleField(FieldInfo fieldInfo, double value) {
            fields.put(fieldInfo.name, Double.toString(value));
          }

          @Override
          public void binaryField(FieldInfo fieldInfo, byte[] value) {
            // Ignore binary stored fields in hit field maps.
          }
        });
    return fields;
  }

  /**
   * Extracts stored fields from a Lucene Document into a String map.
   *
   * <p>IMPORTANT: The index stores the full extracted {@code content} field, which can be very
   * large. For interactive file search, we avoid materializing that content into the hit payload
   * (the UI uses {@code /api/preview} for content). Some flows (e.g., RAG fallback) still need full
   * content.
   *
   * @param doc the Lucene Document
   * @param includeContent whether to include the content field (can be large)
   * @return map of field name to string value
   */
  public static Map<String, String> extractFromDocument(Document doc, boolean includeContent) {
    if (doc == null) {
      throw new NullPointerException("doc must not be null");
    }
    Map<String, String> fields = new HashMap<>();
    for (IndexableField field : doc.getFields()) {
      String name = field.name();
      if (!includeContent && SchemaFields.CONTENT.equals(name)) {
        continue;
      }
      String value = field.stringValue();
      if (value != null) {
        fields.merge(name, value, (existing, newVal) -> existing + " | " + newVal);
      }
    }
    return fields;
  }
}
