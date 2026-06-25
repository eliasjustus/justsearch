/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;

/**
 * Maps IndexApi documents to Lucene Documents using a field catalog.
 *
 * <p>This class supports two modes of operation:
 * <ol>
 *   <li><b>Explicit injection (recommended):</b> Pass a {@link FieldCatalogDef} to the constructor.
 *       This is the IoC-compliant approach used by production code and tests.</li>
 *   <li><b>Auto-discovery (legacy):</b> The default constructor uses {@link JustSearchConfigurationLoader}
 *       to find the SSOT catalog. This is provided for backward compatibility.</li>
 * </ol>
 */
public final class FieldMapper {
  private static final Logger log = LoggerFactory.getLogger(FieldMapper.class);
  private static final ObjectMapper M = new ObjectMapper();

  // Track unknown fields to warn only once per field name (avoid log spam)
  private final java.util.Set<String> warnedUnknownFields = java.util.concurrent.ConcurrentHashMap.newKeySet();

  static final class FieldDef {
    final String id;
    final String type;
    final boolean stored;
    final boolean docValues;
    final List<String> roles;
    final Integer vectorDim; // nullable
    final String analyzerKey; // nullable
    final boolean multiValued;

    FieldDef(String id, String type, boolean stored, boolean docValues, List<String> roles, Integer vectorDim, String analyzerKey, boolean multiValued) {
      this.id = id;
      this.type = type;
      this.stored = stored;
      this.docValues = docValues;
      this.roles = roles;
      this.vectorDim = vectorDim;
      this.analyzerKey = analyzerKey;
      this.multiValued = multiValued;
    }

    /** Creates a FieldDef from the configuration POJO. */
    static FieldDef fromPojo(FieldCatalogDef.FieldDef pojo) {
      return new FieldDef(
          pojo.id(),
          pojo.type(),
          pojo.stored(),
          pojo.docValues(),
          pojo.roles(),
          pojo.vectorDimension(),
          pojo.analyzer(),
          pojo.multiValued()
      );
    }
  }

  private final Map<String, FieldDef> byId;
  private final FieldDef primaryKeyField;
  private final FieldDef docUidField;

  /**
   * Creates a FieldMapper from an explicit {@link FieldCatalogDef}.
   *
   * <p>This is the recommended constructor for production code and tests.
   *
   * @param catalog the field catalog definition (must not be null)
   */
  public FieldMapper(FieldCatalogDef catalog) {
    Objects.requireNonNull(catalog, "catalog");
    this.byId = convertFromPojo(catalog);
    validateMultiValuedConstraints(byId);
    this.primaryKeyField = resolvePrimaryKey(byId);
    this.docUidField = resolveDocUid(byId);
  }

  /**
   * Creates a FieldMapper from a specific path.
   *
   * @param catalogPath path to the JSON catalog file
   */
  FieldMapper(Path catalogPath) {
    this(loadCatalogFromPath(catalogPath, true));
  }

  /**
   * Creates a FieldMapper from a parsed JSON tree.
   *
   * @param catalog the parsed JSON root node
   */
  FieldMapper(JsonNode catalog) {
    this(loadCatalogTree(catalog));
  }

  private FieldMapper(Map<String, FieldDef> catalog) {
    this.byId = Objects.requireNonNull(catalog, "catalog");
    validateMultiValuedConstraints(byId);
    this.primaryKeyField = resolvePrimaryKey(byId);
    this.docUidField = resolveDocUid(byId);
  }

  Document toDocument(Map<String, Object> fields) {
    Document doc = new Document();
    int added = 0;
    if (fields != null) {
      for (Map.Entry<String, Object> e : fields.entrySet()) {
        FieldDef def = byId.get(e.getKey());
        if (def == null) {
          // Warn once per unknown field name to help diagnose silent data loss
          if (warnedUnknownFields.add(e.getKey())) {
            log.warn("Unknown field '{}' ignored - not defined in SSOT field catalog. "
                + "Add to SSOT/catalogs/fields.v1.json if this field should be indexed.", e.getKey());
          }
          continue;
        }
        added += addFields(doc, def, e.getValue());
      }
    }
    if (added == 0) {
      // Ensure at least one field to avoid empty document edge cases
      doc.add(new StoredField("_ingest_ts", System.currentTimeMillis()));
    }
    return doc;
  }

