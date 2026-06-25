package io.justsearch.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PromptTemplateLoaderTest {

  @Test
  void loadsDefaultBlock() throws Exception {
    Path promptsDir = createTemplate(
        "intent/intent.v1.mustache",
        """
        {
          "template_id": "intent.v1",
          "schema_ver": "intent_v1",
          "default_locale": "en-US",
          "task_id": "intent_v1",
          "required_params": ["query"]
        }
        ---
        {{query}}
        """);
    PromptTemplateLoader loader = new PromptTemplateLoader(promptsDir);
    PromptTemplateUri uri = PromptTemplateUri.parse("ssot://prompts/en/intent/intent.v1.mustache");
    PromptTemplate template = loader.load(uri, Locale.forLanguageTag("en-US"));
    PromptRenderResult result =
        template.block("default").render(Map.of("query", "hello world"), Locale.ENGLISH);
    assertEquals("hello world", result.text());
  }

  @Test
  void failsWhenRequiredParamsMissing() throws Exception {
    Path promptsDir =
        createTemplate(
            "intent/intent.v2.mustache",
            """
            {
              "template_id": "intent.v2",
              "schema_ver": "intent_v2",
              "default_locale": "en-US",
              "task_id": "intent_v2",
              "required_params": []
            }
            ---
            {{query}}
            """);
    PromptTemplateLoader loader = new PromptTemplateLoader(promptsDir);
    PromptTemplateUri uri =
        PromptTemplateUri.parse("ssot://prompts/en/intent/intent.v2.mustache");
    PromptTemplateException ex =
        assertThrows(
            PromptTemplateException.class,
            () -> loader.load(uri, Locale.forLanguageTag("en-US")));
    assertTrue(
        ex.getMessage().contains("does not declare required_params"),
        "expected required_params validation message");
  }

  @Test
  void failsWhenPlaceholderNotDeclared() throws Exception {
    Path promptsDir =
        createTemplate(
            "intent/intent.v3.mustache",
            """
            {
              "template_id": "intent.v3",
              "schema_ver": "intent_v3",
              "default_locale": "en-US",
              "task_id": "intent_v3",
              "required_params": ["locale"]
            }
            ---
            {{query}}
            """);
    PromptTemplateLoader loader = new PromptTemplateLoader(promptsDir);
    PromptTemplateUri uri =
        PromptTemplateUri.parse("ssot://prompts/en/intent/intent.v3.mustache");
    PromptTemplateException ex =
        assertThrows(
            PromptTemplateException.class,
            () -> loader.load(uri, Locale.forLanguageTag("en-US")));
    assertTrue(
        ex.getMessage().contains("undeclared placeholder"),
        "expected undeclared placeholder message");
  }

  @Test
  void rendersSsotFeaturesWithoutNashornRuntime() throws Exception {
    Path promptsDir =
        createTemplate(
            "summary/summary.rag.v1.mustache",
            """
            {
              "template_id": "summary.rag.v1",
              "schema_ver": "summary_v1",
              "default_locale": "en-US",
              "task_id": "summary_v1",
              "required_params": ["language", "retrieved_passages", "this", "stub_payload"]
            }
            ---
            {{#if retrieved_passages}}{{#retrieved_passages}}[{{this}}]{{/retrieved_passages}}{{/if}}
            Locale={{default language "en-US"}}
            Raw={{{stub_payload.payload_json}}}
            """);
    PromptTemplateLoader loader = new PromptTemplateLoader(promptsDir);
    PromptTemplateUri uri =
        PromptTemplateUri.parse("ssot://prompts/en/summary/summary.rag.v1.mustache");
    PromptTemplate template = loader.load(uri, Locale.forLanguageTag("en-US"));
    PromptRenderResult result =
        template.block("default")
            .render(
                Map.of(
                    "language", "",
                    "retrieved_passages", List.of("p1", "p2"),
                    "stub_payload", Map.of("payload_json", "{\"ok\":true}")),
                Locale.ENGLISH);
    assertTrue(result.text().contains("[p1][p2]"), "expected section iteration output");
    assertTrue(result.text().contains("Locale=en-US"), "expected custom default helper output");
    assertTrue(result.text().contains("Raw={\"ok\":true}"), "expected triple-brace output");
  }

  @Test
  void nashornEngineFactoryIsAbsentOnClasspath() {
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory"));
  }

  private Path createTemplate(String relative, String content) throws IOException {
    Path repoRoot = Files.createTempDirectory("prompt-template-loader-test");
    repoRoot.toFile().deleteOnExit();
    Path promptsDir = repoRoot.resolve("SSOT").resolve("prompts").resolve("en");
    Path target = promptsDir.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
    return repoRoot;
  }
}
