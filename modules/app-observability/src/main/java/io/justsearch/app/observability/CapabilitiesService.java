/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.prompts.PromptTemplateException;
import io.justsearch.prompts.PromptTemplateLoader;
import io.justsearch.app.util.RepoPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds a capabilities payload describing the current SSOT state and prompt templates.
 *
 * <p>The payload is derived from SSOT artifacts ({@code ssot_snapshot.json}) and prompt packs.
 */
public final class CapabilitiesService {
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private static final HexFormat HEX = HexFormat.of();

  private final Path repoRoot;
  private final PromptTemplateLoader promptTemplateLoader;
  private final java.util.function.LongSupplier catalogVersionSupplier;

  public CapabilitiesService() {
    this(RepoPaths.findRepoRoot(), () -> 0L);
  }

  CapabilitiesService(Path repoRoot) {
    this(repoRoot, () -> 0L);
  }

  /**
   * Production constructor (per tempdoc 429 §F.6 closure). The supplier is queried
   * once per {@link #capabilities()} call so the field reflects the live
   * {@code CapabilitiesChangeRegistry.currentSeq()} (renamed from
   * {@code currentVersion} per slice 436 §B retrofit — the counter is now the SSE
   * envelope seq exposed via the underlying {@code SseStreamChannel}).
   */
  public CapabilitiesService(Path repoRoot, java.util.function.LongSupplier catalogVersionSupplier) {
    this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
    this.catalogVersionSupplier =
        Objects.requireNonNull(catalogVersionSupplier, "catalogVersionSupplier");
    this.promptTemplateLoader = new PromptTemplateLoader(this.repoRoot);
  }

  /** Returns the current capabilities view matching {@code capabilities-view.schema.json}. */
  public CapabilitiesView capabilities() {
    return capabilities(catalogVersionSupplier.getAsLong());
  }

  /**
   * Returns the current capabilities view with a specific {@code catalogVersion} for the
   * {@code serverCapabilities} field. Per tempdoc 429 §C.E + §E.15: the version is a
   * monotonic long incremented on every catalog mutation; it advances on snapshot, change,
   * and heartbeat events emitted by {@code CapabilitiesChangeRegistry}.
   */
  public CapabilitiesView capabilities(long catalogVersion) {
    SsotSnapshot snapshot = loadSnapshot();

    List<PromptTemplateView> prompts = loadPromptTemplates();

    List<PluginView> plugins = List.of();

    SchemaVersions schemaVersions =
        new SchemaVersions(snapshot.schemaVer(), snapshot.grammarVer(), snapshot.templateVer());

    SourceMetadata source =
        new SourceMetadata("phase-7", snapshot.schemaVer(), Instant.now().toString());

    ServerCapabilities serverCapabilities = buildServerCapabilities(catalogVersion);

    return new CapabilitiesView(schemaVersions, prompts, plugins, source, serverCapabilities);
  }

