"""Shared UI selector registry for the ui-eval subcommand.

Ported from modules/ui-web/scripts/evidence/lib/selectors.mjs.
Keep this list intentionally small and stable.
"""

from __future__ import annotations

from dataclasses import dataclass

# data-testid values present on the live Lit shell-v0 (the retired-React testids — inspector-*,
# action-*, filters-toggle, context-* pills, citation-highlight, health-view — were removed in
# tempdoc 615 §6.1b; their steps now target live hooks below or were retired).
TID_SEARCH_INPUT = "search-input"
TID_SEARCH_RESULT_ROW = "search-result-row"
TID_RESULT_ROW_SNIPPET_TOGGLE = "result-row-snippet-toggle"
TID_GLOBAL_COMMAND_CHROME = "global-command-chrome"
TID_SKELETON_LIBRARY = "skeleton-library"
TID_BRAIN_SWITCH_TO_ADVANCED = "brain-switch-to-advanced"

# Activity rail navigation — the rail renders each nav button with
# `data-surface-id="core.<x>-surface"` (`Shell.ts` renderRailButton) and a `@click` that selects
# the surface; there are NO per-view `activity-*` testids. Nav clicks therefore target the
# surface id (Playwright pierces the rail's open shadow root). Repointed from the stale
# `activity-*` testids that no longer exist on the rail (559/571 rail rework).
RAIL_SURFACE_SEARCH = "core.search-surface"
RAIL_SURFACE_LIBRARY = "core.library-surface"
RAIL_SURFACE_BRAIN = "core.brain-surface"
RAIL_SURFACE_HEALTH = "core.health-surface"
RAIL_SURFACE_SETTINGS = "core.settings-surface"
RAIL_SURFACE_SECURITY = "core.security-surface"
RAIL_SURFACE_HELP = "core.help-surface"
RAIL_SURFACE_BROWSE = "core.browse-surface"


def rail_css(surface_id: str) -> str:
    """CSS selector for a rail nav button by its `data-surface-id`."""
    return f'[data-surface-id="{surface_id}"]'


# CSS selectors (for locator()-based queries)
CSS_SEARCH_INPUT = f'[data-testid="{TID_SEARCH_INPUT}"]'
CSS_SEARCH_INPUT_TEXTAREA = f'textarea[data-testid="{TID_SEARCH_INPUT}"]'
CSS_SEARCH_RESULT_ROW = f'[data-testid="{TID_SEARCH_RESULT_ROW}"]'
# tempdoc 615 §6.1b: live Lit hooks (no retired React testids). The inspector is the custom element
# `<jf-inspector-pane>` (chrome/Shell.ts); a citation mark is `.cite-ref`/`[data-cite-key]`
# (components/chat/MarkdownBlock.ts). Selection is a row click, so there is no per-row select checkbox.
CSS_INSPECTOR_PANE = 'jf-inspector-pane'
CSS_CITATION_HIGHLIGHT = '.cite-ref, [data-cite-key]'

@dataclass(frozen=True)
class Selector:
    """Self-healing element selector (tempdoc 615 §11 HARDEN).

    Resolves by accessible ROLE + NAME first, falling back to the `data-testid`.
    Playwright's role/label engines pierce OPEN shadow roots (verified on the Lit
    shell), so role+name is stable across the testid churn that made this session's
    React->Lit migration painful — a testid rename can't break a step that has a
    role+name, and a missing/renamed role can't break one that still has its testid.
    The a11y tree the measurement substrate already records IS this resolution key
    (§11 standout idea #1: "the harness hardens itself").
    """

    role: str
    name: str | None = None
    testid: str | None = None

    async def locate(self, page):
        """Return a Playwright locator: the role+name match if present, else the testid."""
        if self.role:
            loc = page.get_by_role(self.role, name=self.name) if self.name else page.get_by_role(self.role)
            try:
                if await loc.count() > 0:
                    return loc.first
            except Exception:
                pass
        if self.testid:
            return page.get_by_test_id(self.testid)
        # No testid fallback — return the (empty) role locator so the caller's
        # wait_for surfaces a clear "not found by role" failure rather than None.
        return (page.get_by_role(self.role, name=self.name) if self.name
                else page.get_by_role(self.role))


# The search input: role=searchbox / accessible-name "Search files" (verified live via
# the measurement a11y tree), testid as the fallback. Used by the search steps.
SEARCH_INPUT = Selector(role="searchbox", name="Search files", testid=TID_SEARCH_INPUT)


# Navigation views — maps view name to the rail button's surface id
VIEWS: dict[str, str] = {
    "home": RAIL_SURFACE_SEARCH,
    "search": RAIL_SURFACE_SEARCH,
    "library": RAIL_SURFACE_LIBRARY,
    "ai-brain": RAIL_SURFACE_BRAIN,
    "health": RAIL_SURFACE_HEALTH,
    "settings": RAIL_SURFACE_SETTINGS,
    "security": RAIL_SURFACE_SECURITY,
    "help": RAIL_SURFACE_HELP,
}
