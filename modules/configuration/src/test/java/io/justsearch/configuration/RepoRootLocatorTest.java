package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoRootLocatorTest {

  @Test
  void findRepoRoot_prefersExplicitRepoRoot(@TempDir Path tmp) {
    String original = System.getProperty(EnvRegistry.REPO_ROOT.sysProp());
    try {
      System.setProperty(EnvRegistry.REPO_ROOT.sysProp(), tmp.toString());
      assertEquals(tmp, RepoRootLocator.findRepoRoot());
    } finally {
      restoreOrClear(EnvRegistry.REPO_ROOT.sysProp(), original);
    }
  }

  @Test
  void findRepoRoot_derivesFromSsotPathWhenSet(@TempDir Path tmp) throws Exception {
    Path repo = tmp.resolve("repo");
    Path ssot = repo.resolve("SSOT");
    Files.createDirectories(ssot);

    String originalRepoRoot = System.getProperty(EnvRegistry.REPO_ROOT.sysProp());
    String originalSsot = System.getProperty(EnvRegistry.SSOT_PATH.sysProp());
    try {
      System.clearProperty(EnvRegistry.REPO_ROOT.sysProp());
      System.setProperty(EnvRegistry.SSOT_PATH.sysProp(), ssot.toString());
      assertEquals(repo, RepoRootLocator.findRepoRoot());
    } finally {
      restoreOrClear(EnvRegistry.REPO_ROOT.sysProp(), originalRepoRoot);
      restoreOrClear(EnvRegistry.SSOT_PATH.sysProp(), originalSsot);
    }
  }

  @Test
  void findRepoRoot_acceptsSsotPathPointingAtRepoRoot(@TempDir Path tmp) throws Exception {
    Path repo = tmp.resolve("repo");
    Files.createDirectories(repo.resolve("SSOT"));

    String originalRepoRoot = System.getProperty(EnvRegistry.REPO_ROOT.sysProp());
    String originalSsot = System.getProperty(EnvRegistry.SSOT_PATH.sysProp());
    try {
      System.clearProperty(EnvRegistry.REPO_ROOT.sysProp());
      System.setProperty(EnvRegistry.SSOT_PATH.sysProp(), repo.toString());
      assertEquals(repo, RepoRootLocator.findRepoRoot());
    } finally {
      restoreOrClear(EnvRegistry.REPO_ROOT.sysProp(), originalRepoRoot);
      restoreOrClear(EnvRegistry.SSOT_PATH.sysProp(), originalSsot);
    }
  }

  private static void restoreOrClear(String key, String value) {
    if (value != null) {
      System.setProperty(key, value);
    } else {
      System.clearProperty(key);
    }
  }
}
