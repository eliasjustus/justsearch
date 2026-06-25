/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Domain representation of an ONNX model's discovery status, as reported by the Worker.
 *
 * <p>This is a Head-side domain type, decoupled from the proto {@code OnnxDiscoveredModel} message.
 * Updated from the Worker's health check response and cached for the Head's UI status display.
 */
public record OnnxModelStatus(
    String modelName, boolean found, String path, boolean autoDiscovered,
    boolean sessionActive) {}
