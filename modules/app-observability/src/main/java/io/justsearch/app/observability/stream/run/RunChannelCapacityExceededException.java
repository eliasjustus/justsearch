/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

/**
 * Thrown when {@link RunChannelRegistry#open} cannot make room without dropping a LIVE run
 * (tempdoc 834 §2).
 *
 * <p>A TYPED refusal rather than a silent eviction: the alternative — evicting the oldest live
 * channel — would drop an in-flight answer someone is watching, and would do it invisibly. The
 * caller's job is to surface "too many runs at once", which is only possible if the substrate says
 * so out loud.
 */
public final class RunChannelCapacityExceededException extends RuntimeException {

  private final int capacity;

  RunChannelCapacityExceededException(int capacity) {
    super(
        "Cannot open another run channel: "
            + capacity
            + " are already held and all of them are live");
    this.capacity = capacity;
  }

  /** The cap that was reached. */
  public int capacity() {
    return capacity;
  }
}
