// Shared steps for the specs. Everything a test needs (admin, students, courses, groups) is made through
// the real pages with a random suffix, so no test depends on another test, on test order or on seeded data.
// The ONE exception: an admin can only be created by another admin, so newAdmin() logs in as the seeded
// admin just to add a fresh one. The seeded admin's email, password and status are never changed.
const base = require('@playwright/test');

const PASSWORD = 'password123';
const SEED_ADMIN = {
  email: 'admin@studybuddy.com',
  // studybuddy.seed.password in application.properties (a development-only demo password)
  password: process.env.E2E_SEED_ADMIN_PASSWORD || 'password123',
};

// Google Fonts are blocked so runs don't depend on the network; style.css falls back to system fonts
async function blockExternalFonts(context) {
  await context.route(/fonts\.(googleapis|gstatic)\.com/, (route) => route.abort());
}

const test = base.test.extend({
  context: async ({ context }, use) => {
    await blockExternalFonts(context);
    await use(context);
  },
});
const { expect } = base;

function uid() {
  return Date.now().toString(36).slice(-5) + Math.random().toString(36).slice(2, 7);
}

function contactNumber() {
  return String(80000000 + Math.floor(Math.random() * 19999999));
}

/**
 * A second (third...) person in the same test: a new browser context with its own cookies, the same
 * address and screen size as the running project (so a phone test's second person is on a phone too).
 */
async function newPage(browser) {
  let use = {};
  try {
    use = base.test.info().project.use;
  } catch {
    // Outside a test (e.g. in beforeAll): desktop defaults
  }
  const options = { baseURL: use.baseURL || 'http://localhost:8089' };
  for (const key of ['viewport', 'deviceScaleFactor', 'isMobile', 'hasTouch', 'userAgent']) {
    if (use[key] !== undefined) options[key] = use[key];
  }
  const context = await browser.newContext(options);
  await blockExternalFonts(context);
  return context.newPage();
}

async function logIn(page, email, password = PASSWORD) {
  await page.goto('/login');
  await page.locator('#email').fill(email);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

async function logOut(page) {
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login\?logout/);
}

/** Signs a new student up through /register and logs them in. */
async function signUp(page, name) {
  const student = {
    name,
    email: `${name.toLowerCase().replace(/\s+/g, '.')}.${uid()}@example.com`,
    contact: contactNumber(),
  };
  await page.goto('/register');
  await page.locator('#email').fill(student.email);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#name').fill(name);
  await page.locator('#school').fill('SCIS');
  await page.locator('#programme').fill('Information Systems');
  await page.locator('#yearOfStudy').fill('2');
  await page.locator('#contactNumber').fill(student.contact);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/login\?registered/);
  await logIn(page, student.email);
  await expect(page).toHaveURL(/\/home$/);
  return student;
}

/**
 * Logs the seeded admin in (in a throwaway context) only to add a fresh admin, then logs that fresh
 * admin in on {@code page}. Admin tests act on this new admin, never on the seeded one.
 */
async function newAdmin(page, browser) {
  const email = `e2e.admin.${uid()}@example.com`;
  const seedPage = await newPage(browser);
  await logIn(seedPage, SEED_ADMIN.email, SEED_ADMIN.password);
  await expect(seedPage).toHaveURL(/\/home$/);
  await seedPage.goto('/admin/accounts');
  await seedPage.locator('#new-admin-email').fill(email);
  await seedPage.locator('#new-admin-password').fill(PASSWORD);
  await seedPage.getByRole('button', { name: 'Add admin account' }).click();
  await expect(seedPage.getByText(`Admin account ${email} has been created.`)).toBeVisible();
  await seedPage.context().close();
  await logIn(page, email);
  await expect(page).toHaveURL(/\/home$/);
  return { email };
}

/** The admin adds a course on /courses; returns its code and its name as the dropdowns show it. */
async function createCourse(adminPage, prefix = 'E2E') {
  const code = `${prefix}${uid()}`.toUpperCase();
  const name = `E2E course ${code}`;
  await adminPage.goto('/courses');
  await adminPage.locator('#new-courseCode').fill(code);
  await adminPage.locator('#new-courseName').fill(name);
  await adminPage.getByRole('button', { name: 'Add course' }).click();
  await expect(adminPage.getByText(`${code} has been added.`)).toBeVisible();
  return { code, label: `${code} ${name}` };
}

