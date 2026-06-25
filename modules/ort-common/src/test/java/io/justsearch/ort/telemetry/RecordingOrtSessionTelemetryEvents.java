package io.justsearch.ort.telemetry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: an {@link OrtSessionTelemetryEvents} implementation that captures every event
 * into thread-safe lists for assertion. Used by the stress test to verify the line-260
 * silent-fallback path emits {@link TransitionReason.GpuFallbackTaken} (the central operational
 * win of tempdoc 414).
 */
public final class RecordingOrtSessionTelemetryEvents implements OrtSessionTelemetryEvents {

  public final List<TransitionReason> transitions = new CopyOnWriteArrayList<>();
  public final List<AssemblerEvent> assemblerEvents = new CopyOnWriteArrayList<>();
  public final List<Long> semaphoreWaitsUs = new CopyOnWriteArrayList<>();

  @Override
  public void onTransition(TransitionReason reason) {
    transitions.add(reason);
  }

  @Override
  public void onAssemblerEvent(AssemblerEvent event) {
    assemblerEvents.add(event);
  }

  @Override
  public void onSemaphoreWait(String consumer, long waitUs) {
    semaphoreWaitsUs.add(waitUs);
  }

  public long countOf(Class<? extends TransitionReason> permit) {
    return transitions.stream().filter(permit::isInstance).count();
  }
}
