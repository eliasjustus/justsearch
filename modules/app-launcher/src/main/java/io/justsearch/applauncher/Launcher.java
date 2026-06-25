/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.app.util.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application entry point for the launcher module. */
public final class Launcher {
  private static final Logger log = LoggerFactory.getLogger(Launcher.class);
  private static volatile EnvironmentFactory environmentFactory = LauncherEnvironment::create;
  private static volatile CommandRunnerFactory commandRunnerFactory = LauncherCommands::new;
  private static volatile SmokeDriverFactory smokeDriverFactory = SmokeDriver::create;

  public static void main(String[] rawArgs) {
    int exitCode = new Launcher().execute(rawArgs);
    System.exit(exitCode);
  }

  int execute(String[] rawArgs) {
    ensureDataDir();
    if (rawArgs.length == 0) {
      printUsage();
      return 1;
    }

    String command = rawArgs[0];
    try {
      if (looksLikeSmokeOptions(command, rawArgs)) {
        return runSmoke(rawArgs);
      }
      if ("--smoke".equals(command) || "smoke".equals(command)) {
        return runSmoke(stripFirst(rawArgs));
      }
      return switch (command) {
        case "ui" -> {
          log.error("The 'ui' command has been removed. Use the web UI instead:");
          log.error("  1. Start the backend: ./gradlew :modules:ui:runHeadless");
          log.error("  2. Open http://localhost:5173 in your browser");
          log.error("  Or use the Tauri shell for the full desktop experience.");
          yield 1;
        }
        case "reindex" -> runReindex(stripFirst(rawArgs));
        case "seed" -> runSeed(stripFirst(rawArgs));
        case "verify" -> runVerify(stripFirst(rawArgs));
        case "snapshot" -> runSnapshot(stripFirst(rawArgs));
        case "-h", "--help", "help" -> {
          printUsage();
          yield 0;
        }
        default -> {
          log.error("Unknown command '{}'.", command);
          printUsage();
          yield 1;
        }
      };
    } catch (IllegalArgumentException e) {
      log.error("Invalid arguments: {}", e.getMessage());
      return 1;
    } catch (Exception e) {
      log.error("Command '{}' failed: {}", command, e.toString(), e);
      return 2;
    }
  }

  private boolean looksLikeSmokeOptions(String command, String[] rawArgs) {
    if ("--smoke".equals(command) || "smoke".equals(command)) {
      return false;
    }
    if ("-h".equals(command) || "--help".equals(command) || "help".equals(command)) {
      return false;
    }
    if (command.startsWith("--profile") || command.startsWith("--diagnostics-run-id")) {
      return true;
    }
    // All args beginning with -- and no explicit command: treat as smoke options
    if (command.startsWith("--")) {
      return true;
    }
    // When only options follow an empty first arg (shouldn't happen), let runSmoke decide
    if (rawArgs.length > 1) {
      boolean allOptions = true;
      for (String arg : rawArgs) {
        if (!arg.startsWith("--")) {
          allOptions = false;
          break;
        }
      }
      return allOptions;
    }
    return false;
  }

  int runSmoke(String[] args) throws Exception {
    SmokeOptions options = parseSmokeOptions(args);
    try (SmokeDriverHandle driver = smokeDriverFactory.create(options)) {
      SmokeResult result = driver.execute();
      emitSmokeSummary(result, options);
      return result.exitCode();
    }
  }

  private int runReindex(String[] args) throws Exception {
    CommandOptions options = parseCommandOptions(args);
    try (LauncherEnvironment environment = environmentFactory.create(options.profile())) {
      CommandRunner commands = commandRunnerFactory.create(environment);
      LauncherCommands.CommandResult result = commands.reindex();
      emitCommandResult(result);
      return result.success() ? 0 : 2;
    }
  }

  private int runSeed(String[] args) throws Exception {
    SeedOptions options = parseSeedOptions(args);
    try (LauncherEnvironment environment = environmentFactory.create(options.profile())) {
      CommandRunner commands = commandRunnerFactory.create(environment);
      LauncherCommands.CommandResult result = commands.seed(options.fixtures());
      emitCommandResult(result);
      return result.success() ? 0 : 2;
    }
  }

