/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.api.gpl.GplEvalData;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §10 final-push Phase D: bundles 4 head-process infrastructure components into
 * one typed record so the bootstrap exposes one {@code headInfraRegistry()} accessor instead
 * of four flat accessors. Consumers (HeadlessApp, LocalApiServer.Builder) read the record's
 * sub-accessors.
 *
 * <p>This is NOT substrate (no Resource catalogs, no registries, no intent router) — it's the
 * top-level coordination plumbing the bootstrap wires once and hands to the API layer.
 */
public record HeadInfraRegistry(
    OfflineCoordinator offlineCoordinator,
    GplStatusProvider gplJobCoordinator,
    RerankerService lambdaMartReranker,
    Supplier<GplEvalData> gplEvalSnapshotSupplier) {}
