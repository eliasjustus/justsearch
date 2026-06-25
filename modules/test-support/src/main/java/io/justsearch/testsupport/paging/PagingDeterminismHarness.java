/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.testsupport.paging;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.justsearch.testsupport.docs.SampleDoc;
import io.justsearch.testsupport.fixtures.MiniIndexFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Drives deterministic paging flows across fixture documents and emits stable JSON traces.
 */
public final class PagingDeterminismHarness {
  private static final int DEFAULT_PAGE_SIZE = 10;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MiniIndexFixture fixture;

  private PagingDeterminismHarness(MiniIndexFixture fixture) {
    this.fixture = Objects.requireNonNull(fixture, "fixture");
  }

  public static PagingDeterminismHarness from(MiniIndexFixture fixture) {
    return new PagingDeterminismHarness(fixture);
  }

  public PagingTrace capture(String scenario, int pages) {
    Objects.requireNonNull(scenario, "scenario");
    int pageSize = DEFAULT_PAGE_SIZE;
    List<SampleDoc> docs = fixture.docs();
    List<PageWindow> windows = new ArrayList<>();
    for (int page = 0; page < pages; page++) {
      int start = page * pageSize;
      if (start >= docs.size()) {
        break;
      }
      int end = Math.min(start + pageSize, docs.size());
      List<String> docIds =
          docs.subList(start, end).stream().map(SampleDoc::id).toList();
      windows.add(new PageWindow(page + 1, docIds));
    }
    PageWindowAssert.assertNoDuplicates(windows);
    PageWindowAssert.assertNoHoles(windows);
    return new PagingTrace(
        scenario,
        fixture.handles().pagingConfig(),
        windows,
        Instant.now());
  }

  public void writeJson(PagingTrace trace, Path path) {
    Objects.requireNonNull(trace, "trace");
    Objects.requireNonNull(path, "path");
    try {
      Files.createDirectories(path.getParent());
      ObjectNode root = MAPPER.createObjectNode();
      root.put("scenario", trace.scenario());
      root.put("paging_config", trace.pagingConfig());
      root.put("captured_at", trace.capturedAt().toString());
      ArrayNode windows = root.putArray("windows");
      for (PageWindow window : trace.windows()) {
        ObjectNode windowNode = windows.addObject();
        windowNode.put("page_number", window.pageNumber());
        ArrayNode ids = windowNode.putArray("doc_ids");
        window.docIds().forEach(ids::add);
      }
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write paging trace", e);
    }
  }

  public record PagingTrace(
      String scenario,
      String pagingConfig,
      List<PageWindow> windows,
      Instant capturedAt) {
    public PagingTrace {
      windows = List.copyOf(windows);
    }
  }

  public record PageWindow(int pageNumber, List<String> docIds) {
    public PageWindow {
      if (pageNumber <= 0) {
        throw new IllegalArgumentException("pageNumber must be positive");
      }
      docIds = List.copyOf(docIds);
    }

    public Set<String> asSet() {
      return Collections.unmodifiableSet(new LinkedHashSet<>(docIds));
    }
  }
}
