/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

/** Checked exception thrown when a prompt template cannot be loaded or rendered. */
public final class PromptTemplateException extends Exception {

  public PromptTemplateException(String message) {
    super(message);
  }

  public PromptTemplateException(String message, Throwable cause) {
    super(message, cause);
  }
}
