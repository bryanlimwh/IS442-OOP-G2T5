// Banners after an action: success uses role "status", errors role "alert", and each shows only once (UI-2).
const { test, expect, newPage, signUp, newAdmin, createCourse, takeCourse, addPreference } = require('../helpers/app');

// S1, PRF-1, UI-2: a success banner is a green status message that is gone after a reload (MAN-03)
test('a success banner shows once', async ({ page }) => {
  await signUp(page, 'Fran Flash');
  await page.goto('/student/profile');
  await page.locator('#programme').fill('Computer Science');
  await page.getByRole('button', { name: 'Save details' }).click();

  const banner = page.getByRole('status');
  await expect(banner).toHaveText('Your profile has been updated.');
  await expect(banner).toHaveClass(/message success/);
  await page.reload();
  await expect(page.getByText('Your profile has been updated.')).toHaveCount(0);
  await expect(page.locator('#programme')).toHaveValue('Computer Science');
});

// PRF-2, PRE-3, UI-2: an error banner is an alert, e.g. removing a course that still has a preference or an overlapping slot (MAN-03, MAN-04)
test('an error banner is an alert', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await signUp(page, 'Erin Error');
  await takeCourse(page, course);
  await addPreference(page, course, { day: 'Monday', from: '10:00', to: '12:00' });

  await page.locator('select[name=dayOfWeek]').selectOption({ label: 'Monday' });
  await page.locator('select[name=startTime]').selectOption('11:00');
  await page.locator('select[name=endTime]').selectOption('13:00');
  await page.getByRole('button', { name: '+ Add slot' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toHaveText('This slot overlaps one you already have on MONDAY.');
  await expect(alert).toHaveClass(/message error/);

  await page.goto('/student/profile');
  await page.getByRole('button', { name: `Remove ${course.code}` }).click();
  await expect(page.getByRole('alert')).toHaveText(`Delete your ${course.code} study preference before removing the course.`);
});
