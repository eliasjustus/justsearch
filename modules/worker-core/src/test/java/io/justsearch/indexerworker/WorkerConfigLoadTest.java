package io.justsearch.indexerworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WorkerConfigLoadTest {

  @Test
  void loadPopulatesNrtTarget() throws Exception {
    Path dataDir = Files.createTempDirectory("worker-config-data-");
    Path configFile = Files.createTempFile("worker-config-", ".yaml");
    String yaml =
        """
        app:
          data_dir: %s
        index:
          collections:
            - name: local
              roots: ['%s']
          nrt:
            target_max_stale_ms: 750
        workers:
          indexer:
            enabled: true
            host: 127.0.0.1
            port: 0
            deadlineMs: 1000
            queueSize: 16
        """
            .formatted(
                escapeWindowsPath(dataDir),
                escapeWindowsPath(dataDir));
    Files.writeString(configFile, yaml);
    String prevConfig = System.getProperty(EnvRegistry.CONFIG_PATH.sysProp());
    ConfigStore prevStore = ConfigStore.globalOrNull();
    System.setProperty(EnvRegistry.CONFIG_PATH.sysProp(), configFile.toString());
    try {
      var rcBuilder = ResolvedConfig.builder();
      rcBuilder.contributeEnvRegistry();
      rcBuilder.contributeYaml(new tools.jackson.databind.ObjectMapper(
          new tools.jackson.dataformat.yaml.YAMLFactory()).readTree(yaml));
      ConfigStore.setGlobal(new ConfigStore(rcBuilder.build()));
      WorkerConfig config = WorkerConfig.load();
      assertEquals(750L, config.nrtTargetMaxStaleMs());
    } finally {
      if (prevConfig == null) {
        System.clearProperty(EnvRegistry.CONFIG_PATH.sysProp());
      } else {
        System.setProperty(EnvRegistry.CONFIG_PATH.sysProp(), prevConfig);
      }
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  private static String escapeWindowsPath(Path path) {
    String raw = path.toString();
    return raw.replace("\\", "\\\\");
  }

  @Test
  void loadRespectsTelemetryFlushOverride() throws Exception {
    Path dataDir = Files.createTempDirectory("worker-config-data-");
    Path configFile = Files.createTempFile("worker-config-", ".yaml");
    String yaml =
        """
        app:
          data_dir: %s
        index:
          collections:
            - name: local
              roots: ['%s']
        workers:
          indexer:
            enabled: true
            host: 127.0.0.1
            port: 0
            deadlineMs: 1000
            queueSize: 16
        """
            .formatted(
                escapeWindowsPath(dataDir),
                escapeWindowsPath(dataDir));
    Files.writeString(configFile, yaml);

    String prevConfig = System.getProperty(EnvRegistry.CONFIG_PATH.sysProp());
    String prevFlush = System.getProperty(EnvRegistry.TELEMETRY_FLUSH_MS.sysProp());
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty(EnvRegistry.CONFIG_PATH.sysProp(), configFile.toString());
      System.setProperty(EnvRegistry.TELEMETRY_FLUSH_MS.sysProp(), "1234");
      TestResolvedConfigHelper.storeFromEnvironment();
      WorkerConfig config = WorkerConfig.load();
      assertEquals(1234L, config.telemetryFlushMs());
    } finally {
      if (prevConfig == null) {
        System.clearProperty(EnvRegistry.CONFIG_PATH.sysProp());
      } else {
        System.setProperty(EnvRegistry.CONFIG_PATH.sysProp(), prevConfig);
      }
      if (prevFlush == null) {
        System.clearProperty(EnvRegistry.TELEMETRY_FLUSH_MS.sysProp());
      } else {
        System.setProperty(EnvRegistry.TELEMETRY_FLUSH_MS.sysProp(), prevFlush);
      }
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void loadRespectsWorkerVersionOverride() throws Exception {
    Path dataDir = Files.createTempDirectory("worker-config-data-");
    Path configFile = Files.createTempFile("worker-config-", ".yaml");
    String yaml =
        """
        app:
          data_dir: %s
        index:
          collections:
            - name: local
              roots: ['%s']
        workers:
          indexer:
            enabled: true
            host: 127.0.0.1
            port: 0
            deadlineMs: 1000
            queueSize: 16
        """
            .formatted(
                escapeWindowsPath(dataDir),
                escapeWindowsPath(dataDir));
    Files.writeString(configFile, yaml);

    String prevConfig = System.getProperty(EnvRegistry.CONFIG_PATH.sysProp());
    String prevVersion = System.getProperty(EnvRegistry.INDEXER_WORKER_VERSION.sysProp());
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty(EnvRegistry.CONFIG_PATH.sysProp(), configFile.toString());
      System.setProperty(EnvRegistry.INDEXER_WORKER_VERSION.sysProp(), "9.9.9-test");
      TestResolvedConfigHelper.storeFromEnvironment();
      WorkerConfig config = WorkerConfig.load();
      assertEquals("9.9.9-test", config.serviceVersion());
    } finally {
      if (prevConfig == null) {
        System.clearProperty(EnvRegistry.CONFIG_PATH.sysProp());
      } else {
        System.setProperty(EnvRegistry.CONFIG_PATH.sysProp(), prevConfig);
      }
      if (prevVersion == null) {
        System.clearProperty(EnvRegistry.INDEXER_WORKER_VERSION.sysProp());
      } else {
        System.setProperty(EnvRegistry.INDEXER_WORKER_VERSION.sysProp(), prevVersion);
      }
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void sha256ComputesDigestAndWrapsFailures() throws Exception {
    Path temp = Files.createTempFile("worker-config-hash-", ".txt");
    Files.writeString(temp, "hash-me");
    Method method = WorkerConfig.class.getDeclaredMethod("sha256", Path.class);
    method.setAccessible(true);
    String hash = (String) method.invoke(null, temp);
    assertTrue(!hash.isBlank());

    InvocationTargetException thrown =
        assertThrows(InvocationTargetException.class, () -> method.invoke(null, temp.resolveSibling("missing.txt")));
    assertTrue(
        thrown.getCause() instanceof IllegalStateException, "expected illegal state wrapper");
  }
}
