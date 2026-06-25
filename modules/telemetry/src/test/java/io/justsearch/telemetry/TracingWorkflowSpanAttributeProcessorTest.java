package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TracingWorkflowSpanAttributeProcessorTest {
	@Test
	void mirrorsWorkflowIdentityIntoExportedSpanAttributes() {
		GlobalOpenTelemetry.resetForTest();
		var exporter = new CapturingSpanExporter();
		var provider = SdkTracerProvider.builder()
			.addSpanProcessor(TracingBootstrap.buildWorkflowSpanAttributeProcessor(Map.of(
				"JUSTSEARCH_WORKFLOW_RUN_ID", "workflow-run-123",
				"JUSTSEARCH_WORKFLOW_FAMILY", "beir-gate")))
			.addSpanProcessor(SimpleSpanProcessor.create(exporter))
			.build();
		var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
		GlobalOpenTelemetry.set(sdk);
		try {
			var tracer = GlobalOpenTelemetry.get().getTracer("test");
			var span = tracer.spanBuilder("workflow.attr.test").startSpan();
			span.end();
			provider.forceFlush().join(5, TimeUnit.SECONDS);
			assertEquals(1, exporter.spans.size());
			var exported = exporter.spans.get(0);
			assertEquals(
				"workflow-run-123",
				exported.getAttributes().get(AttributeKey.stringKey("justsearch.workflow.run_id")));
			assertEquals(
				"beir-gate",
				exported.getAttributes().get(AttributeKey.stringKey("justsearch.workflow.family")));
		} finally {
			provider.close();
			GlobalOpenTelemetry.resetForTest();
		}
	}

	@Test
	void returnsNullProcessorWhenWorkflowIdentityIsAbsent() {
		assertNull(TracingBootstrap.buildWorkflowSpanAttributeProcessor(Map.of()));
	}

	private static final class CapturingSpanExporter implements SpanExporter {
		private final List<SpanData> spans = new ArrayList<>();

		@Override
		public CompletableResultCode export(Collection<SpanData> spans) {
			this.spans.addAll(spans);
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode shutdown() {
			return CompletableResultCode.ofSuccess();
		}
	}
}
