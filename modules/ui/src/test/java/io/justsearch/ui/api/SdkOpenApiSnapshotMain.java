/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministically regenerates the committed runtime-client OpenAPI input. */
public final class SdkOpenApiSnapshotMain {
  private SdkOpenApiSnapshotMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("expected output path");
    Path output = Path.of(args[0]).toAbsolutePath().normalize();
    Files.createDirectories(output.getParent());
    Files.write(output, SdkOpenApiFixture.document());
    System.out.println("wrote " + output);
  }
}
