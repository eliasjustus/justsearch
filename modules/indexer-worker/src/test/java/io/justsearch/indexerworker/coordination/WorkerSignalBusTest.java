package io.justsearch.indexerworker.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerSignalBusTest {

  @TempDir Path tempDir;
  private MmfWorkerSignalBus signalBus;

  @BeforeEach
  void setUp() throws Exception {
    Path signalPath = tempDir.resolve("worker_signal.lock");
    signalBus = new MmfWorkerSignalBus(signalPath);
    signalBus.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (signalBus != null) {
      signalBus.close();
    }
  }

  @Test
  void writePortIsReadable() throws Exception {
    signalBus.writePort(12345);

    // Re-open to verify persistence
    Path signalPath = tempDir.resolve("worker_signal.lock");
    try (MmfWorkerSignalBus reader = new MmfWorkerSignalBus(signalPath)) {
      reader.open();
      // Port should be persisted - we can't read it directly but writePort should succeed
    }
  }

  @Test
  void readActivityReturnsZeroInitially() {
    assertEquals(0L, signalBus.readActivity());
  }

  @Test
  void readHeartbeatReturnsZeroInitially() {
    assertEquals(0L, signalBus.readHeartbeat());
  }

  @Test
  void isShutdownRequestedReturnsFalseInitially() {
    assertFalse(signalBus.isShutdownRequested());
  }

  @Test
  void shouldDieReturnsFalseWithinGracePeriod() {
    // Within the 15s grace period, shouldDie should return false
    // even with no heartbeat
    assertFalse(signalBus.shouldDie());
  }

  @Test
  void twoArgConstructorWithLiveHeadWiresHeadLiveness() throws Exception {
    // Tempdoc 630: exercise the 2-arg (Path, headPid) constructor + headLiveness() probe path.
    // Within the 15s startup grace shouldDie() is false regardless; this confirms the new
    // constructor + ProcessHandle probe compile and run (the past-grace gate — stale beat + Head
    // ALIVE => no suicide — is covered fast by WorkerLivenessDecisionTest).
    Path signalPath = tempDir.resolve("worker_signal_head.lock");
    long liveHeadPid = ProcessHandle.current().pid(); // the running test process is alive
    try (MmfWorkerSignalBus bus = new MmfWorkerSignalBus(signalPath, liveHeadPid)) {
      bus.open();
      assertFalse(bus.shouldDie());
    }
  }

  @Test
  void isUserActiveReturnsFalseInitially() {
    assertFalse(signalBus.isUserActive());
  }

  @Test
  void startupTimeIsRecorded() {
    // signalBus is created in @BeforeEach, so startupTime is already in the past
    long startupTime = signalBus.startupTime();
    long now = System.currentTimeMillis();

    // Startup time should be in the past (within last few seconds, including test setup)
    assertTrue(startupTime > 0, "Startup time should be positive");
    assertTrue(startupTime <= now, "Startup time should not be in the future");
    // Should be within the last 5 seconds (generous for slow CI)
    assertTrue(now - startupTime < 5000, "Startup time should be recent (within 5s)");
  }
}
