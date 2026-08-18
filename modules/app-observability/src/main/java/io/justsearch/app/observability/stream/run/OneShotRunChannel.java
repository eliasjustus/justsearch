/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

/**
 * A run with no control points — ask / summarize / dispatch (tempdoc 834 §0, column two). It keeps
 * going and persists whether or not anyone is watching.
 *
 * <p><strong>There is deliberately no {@code setPark} here.</strong> Rev 1 of the design guarded
 * the ask-survival law with a javadoc on a uniform {@code setPark} ("empty by construction"); that
 * is the flattening failure wearing a comment. §3.4 makes it structural instead: a handle to a
 * one-shot run has no method that could park it, so the mistake cannot compile.
 */
public non-sealed interface OneShotRunChannel extends RunChannel {}
