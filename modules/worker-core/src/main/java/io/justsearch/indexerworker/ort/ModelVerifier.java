/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ort;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.OrtCudaHelper;
import io.justsearch.ort.OrtSessionAssembler;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Standalone model verification harness for ORT session creation.
 *
 * <p>Loads an ONNX model with production session options (via
 * {@link OrtSessionAssembler#verifyModelSession}) and runs a minimal dummy input through the
 * session. Reports: load success, output shape/dtype, inference latency.
 *
 * <p>Designed to be invoked as a {@code JavaExec} Gradle task ({@code verifyModel}), running in the
 * same JVM with the same ORT JAR and the same session options as production — zero version mismatch
 * risk.
 *
 * <p>Usage: {@code java ... ModelVerifier <modelPath> [--gpu] [--device <id>] [--mem-mb <mb>]
 * [--native-path <path>]}
 */
public final class ModelVerifier {

  private ModelVerifier() {}

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println(
          "Usage: ModelVerifier <modelPath> [--gpu] [--device <id>]"
              + " [--mem-mb <mb>] [--native-path <path>]");
      System.exit(1);
    }

    Path modelPath = Path.of(args[0]).toAbsolutePath().normalize();
    boolean gpu = false;
    int deviceId = 0;
    long memMb = 4096;
    Path nativePath = null;

    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--gpu" -> gpu = true;
        case "--device" -> deviceId = Integer.parseInt(args[++i]);
        case "--mem-mb" -> memMb = Long.parseLong(args[++i]);
        case "--native-path" -> nativePath = Path.of(args[++i]).toAbsolutePath().normalize();
        default -> {
          System.err.println("Unknown argument: " + args[i]);
          System.exit(1);
        }
      }
    }

    if (!Files.exists(modelPath)) {
      System.err.println("Model file not found: " + modelPath);
      System.exit(1);
    }

    try {
      run(modelPath, gpu, deviceId, memMb, nativePath);
    } catch (Exception e) {
      System.err.println("FAILED: " + e.getMessage());
      e.printStackTrace(System.err);
      System.exit(2);
    }
  }

  @SuppressWarnings("PMD.SystemPrintln")
  static void run(Path modelPath, boolean gpu, int deviceId, long memMb, Path nativePath)
      throws OrtException {
    OrtEnvironment env = OrtEnvironment.getEnvironment();

    System.out.println("Model: " + modelPath);
    System.out.println("Mode: " + (gpu ? "GPU (CUDA)" : "CPU"));

    long loadStart = System.nanoTime();
    GpuSessionConfig config = null;
    if (gpu) {
      if (nativePath == null) {
        nativePath = OrtCudaHelper.resolveOrtNativePath(modelPath.getParent());
      }
      OrtCudaHelper.prepareCudaDependencies(nativePath);
      config = new GpuSessionConfig(deviceId, memMb * 1024 * 1024);
    }
    OrtSession session = OrtSessionAssembler.verifyModelSession(env, modelPath, config);
    long loadMs = (System.nanoTime() - loadStart) / 1_000_000;
    System.out.println("Session created in " + loadMs + "ms");

    // Print input/output metadata
    Set<String> inputNames = session.getInputNames();
    System.out.println("Inputs: " + inputNames);
    Set<String> outputNames = session.getOutputNames();
    System.out.println("Outputs: " + outputNames);

    // Run dummy inference with minimal input
    int seqLen = 3; // minimal 3-token input for shape verification
    long[] inputIds = {101, 2023, 102}; // arbitrary valid token IDs (BERT CLS/SEP for BERT models)
    long[] attentionMask = {1, 1, 1};
    long[] shape = {1, seqLen};

    Map<String, OnnxTensor> inputs = new HashMap<>();
    try {
      inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
      inputs.put(
          "attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape));
      if (inputNames.contains("token_type_ids")) {
        inputs.put(
            "token_type_ids",
            OnnxTensor.createTensor(env, LongBuffer.wrap(new long[seqLen]), shape));
      }

      long inferStart = System.nanoTime();
      try (OrtSession.Result result = session.run(inputs)) {
        long inferMs = (System.nanoTime() - inferStart) / 1_000_000;

        for (int i = 0; i < result.size(); i++) {
          var info = result.get(i).getInfo();
          System.out.println("Output[" + i + "]: " + info);
        }
        System.out.println("Inference completed in " + inferMs + "ms");
      }
    } finally {
      for (OnnxTensor tensor : inputs.values()) {
        tensor.close();
      }
    }

    session.close();
    System.out.println("OK");
  }
}
