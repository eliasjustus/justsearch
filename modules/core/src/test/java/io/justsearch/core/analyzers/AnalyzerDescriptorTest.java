package io.justsearch.core.analyzers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AnalyzerDescriptorTest {
  @Test
  void recordAccessorsWork() {
    AnalyzerDescriptor d = new AnalyzerDescriptor("std", "Standard analyzer");
    assertEquals("std", d.id());
    assertEquals("Standard analyzer", d.description());
  }
}
