// Accessibility checks with axe-core (WCAG 2.0 and 2.1, levels A and AA) on every main page, at desktop and phone
// size. Violations already known are pinned in a11y-baseline.json, one entry per page and rule, with the reason;
// a violation that isn't in the baseline fails the test, and so does a baseline entry that no longer occurs (so a
// fixed finding can't stay listed as known). Every page's full axe result is attached to the report.
const AxeBuilder = require('@axe-core/playwright').default;
const { test, expect, signUp } = require('../helpers/app');
const { buildWorld, PUBLIC_PAGES, pageKey } = require('../helpers/world');
const baseline = require('../a11y-baseline.json');

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

// Each test builds its own data, then checks several pages (some long), so it gets more time
test.describe.configure({ timeout: 180_000 });

async function audit(page, path, role, testInfo, unexpected) {
  await page.goto(path);
  const key = pageKey(role, path);
  const results = await new AxeBuilder({ page })
    .options({
      runOnly: { type: 'tag', values: TAGS },
      // Experimental in axe (so off by default), switched on because a visible label that isn't part of the
      // accessible name (WCAG 2.5.3 Label in Name) was spotted in the code scan
      rules: { 'label-content-name-mismatch': { enabled: true } },
    })
    .analyze();
  await testInfo.attach(`axe ${key}`, { body: JSON.stringify(results.violations, null, 2), contentType: 'application/json' });

  const known = (baseline.pages[key] || {});
  const found = results.violations.map((v) => v.id);
  for (const v of results.violations) {
    if (!(v.id in known)) {
      const where = v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ');
      unexpected.push(`${key}: ${v.id} (${v.impact}, ${v.nodes.length} element(s)) ${v.help} [${where}]`);
    }
  }
  for (const id of Object.keys(known).filter((rule) => !found.includes(rule))) {
    unexpected.push(`${key}: ${id} is in a11y-baseline.json but no longer occurs; remove the entry if it is fixed`);
  }
}

// UI-5: the login and sign-up pages have no accessibility violations beyond the pinned baseline
test('login and sign-up pages pass axe', async ({ page }, testInfo) => {
  const unexpected = [];
  for (const path of PUBLIC_PAGES) await audit(page, path, 'public', testInfo, unexpected);
  expect(unexpected, 'axe violations not in a11y-baseline.json, or stale baseline entries').toEqual([]);
});

// UI-5: every student page passes axe beyond the pinned baseline
test('student pages pass axe', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const unexpected = [];
  for (const path of world.studentPages) await audit(page, path, 'student', testInfo, unexpected);
  expect(unexpected, 'axe violations not in a11y-baseline.json, or stale baseline entries').toEqual([]);
});

// UI-5: every study group page passes axe beyond the pinned baseline
test('study group pages pass axe', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const unexpected = [];
  for (const path of world.groupPages) await audit(page, path, 'student', testInfo, unexpected);
  expect(unexpected, 'axe violations not in a11y-baseline.json, or stale baseline entries').toEqual([]);
});

// UI-5: every admin page passes axe beyond the pinned baseline
test('admin pages pass axe', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const unexpected = [];
  for (const path of world.adminPages) await audit(world.adminPage, path, 'admin', testInfo, unexpected);
  expect(unexpected, 'axe violations not in a11y-baseline.json, or stale baseline entries').toEqual([]);
});

// UI-5, GRP-8: a pending join request card (with its Cancel request button) passes axe beyond the pinned baseline
test('a waiting join request passes axe', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const unexpected = [];
  // Ana leads the group, so a new student asks to join it and opens their own My groups and the group page
  const groupPath = world.groupPages[3].replace('?from=mine', '');
  await signUp(page, 'Wyn Waiting');
  await page.goto(groupPath);
  await page.getByRole('button', { name: 'Request to join' }).click();
  await audit(page, '/groups/mine', 'waiting student', testInfo, unexpected);
  await audit(page, groupPath, 'waiting student', testInfo, unexpected);
  expect(unexpected, 'axe violations not in a11y-baseline.json, or stale baseline entries').toEqual([]);
});
