// The confirmation boxes (HTML popover attribute, no JavaScript): each opens from its button, its quiet button
// or Esc closes it without changes, and only its own submit button sends the form (UI-1). Also the two
// refusals that only happen when the database commits, which the rolled-back MockMvc flows can't show.
const {
  test, expect, newPage, signUp, newAdmin, createCourse, takeCourse, studentWithPreference, sendRequest,
  answerRequest, createGroup, askToJoin, openPopover,
} = require('../helpers/app');

// S9, REQ-5, UI-1: ending a connection asks first; Cancel and Esc keep it, End connection ends it (MAN-11)
test('ending a connection asks first', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await studentWithPreference(page, 'Ada Ender', course);
  const benPage = await newPage(browser);
  await studentWithPreference(benPage, 'Ben Ended', course);
  await sendRequest(page, course, 'Ben Ended');
  await answerRequest(benPage, 'Ada Ender', 'Accept');

  await page.goto('/connections');
  await page.getByRole('button', { name: 'End connection with Ben Ended' }).click();
  await expect(openPopover(page)).toContainText("End your connection with Ben Ended? You'll no longer see each other's contact numbers.");
  await openPopover(page).getByRole('button', { name: 'Cancel' }).click();
  await expect(openPopover(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'End connection with Ben Ended' }).click();
  await page.keyboard.press('Escape');
  await expect(openPopover(page)).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Ben Ended' })).toBeVisible();

  await page.getByRole('button', { name: 'End connection with Ben Ended' }).click();
  await openPopover(page).getByRole('button', { name: 'End connection' }).click();
  await expect(page.getByRole('status')).toHaveText("Connection ended. You can no longer see each other's contact numbers.");
  await expect(page.getByRole('link', { name: 'Ben Ended' })).toHaveCount(0);
});

// CRS-1, UI-1: deleting a course asks first; a course a student takes is refused when the database commits, an unused one is deleted (MAN-24)
test('a course that is still used cannot be deleted', async ({ page, browser }) => {
  await newAdmin(page, browser);
  const used = await createCourse(page);
  const unused = await createCourse(page);
  const studentPage = await newPage(browser);
  await signUp(studentPage, 'Cora Course');
  await takeCourse(studentPage, used);

  await page.goto('/courses');
  await page.getByRole('button', { name: `Delete ${used.code}` }).click();
  await expect(openPopover(page)).toContainText(`Delete ${used.label}? This can't be undone.`);
  await openPopover(page).getByRole('button', { name: 'Cancel' }).click();
  await expect(openPopover(page)).toHaveCount(0);

  await page.getByRole('button', { name: `Delete ${used.code}` }).click();
  await openPopover(page).getByRole('button', { name: 'Delete course' }).click();
  await expect(page.getByRole('alert'))
    .toHaveText("Students, study preferences or study groups still use this course, so it can't be deleted.");
  await expect(page.getByRole('cell', { name: used.code, exact: true })).toBeVisible();

  await page.getByRole('button', { name: `Delete ${unused.code}` }).click();
  await openPopover(page).getByRole('button', { name: 'Delete course' }).click();
  await expect(page.getByRole('status')).toHaveText('The course has been deleted.');
  await expect(page.getByRole('cell', { name: unused.code, exact: true })).toHaveCount(0);
});

// A1, ACC-5, UI-1: a student who leads a group can't be deleted (refused when the database commits); an unused admin can (MAN-20)
test('a student who is still used cannot be deleted, an unused admin can', async ({ page, browser }) => {
  await newAdmin(page, browser);
  const course = await createCourse(page);
  const studentPage = await newPage(browser);
  const leader = await signUp(studentPage, 'Lou Leading');
  await createGroup(studentPage, course);
  const extraAdmin = `extra.admin.${Date.now()}@example.com`;
  await page.goto('/admin/accounts');
  await page.locator('#new-admin-email').fill(extraAdmin);
  await page.locator('#new-admin-password').fill('password123');
  await page.getByRole('button', { name: 'Add admin account' }).click();

  await page.getByRole('button', { name: `Delete ${leader.email}` }).click();
  await expect(openPopover(page)).toContainText("A student with match requests, study groups or study buddies can't be deleted.");
  await openPopover(page).getByRole('button', { name: 'Delete account' }).click();
  await expect(page.getByRole('alert')).toHaveText(
    "This student still has match requests, study groups or study buddies, so the account can't be deleted. Deactivate it instead.");
  await expect(page.locator('tr', { hasText: leader.email })).toHaveCount(1);

  await page.getByRole('button', { name: `Delete ${extraAdmin}` }).click();
  await expect(openPopover(page)).not.toContainText("can't be deleted. Deactivate");
  await openPopover(page).getByRole('button', { name: 'Delete account' }).click();
  await expect(page.getByRole('status')).toHaveText('The account has been deleted.');
  await expect(page.locator('tr', { hasText: extraAdmin })).toHaveCount(0);
});

