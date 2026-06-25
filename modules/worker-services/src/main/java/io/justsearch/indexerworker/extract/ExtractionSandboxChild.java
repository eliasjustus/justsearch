/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.PrintStream;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Minimal child-process entry point for out-of-process extraction. */
public final class ExtractionSandboxChild {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ExtractionSandboxChild() {}

  public static void main(String[] args) throws Exception {
    PrintStream protocolOut = System.out;
    System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    String requestJson = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    SandboxExtractionRequest request =
        MAPPER.readValue(requestJson, SandboxExtractionRequest.class);
    TikaExtractionPolicy policy =
        request.policy() == null ? TikaExtractionPolicy.defaults() : request.policy();
    OcrRoutingConfig ocrConfig =
        request.ocrConfig() == null ? OcrRoutingConfig.disabled() : request.ocrConfig();
    SandboxExtractionResponse response;
    try {
      ExtractionArtifact artifact =
          new PolicyDrivenTikaExtractor(policy, ocrConfig)
              .extractArtifact(Path.of(request.path()))
              .validateContentBoundsOnly(policy.maxExtractedChars());
      response = SandboxExtractionResponse.fromArtifact(artifact);
    } catch (ContentExtractor.BudgetExceededException e) {
      response =
          SandboxExtractionResponse.failed(
              ExtractionStatus.BUDGET_EXCEEDED, policy, "sandbox-child", "Budget exceeded", e.reasonCode());
    } catch (Exception e) {
      response =
          SandboxExtractionResponse.failed(
              ExtractionStatus.FAILED, policy, "sandbox-child", "Sandbox parser failed", "PARSER_FAILED");
    }
    protocolOut.write(MAPPER.writeValueAsBytes(response));
    protocolOut.flush();
  }
}
