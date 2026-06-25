package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.services.LanguageUtils.FrontmatterResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LanguageUtils} frontmatter and content preview methods. */
class LanguageUtilsTest {

  // ==================== stripFrontmatter() ====================

  @Nested
  @DisplayName("stripFrontmatter()")
  class StripFrontmatterTests {

    @Test
    @DisplayName("null returns empty body, null frontmatter")
    void nullInput() {
      FrontmatterResult r = LanguageUtils.stripFrontmatter(null);
      assertEquals("", r.body());
      assertNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("empty string returns empty body")
    void emptyInput() {
      FrontmatterResult r = LanguageUtils.stripFrontmatter("");
      assertEquals("", r.body());
      assertNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("content without frontmatter is returned as body")
    void noFrontmatter() {
      String content = "Hello, World!\nThis is content.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals(content, r.body());
      assertNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("YAML frontmatter is stripped, body returned")
    void yamlFrontmatter() {
      String content = "---\ntitle: Test\ndescription: A test doc\n---\nBody text here.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals("Body text here.", r.body());
      assertEquals("title: Test\ndescription: A test doc\n", r.rawFrontmatter());
    }

    @Test
    @DisplayName("YAML frontmatter with immediate body")
    void yamlFrontmatterWithBody() {
      String content = "---\ntitle: Test\n---\nFirst paragraph.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals("First paragraph.", r.body());
      assertNotNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("Pandoc closing delimiter (...) is supported")
    void pandocClosing() {
      String content = "---\ntitle: Pandoc doc\n...\nBody after Pandoc style.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals("Body after Pandoc style.", r.body());
      assertEquals("title: Pandoc doc\n", r.rawFrontmatter());
    }

    @Test
    @DisplayName("TOML frontmatter (+++) is stripped")
    void tomlFrontmatter() {
      String content = "+++\ntitle = \"TOML Doc\"\n+++\nTOML body.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals("TOML body.", r.body());
      assertEquals("title = \"TOML Doc\"\n", r.rawFrontmatter());
    }

    @Test
    @DisplayName("empty frontmatter (---\\n---) returns empty body after close")
    void emptyFrontmatter() {
      String content = "---\n---\nBody after empty frontmatter.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals("Body after empty frontmatter.", r.body());
      assertEquals("", r.rawFrontmatter());
    }

    @Test
    @DisplayName("---- (4+ dashes) is not treated as frontmatter")
    void fourDashes() {
      String content = "----\ntitle: Not frontmatter\n---\nBody.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals(content, r.body());
      assertNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("++++ is not treated as TOML frontmatter")
    void fourPluses() {
      String content = "++++\ntitle = \"Not TOML\"\n+++\nBody.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals(content, r.body());
      assertNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("BOM before frontmatter is handled")
    void bomBeforeFrontmatter() {
      String content = "\uFEFF---\ntitle: BOM doc\n---\nBody.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals("Body.", r.body());
      assertNotNull(r.rawFrontmatter());
      assertTrue(r.rawFrontmatter().contains("title: BOM doc"));
    }

    @Test
    @DisplayName("Windows CRLF line endings are handled")
    void crlfLineEndings() {
      String content = "---\r\ntitle: CRLF doc\r\n---\r\nBody.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertTrue(r.body().contains("Body."));
      assertNotNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("no closing delimiter means no frontmatter")
    void noClosingDelimiter() {
      String content = "---\ntitle: Unclosed\nThis goes on forever...";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals(content, r.body());
      assertNull(r.rawFrontmatter());
    }

    @Test
    @DisplayName("--- not at start of content is not frontmatter")
    void dashesNotAtStart() {
      String content = "Some text first\n---\ntitle: Not frontmatter\n---\nBody.";
      FrontmatterResult r = LanguageUtils.stripFrontmatter(content);
      assertEquals(content, r.body());
      assertNull(r.rawFrontmatter());
    }
  }

  // ==================== extractDescription() ====================

  @Nested
  @DisplayName("extractDescription()")
  class ExtractDescriptionTests {

    @Test
    @DisplayName("null returns null")
    void nullInput() {
      assertNull(LanguageUtils.extractDescription(null));
    }

    @Test
    @DisplayName("blank returns null")
    void blankInput() {
      assertNull(LanguageUtils.extractDescription("  \n  "));
    }

    @Test
    @DisplayName("simple description value is extracted")
    void simpleDescription() {
      assertEquals(
          "A test document",
          LanguageUtils.extractDescription("title: Test\ndescription: A test document\ndate: 2024-01-01"));
    }

    @Test
    @DisplayName("double-quoted description is unquoted")
    void doubleQuotedDescription() {
      assertEquals(
          "A quoted description",
          LanguageUtils.extractDescription("description: \"A quoted description\""));
    }

    @Test
    @DisplayName("single-quoted description is unquoted")
    void singleQuotedDescription() {
      assertEquals(
          "Single quoted",
          LanguageUtils.extractDescription("description: 'Single quoted'"));
    }

    @Test
    @DisplayName("summary field is extracted as fallback")
    void summaryField() {
      assertEquals(
          "A summary",
          LanguageUtils.extractDescription("title: Test\nsummary: A summary"));
    }

    @Test
    @DisplayName("excerpt field is extracted as fallback")
    void excerptField() {
      assertEquals(
          "An excerpt",
          LanguageUtils.extractDescription("title: Test\nexcerpt: An excerpt"));
    }

    @Test
    @DisplayName("abstract field is extracted as fallback")
    void abstractField() {
      assertEquals(
          "An abstract",
          LanguageUtils.extractDescription("title: Test\nabstract: An abstract"));
    }

    @Test
    @DisplayName("description takes priority over summary")
    void descriptionPriority() {
      assertEquals(
          "Primary",
          LanguageUtils.extractDescription("summary: Secondary\ndescription: Primary"));
    }

    @Test
    @DisplayName("no description field returns null")
    void noDescriptionField() {
      assertNull(LanguageUtils.extractDescription("title: Test\ndate: 2024-01-01\ntags: [a, b]"));
    }

    @Test
    @DisplayName("block scalar (|) is extracted")
    void blockScalarLiteral() {
      String fm = "description: |\n  Line one.\n  Line two.";
      String result = LanguageUtils.extractDescription(fm);
      assertNotNull(result);
      assertTrue(result.contains("Line one."));
      assertTrue(result.contains("Line two."));
    }

    @Test
    @DisplayName("block scalar (>) folds into single line")
    void blockScalarFolded() {
      String fm = "description: >\n  First part\n  second part";
      String result = LanguageUtils.extractDescription(fm);
      assertNotNull(result);
      assertTrue(result.contains("First part"));
      assertTrue(result.contains("second part"));
    }

    @Test
    @DisplayName("indented key is not matched (nested object)")
    void indentedKeyIgnored() {
      String fm = "metadata:\n  description: Nested value\ntitle: Top level";
      assertNull(LanguageUtils.extractDescription(fm));
    }

    @Test
    @DisplayName("key prefix is not matched (e.g., my_description)")
    void keyPrefixNotMatched() {
      String fm = "my_description: Not a match\ntitle: Test";
      assertNull(LanguageUtils.extractDescription(fm));
    }

    @Test
    @DisplayName("TOML description = \"value\" is extracted")
    void tomlDescription() {
      assertEquals(
          "TOML description",
          LanguageUtils.extractDescription("title = \"Test\"\ndescription = \"TOML description\""));
    }

    @Test
    @DisplayName("TOML single-quoted value is extracted")
    void tomlSingleQuoted() {
      assertEquals(
          "TOML single",
          LanguageUtils.extractDescription("description = 'TOML single'"));
    }
  }

  // ==================== skipLeadingHeading() ====================

  @Nested
  @DisplayName("skipLeadingHeading()")
  class SkipLeadingHeadingTests {

    @Test
    @DisplayName("null returns empty string")
    void nullInput() {
      assertEquals("", LanguageUtils.skipLeadingHeading(null));
    }

    @Test
    @DisplayName("empty string returns empty")
    void emptyInput() {
      assertEquals("", LanguageUtils.skipLeadingHeading(""));
    }

    @Test
    @DisplayName("H1 heading is skipped")
    void h1Skipped() {
      String result = LanguageUtils.skipLeadingHeading("# Title\nBody text.");
      assertEquals("Body text.", result);
    }

    @Test
    @DisplayName("H2 heading is NOT skipped")
    void h2NotSkipped() {
      String body = "## Subtitle\nBody text.";
      assertEquals(body, LanguageUtils.skipLeadingHeading(body));
    }

    @Test
    @DisplayName("H1 after blank lines is skipped")
    void h1AfterBlankLines() {
      String result = LanguageUtils.skipLeadingHeading("\n\n# Title\nBody text.");
      assertEquals("Body text.", result);
    }

    @Test
    @DisplayName("heading after body text is not skipped")
    void headingAfterBody() {
      String body = "Some content first.\n# Title";
      assertEquals(body, LanguageUtils.skipLeadingHeading(body));
    }

    @Test
    @DisplayName("body without heading is unchanged")
    void noHeading() {
      String body = "Just regular text.\nMore text.";
      assertEquals(body, LanguageUtils.skipLeadingHeading(body));
    }

    @Test
    @DisplayName("bare # is treated as H1")
    void bareHash() {
      String result = LanguageUtils.skipLeadingHeading("#\nBody.");
      assertEquals("Body.", result);
    }
  }

  // ==================== contentPreview() end-to-end ====================

  @Nested
  @DisplayName("contentPreview() end-to-end")
  class ContentPreviewEndToEndTests {

    @Test
    @DisplayName("null returns empty string")
    void nullReturnsEmpty() {
      assertEquals("", LanguageUtils.contentPreview(null, 4096, true));
    }

    @Test
    @DisplayName("blank returns empty string")
    void blankReturnsEmpty() {
      assertEquals("", LanguageUtils.contentPreview("   ", 4096, true));
    }

    @Test
    @DisplayName("plain text without frontmatter is returned as-is")
    void plainText() {
      String text = "Hello, World!";
      assertEquals(text, LanguageUtils.contentPreview(text, 4096, true));
    }

    @Test
    @DisplayName("long content is truncated to maxChars")
    void longContentTruncated() {
      String text = "a".repeat(5000);
      String result = LanguageUtils.contentPreview(text, 4096, true);
      assertEquals(4096, result.length());
    }

    @Test
    @DisplayName("full markdown: description extracted, heading skipped, body follows")
    void fullMarkdownPipeline() {
      String content =
          "---\ntitle: Knowledge Server\ndescription: A guide to the knowledge server.\n---\n# Knowledge Server\n\nThe server manages indexing.";
      String result = LanguageUtils.contentPreview(content, 4096, true);

      // Description should be at the start
      assertTrue(result.startsWith("A guide to the knowledge server."), "Should start with description");
      // Body content should follow
      assertTrue(result.contains("The server manages indexing."), "Should contain body text");
      // Frontmatter should be gone
      assertFalse(result.contains("---"), "Should not contain frontmatter delimiters");
      // H1 heading should be skipped
      assertFalse(result.contains("# Knowledge Server"), "Should not contain H1 heading");
    }

    @Test
    @DisplayName("markdown without description: heading skipped, body returned")
    void markdownNoDescription() {
      String content = "---\ntitle: Test\ndate: 2024-01-01\n---\n# Test\n\nBody content here.";
      String result = LanguageUtils.contentPreview(content, 4096, true);

      assertFalse(result.contains("---"), "Should not contain frontmatter");
      assertFalse(result.contains("# Test"), "Should not contain H1");
      assertTrue(result.contains("Body content here."), "Should contain body");
    }

    @Test
    @DisplayName("markdown without frontmatter: heading skipped")
    void markdownNoFrontmatter() {
      String content = "# Title\n\nBody content.";
      String result = LanguageUtils.contentPreview(content, 4096, true);

      assertFalse(result.contains("# Title"), "Should not contain H1");
      assertTrue(result.contains("Body content."), "Should contain body");
    }

    @Test
    @DisplayName("plain text file is not affected")
    void plainTextUnaffected() {
      String content = "This is a plain text file.\nNo frontmatter here.";
      assertEquals(content, LanguageUtils.contentPreview(content, 4096, true));
    }

    @Test
    @DisplayName("description-only preview when body is empty after heading")
    void descriptionOnlyWhenEmptyBody() {
      String content = "---\ndescription: Just a description.\n---\n# Title";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertEquals("Just a description.", result);
    }

    @Test
    @DisplayName("TOML frontmatter with description")
    void tomlWithDescription() {
      String content = "+++\ntitle = \"TOML\"\ndescription = \"A TOML doc\"\n+++\n# TOML Title\n\nBody.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.startsWith("A TOML doc"), "Should start with TOML description");
      assertTrue(result.contains("Body."), "Should contain body");
    }

    @Test
    @DisplayName("BOM + frontmatter is handled end-to-end")
    void bomEndToEnd() {
      String content = "\uFEFF---\ndescription: BOM test\n---\n# Title\n\nContent.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.startsWith("BOM test"), "Should start with description despite BOM");
    }
  }

  // ==================== contentPreview() markdown stripping ====================

  @Nested
  @DisplayName("contentPreview() markdown stripping")
  class MarkdownStrippingTests {

    @Test
    @DisplayName("bold and italic syntax is stripped")
    void boldItalicStripped() {
      String content = "This has **bold** and *italic* text.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("**"), "Should not contain bold markers");
      assertFalse(result.contains("*italic*"), "Should not contain italic markers");
      assertTrue(result.contains("bold"), "Should preserve bold text");
      assertTrue(result.contains("italic"), "Should preserve italic text");
    }

    @Test
    @DisplayName("inline code backticks are stripped")
    void inlineCodeStripped() {
      String content = "Use the `println` function.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("`"), "Should not contain backticks");
      assertTrue(result.contains("println"), "Should preserve code text");
    }

    @Test
    @DisplayName("link syntax is stripped, text preserved")
    void linkStripped() {
      String content = "See [the docs](https://example.com) for details.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("[the docs]"), "Should not contain markdown link syntax");
      assertTrue(result.contains("the docs"), "Should preserve link text");
    }

    @Test
    @DisplayName("fenced code block content is preserved as text")
    void fencedCodeBlock() {
      String content = "Before.\n\n```java\nSystem.out.println(\"hello\");\n```\n\nAfter.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("```"), "Should not contain code fences");
      assertTrue(result.contains("System.out.println"), "Should preserve code content");
      assertTrue(result.contains("Before."), "Should preserve text before code block");
      assertTrue(result.contains("After."), "Should preserve text after code block");
    }

    @Test
    @DisplayName("blockquote markers are stripped")
    void blockquoteStripped() {
      String content = "> This is a quote.\n> Second line.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("> "), "Should not contain blockquote markers");
      assertTrue(result.contains("This is a quote."), "Should preserve quote text");
    }

    @Test
    @DisplayName("unordered list items are preserved")
    void unorderedList() {
      String content = "- Item one\n- Item two\n- Item three";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.contains("Item one"), "Should preserve list item text");
      assertTrue(result.contains("Item two"), "Should preserve list item text");
    }

    @Test
    @DisplayName("ordered list items are preserved")
    void orderedList() {
      String content = "1. First\n2. Second\n3. Third";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.contains("First"), "Should preserve list item text");
      assertTrue(result.contains("Second"), "Should preserve list item text");
    }

    @Test
    @DisplayName("H2-H6 headings have syntax stripped")
    void subheadingsStripped() {
      String content = "## Section Title\n\nContent under section.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("## "), "Should not contain heading markers");
      assertTrue(result.contains("Section Title"), "Should preserve heading text");
      assertTrue(result.contains("Content under section."), "Should preserve body content");
    }

    @Test
    @DisplayName("full markdown document with frontmatter strips everything")
    void fullDocumentEndToEnd() {
      String content =
          "---\ndescription: A guide.\n---\n# Title\n\n"
              + "This has **bold**, a [link](url), and `code`.\n\n"
              + "> A quote.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.startsWith("A guide."), "Should start with description");
      assertFalse(result.contains("**"), "Should not contain bold markers");
      assertFalse(result.contains("`code`"), "Should not contain backtick-wrapped code");
      assertFalse(result.contains("# Title"), "Should not contain H1 heading");
      assertFalse(result.contains("---"), "Should not contain frontmatter delimiters");
    }

    @Test
    @DisplayName("plain text without markdown syntax passes through unchanged")
    void plainTextUnchanged() {
      String content = "Just regular text with no markdown.";
      assertEquals(content, LanguageUtils.contentPreview(content, 4096, true));
    }

    @Test
    @DisplayName("description markdown syntax is stripped")
    void descriptionMarkdownStripped() {
      String content = "---\ndescription: A **bold** claim with [link](url)\n---\nBody text.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.startsWith("A bold claim"), "Description markdown should be stripped");
      assertFalse(result.contains("**"), "Should not contain bold markers in description");
      assertFalse(result.contains("[link]"), "Should not contain link syntax in description");
      assertTrue(result.contains("link"), "Should preserve link text in description");
    }
  }

  // ==================== contentPreview() whitespace collapsing ====================

  @Nested
  @DisplayName("contentPreview() whitespace collapsing")
  class WhitespaceCollapsingTests {

    @Test
    @DisplayName("3+ blank lines collapsed to single paragraph break")
    void multipleBlankLinesCollapsed() {
      String content = "Paragraph one.\n\n\n\nParagraph two.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("\n\n\n"), "Should not have 3+ consecutive newlines");
      assertTrue(result.contains("Paragraph one."), "Should preserve first paragraph");
      assertTrue(result.contains("Paragraph two."), "Should preserve second paragraph");
    }

    @Test
    @DisplayName("single blank line (paragraph break) is preserved")
    void singleBlankLinePreserved() {
      String content = "Paragraph one.\n\nParagraph two.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertTrue(result.contains("Paragraph one."), "Should preserve first paragraph");
      assertTrue(result.contains("Paragraph two."), "Should preserve second paragraph");
    }

    @Test
    @DisplayName("description + body composition does not produce triple newlines")
    void compositionWhitespace() {
      String content = "---\ndescription: Desc.\n---\n\n\nBody text.";
      String result = LanguageUtils.contentPreview(content, 4096, true);
      assertFalse(result.contains("\n\n\n"), "Should not have 3+ consecutive newlines");
      assertTrue(result.contains("Desc."), "Should contain description");
      assertTrue(result.contains("Body text."), "Should contain body");
    }
  }

  // ==================== contentPreview() MIME gating (non-markdown) ====================

  @Nested
  @DisplayName("contentPreview() non-markdown (isMarkdown=false)")
  class NonMarkdownPreviewTests {

    @Test
    @DisplayName("YAML multi-document is not treated as frontmatter")
    void yamlMultiDocPreserved() {
      String yaml = "---\napiVersion: v1\nkind: Service\n---\napiVersion: v1\nkind: ConfigMap";
      String result = LanguageUtils.contentPreview(yaml, 4096, false);
      assertTrue(result.contains("Service"), "First YAML document should be preserved");
      assertTrue(result.contains("ConfigMap"), "Second YAML document should be preserved");
    }

    @Test
    @DisplayName("# comment first line is preserved for non-markdown")
    void hashCommentPreserved() {
      String config = "# Configuration defaults\nDB_HOST=localhost\nDB_PORT=5432";
      String result = LanguageUtils.contentPreview(config, 4096, false);
      assertTrue(result.contains("# Configuration defaults"), "Comment line should be preserved");
    }

    @Test
    @DisplayName("--- is not converted to *** for non-markdown")
    void thematicBreakNotConverted() {
      String yaml = "---\nname: John Doe\nage: 30";
      String result = LanguageUtils.contentPreview(yaml, 4096, false);
      assertFalse(result.contains("***"), "Should not render thematic break");
      assertTrue(result.contains("---"), "Original --- should be preserved");
    }

    @Test
    @DisplayName("non-markdown content is just truncated")
    void nonMarkdownJustTruncated() {
      String longText = "a".repeat(5000);
      String result = LanguageUtils.contentPreview(longText, 4096, false);
      assertEquals(4096, result.length());
    }

    @Test
    @DisplayName("2-arg overload defaults to non-markdown")
    void twoArgOverloadDefaultsNonMarkdown() {
      String yaml = "---\napiVersion: v1\nkind: Service\n---\napiVersion: v1\nkind: ConfigMap";
      String result = LanguageUtils.contentPreview(yaml, 4096);
      assertTrue(result.contains("Service"), "Default should preserve YAML content");
    }
  }

  // ==================== extractFrontmatterMetadata() ====================

  @Nested
  @DisplayName("extractFrontmatterMetadata")
  class ExtractFrontmatterMetadataTests {

    @Test
    void parsesAllFrontmatterFields() {
      String content = "---\n"
          + "title: \"Test Article\"\n"
          + "author: \"Stan Choe\"\n"
          + "source: \"The Verge\"\n"
          + "category: \"technology\"\n"
          + "published_at: \"2023-11-27 08:45:59\"\n"
          + "---\nBody text.";
      var meta = LanguageUtils.extractFrontmatterMetadata(content);
      assertEquals("The Verge", meta.get("source"));
      assertEquals("Stan Choe", meta.get("author"));
      assertEquals("technology", meta.get("category"));
      assertEquals("2023-11-27 08:45:59", meta.get("published_at"));
      assertEquals(4, meta.size());
    }

    @Test
    void skipsAuthorWhenNone() {
      String content = "---\nauthor: \"None\"\nsource: Mashable\n---\nBody.";
      var meta = LanguageUtils.extractFrontmatterMetadata(content);
      assertNull(meta.get("author"), "author='None' should be skipped");
      assertEquals("Mashable", meta.get("source"));
    }

    @Test
    void returnsEmptyMapWhenNoFrontmatter() {
      var meta = LanguageUtils.extractFrontmatterMetadata("Just plain text.");
      assertTrue(meta.isEmpty());
    }

    @Test
    void returnsEmptyMapWhenFrontmatterHasNoTargetKeys() {
      String content = "---\ntitle: Something\nlayout: post\n---\nBody.";
      var meta = LanguageUtils.extractFrontmatterMetadata(content);
      assertTrue(meta.isEmpty(), "title and layout are not target metadata keys");
    }

    @Test
    void skipsBlankValues() {
      String content = "---\nsource: \"\"\ncategory: tech\n---\nBody.";
      var meta = LanguageUtils.extractFrontmatterMetadata(content);
      assertNull(meta.get("source"), "blank source should be skipped");
      assertEquals("tech", meta.get("category"));
    }
  }
}
