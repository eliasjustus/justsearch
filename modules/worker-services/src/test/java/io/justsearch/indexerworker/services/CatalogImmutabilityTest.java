package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.Metric;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 417 F2 fix: structural test verifying every {@link Metric}-typed instrument field on
 * {@link IndexRuntimeMetricCatalog} is {@code final}. Catches regressions where someone
 * accidentally drops the {@code final} modifier and reintroduces the mutable two-phase init
 * footgun.
 */
final class CatalogImmutabilityTest {

  @Test
  void allInstrumentFieldsAreFinal() {
    Class<?> catalog = IndexRuntimeMetricCatalog.class;
    int instrumentFieldCount = 0;
    for (Field f : catalog.getDeclaredFields()) {
      if (!Metric.class.isAssignableFrom(f.getType())) continue;
      instrumentFieldCount++;
      assertTrue(
          Modifier.isFinal(f.getModifiers()),
          "Instrument field '"
              + f.getName()
              + "' on IndexRuntimeMetricCatalog must be final (F2 invariant)");
    }
    assertTrue(
        instrumentFieldCount >= 9,
        "expected at least 9 instrument fields on IndexRuntimeMetricCatalog; got "
            + instrumentFieldCount);
  }
}