// A1, ACC-3, UI-1: the admin's Edit box changes an email and closes with Close (MAN-17)
test("the admin's Edit box changes an email", async ({ page, browser }) => {
  await newAdmin(page, browser);
  const studentPage = await newPage(browser);
  const student = await signUp(studentPage, 'Edie Edited');
  const newEmail = `edie.new.${Date.now()}@example.com`;

  await page.goto('/admin/accounts');
  await page.getByRole('button', { name: `Edit ${student.email}` }).click();
  await openPopover(page).getByRole('button', { name: 'Close' }).click();
  await expect(openPopover(page)).toHaveCount(0);
  await page.getByRole('button', { name: `Edit ${student.email}` }).click();
  await openPopover(page).getByLabel('New email').fill(newEmail);
  await openPopover(page).getByRole('button', { name: 'Save email' }).click();
  await expect(page.getByRole('status')).toHaveText(`The account now logs in with ${newEmail}.`);
});

// PRE-2, UI-1: deleting a study preference asks first (MAN-04)
test('deleting a study preference asks first', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await studentWithPreference(page, 'Pia Pref', course);

  await page.goto('/student/preferences');
  await page.getByRole('button', { name: `Delete ${course.code} preference` }).click();
  await expect(openPopover(page)).toContainText(`Delete your ${course.code} study preference and its time slots? This can't be undone.`);
  await openPopover(page).getByRole('button', { name: 'Cancel' }).click();
  await expect(page.locator('td[data-label="Course"]', { hasText: course.code })).toBeVisible();
  await page.getByRole('button', { name: `Delete ${course.code} preference` }).click();
  await openPopover(page).getByRole('button', { name: 'Delete preference' }).click();
  await expect(page.getByRole('status')).toHaveText('The study preference has been deleted.');
});

// L3, GRP-6, TD-4, UI-1: closing a group says how many requests will end, Cancel keeps it open (MAN-13, MAN-34)
test('closing a group asks first and says how many requests end', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await signUp(page, 'Cal Closer');
  const group = await createGroup(page, course);
  const joiner = await newPage(browser);
  await signUp(joiner, 'Jen Joiner');
  await askToJoin(joiner, group);

  await page.goto(`/groups/${group.id}?from=mine`);
  await page.getByRole('button', { name: 'Close group' }).click();
  await expect(openPopover(page))
    .toContainText("Close this group? Its 1 pending request will end without an answer, and you can't reopen it.");
  await openPopover(page).getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByRole('link', { name: 'Edit group' })).toBeVisible();

  await page.getByRole('button', { name: 'Close group' }).click();
  await openPopover(page).getByRole('button', { name: 'Close group' }).click();
  await expect(page.getByRole('status')).toHaveText('Group closed. 1 pending request ended without an answer.');
  await expect(page.getByRole('link', { name: 'Edit group' })).toHaveCount(0);
});

