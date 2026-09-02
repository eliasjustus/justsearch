/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 885 review S4 — the freshness watermark across a mid-session re-open.
 *
 * <p>{@code RuntimeSession.openComponents} runs up to three times within ONE session (the
 * corruption-recovery and rebuild paths re-open components on the same session, which holds a
 * single {@code final NrtReopenStats}). The second writer starts its sequence numbers low again,
 * so a watermark carried over from the first — and {@code recordCovered} max-accumulates precisely
 * so a late writer cannot lower it — would never match again, and every foreground query in
 * {@code on_demand} mode would refresh forever: the mode silently cancelled, with no error and no
 * log line.
 *
 * <p>{@code install} therefore SETS both baselines rather than accumulating. This test is the
 * mechanism, not the symptom: the symptom (reopen count) is invisible, because a refresh that finds
 * nothing changed reports {@code didRefresh=false} and never increments the counter — which is why
 * the earlier "untouched index does not reopen" test passed against the unfixed code and proved
 * nothing.
 */
final class NrtReopenStatsTest {

  @TempDir Path tmp;

  private static IndexWriter writer(Directory dir) throws Exception {
    return new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()));
  }

  private static void add(IndexWriter w, String id) throws Exception {
    Document d = new Document();
    d.add(new StringField("id", id, Field.Store.NO));
    w.addDocument(d);
  }

  @Test
  @DisplayName("a mid-session re-open reseeds the watermark from the NEW writer")
  void reinstallReseedsTheWatermarkFromTheNewWriter() throws Exception {
    NrtReopenStats stats = new NrtReopenStats();

    try (Directory dirA = new ByteBuffersDirectory();
        Directory dirB = new ByteBuffersDirectory()) {
      IndexWriter a = writer(dirA);
      for (int i = 0; i < 25; i++) {
        add(a, "a-" + i);
      }
      a.commit();
      SearcherManager mgrA = new SearcherManager(a, null);
      stats.install(mgrA, new AtomicLong(), a);
      long seqA = stats.seqNoAtLastReopen.get();
      assertTrue(seqA > 0, "the first writer's watermark is its own sequence number, not a sentinel");

      // The recovery/rebuild path: a brand-new writer on a fresh directory, whose sequence
      // numbers restart well below the first writer's.
      IndexWriter b = writer(dirB);
      SearcherManager mgrB = new SearcherManager(b, null);
      stats.install(mgrB, new AtomicLong(), b);

      assertEquals(
          b.getMaxCompletedSequenceNumber(),
          stats.seqNoAtLastReopen.get(),
          "the re-open must reseed from the new writer; keeping the old, higher watermark would "
              + "make every subsequent query refresh forever");
      assertTrue(
          stats.seqNoAtLastReopen.get() < seqA,
          "the new writer's sequence number is genuinely lower — this is the case that breaks");

      mgrA.close();
      mgrB.close();
      a.close();
      b.close();
    }
  }

  @Test
  @DisplayName("recordCovered never lowers the watermark within one writer's life")
  void recordCoveredNeverGoesBackwards() {
    NrtReopenStats stats = new NrtReopenStats();
    stats.recordCovered(100L);
    stats.recordCovered(40L);
    assertEquals(
        100L,
        stats.seqNoAtLastReopen.get(),
        "a late concurrent writer must not lower the watermark and re-trigger refreshes");
  }
}
