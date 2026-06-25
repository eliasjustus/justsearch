/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import java.nio.file.Path;

/**
 * Small Java entrypoint used by workflow OTLP pilots to emit one trace through the shared
 * telemetry module without depending on app-specific trace coverage.
 */
public final class WorkflowTraceProbe {
	private WorkflowTraceProbe() {
	}

	public static void main(String[] args) {
		if (args.length < 1 || args[0] == null || args[0].isBlank()) {
			throw new IllegalArgumentException("Usage: WorkflowTraceProbe <dataDir> [spanName]");
		}
		Path dataDir = Path.of(args[0]).toAbsolutePath().normalize();
		String spanName = args.length >= 2 && args[1] != null && !args[1].isBlank()
			? args[1].trim()
			: "workflow.trace.probe";
		try (var bootstrap = new TracingBootstrap(dataDir)) {
			var tracer = GlobalOpenTelemetry.get().getTracer("io.justsearch.telemetry.WorkflowTraceProbe");
			var span = tracer.spanBuilder(spanName)
				.setSpanKind(SpanKind.INTERNAL)
				.setAttribute("pipeline_name", "workflow_otlp_pilot")
				.setAttribute("stage_id", "probe")
				.startSpan();
			try {
				span.setAttribute("reason_code", "workflow_otlp_pilot");
			} finally {
				span.end();
			}
			bootstrap.flush();
		}
	}
}
