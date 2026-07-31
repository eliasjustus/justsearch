/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.justsearch.app.api.OperationLeaseService;
import java.util.Set;

/** Upgrade lifecycle control cohort over the existing loopback/session-token transport. */
final class UpgradeApiModule implements ApiModule {
  private static final Set<String> ROUTES =
      Set.of(
          "/api/upgrade/prepare",
          "/api/upgrade/cancel",
          "/api/upgrade/commit-shutdown");

  private final UpgradeController controller;

  UpgradeApiModule(OperationLeaseService leases, Runnable orderlyShutdown) {
    this.controller = new UpgradeController(leases, orderlyShutdown);
  }

  @Override
  public void register(Javalin app) {
    app.post("/api/upgrade/prepare", controller::prepare);
    app.post("/api/upgrade/cancel", controller::cancel);
    app.post("/api/upgrade/commit-shutdown", controller::commitShutdown);
  }

  @Override
  public Set<String> ownedRoutePaths() {
    return ROUTES;
  }
}