  private static ServerCapabilities buildServerCapabilities(long catalogVersion) {
    Map<String, PrimitiveDescriptor> primitives = new LinkedHashMap<>();
    primitives.put(
        "Operation",
        new PrimitiveDescriptor(
            "v1",
            "/api/registry/operations",
            "/api/messages/registry-operation/{locale}",
            true));
    primitives.put(
        "Resource",
        new PrimitiveDescriptor(
            "v1",
            "/api/registry/resources",
            "/api/messages/registry-resource/{locale}",
            true));
    primitives.put(
        "Prompt",
        new PrimitiveDescriptor(
            "v1",
            "/api/registry/prompts",
            "/api/messages/registry-prompt/{locale}",
            true));
    // Slice 448 phase 4: DiagnosticChannel — the fourth registry primitive (operator-trace
    // surfaces). Per CONFLICT-LEDGER C-012 path-b chosen 2026-05-07. Capabilities handshake
    // is a Map<String, PrimitiveDescriptor> open to extension; adding this entry is purely
    // declarative — no proto regen, no FE code changes (the FE consumes
    // Record<string, PrimitiveDescriptor>).
    primitives.put(
        "DiagnosticChannel",
        new PrimitiveDescriptor(
            "v1",
            "/api/registry/diagnostic-channels",
            "/api/messages/registry-diagnostic/{locale}",
            true));
    // Slice 443: i18n capability slot. Single locale ("en") today; future locales add to
    // the list. Version pinned at 1; bumping the version is a breaking change for FE
    // consumers, while adding a new optional sibling field within version 1 is not.
    I18nCapability i18n = new I18nCapability(1, List.of("en"));
    // Slice 436 §B.8: streamingEnvelope advertisement. FE consumers feature-detect the
    // universal envelope shape; absence implies bespoke per-endpoint shape.
    StreamingEnvelopeCapability streamingEnvelope = new StreamingEnvelopeCapability(1);
    // Slice 3a-1-8 Phase 6: per-Category contract version map. V1 carries one
    // Category (wire). Future Categories (3a-1-8b plugin Category, 3a-1-8d
    // catalog Categories) add entries without breaking the wire shape.
    // Source-of-truth is contracts/wire/VERSION; the constant in
    // io.justsearch.app.api.stream.WireContractVersion mirrors it.
    Map<String, String> contractVersions = new LinkedHashMap<>();
    contractVersions.put("wire", io.justsearch.app.api.stream.WireContractVersion.CURRENT);
    // Tempdoc 508 §11.8 / §13.8 — per-sub-API contract versions for PluginHostApi
    // sub-interfaces. Plugins declare which sub-APIs they consume in
    // PluginManifest.contractVersions; the existing per-Category validator
    // (PluginRegistry.assertCompatibleAgainstHostCategories) then checks per-key
    // major-match + ≥-minor against this map. Adding new sub-API keys here is
    // additive — V1 plugins that don't declare host.* keys continue to work.
    contractVersions.put("host.data", "1.0");
    contractVersions.put("host.search", "1.0");
    contractVersions.put("host.navigation", "1.0");
    contractVersions.put("host.ui", "1.0");
    contractVersions.put("host.discovery", "1.0");
    contractVersions.put("host.settings", "1.0");
    contractVersions.put("host.platform", "1.0");
    contractVersions.put("host.inspector", "1.0");
    contractVersions.put("host.theme", "1.0");
    contractVersions.put("host.layout", "1.0");
    contractVersions.put("host.utilities", "1.0");
    contractVersions.put("host.registration", "1.0");
    // Tempdoc 508-followup §γ2 — promoted from §13 critical-analysis A3
    // (which removed the phantom advertise) to a real sub-interface.
    // UNTRUSTED plugins get read-only (current + subscribe); TRUSTED+
    // and CORE additionally get setSelection + clearSelection. The
    // attenuation is per-tier composition in HostApiImpl, not a
    // runtime trust check.
    contractVersions.put("host.selection", "1.0");
    // Tempdoc 508-followup §ε1 — host.ai is now 1.0. The original 0.9
    // shipped invokeShape / streamShape / openSession; §ε1 added
    // getSessionTranscript + getSessionMetadata, and §β1 fixed the
    // backend shape-dispatch contract so shapeId is honored, not
    // silently ignored. The combination promotes the sub-interface
    // out of experimental.
    contractVersions.put("host.ai", "1.0");
    // Slice 449 phase 3: Manifest tier slot. Surface is the first Manifest;
    // Plugin reification deferred to V1.5 plugin maturity. Per §B.A.5 the
    // addition is purely additive (proto field 7 unallocated, JSON consumers
    // ignore unknown fields), so V1 consumers ignoring `manifests` still
    // function correctly.
    Map<String, ManifestDescriptor> manifests = new LinkedHashMap<>();
    manifests.put(
        "Surface",
        new ManifestDescriptor(
            "v1",
            "/api/registry/surfaces",
            "/api/messages/registry-surface/{locale}",
            true));
    return new ServerCapabilities(
        primitives,
        catalogVersion,
        "1.0",
        i18n,
        streamingEnvelope,
        Map.copyOf(contractVersions),
        Map.copyOf(manifests));
  }

