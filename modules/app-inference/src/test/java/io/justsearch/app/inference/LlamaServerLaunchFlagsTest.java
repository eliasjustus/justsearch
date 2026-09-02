/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact llama-server launch flags (tempdoc 883 decision 2).
 *
 * <p>These are asserted as an exact ordered list, not by {@code contains}: the fold's [R1]
 * measurement is that {@code -np} WITHOUT {@code -kvu} halves the window a request gets while
 * {@code /props} still reports the full one. A test that only checks each flag is present would
 * pass on the launch line that produces that silent halving.
 */
@DisplayName("llama-server launch flags")
final class LlamaServerLaunchFlagsTest {

  @Test
  @DisplayName("window flags carry the ladder rung and the offload layers, in that order")
  void windowFlags() {
    assertEquals(
        List.of("-c", "32768", "-ngl", "99"), LlamaServerOps.windowFlags(32768, 99));
    assertEquals(List.of("-c", "8192", "-ngl", "0"), LlamaServerOps.windowFlags(8192, 0));
  }

  @Test
  @DisplayName("slot + KV flags are exactly -np N -kvu -ctk T -ctv T -fa on")
  void slotAndKvFlags() {
    assertEquals(
        List.of("-np", "2", "-kvu", "-ctk", "q8_0", "-ctv", "q8_0", "-fa", "on"),
        LlamaServerOps.slotAndKvFlags(2, "q8_0"));
  }

  @Test
  @DisplayName("-kvu is always emitted alongside an explicit -np")
  void kvUnifiedAccompaniesExplicitSlots() {
    for (int slots : new int[] {1, 2, 4}) {
      List<String> flags = LlamaServerOps.slotAndKvFlags(slots, "f16");
      assertTrue(flags.contains("-np"), "slots are always explicit");
      assertTrue(
          flags.indexOf("-kvu") > flags.indexOf("-np"),
          "an explicit -np disables llama-server's automatic kv_unified, so -kvu must follow it"
              + " (fold [R1]: without it, -c 32768 -np 2 gives n_ctx_seq 16384 while /props still"
              + " reports 32768)");
    }
  }

  @Test
  @DisplayName("flash attention is 'on', never 'auto' — a quantized V-cache aborts without it")
  void flashAttentionIsExplicit() {
    List<String> flags = LlamaServerOps.slotAndKvFlags(2, "q8_0");
    assertEquals("on", flags.get(flags.indexOf("-fa") + 1));
  }

  @Test
  @DisplayName("K and V cache types are the same type, from one key")
  void cacheTypesAgree() {
    List<String> flags = LlamaServerOps.slotAndKvFlags(2, "q4_0");
    assertEquals("q4_0", flags.get(flags.indexOf("-ctk") + 1));
    assertEquals("q4_0", flags.get(flags.indexOf("-ctv") + 1));
  }
}
