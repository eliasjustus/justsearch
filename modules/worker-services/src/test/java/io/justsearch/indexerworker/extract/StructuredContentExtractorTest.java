package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredContentExtractorTest {

  @TempDir Path tempDir;

  @Nested
  class BasicExtraction {

    @Test
    void plainTextFile() throws Exception {
      Path file = tempDir.resolve("test.txt");
      Files.writeString(file, "Hello, world!");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      assertTrue(result.content().contains("Hello, world"), result.content());
      assertTrue(result.isTextBased());
    }

    @Test
    void markdownFile() throws Exception {
      Path file = tempDir.resolve("readme.md");
      Files.writeString(file, "# Title\n\nSome body text.\n\n## Section\n\nMore text.");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      assertTrue(result.content().contains("Title"), result.content());
      assertTrue(result.content().contains("body text"), result.content());
      assertTrue(result.content().contains("Section"), result.content());
    }

    @Test
    void htmlFilePreservesStructure() throws Exception {
      Path file = tempDir.resolve("test.html");
      Files.writeString(
          file,
          """
          <html><body>
          <h1>Report</h1>
          <p>Introduction paragraph.</p>
          <table>
            <tr><th>Name</th><th>Value</th></tr>
            <tr><td>Revenue</td><td>$100M</td></tr>
          </table>
          </body></html>
          """);

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      String content = result.content();
      assertTrue(content.contains("Report"), "Should contain heading text");
      assertTrue(content.contains("Introduction"), "Should contain paragraph text");
      assertTrue(content.contains("Revenue"), "Should contain table data");
      assertTrue(content.contains("$100M"), "Should contain table values");
    }

    @Test
    void emptyFile() throws Exception {
      Path file = tempDir.resolve("empty.txt");
      Files.writeString(file, "");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertEquals("", result.content());
    }

    @Test
    void jsonFile() throws Exception {
      Path file = tempDir.resolve("data.json");
      Files.writeString(file, """
          {"name": "test", "value": 42}
          """);

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      assertTrue(result.content().contains("test"), result.content());
    }
  }

  @Nested
  class SizeGuards {

    @Test
    void nonExistentFile() {
      var extractor = new StructuredContentExtractor();
      assertThrows(IOException.class, () -> extractor.extract(tempDir.resolve("missing.txt")));
    }

    @Test
    void nullFile() {
      var extractor = new StructuredContentExtractor();
      assertThrows(NullPointerException.class, () -> extractor.extract(null));
    }
  }

  @Nested
  class FallbackBehavior {

    @Test
    void fallbackProducesSameContentAsFlat() throws Exception {
      // A simple text file should produce equivalent content from both extractors
      Path file = tempDir.resolve("simple.txt");
      Files.writeString(file, "Simple plain text content for testing.");

      var structured = new StructuredContentExtractor();
      var flat = new ContentExtractor();

      ExtractionResult structuredResult = structured.extract(file);
      ExtractionResult flatResult = flat.extract(file);

      // Both should contain the same key terms
      assertTrue(structuredResult.content().contains("Simple plain text"));
      assertTrue(flatResult.content().contains("Simple plain text"));
    }
  }

  @Nested
  class MimeDetection {

    @Test
    void detectsTextPlain() throws Exception {
      Path file = tempDir.resolve("test.txt");
      Files.writeString(file, "text content");

      var extractor = new StructuredContentExtractor();
      String mime = extractor.detectMimeType(file);
      assertTrue(mime.startsWith("text/"), "Expected text/* MIME type, got: " + mime);
    }

    @Test
    void detectsHtml() throws Exception {
      Path file = tempDir.resolve("test.html");
      Files.writeString(file, "<html><body>Hello</body></html>");

      var extractor = new StructuredContentExtractor();
      String mime = extractor.detectMimeType(file);
      assertTrue(
          mime.contains("html") || mime.contains("xml"),
          "Expected HTML MIME type, got: " + mime);
    }
  }

  @Nested
  class FrontmatterTitle {

    @Test
    void extractsYamlFrontmatter() throws Exception {
      Path file = tempDir.resolve("doc.md");
      Files.writeString(file, "---\ntitle: My Document\n---\n\nContent here.");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertEquals("My Document", result.title());
    }
  }
}
