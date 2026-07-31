package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.ApiErrorCode;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Duplicate/concurrent start requests must be refused rather than run two download loops over the
 * same {@code .partial} files — which, now that partials are resumed instead of deleted, would mean
 * two writers appending to one file.
 */
final class AiInstallServiceDuplicateStartTest {

  @TempDir Path tmp;

  private static AtomicBoolean runningFlag(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("running");
    f.setAccessible(true);
    return (AtomicBoolean) f.get(svc);
  }

  @Test
  void secondStartWhileRunningIsRejectedWith409() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    runningFlag(svc).set(true);

    AiInstallException ex = assertThrows(AiInstallException.class, () -> svc.startInstall(true));

    assertEquals(409, ex.httpStatus());
    assertEquals(ApiErrorCode.INSTALL_ALREADY_RUNNING, ex.errorCode());
  }
}
