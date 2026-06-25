package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.AiInstallStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AiInstallServicePackageStateTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static void invoke(AiInstallService svc, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method m = AiInstallService.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    m.invoke(svc, args);
  }

  @Test
  void failedPackageStaysFailedAcrossLaterMultiFileTransitions() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    var pkg = new AiInstallStatus.PackageStatus();
    pkg.packageId = "splade";
    pkg.state = "pending";
    pkg.bytesDownloaded = 10;
    pkg.bytesTotal = 100;
    status.packages.add(pkg);

    invoke(
        svc,
        "failPackage",
        new Class<?>[] {String.class, String.class},
        "splade",
        "Download failed for splade/naver-splade-v3/idf.json");
    invoke(svc, "updatePackageState", new Class<?>[] {String.class, String.class}, "splade", "downloading");
    invoke(
        svc,
        "updatePackageProgress",
        new Class<?>[] {String.class, long.class, long.class},
        "splade",
        90L,
        100L);
    invoke(svc, "updatePackageState", new Class<?>[] {String.class, String.class}, "splade", "installed");

    assertEquals("failed", pkg.state);
    assertEquals("Download failed for splade/naver-splade-v3/idf.json", pkg.error);
    assertEquals(10, pkg.bytesDownloaded);
  }
}