// L5, L6, GRP-4, UI-1: rejecting, removing and asking again each ask first (MAN-14, MAN-15, MAN-35)
test('rejecting, removing and asking again each ask first', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await signUp(page, 'Rae Leader');
  const group = await createGroup(page, course);
  const rita = await newPage(browser);
  await signUp(rita, 'Rita Rejected');
  await askToJoin(rita, group);
  const mo = await newPage(browser);
  await signUp(mo, 'Mo Removed');
  await askToJoin(mo, group);

  await page.goto(`/groups/${group.id}/members`);
  await page.getByRole('button', { name: "Reject Rita Rejected's request" }).click();
  await expect(openPopover(page)).toContainText("Reject Rita Rejected's request? They can ask to join again later.");
  await openPopover(page).getByRole('button', { name: 'Reject request' }).click();
  await expect(page.getByRole('status')).toHaveText("Rita Rejected's request was rejected.");

  await page.getByRole('button', { name: "Accept Mo Removed's request" }).click();
  await page.getByRole('button', { name: 'Remove Mo Removed from the group' }).click();
  await expect(openPopover(page)).toContainText(`Remove Mo Removed from ${group.name}?`);
  await page.keyboard.press('Escape');
  await expect(openPopover(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'Remove Mo Removed from the group' }).click();
  await openPopover(page).getByRole('button', { name: 'Remove member' }).click();
  await expect(page.getByRole('status')).toHaveText('Mo Removed was removed from the group.');

  await mo.goto(`/groups/${group.id}`);
  await mo.getByRole('button', { name: 'Request to join again' }).click();
  await expect(openPopover(mo)).toContainText('You were removed from this group before. Do you want to request to join again?');
  await openPopover(mo).getByRole('button', { name: 'Request again' }).click();
  await expect(mo.getByRole('status')).toHaveText("Request sent. The group's leader will review it.");
});

// L4, GRP-8, UI-1: cancelling a join request from My groups: Keep request changes nothing (MAN-47)
test('cancelling a join request asks first', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  const leaderPage = await newPage(browser);
  await signUp(leaderPage, 'Kai Keeper');
  const group = await createGroup(leaderPage, course);
  await signUp(page, 'Faye Cancel');
  await askToJoin(page, group);

  await page.goto('/groups/mine');
  const cancel = page.getByRole('button', { name: `Cancel your request to join ${group.name}` });
  await cancel.click();
  await expect(openPopover(page)).toContainText(
    `Cancel your request to join ${group.name}? If you ask again later, it will count as a new request.`);
  await openPopover(page).getByRole('button', { name: 'Keep request' }).click();
  await expect(openPopover(page)).toHaveCount(0);
  await expect(page.getByRole('heading', { name: group.name })).toBeVisible();

  await cancel.click();
  await openPopover(page).getByRole('button', { name: 'Cancel request' }).click();
  await expect(page).toHaveURL(/\/groups\/mine$/);
  await expect(page.getByRole('status')).toHaveText('Request cancelled. You can ask to join again later.');
  await expect(page.getByRole('heading', { name: group.name })).toHaveCount(0);
});

// L4, GRP-9, UI-1: leaving asks first; Stay in group keeps the place; the reason typed in the box reaches the leader (MAN-48)
test('leaving a group asks first and sends the reason', async ({ page, browser }) => {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  const leaderPage = await newPage(browser);
  await signUp(leaderPage, 'Lana Lead');
  const group = await createGroup(leaderPage, course);
  await signUp(page, 'Liam Leaving');
  await askToJoin(page, group);
  await leaderPage.goto(`/groups/${group.id}/members`);
  await leaderPage.getByRole('button', { name: "Accept Liam Leaving's request" }).click();

  await page.goto(`/groups/${group.id}`);
  await page.getByRole('button', { name: 'Leave group' }).click();
  await expect(openPopover(page)).toContainText(`Leave ${group.name}? You can ask to join again later.`);
  await openPopover(page).getByRole('button', { name: 'Stay in group' }).click();
  await expect(page.getByText("You're a member of this group.")).toBeVisible();

  await page.getByRole('button', { name: 'Leave group' }).click();
  await openPopover(page).getByLabel('Reason').fill('Clashes with my internship');
  await openPopover(page).getByRole('button', { name: 'Leave group' }).click();
  await expect(page.getByRole('status')).toHaveText('You left the group. You can ask to join again later.');

  await leaderPage.goto(`/groups/${group.id}/members`);
  const left = leaderPage.getByRole('region', { name: 'Members who left' });
  await expect(left).toContainText('Liam Leaving');
  await expect(left).toContainText('Reason: Clashes with my internship');
});
