import { expect, type Page, type Locator } from '@playwright/test';
import { E2E_TEST_IDS, type E2EActivityView } from './selectors';

type Rect = { x: number; y: number; width: number; height: number };

const intersects = (a: Rect, b: Rect): boolean => {
  // Allow tiny overlap due to subpixel rounding / borders.
  const eps = 1;
  const ax2 = a.x + a.width;
  const ay2 = a.y + a.height;
  const bx2 = b.x + b.width;
  const by2 = b.y + b.height;
  return a.x + eps < bx2 && ax2 - eps > b.x && a.y + eps < by2 && ay2 - eps > b.y;
};

async function rectOf(locator: Locator): Promise<Rect> {
  const box = await locator.boundingBox();
  expect(box, 'Expected element to have a bounding box').not.toBeNull();
  return box as Rect;
}

export async function assertZoneGridNoOverlap(page: Page) {
  const zones = [
    { name: 'rail', loc: page.locator('.zone-rail') },
    { name: 'command', loc: page.locator('.zone-command') },
    { name: 'stage', loc: page.locator('.zone-stage') },
    { name: 'inspector', loc: page.locator('.zone-inspector') },
    { name: 'status', loc: page.locator('.zone-status') },
  ];

  const present = [];
  for (const z of zones) {
    if ((await z.loc.count()) > 0) present.push(z);
  }

  const rects: Array<{ name: string; rect: Rect }> = [];
  for (const z of present) {
    rects.push({ name: z.name, rect: await rectOf(z.loc.first()) });
  }

  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      const a = rects[i]!;
      const b = rects[j]!;

      // Inspector may be collapsed (0 width) – ignore degenerate boxes.
      if (a.rect.width <= 1 || a.rect.height <= 1) continue;
      if (b.rect.width <= 1 || b.rect.height <= 1) continue;

      expect(
        intersects(a.rect, b.rect),
        `Expected zones not to overlap: ${a.name} intersects ${b.name}`
      ).toBeFalsy();
    }
  }
}

export async function assertPrimaryControlVisible(page: Page, view: E2EActivityView) {
  if (view === 'search') {
    // Use testid instead of placeholder - placeholder is hidden when input has text (e.g., demo mode)
    await expect(page.getByTestId(E2E_TEST_IDS.searchInput)).toBeVisible();
    return;
  }
  if (view === 'library') {
    await expect(
      page.getByText('Library', { exact: true }).or(page.getByText('No API Connection', { exact: true }))
    ).toBeVisible();
    return;
  }
  if (view === 'browse') {
    await expect(page.getByText('Browse', { exact: true })).toBeVisible();
    return;
  }
  if (view === 'brain') {
    await expect(
      page.getByText('AI Brain', { exact: true }).or(page.getByText('No API Connection', { exact: true }))
    ).toBeVisible();
    return;
  }
  if (view === 'health') {
    await expect(page.getByText('System Health', { exact: true })).toBeVisible();
    return;
  }
  await expect(page.getByText('Settings', { exact: true })).toBeVisible();
}


