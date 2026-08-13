package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.HealthServiceGrpc;
import io.justsearch.ipc.mmf.MmfWorkerSignalLayoutV1;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-call deadline override on {@code getHealthCheck(long)} must actually reach the wire.
 *
 * <p>Without this, the PID-validation retry loop's attempt schedule would be inert: it would hand
 * out 1s/2s per-attempt budgets that the client silently replaced with the 5s STANDARD deadline —
 * exactly the defect the schedule exists to fix.
 */
@DisplayName("RemoteKnowledgeClient health-check per-call deadline")
final class RemoteKnowledgeClientHealthDeadlineTest {

  private static final long WORKER_PID = 8556;
  /** Base deadline; every category-driven health call would get at least this much. */
  private static final long BASE_DEADLINE_MS = 5_000;

  private Server server;
  private MainSignalBus signalBus;
  private RemoteKnowledgeClient client;

  /** Blocks the first call past any sane per-attempt budget, then answers instantly. */
  private static final class ColdThenWarmHealthService extends HealthServiceGrpc.HealthServiceImplBase {
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> observer) {
      if (calls.incrementAndGet() == 1) {
        try {
          // The worker-side deep check (live SQLite + Lucene) is expensive on first contact.
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      observer.onNext(HealthCheckResponse.newBuilder().setServing(true).setPid(WORKER_PID).build());
      observer.onCompleted();
    }
  }

  private final ColdThenWarmHealthService health = new ColdThenWarmHealthService();

  @AfterEach
  void tearDown() throws Exception {
    health.release.countDown();
    if (client != null) {
      client.close();
    }
    if (signalBus != null) {
      signalBus.close();
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("a 1s per-call deadline fails fast and leaves the budget for a second attempt")
  void perCallDeadlineIsHonoured(@TempDir Path tempDir) throws Exception {
    // Loopback-only, like the Worker's own gRPC server — never INADDR_ANY.
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .addService(health)
            .build()
            .start();
    int port = server.getPort();

    Path signalPath = seedSignalFileWithPort(tempDir.resolve("worker_signal.lock"), port);
    signalBus = new MainSignalBus(signalPath);
    signalBus.open();
    assertEquals(port, signalBus.readPort(), "test fixture must publish the server port");

    // maxRetries=1 is the floor a real channel accepts; the retry policy covers UNAVAILABLE only,
    // so it never re-issues the DEADLINE_EXCEEDED call this test measures.
    client = new RemoteKnowledgeClient(signalBus, BASE_DEADLINE_MS, /*maxRetries=*/ 1);
    client.connect(port);

    long startedAt = System.currentTimeMillis();
    assertThrows(StatusRuntimeException.class, () -> client.getHealthCheck(1_000));
    long elapsedMs = System.currentTimeMillis() - startedAt;

    assertTrue(
        elapsedMs < BASE_DEADLINE_MS,
        "the 1s override must bound the call, not the 5s STANDARD deadline (took "
            + elapsedMs
            + "ms)");

    // The worker "warms up": the second attempt now answers, inside the same 5s window.
    health.release.countDown();
    assertEquals(WORKER_PID, client.getHealthCheck(2_000).getPid());
  }

  /** Writes a worker gRPC port into a fresh signal file, as the Worker process would. */
  private static Path seedSignalFileWithPort(Path signalPath, int port) throws Exception {
    Files.createDirectories(signalPath.getParent());
    try (RandomAccessFile raf = new RandomAccessFile(signalPath.toFile(), "rw")) {
      raf.setLength(MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES);
      raf.seek(MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT);
      raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(port).array());
    }
    return signalPath;
  }
}