async function takeCourse(page, course) {
  await page.goto('/student/profile');
  await page.locator('#courseId').selectOption({ label: course.label });
  await page.getByRole('button', { name: 'Add course' }).click();
  await expect(page.getByText('The course has been added.')).toBeVisible();
}

/** Adds a preference for the course and one weekly slot. Labels as the page shows them. */
async function addPreference(page, course, { mode = 'Online', size = 'One-to-one', goal = 'Exam preparation',
  day = 'Monday', from = '10:00', to = '12:00' } = {}) {
  await page.goto('/student/preferences');
  await page.locator('#new-courseId').selectOption({ label: course.label });
  await page.locator('#new-studyMode').selectOption({ label: mode });
  await page.locator('#new-arrangement').selectOption({ label: size });
  await page.locator('#new-studyGoal').selectOption({ label: goal });
  await page.getByRole('button', { name: 'Add preference' }).click();
  await expect(page.getByText(`Your ${course.code} preference has been added.`)).toBeVisible();
  await page.locator('select[name=dayOfWeek]').selectOption({ label: day });
  await page.locator('select[name=startTime]').selectOption(from);
  await page.locator('select[name=endTime]').selectOption(to);
  await page.getByRole('button', { name: '+ Add slot' }).click();
  await expect(page.getByText('The time slot has been added.')).toBeVisible();
}

/** A signed-up student who takes the course and has a matching preference. */
async function studentWithPreference(page, name, course, preference) {
  const student = await signUp(page, name);
  await takeCourse(page, course);
  await addPreference(page, course, preference);
  return student;
}

/** The student's match card on /matches for the course. */
async function matchCard(page, course, name) {
  await page.goto('/matches');
  await page.locator('#courseId').selectOption({ label: course.code });
  await page.getByRole('button', { name: 'Search' }).click();
  return page.locator('li.match-card', { has: page.getByRole('link', { name, exact: true }) });
}

async function sendRequest(page, course, toName, message = '') {
  const card = await matchCard(page, course, toName);
  if (message) {
    await card.locator('textarea[name=message]').fill(message);
  }
  await card.getByRole('button', { name: 'Send request' }).click();
  await expect(page.getByText(`Request sent to ${toName}.`)).toBeVisible();
}

async function answerRequest(page, fromName, button) {
  await page.goto('/match-requests');
  const card = page.locator('li.match-card', { has: page.getByRole('link', { name: fromName, exact: true }) });
  await card.getByRole('button', { name: button }).click();
}

/** The leader creates a group through the form (one Monday 10:00-12:00 slot); returns its name and id. */
async function createGroup(page, course, { size = 4, prefix = 'E2E Group' } = {}) {
  const name = `${prefix} ${uid()}`;
  await page.goto('/groups/new');
  await page.locator('#name').fill(name);
  await page.locator('#courseId').selectOption({ label: course.label });
  await page.locator('#studyGoal').selectOption({ label: 'Exam preparation' });
  await page.locator('#studyMode').selectOption({ label: 'Online' });
  await page.locator('#maxSize').fill(String(size));
  await page.locator('select[name="slots[0].dayOfWeek"]').selectOption({ label: 'Monday' });
  await page.locator('select[name="slots[0].startTime"]').selectOption('10:00');
  await page.locator('select[name="slots[0].endTime"]').selectOption('12:00');
  await page.getByRole('button', { name: 'Create group' }).click();
  await expect(page.getByText('Group created.')).toBeVisible();
  const id = page.url().match(/\/groups\/(\d+)/)[1];
  return { name, id };
}

async function askToJoin(page, group) {
  await page.goto(`/groups/${group.id}`);
  await page.getByRole('button', { name: 'Request to join' }).click();
  await expect(page.getByText("Request sent. The group's leader will review it.")).toBeVisible();
}

/** The confirmation box that is open right now (popover attribute). */
function openPopover(page) {
  return page.locator('[popover]:popover-open');
}

module.exports = {
  test, expect, PASSWORD, SEED_ADMIN, uid, contactNumber, newPage, logIn, logOut, signUp, newAdmin, createCourse,
  takeCourse, addPreference, studentWithPreference, matchCard, sendRequest, answerRequest, createGroup, askToJoin,
  openPopover,
};
