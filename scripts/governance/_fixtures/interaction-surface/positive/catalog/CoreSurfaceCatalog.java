package fixture;

// Fixture surface catalog (positive): ONE visible interaction surface (the one window) consuming
// all core shapes; the Ask surface is DEEPLINK (exempt). The interaction-surface gate must PASS.
public final class CoreSurfaceCatalog {
  public static final SurfaceRef UNIFIED = new SurfaceRef("core.unified-chat-surface");
  public static final SurfaceRef ASK = new SurfaceRef("core.ask-surface");

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
            ASK,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.DEEPLINK,
            new SurfaceConsumes(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                /* conversationShapes */ Set.of(SHAPE_RAG_ASK)),
            "jf-chat-shape-mount"));
  }
}
