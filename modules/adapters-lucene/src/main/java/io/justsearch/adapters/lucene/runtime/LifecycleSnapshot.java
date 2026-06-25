/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.nio.file.Path;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;

/**
 * Immutable snapshot of Lucene infrastructure fields that are written together during {@code
 * applyComponents()} and nulled together during {@code close()}.
 *
 * <p>Published as a single volatile reference on {@link RuntimeSession#snapshot}. Reading one
 * volatile gives a consistent view of all infrastructure fields — eliminating the TOCTOU race where
 * {@code close()} nulls individual fields between separate volatile reads.
 */
record LifecycleSnapshot(
    Directory directory,
    IndexWriter writer,
    SearcherManager searcherManager,
    Path indexPath,
    boolean ephemeralPath,
    Analyzer indexAnalyzer) {}
