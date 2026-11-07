// Find study buddies and Match requests in the browser: the request just sent is framed and labelled, and the
// labels that replace "Send request" take the student to the right page (S7, S8, TD-5).
const {
  test, expect, newPage, newAdmin, createCourse, studentWithPreference, matchCard, sendRequest, answerRequest,
} = require('../helpers/app');

async function threeStudents(page, browser) {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await studentWithPreference(page, 'Nia Newcomer', course);
  const ben = await newPage(browser);
  await studentWithPreference(ben, 'Ben Buddy', course);
  const cat = await newPage(browser);
  await studentWithPreference(cat, 'Cat Caller', course);
  return { course, ben, cat };
}

// S7, REQ-1, TD-5, K12: the request just sent is framed under Sent with its label and message, even a message of 'no' (MAN-08, MAN-41)
test('the request just sent is framed and labelled', async ({ page, browser }) => {
  const { course } = await threeStudents(page, browser);

  await sendRequest(page, course, 'Ben Buddy', 'Want to study together?');
  await expect(page.getByRole('status')).toHaveText('Request sent to Ben Buddy. Their reply will show under Sent below.');
  const framed = page.locator('li.match-card-new');
  await expect(framed).toHaveCount(1);
  await expect(framed).toContainText('Ben Buddy');
  await expect(framed).toContainText('Want to study together?');
  await expect(framed.locator('.status-label-pending')).toHaveText('Waiting for a reply');

  await sendRequest(page, course, 'Cat Caller', 'no');
  await expect(page.locator('li.match-card-new')).toContainText('Cat Caller');
  await expect(page.locator('li.match-card-new')).toContainText('no');
});

// S3, S7, TD-5: each label replaces the Send button and opens the right page (MAN-42)
test('the labels on match cards open the right page', async ({ page, browser }) => {
  const { course, ben, cat } = await threeStudents(page, browser);
  await sendRequest(ben, course, 'Nia Newcomer');
  await answerRequest(page, 'Ben Buddy', 'Accept');
  await sendRequest(cat, course, 'Nia Newcomer');

  let card = await matchCard(page, course, 'Ben Buddy');
  await expect(card.getByRole('button', { name: 'Send request' })).toHaveCount(0);
  await card.getByRole('link', { name: "You're already study buddies" }).click();
  await expect(page).toHaveURL(/\/connections$/);
  await expect(page.getByRole('link', { name: 'Ben Buddy' })).toBeVisible();

  card = await matchCard(page, course, 'Cat Caller');
  await card.getByRole('link', { name: "They've sent you a request" }).click();
  await expect(page).toHaveURL(/\/match-requests$/);

  card = await matchCard(cat, course, 'Nia Newcomer');
  await expect(card.getByRole('link', { name: 'Request sent' })).toBeVisible();
});

// S8, REQ-2: accepted and declined requests show their coloured labels, with no buttons left (MAN-09, MAN-10)
test('accepted and declined requests show their labels', async ({ page, browser }) => {
  const { course, ben, cat } = await threeStudents(page, browser);
  await sendRequest(ben, course, 'Nia Newcomer');
  await sendRequest(cat, course, 'Nia Newcomer');

  await answerRequest(page, 'Ben Buddy', 'Accept');
  await expect(page.getByRole('status'))
    .toHaveText("Request accepted. You're now study buddies: their contact number is on the Study buddies page.");
  await answerRequest(page, 'Cat Caller', 'Decline');
  await expect(page.getByRole('status')).toHaveText('Request declined.');

  const received = (name) => page.locator('li.match-card', { has: page.getByRole('link', { name, exact: true }) });
  await expect(received('Ben Buddy').locator('.status-label-accepted')).toHaveText('Accepted');
  await expect(received('Cat Caller').locator('.status-label-rejected')).toHaveText('Declined');
  await expect(page.getByRole('button', { name: 'Accept' })).toHaveCount(0);

  await cat.goto('/match-requests');
  await expect(cat.locator('.status-label-rejected')).toHaveText('Declined');
});
