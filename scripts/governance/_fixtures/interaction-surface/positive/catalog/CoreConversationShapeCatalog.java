package fixture;

import java.util.Set;

// Fixture authority for the interaction-surface gate self-test (positive flavor).
public final class CoreConversationShapeCatalog {
  public static final Set<String> CORE_USER_INTERACTION_SHAPES =
      Set.of("core.rag-ask", "core.free-chat", "core.extract", "core.agent-run");
}
