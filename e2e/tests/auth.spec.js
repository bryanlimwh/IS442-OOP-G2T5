// Logging in and out, the browser's own sign-up checks, and which pages each role reaches, in a real browser.
const { test, expect, PASSWORD, uid, contactNumber, newPage, logIn, logOut, signUp, newAdmin } = require('../helpers/app');

// S1, ACC-1: a student signs up, logs in and logs out in a real browser (MAN-01)
test('student signs up, logs in and logs out', async ({ page }) => {
  const student = await signUp(page, 'Eve Entry');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await expect(page.getByText(`Logged in as ${student.email}`)).toBeVisible();

  await logOut(page);
  await expect(page.getByRole('status')).toHaveText("You've logged out.");
  await page.goto('/home');
  await expect(page).toHaveURL(/\/login$/);
});

// PRF-1, ACC-2: the browser itself refuses a 7-digit contact number and a short password; nothing is sent (MAN-02)
test('the browser blocks a short password and a 7-digit contact number on sign-up', async ({ page }) => {
  await page.goto('/register');
  await page.locator('#email').fill(`blocked.${uid()}@example.com`);
  await page.locator('#password').fill('short');
  await page.locator('#name').fill('Blocked Sign Up');
  await page.locator('#school').fill('SCIS');
  await page.locator('#programme').fill('IS');
  await page.locator('#yearOfStudy').fill('1');
  await page.locator('#contactNumber').fill('1234567');
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page).toHaveURL(/\/register$/);
  expect(await page.locator('#password').evaluate((el) => el.validity.tooShort)).toBe(true);
  expect(await page.locator('#contactNumber').evaluate((el) => el.validity.patternMismatch)).toBe(true);

  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/register$/);
  await page.locator('#contactNumber').fill(contactNumber());
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/login\?registered/);
});

// ACC-6: once deactivated by an admin, a student can't log in in the browser (MAN-18)
test('a deactivated student cannot log in', async ({ page, browser }) => {
  const studentPage = await newPage(browser);
  const student = await signUp(studentPage, 'Dee Activated');
  await logOut(studentPage);

  await newAdmin(page, browser);
  await page.goto('/admin/accounts');
  await page.getByRole('button', { name: `Deactivate ${student.email}` }).click();
  await expect(page.getByText(`${student.email} has been deactivated and can no longer log in.`)).toBeVisible();

  await logIn(studentPage, student.email);
  await expect(studentPage).toHaveURL(/\/login\?error/);
  await expect(studentPage.getByRole('alert'))
    .toHaveText("That email and password don't match an active account. Check them and try again.");
});

// D3, SEC-1, SEC-2: a student gets 403 on admin pages, an admin gets 403 on student pages, a visitor is sent to log in (MAN-26)
test('each role only reaches its own pages', async ({ page, browser }) => {
  await signUp(page, 'Rory Role');
  expect((await page.goto('/admin/accounts')).status()).toBe(403);

  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  expect((await adminPage.goto('/matches')).status()).toBe(403);
  expect((await adminPage.goto('/groups')).status()).toBe(403);

  const visitor = await newPage(browser);
  await visitor.goto('/groups');
  await expect(visitor).toHaveURL(/\/login$/);
});

// SEC-3, K15: pins current behaviour (suspected bug K15): the H2 console opens for anyone, logged in or not (MAN-39)
test('the H2 console opens without logging in', async ({ page }) => {
  await page.goto('/h2-console');
  await expect(page).not.toHaveURL(/\/login$/);
  await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeVisible();

  await signUp(page, 'Hugo Console');
  await page.goto('/h2-console');
  await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeVisible();
});