  Integer ssotVectorDimensionOrNull() {
    FieldDef def = byId.get("vector");
    return def == null ? null : def.vectorDim;
  }

  FieldDef fieldDef(String id) { return byId.get(id); }

  Map<String, FieldDef> fieldDefs() { return java.util.Collections.unmodifiableMap(byId); }

  String idField() { return primaryKeyField.id; }

  void validatePrimaryKeySupport() {
    if (!primaryKeyField.docValues) {
      throw new IllegalStateException("Primary key field " + primaryKeyField.id + " must be DocValues-backed");
    }
    if (docUidField == null) {
      throw new IllegalStateException("Field catalog missing doc_uid tiebreaker");
    }
    if (!docUidField.docValues) {
      throw new IllegalStateException("Field doc_uid must be DocValues-backed");
    }
    for (FieldDef def : byId.values()) {
      if (def.roles.contains("id") && !def.docValues) {
        throw new IllegalStateException("Field " + def.id + " carries role id but lacks DocValues");
      }
    }
  }

  private int addFields(Document doc, FieldDef def, Object value) {
    int count = 0;
    switch (def.type) {
      case "text" -> {
        String s = asString(value);
        if (s != null) {
          doc.add(new TextField(def.id, s, def.stored ? Field.Store.YES : Field.Store.NO));
          count++;
        }
      }
      case "keyword" -> {
        if (def.multiValued && def.docValues) {
          List<String> values = asList(value);
          for (String v : values) {
            if (v == null || v.isEmpty()) continue;
            if (def.roles != null && def.roles.contains("filter")) {
              doc.add(new StringField(def.id, v, def.stored ? Field.Store.YES : Field.Store.NO));
            } else if (def.stored) {
              doc.add(new StoredField(def.id, v));
            }
            doc.add(new SortedSetDocValuesField(def.id, new BytesRef(v)));
            count++;
          }
        } else {
          String s = asString(value);
          if (s != null && def.docValues) {
            // Create StringField (inverted index) if primary key OR has filter role
            // This enables O(log n) TermQuery lookups for status fields
            if (def == primaryKeyField || (def.roles != null && def.roles.contains("filter"))) {
              doc.add(new StringField(def.id, s, def.stored ? Field.Store.YES : Field.Store.NO));
            } else if (def.stored) {
              doc.add(new StoredField(def.id, s));
            }
            // DocValues for sorting/faceting
            doc.add(new SortedDocValuesField(def.id, new BytesRef(s)));
            count++;
          }
        }
      }
      case "long" -> {
        Long v = asLong(value);
        if (v != null && def.docValues) {
          doc.add(new NumericDocValuesField(def.id, v));
          if (def.roles != null && def.roles.contains("filter")) {
            doc.add(new LongPoint(def.id, v));
          }
          if (def.stored) doc.add(new StoredField(def.id, v));
          count++;
        }
      }
      case "boolean" -> {
        Boolean b = asBoolean(value);
        if (b != null && def.docValues) {
          doc.add(new NumericDocValuesField(def.id, b ? 1L : 0L));
          if (def.stored) doc.add(new StoredField(def.id, b ? 1 : 0));
          count++;
        }
      }
      case "vector" -> {
        float[] vec = asFloatArray(value);
        if (vec != null) {
          if (def.vectorDim != null && vec.length != def.vectorDim) {
            throw new IllegalArgumentException("vector dimension mismatch for " + def.id + ": expected " + def.vectorDim + ", got " + vec.length);
          }
          doc.add(new KnnFloatVectorField(def.id, vec));
          count++;
        }
      }
      case "splade" -> {
        @SuppressWarnings("unchecked")
        Map<String, Float> sparseVec = (Map<String, Float>) value;
        if (sparseVec != null) {
          for (var entry : sparseVec.entrySet()) {
            float weight = Math.min(entry.getValue(), 64.0f);
            if (weight > 0.0f) {
              doc.add(new FeatureField(def.id, entry.getKey(), weight));
              count++;
            }
          }
        }
      }
      default -> {
        // ignore unknown types
      }
    }
    return count;
  }

  /**
   * Converts from the configuration POJO to the internal map structure.
   */
  private static Map<String, FieldDef> convertFromPojo(FieldCatalogDef catalog) {
    Map<String, FieldDef> map = new HashMap<>();
    for (FieldCatalogDef.FieldDef field : catalog.fields()) {
      map.put(field.id(), FieldDef.fromPojo(field));
    }
    return map;
  }

