/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

public record GpuDiagnosticsView(
    OrtCudaView rerankerOrtCuda,
    OrtCudaView spladeOrtCuda,
    OrtCudaView embedOrtCuda,
    String embedBackend,
    int embedGpuLayers,
    String spladeModelPath,
    String rerankerModelPath,
    String nerModelPath,
    boolean nerGpuEnabled,
    OrtCudaView nerOrtCuda,
    OrtCudaView citationOrtCuda,
    OrtCudaView bgeM3OrtCuda) {
  public GpuDiagnosticsView {
    embedBackend = embedBackend == null ? "" : embedBackend;
    spladeModelPath = spladeModelPath == null ? "" : spladeModelPath;
    rerankerModelPath = rerankerModelPath == null ? "" : rerankerModelPath;
    spladeOrtCuda = spladeOrtCuda == null ? OrtCudaView.notConfigured() : spladeOrtCuda;
    embedOrtCuda = embedOrtCuda == null ? OrtCudaView.notConfigured() : embedOrtCuda;
    nerOrtCuda = nerOrtCuda == null ? OrtCudaView.notConfigured() : nerOrtCuda;
    citationOrtCuda = citationOrtCuda == null ? OrtCudaView.notConfigured() : citationOrtCuda;
    bgeM3OrtCuda = bgeM3OrtCuda == null ? OrtCudaView.notConfigured() : bgeM3OrtCuda;
  }

  public static GpuDiagnosticsView empty() {
    return new GpuDiagnosticsView(
        OrtCudaView.notConfigured(), OrtCudaView.notConfigured(), OrtCudaView.notConfigured(),
        "", 0, "", "", "", false,
        OrtCudaView.notConfigured(), OrtCudaView.notConfigured(), OrtCudaView.notConfigured());
  }
}
