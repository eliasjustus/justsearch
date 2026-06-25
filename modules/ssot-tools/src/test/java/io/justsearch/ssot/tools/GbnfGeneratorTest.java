package io.justsearch.ssot.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GbnfGeneratorTest {

  @TempDir Path tempDir;

  @Test
  void generatesGbnfAndManifest() throws Exception {
    Path ssotRoot = tempDir.resolve("SSOT");
    Files.createDirectories(ssotRoot.resolve("schemas/domain"));
    Files.writeString(ssotRoot.resolve("schemas/domain/search-intent.schema.json"), "{}");

    GbnfGenerator.main(new String[] {ssotRoot.toString()});

    Path gbnf = ssotRoot.resolve("artifacts/grammars/intent_v1.gbnf");
    Path manifest = ssotRoot.resolve("artifacts/grammars/manifest.json");
    assertTrue(Files.exists(gbnf), "GBNF file should exist");
    assertTrue(Files.size(gbnf) > 100, "GBNF should contain grammar rules");
    assertTrue(Files.exists(manifest), "manifest.json should exist");
    assertTrue(Files.readString(manifest).contains("source"), "manifest should have source key");
  }
}
