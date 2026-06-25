package io.justsearch.adapters.lucene.runtime;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension that saves and restores system properties around each test.
 *
 * <p>Usage:
 * <pre>{@code
 * @RegisterExtension
 * SystemPropertyExtension sysprops = new SystemPropertyExtension("justsearch.config");
 * }</pre>
 *
 * <p>Properties listed at construction time are saved before each test and restored after,
 * regardless of whether the test passes or fails. Tests can freely call
 * {@code System.setProperty()} without manual try/finally cleanup.
 */
final class SystemPropertyExtension implements BeforeEachCallback, AfterEachCallback {

    private final String[] keys;
    private final Map<String, String> saved = new HashMap<>();

    SystemPropertyExtension(String... keys) {
        this.keys = keys;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        for (String key : keys) {
            saved.put(key, System.getProperty(key));
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        saved.clear();
    }
}
