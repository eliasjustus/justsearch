/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 840 B4 — pins the regression the registry relocation fixes.
 *
 * <p>Before tempdoc 840, {@code ai/model-registry.v2.json} lived only under {@code
 * modules/ui/src/main/resources/}, so app-services reached it at runtime purely by classpath
 * coincidence: {@code app-services} does not and must not depend on {@code modules:ui}, and
 * {@code ModelRegistryLoader.loadFromClasspath} resolved the resource via {@code
 * Thread.currentThread().getContextClassLoader()}, which happens to see the head process's
 * classpath at runtime but has no compile-time guarantee of doing so — and does NOT see it on
 * app-services' own test classpath, which is exactly why {@code ModelRegistryLoaderTest} had to
 * keep a second copy of the registry under {@code modules/configuration/src/test/resources/}.
 *
 * <p>The registry now ships as a main resource of {@code modules:configuration}, which {@code
 * modules:app-services} depends on via {@code api(project(":modules:configuration"))}. This test
 * proves the resource is reachable from app-services' own test classpath without any special
 * classloader trick and without a duplicated test-only copy.
 */
final class ModelRegistryClasspathReachabilityTest {

  @Test
  void registryResourceIsReachableFromAppServicesTestClasspath() {
    ModelRegistry registry = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertEquals(2, registry.schemaVersion());
    assertEquals(8, registry.packages().size());
    assertNotNull(registry.findPackage("embedding"));
    assertNotNull(registry.findPackage("splade"));
    assertNotNull(registry.findPackage("reranker"));
    assertNotNull(registry.findPackage("ner"));
    assertNotNull(registry.findPackage("citation-scorer"));
    assertNotNull(registry.findPackage("chat"));
    // The dev-only compact chat package (tempdoc 842) ships in the same registry — it is excluded
    // from install plans by its devOnly flag, not by being absent from the model-identity authority.
    assertNotNull(registry.findPackage("chat-compact"));
    assertNotNull(registry.findPackage("cuda-runtime"));
  }
}