  private List<PromptTemplateView> loadPromptTemplates() {
    Path promptsDir = repoRoot.resolve("SSOT/prompts");
    if (!Files.isDirectory(promptsDir)) {
      return List.of();
    }
    List<PromptTemplateView> templates = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(promptsDir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> {
            String name = path.getFileName().toString();
            return name.endsWith(".json") || name.endsWith(".mustache");
          })
          .forEach(path -> templates.add(readPromptTemplate(path)));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to enumerate prompt templates under " + promptsDir, e);
    }
    templates.sort(Comparator.comparing(PromptTemplateView::taskId));
    return List.copyOf(templates);
  }

  private PromptTemplateView readPromptTemplate(Path path) {
    String fileName = path.getFileName().toString();
    if (fileName.endsWith(".mustache")) {
      return readMustacheTemplate(path);
    }
    try {
      JsonNode node = JSON.readTree(path.toFile());
      String taskId = textOrFallback(node.path("task_id"), stripExtension(path.getFileName().toString()));
      String templateVer = deriveTemplateVersion(path.getFileName().toString());
      String hash = sha256(path);
      return new PromptTemplateView(taskId, templateVer, hash);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read prompt template " + path, e);
    }
  }

  private PromptTemplateView readMustacheTemplate(Path path) {
    try {
      var metadata = promptTemplateLoader.loadMetadataOnly(path);
      String templateVer =
          metadata.attributes().getOrDefault("template_ver", metadata.templateId()).toString();
      String hash = sha256(path);
      return new PromptTemplateView(metadata.taskId(), templateVer, hash);
    } catch (PromptTemplateException e) {
      throw new IllegalStateException("Failed to parse prompt template " + path, e);
    }
  }

  private static String stripExtension(String fileName) {
    int idx = fileName.lastIndexOf('.');
    return idx > 0 ? fileName.substring(0, idx) : fileName;
  }

  private static String deriveTemplateVersion(String fileName) {
    int firstDot = fileName.indexOf('.');
    int lastDot = fileName.lastIndexOf('.');
    if (firstDot > 0 && lastDot > firstDot) {
      return fileName.substring(firstDot + 1, lastDot);
    }
    return "v1";
  }

  private static String textOrFallback(JsonNode node, String fallback) {
    String value = node.isTextual() ? node.asText() : null;
    return (value == null || value.isBlank()) ? fallback : value;
  }

  private static String sha256(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = Files.readAllBytes(path);
      return HEX.formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new IllegalStateException("Failed to hash prompt template " + path, e);
    }
  }

  private SsotSnapshot loadSnapshot() {
    try (InputStream in = CapabilitiesService.class.getResourceAsStream("/ssot_snapshot.json")) {
      if (in == null) {
        throw new IllegalStateException("Missing ssot_snapshot.json on classpath");
      }
      JsonNode root = JSON.readTree(in).path("ssot");
      return new SsotSnapshot(
          root.path("schema_ver").asText(),
          root.path("grammar_ver").asText(),
          root.path("template_ver").asInt());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read ssot_snapshot.json", e);
    }
  }

  /** JSON payload aligned with {@code capabilities-view.schema.json}. */
  public record CapabilitiesView(
      @JsonProperty("schema_versions") SchemaVersions schemaVersions,
      @JsonProperty("prompt_templates") List<PromptTemplateView> promptTemplates,
      @JsonProperty("plugins") List<PluginView> plugins,
      @JsonProperty("source") SourceMetadata source,
      @JsonProperty("serverCapabilities") ServerCapabilities serverCapabilities) {}

  /**
   * LSP-style capability handshake declaration (per tempdoc 429 §A.4 + §E.15).
   * Declares the registered registry primitives with their endpoints + per-locale
   * message catalog URLs (per tempdoc 434 §8). The FE pre-fetches the message catalogs
   * at handshake time.
   *
   * <p>Per slice 443: typed-slot envelope for cross-feature capability advertising. New
   * slots populate as each dependent slice ships their substrate; FE consumers feature-
   * detect against per-slot {@code version} fields. Per the LSP soft-fail discipline,
   * unknown slots are ignored client-side; bumping a slot's {@code version} is a breaking
   * change while adding a new optional sibling field within an existing version is not.
   */
  public record ServerCapabilities(
      @JsonProperty("primitives") Map<String, PrimitiveDescriptor> primitives,
      @JsonProperty("catalogVersion") long catalogVersion,
      @JsonProperty("protocolVersion") String protocolVersion,
      @JsonProperty("i18n") I18nCapability i18n,
      @JsonProperty("streamingEnvelope") StreamingEnvelopeCapability streamingEnvelope,
      // Slice 3a-1-8 Phase 6: per-Category contract version map. V1: { "wire": "0.1.0" }.
      // Future Categories add entries without breaking the wire shape.
      @JsonProperty("contractVersions") Map<String, String> contractVersions,
      // Slice 449 phase 3: Manifest tier slot. V1 ships one entry: `Surface`.
      // Plugin reification into the same map is deferred to V1.5 plugin maturity
      // per slice 449 §6. Per slice 449 §B.A.5 the field is purely additive.
      @JsonProperty("manifests") Map<String, ManifestDescriptor> manifests) {}

  /**
   * I18n slot — declares supported message-catalog locales. Per slice 443 §"What populates
   * today": the per-primitive {@code messageCatalogUrl} fields template a {@code {locale}}
   * placeholder; this slot enumerates which substitutions are valid. Today only English
   * ships; future locales add to {@code availableLocales}.
   */
  public record I18nCapability(
      @JsonProperty("version") int version,
      @JsonProperty("availableLocales") List<String> availableLocales) {}

  /**
   * Streaming envelope slot — per slice 436 §B.8: FE consumers feature-detect that the
   * server emits the universal SSE envelope (single-frame-event, frameKind discriminator,
   * resume-token semantics). Absence of this slot implies the bespoke per-endpoint shape
   * (event-named SSE frames). Version 1 = the slice 436 envelope shape.
   */
  public record StreamingEnvelopeCapability(@JsonProperty("version") int version) {}

  /** Per-primitive entry within {@link ServerCapabilities#primitives()}. */
  public record PrimitiveDescriptor(
      @JsonProperty("current") String current,
      @JsonProperty("endpoint") String endpoint,
      @JsonProperty("messageCatalogUrl") String messageCatalogUrl,
      @JsonProperty("dynamicRegistration") boolean dynamicRegistration) {}

  /**
   * Slice 449 phase 3: per-Manifest entry within
   * {@link ServerCapabilities#manifests()}. Same wire shape as
   * {@link PrimitiveDescriptor} but modeled as a separate type so future
   * Manifest-specific fields can be added without affecting primitives.
   *
   * <p>Manifests differ from Primitives in the framework's ontology
   * (composition layer over primitives) but the descriptor wire shape is
   * intentionally identical — discovery semantics are the same.
   */
  public record ManifestDescriptor(
      @JsonProperty("current") String current,
      @JsonProperty("endpoint") String endpoint,
      @JsonProperty("messageCatalogUrl") String messageCatalogUrl,
      @JsonProperty("dynamicRegistration") boolean dynamicRegistration) {}

  public record SchemaVersions(
      @JsonProperty("schema_ver") String schemaVer,
      @JsonProperty("grammar_ver") String grammarVer,
      @JsonProperty("template_ver") Object templateVer) {}

  public record PromptTemplateView(
      @JsonProperty("task_id") String taskId,
      @JsonProperty("template_ver") String templateVer,
      @JsonProperty("hash") String hash) {}

  public record PluginView(
      @JsonProperty("id") String id,
      @JsonProperty("version") String version,
      @JsonProperty("spis") List<String> spis) {}

  public record SourceMetadata(
      @JsonProperty("phase") String phase,
      @JsonProperty("schema_ver") String schemaVer,
      @JsonProperty("generated_at") String generatedAt) {}

  private record SsotSnapshot(
      String schemaVer,
      String grammarVer,
      int templateVer) {}
}
