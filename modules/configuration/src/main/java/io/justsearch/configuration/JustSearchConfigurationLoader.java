/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized loader for JustSearch configuration artifacts.
 *
 * <p>This class is the <b>only</b> place where SSOT auto-discovery logic should live.
 * Low-level libraries should NOT scan the file system themselves—they should accept
 * pre-loaded configuration objects (POJOs) from this loader.
 *
 * <h2>Resolution Order</h2>
 * <ol>
 *   <li>Explicit path via system property or environment variable</li>
 *   <li>Repository layout (developer mode): traverse up from CWD looking for SSOT/</li>
 *   <li>Classpath resources (production mode): embedded in JAR</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Application bootstrap:
 * JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
 * FieldCatalogDef catalog = loader.loadFieldCatalog();
 *
 * // Pass to library (no auto-discovery in library):
 * FieldMapper mapper = new FieldMapper(catalog);
 * }</pre>
 */
public final class JustSearchConfigurationLoader {
    private static final Logger log = LoggerFactory.getLogger(JustSearchConfigurationLoader.class);
    // Jackson 3 defaults FAIL_ON_NULL_FOR_PRIMITIVES to true; our SSOT catalogs have null booleans.
    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder()
        .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private static final String CLASSPATH_FIELD_CATALOG = "/SSOT/catalogs/fields.v1.json";
    private static final String CLASSPATH_FIELD_CATALOG_ALT = "/catalogs/fields.v1.json";

    private final Path ssotRoot;
    private final boolean fromClasspath;

    /**
     * Creates a loader with auto-discovered SSOT location.
     */
    public JustSearchConfigurationLoader() {
        Path explicit = EnvRegistry.SSOT_PATH.getPath();
        if (explicit != null && Files.isDirectory(explicit)) {
            this.ssotRoot = explicit;
            this.fromClasspath = false;
            log.info("Using SSOT from explicit path: {}", ssotRoot);
        } else {
            Path repoRoot = RepoRootLocator.findRepoRootOrNull();
            if (repoRoot != null) {
                this.ssotRoot = repoRoot.resolve("SSOT");
                this.fromClasspath = false;
                log.info("Using SSOT from repo layout: {}", ssotRoot);
            } else {
                this.ssotRoot = null;
                this.fromClasspath = true;
                log.info("Using SSOT from classpath (production mode)");
            }
        }
    }

    /**
     * Creates a loader with an explicit SSOT root directory.
     *
     * <p>This constructor is useful for testing and for applications that
     * want to bypass auto-discovery entirely.
     *
     * @param ssotRoot the directory containing SSOT artifacts
     */
    public JustSearchConfigurationLoader(Path ssotRoot) {
        this.ssotRoot = Objects.requireNonNull(ssotRoot, "ssotRoot");
        this.fromClasspath = false;
    }

    /**
     * Returns the resolved SSOT root path, or empty if using classpath.
     */
    public Optional<Path> ssotRoot() {
        return Optional.ofNullable(ssotRoot);
    }

    /**
     * Returns true if configuration is being loaded from classpath (production mode).
     */
    public boolean isClasspathMode() {
        return fromClasspath;
    }

    /**
     * Loads the field catalog definition.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit path via {@link EnvRegistry#FIELD_CATALOG}</li>
     *   <li>SSOT directory (if available)</li>
     *   <li>Classpath resources</li>
     * </ol>
     *
     * @return the loaded field catalog
     * @throws ConfigurationException if loading fails
     */
    public FieldCatalogDef loadFieldCatalog() {
        // 1) Explicit override
        Path explicit = EnvRegistry.FIELD_CATALOG.getPath();
        if (explicit != null) {
            log.info("Loading field catalog from explicit path: {}", explicit);
            return loadFieldCatalogFromPath(explicit);
        }

        // 2) SSOT directory
        if (ssotRoot != null) {
            Path catalogPath = ssotRoot.resolve("catalogs").resolve("fields.v1.json");
            if (Files.exists(catalogPath)) {
                log.info("Loading field catalog from SSOT: {}", catalogPath);
                return loadFieldCatalogFromPath(catalogPath);
            }
        }

        // 3) Classpath
        log.info("Loading field catalog from classpath");
        return loadFieldCatalogFromClasspath();
    }

