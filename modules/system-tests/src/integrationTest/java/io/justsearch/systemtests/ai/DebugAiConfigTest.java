package io.justsearch.systemtests.ai;

import org.junit.jupiter.api.Test;

class DebugAiConfigTest {
  @Test
  void debugConfig() {
    System.out.println("=== DEBUG AI CONFIG ===");
    System.out.println("User Dir: " + System.getProperty("user.dir"));

    try {
      var tinyLlama = AiQualityTestConfig.findTinyLlamaModel();
      System.out.println("TinyLlama found: " + tinyLlama);
    } catch (Exception e) {
      System.out.println("TinyLlama NOT found: " + e.getMessage());
    }

    try {
      var nomic = AiQualityTestConfig.findNomicEmbedModel();
      System.out.println("Nomic found: " + nomic);
    } catch (Exception e) {
      System.out.println("Nomic NOT found: " + e.getMessage());
    }

    System.out.println("Native FFM backend: removed (using ONNX embedding)");
    System.out.println("=======================");
  }
}
