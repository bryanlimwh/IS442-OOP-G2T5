// What the real forms do in the browser: the slot rows, the limits the browser itself enforces, and buttons
// that are switched off.
const {
  test, expect, newPage, signUp, newAdmin, createCourse, studentWithPreference, matchCard, createGroup, askToJoin,
} = require('../helpers/app');

// L2, GRP-7: on the create form '+ Add slot' adds rows up to three, then disappears; Remove takes one away (MAN-32)
test('the group form stops at three slots', async ({ page }) => {
  await signUp(page, 'Sid Slots');
  await page.goto('/groups/new');
  await expect(page.getByText('A group can have up to 3 slots.')).toBeVisible();
  await page.getByRole('button', { name: '+ Add slot' }).click();
  await page.getByRole('button', { name: '+ Add slot' }).click();

  await expect(page.getByRole('group', { name: 'Slot 3' })).toBeVisible();
  await expect(page.getByText('Max time slots reached (3)')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Add slot' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Remove slot 2' }).click();
  await expect(page.getByRole('group', { name: 'Slot 3' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '+ Add slot' })).toBeVisible();
});

// MAT-1: the browser refuses a negative weight on the matching settings form, so nothing is sent (MAN-22)
test('the browser refuses a negative matching weight', async ({ page, browser }) => {
  await newAdmin(page, browser);
  await page.goto('/admin/matching');
  await page.locator('#courseWeight').fill('-1');
  await page.getByRole('button', { name: 'Save settings' }).click();

  await expect(page.getByText('Settings saved.')).toHaveCount(0);
  expect(await page.locator('#courseWeight').evaluate((el) => el.validity.rangeUnderflow)).toBe(true);
});

// S7, GRP-9: the message box keeps at most 255 characters and the leave reason at most 500
test('the message and reason boxes stop at their limits', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await studentWithPreference(page, 'Max Message', course);
  const other = await newPage(browser);
  await studentWithPreference(other, 'Mel Matched', course);

  const card = await matchCard(page, course, 'Mel Matched');
  const message = card.getByLabel('Message (optional)');
  await message.click();
  await page.keyboard.insertText('m'.repeat(300));
  await expect(message).toHaveValue('m'.repeat(255));

  const group = await createGroup(other, course);
  await askToJoin(page, group);
  await other.goto(`/groups/${group.id}/members`);
  await other.getByRole('button', { name: "Accept Max Message's request" }).click();
  await page.goto(`/groups/${group.id}`);
  await page.getByRole('button', { name: 'Leave group' }).click();
  const reason = page.getByLabel('Reason');
  await reason.click();
  await page.keyboard.insertText('r'.repeat(600));
  await expect(reason).toHaveValue('r'.repeat(500));
});

// L5, GRP-4, GRP-5, TD-3: in a full group the join button and the leader's Accept are switched off (MAN-31, MAN-36)
test("a full group's join and accept buttons are switched off", async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await signUp(page, 'Fia Full');
  const group = await createGroup(page, course, { size: 2 });
  const first = await newPage(browser);
  await signUp(first, 'Ivo First');
  await askToJoin(first, group);
  const second = await newPage(browser);
  await signUp(second, 'Joy Second');
  await askToJoin(second, group);

  await page.goto(`/groups/${group.id}/members`);
  await page.getByRole('button', { name: "Accept Ivo First's request" }).click();
  await expect(page.getByText('Group is full. Remove a member to accept new members.')).toBeVisible();
  await expect(page.getByRole('button', { name: "Accept Joy Second's request" })).toBeDisabled();

  const late = await newPage(browser);
  await signUp(late, 'Lex Late');
  await late.goto(`/groups/${group.id}`);
  await expect(late.getByRole('button', { name: 'Request to join' })).toBeDisabled();
  await expect(late.getByText('This group is full.')).toBeVisible();
});
