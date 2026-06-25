/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsRequest;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import io.justsearch.ipc.ListFolderFilesRequest;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersRequest;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.MatchCitationsRequest;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import java.util.Objects;

/**
 * Delegating wrapper for SearchService that enables runtime service swapping.
 *
 * <p>Registered once with the gRPC server. All RPC calls are forwarded to a {@code volatile}
 * delegate that can be swapped without restarting the gRPC server. The delegate is typed as the
 * generated ImplBase to support cross-classloader hot-reload (Phase 2, tempdoc 305).
 *
 * <p>Non-RPC operations (model wiring, GPU lifecycle) are routed through {@code WorkerAppServices},
 * not through this wrapper.
 */
public final class DelegatingSearchService extends SearchServiceGrpc.SearchServiceImplBase {

  private volatile SearchServiceGrpc.SearchServiceImplBase delegate;

  public DelegatingSearchService(SearchServiceGrpc.SearchServiceImplBase delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public void setDelegate(SearchServiceGrpc.SearchServiceImplBase delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  // ==================== RPC forwards ====================

  @Override
  public void search(SearchRequest req, StreamObserver<SearchResponse> obs) {
    delegate.search(req, obs);
  }

  @Override
  public void suggest(SuggestRequest req, StreamObserver<SuggestResponse> obs) {
    delegate.suggest(req, obs);
  }

  @Override
  public void fetchDocuments(FetchDocumentsRequest req, StreamObserver<FetchDocumentsResponse> obs) {
    delegate.fetchDocuments(req, obs);
  }

  @Override
  public void fetchDocumentSlice(
      FetchDocumentSliceRequest req, StreamObserver<FetchDocumentSliceResponse> obs) {
    delegate.fetchDocumentSlice(req, obs);
  }

  @Override
  public void retrieveContext(
      RetrieveContextRequest req, StreamObserver<RetrieveContextResponse> obs) {
    delegate.retrieveContext(req, obs);
  }

  @Override
  public void matchCitations(
      MatchCitationsRequest req, StreamObserver<MatchCitationsResponse> obs) {
    delegate.matchCitations(req, obs);
  }

  @Override
  public void listFolders(ListFoldersRequest req, StreamObserver<ListFoldersResponse> obs) {
    delegate.listFolders(req, obs);
  }

  @Override
  public void listFolderFiles(
      ListFolderFilesRequest req, StreamObserver<ListFolderFilesResponse> obs) {
    delegate.listFolderFiles(req, obs);
  }

  @Override
  public void listAllDocumentIds(
      ListAllDocumentIdsRequest req, StreamObserver<ListAllDocumentIdsResponse> obs) {
    delegate.listAllDocumentIds(req, obs);
  }

  @Override
  public void rerank(RerankRequest req, StreamObserver<RerankResponse> obs) {
    delegate.rerank(req, obs);
  }
}