  private int runVerify(String[] args) throws Exception {
    CommandOptions options = parseCommandOptions(args);
    try (LauncherEnvironment environment = environmentFactory.create(options.profile())) {
      CommandRunner commands = commandRunnerFactory.create(environment);
      LauncherCommands.CommandResult result = commands.verify();
      emitCommandResult(result);
      return result.success() ? 0 : 2;
    }
  }

  private int runSnapshot(String[] args) throws Exception {
    CommandOptions options = parseCommandOptions(args);
    try (LauncherEnvironment environment = environmentFactory.create(options.profile())) {
      CommandRunner commands = commandRunnerFactory.create(environment);
      LauncherCommands.CommandResult result = commands.snapshot();
      emitCommandResult(result);
      return result.success() ? 0 : 2;
    }
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private void emitSmokeSummary(SmokeResult result, SmokeOptions options) {
    System.out.println("JDK=" + System.getProperty("java.version"));
    if (result.egressBlocked()) {
      System.out.println("EGRESS/BLOCK=ON");
    }
    if (options.diagnosticsRunId() != null && !options.diagnosticsRunId().isBlank()) {
      System.out.println("RUN_ID=" + options.diagnosticsRunId());
    }
    for (String diagnostic : result.diagnostics()) {
      System.out.println(diagnostic);
    }
    if (result.failures().isEmpty()) {
      System.out.println("SMOKE/OK");
    } else {
      for (String failure : result.failures()) {
        System.out.println("SMOKE/FAIL code=" + failure);
      }
    }
  }

  SmokeOptions parseSmokeOptions(String[] args) {
    String profile = "smoke";
    String diagnosticsRunId = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--profile=")) {
        profile = splitOption(arg)[1];
        continue;
      }
      if ("--profile".equals(arg)) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("--profile requires a value");
        }
        profile = args[++i];
        continue;
      }
      if (arg.startsWith("--diagnostics-run-id=")) {
        diagnosticsRunId = splitOption(arg)[1];
        continue;
      }
      if ("--diagnostics-run-id".equals(arg)) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("--diagnostics-run-id requires a value");
        }
        diagnosticsRunId = args[++i];
        continue;
      }
      throw new IllegalArgumentException("Unknown option '" + arg + "'");
    }
    return new SmokeOptions(profile, diagnosticsRunId);
  }

  CommandOptions parseCommandOptions(String[] args) {
    String profile = "smoke";
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--profile=")) {
        profile = splitOption(arg)[1];
        continue;
      }
      if ("--profile".equals(arg)) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("--profile requires a value");
        }
        profile = args[++i];
        continue;
      }
      throw new IllegalArgumentException("Unknown option '" + arg + "'");
    }
    return new CommandOptions(profile);
  }

  private SeedOptions parseSeedOptions(String[] args) {
    String profile = "smoke";
    Path fixtures = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--profile=")) {
        profile = splitOption(arg)[1];
        continue;
      }
      if ("--profile".equals(arg)) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("--profile requires a value");
        }
        profile = args[++i];
        continue;
      }
      if (arg.startsWith("--fixtures=")) {
        fixtures = Paths.get(splitOption(arg)[1]);
        continue;
      }
      if ("--fixtures".equals(arg)) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("--fixtures requires a value");
        }
        fixtures = Paths.get(args[++i]);
        continue;
      }
      throw new IllegalArgumentException("Unknown option '" + arg + "'");
    }
    if (fixtures == null) {
      fixtures = RepoPaths.findRepoRoot().resolve("fixtures/demo/corpus.json");
    }
    return new SeedOptions(profile, fixtures);
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private void emitCommandResult(LauncherCommands.CommandResult result) {
    if (result == null) {
      return;
    }
    for (String marker : result.markers()) {
      System.out.println(marker);
    }
    if (!result.success() && result.error() != null) {
      log.error("Launcher command failed", result.error());
      // Also print to stderr for CLI consumers that don't capture logger output
      result.error().printStackTrace(System.err);
    }
  }

  private String[] stripFirst(String[] args) {
    if (args.length <= 1) {
      return new String[0];
    }
    String[] rest = new String[args.length - 1];
    System.arraycopy(args, 1, rest, 0, args.length - 1);
    return rest;
  }

  private String[] splitOption(String option) {
    int idx = option.indexOf('=');
    if (idx == -1) {
      throw new IllegalArgumentException("Option '" + option + "' must be provided as --key=value");
    }
    String key = option.substring(0, idx);
    String value = option.substring(idx + 1);
    if (value.isBlank()) {
      throw new IllegalArgumentException("Option '" + option + "' is missing a value");
    }
    return new String[] {key, value};
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private void printUsage() {
    System.out.println(
        """
        Usage:
          app-launcher --smoke [--profile=<name>] [--diagnostics-run-id=<id>]
          app-launcher simulate search|indexing

        The simulate subcommand validates the structural integrity of SSOT pipeline artifacts
        under SSOT/artifacts/pipelines/*.resolved.json.
        """);
  }

  private void ensureDataDir() {
    try {
      // Keep canonical + legacy aliases in sync (best-effort).
      // Note: logback may initialize during class loading, so this method alone is not sufficient
      // to prevent `${..._IS_UNDEFINED}` paths — use LauncherBootstrap for a hard guarantee.
      String resolved =
          firstNonBlank(
              EnvRegistry.DATA_DIR.get().orElse(null),
              System.getProperty("justsearch.data_dir"), // SYS-PROP-LEGACY-COMPAT
              System.getProperty("app.data_dir"));

      Path dataDir =
          resolved == null || resolved.isBlank()
              ? Paths.get("build", "applauncher-data").toAbsolutePath().normalize()
              : Path.of(resolved).toAbsolutePath().normalize();
      Files.createDirectories(dataDir);
      String normalized = dataDir.toString();

      setIfBlank("justsearch.data.dir", normalized);
      setIfBlank("justsearch.data_dir", normalized); // legacy underscore alias
      setIfBlank("app.data_dir", normalized); // legacy logback alias
    } catch (IOException e) {
      throw new IllegalStateException("Failed to initialize app.data_dir", e);
    }
  }

  private static void setIfBlank(String key, String value) {
    String existing = System.getProperty(key);
    if (existing == null || existing.isBlank()) {
      System.setProperty(key, value);
    }
  }

  private static String firstNonBlank(String... candidates) {
    if (candidates == null) return null;
    for (String c : candidates) {
      if (c != null && !c.isBlank()) {
        return c;
      }
    }
    return null;
  }

  record SmokeOptions(String profile, String diagnosticsRunId) {}

  record CommandOptions(String profile) {}

  record SeedOptions(String profile, Path fixtures) {}

  static void installFactories(
      EnvironmentFactory envFactory,
      CommandRunnerFactory commandsFactory,
      SmokeDriverFactory driverFactory) {
    environmentFactory = envFactory != null ? envFactory : LauncherEnvironment::create;
    commandRunnerFactory = commandsFactory != null ? commandsFactory : LauncherCommands::new;
    smokeDriverFactory = driverFactory != null ? driverFactory : SmokeDriver::create;
  }

  static void resetFactories() {
    environmentFactory = LauncherEnvironment::create;
    commandRunnerFactory = LauncherCommands::new;
    smokeDriverFactory = SmokeDriver::create;
  }

  @FunctionalInterface
  interface EnvironmentFactory {
    LauncherEnvironment create(String profile) throws Exception;
  }

  @FunctionalInterface
  interface CommandRunnerFactory {
    CommandRunner create(LauncherEnvironment environment) throws Exception;
  }

  @FunctionalInterface
  interface SmokeDriverFactory {
    SmokeDriverHandle create(SmokeOptions options) throws Exception;
  }

  interface CommandRunner {
    LauncherCommands.CommandResult reindex() throws Exception;

    LauncherCommands.CommandResult verify() throws Exception;

    LauncherCommands.CommandResult snapshot() throws Exception;

    default LauncherCommands.CommandResult seed(Path fixtures) throws Exception {
      throw new UnsupportedOperationException("seed command not implemented");
    }
  }

  interface SmokeDriverHandle extends AutoCloseable {
    SmokeResult execute() throws Exception;
  }

}