    /**
     * Loads the field catalog from a specific path.
     *
     * @param path the path to the catalog JSON file
     * @return the loaded field catalog
     * @throws ConfigurationException if loading fails
     */
    public FieldCatalogDef loadFieldCatalogFromPath(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return MAPPER.readValue(in, FieldCatalogDef.class);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load field catalog from: " + path, e);
        }
    }

    /**
     * Loads the field catalog from classpath resources.
     *
     * @return the loaded field catalog
     * @throws ConfigurationException if not found or loading fails
     */
    public FieldCatalogDef loadFieldCatalogFromClasspath() {
        InputStream stream = getClass().getResourceAsStream(CLASSPATH_FIELD_CATALOG);
        if (stream == null) {
            stream = getClass().getResourceAsStream(CLASSPATH_FIELD_CATALOG_ALT);
        }
        if (stream == null) {
            throw new ConfigurationException(
                    "Field catalog not found on classpath. Tried: " +
                    CLASSPATH_FIELD_CATALOG + ", " + CLASSPATH_FIELD_CATALOG_ALT);
        }
        try (InputStream in = stream) {
            return MAPPER.readValue(in, FieldCatalogDef.class);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load field catalog from classpath", e);
        }
    }

    /**
     * Resolves an SSOT-relative path, returning null if not available.
     *
     * @param relativePath path relative to SSOT root (e.g., "catalogs/fields.v1.json")
     * @return the resolved path, or null if SSOT is not available
     */
    public Path resolveSsotPath(String relativePath) {
        if (ssotRoot == null) {
            return null;
        }
        return ssotRoot.resolve(relativePath);
    }

    /**
     * Resolves the path to a pipeline definition file.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>SSOT directory (if available): {@code artifacts/pipelines/{name}.resolved.json}</li>
     *   <li>Returns null if SSOT is not available (caller should use classpath)</li>
     * </ol>
     *
     * @param pipelineName the pipeline name (e.g., "indexing.v1", "search.v1")
     * @return the resolved path, or null if SSOT is not available
     */
    public Path resolvePipelinePath(String pipelineName) {
        if (ssotRoot == null) {
            return null;
        }
        return ssotRoot.resolve("artifacts").resolve("pipelines")
                .resolve(pipelineName + ".resolved.json");
    }

    /**
     * Returns the repository root (parent of SSOT), or empty if using classpath.
     *
     * @return the repository root path, or empty if in classpath mode
     */
    public Optional<Path> repoRoot() {
        if (ssotRoot == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ssotRoot.getParent());
    }

    /**
     * Static convenience method for finding the repository root.
     *
     * <p>This is the canonical implementation for directory scanning. All classes
     * that need to find the SSOT directory should call this method rather than
     * implementing their own scanning logic.
     *
     * @return the repository root path, or null if not found
     */
    public static Path repoRootStatic() {
        return RepoRootLocator.findRepoRootOrNull();
    }

    /**
     * Loads the application YAML config and returns the raw {@code JsonNode} root.
     *
     * <p>Resolves the config file the same way {@code RuntimeConfig.load()} does
     * ({@link EnvRegistry#CONFIG_PATH} override, then {@code config/application.yaml}
     * relative to repo root), but without schema validation or accessor construction.
     *
     * <p>Returns {@link Optional#empty()} if the config file does not exist (logged at DEBUG)
     * or cannot be parsed (logged at WARN). Callers no longer need try/catch blocks.
     */
    public static Optional<tools.jackson.databind.JsonNode> loadYamlRoot() {
        String override = EnvRegistry.CONFIG_PATH.get().orElse(null);
        Path configFile;
        if (override != null && !override.isBlank()) {
            configFile = Path.of(override);
        } else {
            Path repoRoot = RepoRootLocator.findRepoRoot();
            configFile = repoRoot.resolve("config").resolve("application.yaml");
        }
        if (!Files.exists(configFile)) {
            log.debug("No YAML config at {}, using defaults", configFile);
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new tools.jackson.dataformat.yaml.YAMLMapper().readTree(configFile.toFile()));
        } catch (Exception e) {
            log.warn("Failed to parse YAML config at {}: {}", configFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Exception thrown when configuration loading fails.
     */
    public static final class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
