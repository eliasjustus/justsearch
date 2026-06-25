package io.justsearch.benchmarks.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MachineFingerprint}. */
class MachineFingerprintTest {

  @Test
  void capture_returnsNonNull() {
    MachineFingerprint fp = MachineFingerprint.capture();
    assertNotNull(fp);
  }

  @Test
  void capture_hostnameIsNonNull() {
    MachineFingerprint fp = MachineFingerprint.capture();
    assertNotNull(fp.hostname());
    // Hostname should be either valid or "unknown"
    assertFalse(fp.hostname().isEmpty());
  }

  @Test
  void capture_osNameIsNonNull() {
    MachineFingerprint fp = MachineFingerprint.capture();
    assertNotNull(fp.osName());
  }

  @Test
  void capture_osVersionIsNonNull() {
    MachineFingerprint fp = MachineFingerprint.capture();
    assertNotNull(fp.osVersion());
  }

  @Test
  void capture_javaVersionIsNonNull() {
    MachineFingerprint fp = MachineFingerprint.capture();
    assertNotNull(fp.javaVersion());
    // Should match System.getProperty("java.version")
    assertEquals(System.getProperty("java.version"), fp.javaVersion());
  }

  @Test
  void capture_availableProcessorsIsPositive() {
    MachineFingerprint fp = MachineFingerprint.capture();
    assertTrue(fp.availableProcessors() > 0, "Should have at least 1 processor");
    // Should match Runtime value
    assertEquals(Runtime.getRuntime().availableProcessors(), fp.availableProcessors());
  }

  @Test
  void capture_totalRamBytesIsPositiveOrNegativeOne() {
    MachineFingerprint fp = MachineFingerprint.capture();
    // totalRamBytes should be either -1 (unavailable) or a positive value
    assertTrue(
        fp.totalRamBytes() == -1 || fp.totalRamBytes() > 0,
        "totalRamBytes should be -1 or positive, but was: " + fp.totalRamBytes());
  }

  @Test
  void capture_totalRamBytesIsReasonable() {
    MachineFingerprint fp = MachineFingerprint.capture();
    if (fp.totalRamBytes() > 0) {
      // If available, RAM should be at least 256MB (reasonable minimum for any JVM)
      assertTrue(
          fp.totalRamBytes() >= 256L * 1024 * 1024,
          "totalRamBytes seems too small: " + fp.totalRamBytes());
      // And less than 100TB (sanity check)
      assertTrue(
          fp.totalRamBytes() < 100L * 1024 * 1024 * 1024 * 1024,
          "totalRamBytes seems too large: " + fp.totalRamBytes());
    }
  }

  @Test
  void toMap_containsAllFields() {
    MachineFingerprint fp = MachineFingerprint.capture();
    Map<String, Object> map = fp.toMap();

    assertNotNull(map);
    // Base fields always present
    assertTrue(map.containsKey("hostname"));
    assertTrue(map.containsKey("os_name"));
    assertTrue(map.containsKey("os_version"));
    assertTrue(map.containsKey("java_version"));
    assertTrue(map.containsKey("available_processors"));
    // total_ram_bytes is only included if positive
    if (fp.totalRamBytes() > 0) {
      assertTrue(map.containsKey("total_ram_bytes"));
      assertEquals(6, map.size());
    } else {
      assertFalse(map.containsKey("total_ram_bytes"));
      assertEquals(5, map.size());
    }
  }

  @Test
  void toMap_valuesMatchRecordFields() {
    MachineFingerprint fp = MachineFingerprint.capture();
    Map<String, Object> map = fp.toMap();

    assertEquals(fp.hostname(), map.get("hostname"));
    assertEquals(fp.osName(), map.get("os_name"));
    assertEquals(fp.osVersion(), map.get("os_version"));
    assertEquals(fp.javaVersion(), map.get("java_version"));
    assertEquals(fp.availableProcessors(), map.get("available_processors"));
    if (fp.totalRamBytes() > 0) {
      assertEquals(fp.totalRamBytes(), map.get("total_ram_bytes"));
    }
  }

  @Test
  void toMap_usesSnakeCaseKeys() {
    MachineFingerprint fp = MachineFingerprint.capture();
    Map<String, Object> map = fp.toMap();

    // Verify snake_case naming convention (for JSON serialization consistency)
    assertTrue(map.containsKey("os_name"), "Should use os_name, not osName");
    assertTrue(map.containsKey("os_version"), "Should use os_version, not osVersion");
    assertTrue(map.containsKey("java_version"), "Should use java_version, not javaVersion");
    assertTrue(
        map.containsKey("available_processors"),
        "Should use available_processors, not availableProcessors");
  }

  @Test
  void recordEquality_worksCorrectly() {
    // Two captures should generally be equal (same machine, same time)
    MachineFingerprint fp1 = MachineFingerprint.capture();
    MachineFingerprint fp2 = MachineFingerprint.capture();

    assertEquals(fp1, fp2);
    assertEquals(fp1.hashCode(), fp2.hashCode());
  }

  @Test
  void recordToString_isReadable() {
    MachineFingerprint fp = MachineFingerprint.capture();
    String str = fp.toString();

    assertNotNull(str);
    assertTrue(str.contains("hostname"));
    assertTrue(str.contains("osName"));
    assertTrue(str.contains("javaVersion"));
    assertTrue(str.contains("totalRamBytes"));
  }
}
