/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.api;

/** Minimal index document representation. */
public record IndexDocument(java.util.Map<String, Object> fields) {}
