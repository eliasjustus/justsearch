package fixture;

// NEGATIVE fixture surface catalog — declares only jf-health-surface (as a string literal below), so a
// concept whose projection names a non-declared mount tag is unresolved (projection-unresolved fires).
public final class FixtureCoreSurfaceCatalog {
  public static final String HEALTH_MOUNT_TAG = "jf-health-surface";
}
