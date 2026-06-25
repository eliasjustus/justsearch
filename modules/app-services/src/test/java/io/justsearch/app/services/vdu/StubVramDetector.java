package io.justsearch.app.services.vdu;

/**
 * Stub VramDetector for unit testing.
 *
 * <p>Allows tests to control VRAM availability.
 */
public class StubVramDetector {

    private boolean meetsVduRequirements = true;
    private boolean hasComfortableVram = true;
    private long totalVramBytes = 12L * 1024 * 1024 * 1024;  // 12GB default
    private long availableVramBytes = 10L * 1024 * 1024 * 1024;  // 10GB default

    // ========== Simulated Methods ==========

    public boolean meetsVduRequirements() {
        return meetsVduRequirements;
    }

    public boolean hasComfortableVram() {
        return hasComfortableVram;
    }

    public long getTotalVramBytes() {
        return totalVramBytes;
    }

    public long getAvailableVramBytes() {
        return availableVramBytes;
    }

    public String getVramDescription() {
        return String.format("%.1f GB / %.1f GB",
            availableVramBytes / (1024.0 * 1024 * 1024),
            totalVramBytes / (1024.0 * 1024 * 1024));
    }

    // ========== Configuration Methods ==========

    public StubVramDetector withMeetsVduRequirements(boolean meets) {
        this.meetsVduRequirements = meets;
        return this;
    }

    public StubVramDetector withHasComfortableVram(boolean comfortable) {
        this.hasComfortableVram = comfortable;
        return this;
    }

    public StubVramDetector withTotalVramBytes(long bytes) {
        this.totalVramBytes = bytes;
        return this;
    }

    public StubVramDetector withAvailableVramBytes(long bytes) {
        this.availableVramBytes = bytes;
        return this;
    }

    public void reset() {
        meetsVduRequirements = true;
        hasComfortableVram = true;
        totalVramBytes = 12L * 1024 * 1024 * 1024;
        availableVramBytes = 10L * 1024 * 1024 * 1024;
    }
}
