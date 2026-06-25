package fixture;

// Fixture surface catalog (NEGATIVE): TWO visible (USER, RAIL) interaction surfaces — the one
// window AND a reintroduced standalone Agent surface, both consuming a core interaction shape. This
// is exactly the 561 surface-tier fork the gate forbids; the interaction-surface gate must FAIL.
public final class CoreSurfaceCatalog {
  public static final SurfaceRef UNIFIED = new SurfaceRef("core.unified-chat-surface");
  public static final SurfaceRef AGENT = new SurfaceRef("core.agent-surface");

  private static final ConversationShapeRef SHAPE_RAG_ASK = new ConversationShapeRef("core.rag-ask");
  private static final ConversationShapeRef SHAPE_FREE_CHAT = new ConversationShapeRef("core.free-chat");
  private static final ConversationShapeRef SHAPE_EXTRACT = new ConversationShapeRef("core.extract");
  private static final ConversationShapeRef SHAPE_AGENT_RUN = new ConversationShapeRef("core.agent-run");

  static java.util.List<Surface> defs() {
    return java.util.List.of(
        new Surface(
            UNIFIED,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                /* conversationShapes */ Set.of(
                    SHAPE_RAG_ASK, SHAPE_FREE_CHAT, SHAPE_EXTRACT, SHAPE_AGENT_RUN)),
            "jf-unified-chat-view"),
        new Surface(
            AGENT,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                /* conversationShapes */ Set.of(SHAPE_AGENT_RUN)),
            "jf-agent-surface"));
  }
}
