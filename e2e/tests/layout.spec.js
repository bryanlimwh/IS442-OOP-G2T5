// The phone layout (UI-3), on a 375 x 667 screen (phone project only). On every main page: no sideways scrolling,
// the top bar's Home link and Log out visible, every visible button inside the screen, stacked tables labelled.
// Each page's full-page screenshot is attached to the report for a person to look at.
const { test, expect, signUp } = require('../helpers/app');
const { buildWorld, PUBLIC_PAGES, pageKey } = require('../helpers/world');

// Each test builds its own data, then checks several pages, so it gets more time
test.describe.configure({ timeout: 180_000 });

async function checkPage(page, path, role, testInfo, problems) {
  await page.goto(path);
  const key = pageKey(role, path);
  await testInfo.attach(`phone ${key}`, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

  const layout = await page.evaluate(() => {
    const width = window.innerWidth;
    const scrolls = (el) => {
      for (let p = el.parentElement; p; p = p.parentElement) {
        const overflow = getComputedStyle(p).overflowX;
        if ((overflow === 'auto' || overflow === 'scroll') && p.scrollWidth > p.clientWidth) return true;
      }
      return false;
    };
    const visible = (el) => {
      const rect = el.getBoundingClientRect();
      return rect.width > 1 && rect.height > 1 && getComputedStyle(el).visibility !== 'hidden'
        && !el.closest('[popover]:not(:popover-open)') && !el.closest('.visually-hidden');
    };
    const name = (el) => (el.getAttribute('aria-label') || el.textContent || el.value || '').trim().slice(0, 40);
    const controls = [...document.querySelectorAll('button, a.button, input[type=submit]')].filter(visible);
    const outside = controls.filter((el) => {
      const rect = el.getBoundingClientRect();
      return rect.right > width + 1 || rect.left < -1;
    });
    const stacked = [...document.querySelectorAll('.data-table-stack')].map((table) => ({
      headerHidden: table.querySelector('thead').getBoundingClientRect().height <= 1,
      unlabelled: [...table.querySelectorAll('td[data-label]')]
        .filter((td) => getComputedStyle(td, '::before').content === 'none').length,
    }));
    return {
      width,
      pageWidth: document.documentElement.scrollWidth,
      outsideOnPage: outside.filter((el) => !scrolls(el)).map(name),
      outsideInScrollBox: outside.filter(scrolls).map(name),
      stacked,
    };
  });

  if (layout.pageWidth > layout.width) problems.push(`${key}: the page scrolls sideways (${layout.pageWidth}px on a ${layout.width}px screen)`);
  if (layout.outsideOnPage.length) problems.push(`${key}: buttons outside the screen: ${layout.outsideOnPage.join(', ')}`);
  for (const table of layout.stacked) {
    if (!table.headerHidden || table.unlabelled) problems.push(`${key}: a stacked table isn't shown as labelled cards`);
  }
  // A table that scrolls inside its own box (UI-3: Courses, Profile) is allowed; report it for a person to judge
  if (layout.outsideInScrollBox.length) {
    testInfo.annotations.push({ type: 'layout-finding',
      description: `${key}: off-screen until the table is scrolled sideways: ${[...new Set(layout.outsideInScrollBox)].join(', ')}` });
  }
  if (role !== 'public') {
    await expect(page.getByRole('link', { name: 'Study Buddy Matcher' }), `${key}: Home link`).toBeInViewport();
    await expect(page.getByRole('button', { name: 'Log out' }), `${key}: Log out`).toBeInViewport();
  }
}

// UI-3: the login and sign-up pages fit a phone, with the form before the illustration
test('login and sign-up fit a phone screen', async ({ page }, testInfo) => {
  const problems = [];
  for (const path of PUBLIC_PAGES) {
    await checkPage(page, path, 'public', testInfo, problems);
    const form = await page.locator('main.auth-form-panel').boundingBox();
    const intro = await page.locator('section.auth-intro').boundingBox();
    if (form.y > intro.y) problems.push(`${path}: the form comes after the illustration`);
  }
  expect(problems).toEqual([]);
});

// UI-3, UI-1: every student page fits a phone, and a confirmation box fits the screen
test('student pages fit a phone screen', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const problems = [];
  for (const path of world.studentPages) {
    await checkPage(page, path, 'student', testInfo, problems);
  }
  await page.goto('/connections');
  await page.getByRole('button', { name: 'End connection with Ben Beside' }).click();
  const box = await page.locator('[popover]:popover-open').boundingBox();
  if (box.x < 0 || box.x + box.width > 375) problems.push('/connections: the End connection box is wider than the screen');
  await testInfo.attach('phone confirmation box', { body: await page.screenshot(), contentType: 'image/png' });
  expect(problems).toEqual([]);
});

// UI-3: every study group page fits a phone, including the slot rows of the form
test('study group pages fit a phone screen', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const problems = [];
  for (const path of world.groupPages) {
    await checkPage(page, path, 'student', testInfo, problems);
  }
  expect(problems).toEqual([]);
});

// UI-3: every admin page fits a phone, the accounts table shown as labelled cards
test('admin pages fit a phone screen', async ({ page, browser }, testInfo) => {
  const world = await buildWorld(page, browser);
  const problems = [];
  for (const path of world.adminPages) {
    await checkPage(world.adminPage, path, 'admin', testInfo, problems);
  }
  expect(problems).toEqual([]);
});

// UI-3: a brand-new student (no courses, preferences or groups yet) sees pages that fit too
test('empty student pages fit a phone screen', async ({ page }, testInfo) => {
  await signUp(page, 'Nell New');
  const problems = [];
  for (const path of ['/home', '/student/profile', '/student/preferences', '/matches', '/match-requests',
    '/connections', '/groups', '/groups/mine']) {
    await checkPage(page, path, 'new student', testInfo, problems);
  }
  expect(problems).toEqual([]);
});
