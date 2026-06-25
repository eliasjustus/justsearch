/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.IterationDecision;

/**
 * Slice 496 §3.C — IterationController that reads the validation result from
 * the {@code ConversationContext.attributes()} map (set by
 * {@link ValidationConsumer}) and decides whether to retry.
 *
 * <p>Decision logic:
 * <ul>
 *   <li>{@code validation.passed == true} → STOP_SUCCESS (valid output)
 *   <li>{@code validation.passed == false} and under maxRetries → CONTINUE
 *   <li>{@code validation.passed == false} and at/over maxRetries → STOP_ERROR
 * </ul>
 */
public final class ValidatingController implements IterationController {

  public static final String ID = "core.validating-controller";

  private final int maxRetries;

  public ValidatingController() {
    this(3);
  }

  public ValidatingController(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public IterationDecision next(ConversationContext ctx) {
    Object passed = ctx.attributes().get("validation.passed");
    if (Boolean.TRUE.equals(passed)) {
      return IterationDecision.STOP_SUCCESS;
    }
    if (ctx.iteration() >= maxRetries) {
      return IterationDecision.STOP_ERROR;
    }
    return IterationDecision.CONTINUE;
  }
}
