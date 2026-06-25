/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.Map;

/** Per-encoder profiling snapshot with raw cumulative totals (357). */
public record EncoderProfileView(
    long calls,
    Map<String, Long> phaseTotalUs,
    long ortMinUs,
    long ortMaxUs,
    long ortP50Us,
    long ortP95Us,
    long ortP99Us) {}
