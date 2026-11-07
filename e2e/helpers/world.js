// Builds, through the real pages, enough data that every main page has something on it, for the layout and
// accessibility checks. Each test calls it for itself, so those tests stay independent too.
const {
  expect, newPage, newAdmin, createCourse, studentWithPreference, sendRequest, answerRequest, createGroup, askToJoin,
} = require('./app');

/**
 * Ana (on {@code page}) takes a new course with a preference; Ben is her study buddy and a member of the group
 * she leads; Cy has sent her a match request and is waiting to join her group.
 */
async function buildWorld(page, browser) {
  const adminPage = await newPage(browser);
  await newAdmin(adminPage, browser);
  const course = await createCourse(adminPage);
  await studentWithPreference(page, 'Ana Anchor', course);
  const ben = await newPage(browser);
  await studentWithPreference(ben, 'Ben Beside', course);
  const cy = await newPage(browser);
  await studentWithPreference(cy, 'Cy Curious', course);

  await sendRequest(page, course, 'Ben Beside', 'Study together?');
  await answerRequest(ben, 'Ana Anchor', 'Accept');
  await sendRequest(cy, course, 'Ana Anchor', 'Hello!');

  const group = await createGroup(page, course);
  await askToJoin(ben, group);
  await askToJoin(cy, group);
  await page.goto(`/groups/${group.id}/members`);
  await page.getByRole('button', { name: "Accept Ben Beside's request" }).click();
  await expect(page.getByText('Ben Beside is now a member of the group.')).toBeVisible();

  await page.goto('/connections');
  const benProfile = await page.getByRole('link', { name: 'Ben Beside' }).getAttribute('href');

  return {
    adminPage,
    studentPages: ['/home', '/student/profile', '/student/preferences', '/matches', '/match-requests', '/connections',
      benProfile, '/courses'],
    groupPages: ['/groups', '/groups/mine', '/groups/new', `/groups/${group.id}?from=mine`,
      `/groups/${group.id}/members`, `/groups/${group.id}/edit?from=mine`],
    adminPages: ['/home', '/admin/accounts', '/admin/matching', '/courses'],
  };
}

const PUBLIC_PAGES = ['/login', '/register'];

/** A stable name for a page in reports and the accessibility baseline, e.g. "/groups/{id}/members". */
function pageKey(role, path) {
  return `${role} ${path.replace(/\/\d+/g, '/{id}').replace(/\?.*$/, '')}`;
}

module.exports = { buildWorld, PUBLIC_PAGES, pageKey };