  private static Map<String, FieldDef> loadCatalogFromPath(Path path, boolean explicit) {
    Objects.requireNonNull(path, "path");
    if (!Files.exists(path)) {
      String msg = "Field catalog not found: " + path;
      if (explicit) {
        throw new IllegalStateException(msg);
      }
      log.warn(msg);
      throw new IllegalStateException(msg);
    }
    try (InputStream in = Files.newInputStream(path)) {
      return loadCatalogTree(M.readTree(in));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load field catalog: " + path, e);
    }
  }

  private static Map<String, FieldDef> loadCatalogTree(JsonNode root) {
    Objects.requireNonNull(root, "root");
    Map<String, FieldDef> map = new HashMap<>();
    for (JsonNode n : root.withArray("fields")) {
      String id = n.path("id").asText();
      String type = n.path("type").asText();
      boolean stored = n.path("stored").asBoolean(false);
      boolean docValues = n.path("docValues").asBoolean(false);
      List<String> roles = new ArrayList<>();
      for (JsonNode roleNode : n.withArray("roles")) roles.add(roleNode.asText());
      Integer dim = n.has("vector") ? n.path("vector").path("dimension").asInt() : null;
      if (dim != null && dim == 0) dim = null;
      String analyzer = n.has("analyzer") ? n.path("analyzer").asText(null) : null;
      if (analyzer != null && analyzer.isBlank()) analyzer = null;
      boolean multiValued = n.path("multiValued").asBoolean(false);
      map.put(id, new FieldDef(id, type, stored, docValues, roles, dim, analyzer, multiValued));
    }
    return map;
  }

  /**
   * Converts a value to a list of strings for multi-valued field indexing.
   * Null inputs return empty list. List inputs have null entries removed.
   * Non-list inputs are wrapped as a single-element list via {@link #asString}.
   * Nested lists are stringified (not flattened) — callers should pass flat lists.
   */
  private static List<String> asList(Object v) {
    if (v == null) return List.of();
    if (v instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        if (item != null) result.add(String.valueOf(item));
      }
      return result;
    }
    String s = asString(v);
    return s != null ? List.of(s) : List.of();
  }

  private static String asString(Object v) {
    if (v == null) return null;
    if (v instanceof String s) return s;
    return String.valueOf(v);
  }

  private static Long asLong(Object v) {
    if (v instanceof Number n) return n.longValue();
    try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignored) { return null; }
  }

  private static Boolean asBoolean(Object v) {
    if (v instanceof Boolean b) return b;
    if (v instanceof Number n) return n.longValue() != 0L;
    if (v != null) return Boolean.parseBoolean(String.valueOf(v).toLowerCase(Locale.ROOT));
    return null;
  }

  @SuppressWarnings("unchecked")
  private static float[] asFloatArray(Object v) {
    if (v == null) return null;
    if (v instanceof float[] fa) return fa;
    if (v instanceof double[] da) {
      float[] out = new float[da.length];
      for (int i = 0; i < da.length; i++) out[i] = (float) da[i];
      return out;
    }
    if (v instanceof List<?> list) {
      float[] out = new float[list.size()];
      for (int i = 0; i < list.size(); i++) out[i] = ((Number) list.get(i)).floatValue();
      return out;
    }
    return null;
  }

  private static void validateMultiValuedConstraints(Map<String, FieldDef> fields) {
    for (FieldDef def : fields.values()) {
      if (def.multiValued && !def.docValues) {
        throw new IllegalStateException(
            "Multi-valued field '" + def.id + "' requires docValues=true");
      }
      if (def.multiValued && def.roles != null && def.roles.contains("id")) {
        throw new IllegalStateException(
            "Multi-valued field '" + def.id + "' cannot have 'id' role");
      }
    }
  }

  private static FieldDef resolvePrimaryKey(Map<String, FieldDef> fields) {
    return fields.values().stream()
        .filter(def -> def.roles != null && def.roles.contains("id"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Field catalog must define a primary key field with role 'id'"));
  }

  private static FieldDef resolveDocUid(Map<String, FieldDef> fields) {
    return fields.get(SchemaFields.DOC_UID);
  }
}
