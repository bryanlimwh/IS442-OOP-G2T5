// The admin's matching settings change what every student sees, so this spec runs in its own project after all
// the others (playwright.config.js), its tests run one after another, and the settings are always put back
// afterwards, even when a test fails.
const {
  test, expect, newPage, logIn, newAdmin, createCourse, studentWithPreference, matchCard,
} = require('../helpers/app');

test.describe.configure({ mode: 'serial' });

const FIELDS = ['courseWeight', 'availabilityWeight', 'studyModeWeight', 'studyGoalWeight', 'groupSizeWeight',
  'targetSessionMinutes'];
let admin;      // a fresh admin made for this spec
let original;   // the settings as they were before this spec, put back after every test

async function readSettings(page) {
  await page.goto('/admin/matching');
  const values = { strategy: await page.locator('input[name=strategy]:checked').getAttribute('value') };
  for (const field of FIELDS) {
    values[field] = await page.locator(`#${field}`).inputValue();
  }
  return values;
}

async function saveSettings(page, strategyLabel, values) {
  await page.goto('/admin/matching');
  await page.getByRole('radio', { name: new RegExp(`^${strategyLabel}`) }).check();
  for (const [field, value] of Object.entries(values)) {
    await page.locator(`#${field}`).fill(String(value));
  }
  await page.getByRole('button', { name: 'Save settings' }).click();
  await expect(page.getByRole('status')).toHaveText('Settings saved. New searches use them straight away.');
}

test.beforeAll(async ({ browser }) => {
  const page = await newPage(browser);
  admin = await newAdmin(page, browser);
  original = await readSettings(page);
  await page.context().close();
});

test.afterEach(async ({ browser }) => {
  const page = await newPage(browser);
  try {
    await logIn(page, admin.email);
    await page.goto('/admin/matching');
    await page.locator(`input[name=strategy][value=${original.strategy}]`).check();
    for (const field of FIELDS) {
      await page.locator(`#${field}`).fill(original[field]);
    }
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByRole('status')).toHaveText('Settings saved. New searches use them straight away.');
    expect(await readSettings(page)).toEqual(original);
  } finally {
    await page.context().close();
  }
});

// Alex (Mon 10-14, In person, One-to-one, Exam preparation) searches; Pia matches everything but shares only
// 30 minutes, Quinn shares all 4 hours and nothing else.
async function world(browser) {
  const adminPage = await newPage(browser);
  await logIn(adminPage, admin.email);
  const course = await createCourse(adminPage, 'SET');
  const alex = await newPage(browser);
  await studentWithPreference(alex, 'Alex Seeker', course,
    { mode: 'In person', size: 'One-to-one', goal: 'Exam preparation', day: 'Monday', from: '10:00', to: '14:00' });
  const pia = await newPage(browser);
  await studentWithPreference(pia, 'Pia Precise', course,
    { mode: 'In person', size: 'One-to-one', goal: 'Exam preparation', day: 'Monday', from: '10:00', to: '10:30' });
  const quinn = await newPage(browser);
  await studentWithPreference(quinn, 'Quinn Free', course,
    { mode: 'Online', size: 'Small group', goal: 'Concept review', day: 'Monday', from: '10:00', to: '14:00' });
  return { adminPage, course, alex };
}

async function namesInOrder(page) {
  return page.locator('li.match-card .match-name').allTextContents();
}

// D2, A4, MAT-3: switching the strategy changes the order a student sees, not the scores (MAN-23)
test('the strategy changes the order students see', async ({ browser }) => {
  const { adminPage, course, alex } = await world(browser);
  await saveSettings(adminPage, 'Balanced', { courseWeight: 20, availabilityWeight: 20, studyModeWeight: 20,
    studyGoalWeight: 20, groupSizeWeight: 20, targetSessionMinutes: 120 });
  await matchCard(alex, course, 'Pia Precise');
  await expect(alex.getByRole('status')).toContainText('ranked by Balanced matching.');
  expect(await namesInOrder(alex)).toEqual(['Pia Precise', 'Quinn Free']);

  await saveSettings(adminPage, 'Availability first', {});
  await matchCard(alex, course, 'Pia Precise');
  await expect(alex.getByRole('status')).toContainText('ranked by Availability first matching.');
  expect(await namesInOrder(alex)).toEqual(['Quinn Free', 'Pia Precise']);
});

// A3, A4, D2, MAT-2: with Course first and only the course weight, every course mate scores 100 (MAN-43)
test('course first with only the course weight scores every course mate 100', async ({ browser }) => {
  const { adminPage, course, alex } = await world(browser);
  await saveSettings(adminPage, 'Course first', { courseWeight: 1, availabilityWeight: 0, studyModeWeight: 0,
    studyGoalWeight: 0, groupSizeWeight: 0 });
  await expect(adminPage.getByRole('row', { name: /Same course/ })).toContainText('100%');

  await matchCard(alex, course, 'Pia Precise');
  await expect(alex.getByRole('status')).toContainText('ranked by Course first matching.');
  await expect(alex.locator('.match-score-value')).toHaveText(['100', '100']);
});
