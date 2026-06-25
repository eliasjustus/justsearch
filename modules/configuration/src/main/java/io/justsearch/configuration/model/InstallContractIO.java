/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the install contract JSON file.
 *
 * <p>The contract is stored as {@code install-contract.v2.json} in the AI Home directory. The
 * install pipeline writes it after successful completion; the runtime reads it on startup.
 */
public final class InstallContractIO {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private InstallContractIO() {}

  /** Reads the install contract from the AI Home directory. Returns null if not found. */
  public static InstallContract read(Path homeDir) {
    Path contractPath = homeDir.resolve(InstallContract.CONTRACT_FILENAME);
    if (!Files.isRegularFile(contractPath)) {
      return null;
    }
    // Jackson 3.x readValue throws JacksonException (RuntimeException), not IOException
    return JSON.readValue(contractPath.toFile(), InstallContract.class);
  }

  /** Writes the install contract to the AI Home directory. Creates parent directories. */
  public static void write(InstallContract contract, Path homeDir) {
    Path contractPath = homeDir.resolve(InstallContract.CONTRACT_FILENAME);
    try {
      Files.createDirectories(homeDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create contract directory: " + homeDir, e);
    }
    // Jackson 3.x writeValue throws JacksonException (RuntimeException), not IOException
    JSON.writeValue(contractPath.toFile(), contract);
  }
}
