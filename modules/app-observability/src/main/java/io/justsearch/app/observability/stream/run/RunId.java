/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.app.api.stream.StreamId;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The ONE run-identity namespace (tempdoc 834 §3.2), backend-minted.
 *
 * <p>Agent runs ALIAS this to their existing {@code sessionId} — no mapping table, because a table
 * is a second authority that drifts; aliasing preserves every existing URL and the on-disk run
 * directory name. Conversational runs mint {@code run-<uuid>}.
 *
 * <p><strong>Why {@link #streamId()} derives rather than passes through.</strong> {@link StreamId}
 * requires a letter-initial slug ({@code StreamId.java} pattern), but an agent {@code sessionId} is
 * {@code UUID.randomUUID().toString()}, which starts with a digit roughly six times in ten. §3.2's
 * "the {@code run-} prefix also satisfies StreamId's letter-initial rule" therefore covers only the
 * conversational half. So the slug is DERIVED with one unconditional rule — {@code "r-" + lowercase}
 * — rather than a branch: a pure function of the id, injective over the id alphabet
 * ({@code [A-Za-z0-9-]}, case-folded only for hex UUIDs which are already lowercase), so it is not a
 * second authority any more than the {@code run:} prefix is.
 *
 * @param value the run identity as every other surface knows it (an agent {@code sessionId}, or a
 *     minted {@code run-<uuid>})
 */
public record RunId(String value) {

  private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");

  public RunId {
    Objects.requireNonNull(value, "value");
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "RunId must match " + PATTERN.pattern() + ", got: " + value);
    }
  }

  /** Mints a fresh conversational run id ({@code run-<uuid>}, tempdoc 834 §3.2). */
  public static RunId mint() {
    return new RunId("run-" + java.util.UUID.randomUUID());
  }

  /** The observation stream this run publishes on. Always a valid letter-initial {@code run:} slug. */
  public StreamId streamId() {
    return StreamId.run("r-" + value.toLowerCase(Locale.ROOT));
  }
}
