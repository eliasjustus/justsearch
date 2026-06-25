/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

final class WorkflowSpanAttributeProcessor implements SpanProcessor {
	private static final AttributeKey<String> WORKFLOW_RUN_ID =
		AttributeKey.stringKey("justsearch.workflow.run_id");
	private static final AttributeKey<String> WORKFLOW_FAMILY =
		AttributeKey.stringKey("justsearch.workflow.family");

	private final String workflowRunId;
	private final String workflowFamily;

	WorkflowSpanAttributeProcessor(String workflowRunId, String workflowFamily) {
		this.workflowRunId = workflowRunId;
		this.workflowFamily = workflowFamily;
	}

	@Override
	public void onStart(Context parentContext, ReadWriteSpan span) {
		if (workflowRunId != null) {
			span.setAttribute(WORKFLOW_RUN_ID, workflowRunId);
		}
		if (workflowFamily != null) {
			span.setAttribute(WORKFLOW_FAMILY, workflowFamily);
		}
	}

	@Override
	public boolean isStartRequired() {
		return true;
	}

	@Override
	public void onEnd(ReadableSpan span) {
		// No-op: we only need start-time attribute mirroring.
	}

	@Override
	public boolean isEndRequired() {
		return false;
	}
}
