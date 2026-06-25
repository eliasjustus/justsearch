package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.FetchRequest;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

/**
 * Proof-of-concept test for RRD4J API.
 *
 * <p>Validates understanding of: database creation, sample insertion, and time-range queries.
 * This is research for G1 (time-series query API) in tempdoc 193.
 */
class Rrd4jSpikeTest {

  @Test
  void basicRoundTrip_createWriteQuery(@TempDir Path tempDir) throws Exception {
    Path rrdPath = tempDir.resolve("test.rrd");
    long startTime = 1700000000L; // Fixed timestamp for reproducibility
    long step = 60; // 60-second step

    // 1. Define database structure
    RrdDef rrdDef = new RrdDef(rrdPath.toString(), startTime, step);

    // Add two datasources: a counter and a gauge
    rrdDef.addDatasource("requests", DsType.COUNTER, 120, 0, Double.NaN);
    rrdDef.addDatasource("heap_mb", DsType.GAUGE, 120, 0, Double.NaN);

    // Add archive: 1-minute resolution, 60 points (1 hour of data)
    rrdDef.addArchive(ConsolFun.AVERAGE, 0.5, 1, 60);

    // 2. Create database
    try (RrdDb rrdDb = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
      assertNotNull(rrdDb);
      assertTrue(Files.exists(rrdPath));
    }

    // 3. Write samples
    try (RrdDb rrdDb = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
      // Write 10 samples, 1 minute apart
      long currentTime = startTime + step; // First sample must be after start time
      long requestCount = 1000;

      for (int i = 0; i < 10; i++) {
        Sample sample = rrdDb.createSample(currentTime);
        sample.setValue("requests", requestCount);
        sample.setValue("heap_mb", 256 + i * 10); // 256, 266, 276, ...
        sample.update();

        requestCount += 100; // Increment counter
        currentTime += step;
      }
    }

    // 4. Query data
    try (RrdDb rrdDb = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
      long queryStart = startTime;
      long queryEnd = startTime + (step * 12);

      FetchRequest request = rrdDb.createFetchRequest(ConsolFun.AVERAGE, queryStart, queryEnd);
      FetchData data = request.fetchData();

      // Verify we got data back
      long[] timestamps = data.getTimestamps();
      double[] heapValues = data.getValues("heap_mb");

      assertTrue(timestamps.length > 0, "Should have timestamps");
      assertTrue(heapValues.length > 0, "Should have heap values");

      // Print for inspection
      System.out.println("=== RRD4J Spike Results ===");
      System.out.println("Timestamps: " + timestamps.length);
      System.out.println("First timestamp: " + timestamps[0]);
      System.out.println("Last timestamp: " + timestamps[timestamps.length - 1]);

      // Check that heap values are in expected range (256-346)
      boolean hasValidHeapValue = false;
      for (double v : heapValues) {
        if (!Double.isNaN(v)) {
          System.out.println("Heap value: " + v);
          hasValidHeapValue = true;
          assertTrue(v >= 250 && v <= 360, "Heap should be in range: " + v);
        }
      }
      assertTrue(hasValidHeapValue, "Should have at least one valid heap value");
    }

    // 5. Verify file size is reasonable (RRD files are fixed-size)
    long fileSize = Files.size(rrdPath);
    System.out.println("RRD file size: " + fileSize + " bytes");
    assertTrue(fileSize > 0 && fileSize < 100_000, "RRD file should be small");
  }

  @Test
  void counterRateCalculation(@TempDir Path tempDir) throws Exception {
    // Test that COUNTER type correctly calculates rate (per-second)
    Path rrdPath = tempDir.resolve("counter.rrd");
    long startTime = 1700000000L;
    long step = 60;

    RrdDef rrdDef = new RrdDef(rrdPath.toString(), startTime, step);
    rrdDef.addDatasource("total", DsType.COUNTER, 120, 0, Double.NaN);
    rrdDef.addArchive(ConsolFun.AVERAGE, 0.5, 1, 60);

    try (RrdDb rrdDb = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
      // Write monotonically increasing counter
      long time = startTime + step;
      for (int i = 0; i < 5; i++) {
        Sample s = rrdDb.createSample(time);
        s.setValue("total", 1000 + (i * 600)); // +600 per minute = 10/sec rate
        s.update();
        time += step;
      }
    }

    try (RrdDb rrdDb = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
      FetchRequest req = rrdDb.createFetchRequest(ConsolFun.AVERAGE, startTime, startTime + 400);
      FetchData data = req.fetchData();

      double[] rates = data.getValues("total");
      System.out.println("=== Counter Rate Test ===");
      for (double r : rates) {
        if (!Double.isNaN(r)) {
          System.out.println("Rate: " + r + " per second");
          // Rate should be ~10/sec (600 per 60 seconds)
          assertTrue(r >= 9 && r <= 11, "Rate should be ~10/sec: " + r);
        }
      }
    }
  }
}
