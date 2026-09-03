/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import static io.justsearch.configuration.EnvRegistry.LifecycleStage.EXPERIMENTAL;
import static io.justsearch.configuration.EnvRegistry.LifecycleStage.PERMANENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConfigLifecycleTest {

    @Test
    void everyDeclarationHasALifecycleStage() {
        for (EnvRegistry entry : EnvRegistry.values()) {
            assertNotNull(entry.lifecycleStage(), entry.name());
        }
        for (ConfigKey entry : ConfigKey.values()) {
            assertNotNull(entry.lifecycleStage(), entry.name());
        }
    }

    @Test
    void onlyExistingExplicitExperimentsAreNonPermanent() {
        Set<EnvRegistry> experimental = EnumSet.allOf(EnvRegistry.class).stream()
            .filter(entry -> entry.lifecycleStage() == EXPERIMENTAL)
            .collect(Collectors.toSet());

        assertEquals(Set.of(EnvRegistry.QU_ENABLED, EnvRegistry.FILTER_NORM_ENABLED), experimental);
        assertEquals(
            (long) ConfigKey.values().length,
            EnumSet.allOf(ConfigKey.class).stream()
                .filter(entry -> entry.lifecycleStage() == PERMANENT)
                .count());
    }
}
