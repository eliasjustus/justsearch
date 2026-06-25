package io.justsearch.systemtests.torture;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "File Intruder" - Simulates external processes (Antivirus, Indexers, Backup agents)
 * that aggressively lock files in the data directory.
 *
 * <p>This harness spawns a background thread that:
 * <ol>
 *   <li>Scans the target directory for interesting files (db, log, index)</li>
 *   <li>Randomly opens them with shared or exclusive locks</li>
 *   <li>Holds the lock for a short duration</li>
 *   <li>Releases and repeats</li>
 * </ol>
 */
public class FileIntruder implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(FileIntruder.class);

  private final Path targetDir;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final List<Thread> intruderThreads = new ArrayList<>();
  private final Random random = new Random();

  // Keep track of open channels/locks to close them properly
  private final List<FileLock> activeLocks = new CopyOnWriteArrayList<>();
  private final List<FileChannel> activeChannels = new CopyOnWriteArrayList<>();

  public FileIntruder(Path targetDir) {
    this.targetDir = targetDir;
  }

  /**
   * Starts the intruder threads.
   *
   * @param threadCount Number of concurrent intruder threads
   * @param intensityMs Average duration to hold a lock (ms)
   */
  public void start(int threadCount, int intensityMs) {
    if (running.getAndSet(true)) {
      return;
    }

    for (int i = 0; i < threadCount; i++) {
      Thread t = new Thread(() -> runIntruderLoop(intensityMs), "intruder-" + i);
      t.setDaemon(true);
      t.start();
      intruderThreads.add(t);
    }
    log.info("File Intruder started with {} threads in {}", threadCount, targetDir);
  }

  private void runIntruderLoop(int intensityMs) {
    while (running.get()) {
      try {
        if (!Files.exists(targetDir)) {
          Thread.sleep(100);
          continue;
        }

        // Find a victim file
        List<Path> victims = new ArrayList<>();
        try (var stream = Files.walk(targetDir)) {
          stream.filter(Files::isRegularFile)
              .filter(p -> !p.getFileName().toString().endsWith(".lock")) // Don't lock lockfiles (too rude)
              .forEach(victims::add);
        }

        if (victims.isEmpty()) {
          Thread.sleep(100);
          continue;
        }

        Path victim = victims.get(random.nextInt(victims.size()));
        lockAndHold(victim, intensityMs);

        // Random sleep between attacks
        Thread.sleep(random.nextInt(intensityMs * 2));

      } catch (Exception e) {
        // Ignore errors - we are the chaos
      }
    }
  }

  private void lockAndHold(Path file, int durationMs) {
    try {
      // Randomly choose shared (read) or exclusive (write) lock
      boolean exclusive = random.nextBoolean();

      FileChannel channel = FileChannel.open(file,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE); // Need write for exclusive lock

      activeChannels.add(channel);

      try {
        FileLock lock = channel.tryLock(0, Long.MAX_VALUE, !exclusive);
        if (lock != null) {
          activeLocks.add(lock);
          log.debug("Locked {} ({})", file.getFileName(), exclusive ? "EXCL" : "SHARED");
          Thread.sleep(random.nextInt(durationMs) + 1);
          lock.release();
          activeLocks.remove(lock);
        }
      } finally {
        channel.close();
        activeChannels.remove(channel);
      }
    } catch (Exception e) {
      // Lock failed (file already locked by worker?), expected
    }
  }

  @Override
  public void close() {
    running.set(false);
    for (Thread t : intruderThreads) {
      t.interrupt();
    }
    intruderThreads.clear();

    // Cleanup any dangling locks
    for (FileLock lock : activeLocks) {
      try { lock.release(); } catch (Exception e) {}
    }
    for (FileChannel ch : activeChannels) {
      try { ch.close(); } catch (Exception e) {}
    }
    activeLocks.clear();
    activeChannels.clear();
    log.info("File Intruder stopped");
  }
}
