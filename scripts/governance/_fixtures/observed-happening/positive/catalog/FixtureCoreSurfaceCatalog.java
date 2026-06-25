package fixture;

// POSITIVE fixture surface catalog — text-parsed by the enforcer (never compiled). The gate reads the
// "jf-…" mount-tag literals as the valid read-view set that a concept's `projections` must resolve to.
public final class FixtureCoreSurfaceCatalog {
  public static final String ACTIVITY_MOUNT_TAG = "jf-activity-surface";
  public static final String HEALTH_MOUNT_TAG = "jf-health-surface";
  public static final String LOGS_MOUNT_TAG = "jf-log-surface";
}
