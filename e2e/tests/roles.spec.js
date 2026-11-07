// Home is the way around the app (there is no menu bar): each role's Home lists its own pages and every link opens.
const { test, expect, signUp, newAdmin } = require('../helpers/app');

async function followEveryLink(page, links) {
  for (const [link, heading] of links) {
    await page.goto('/home');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: new RegExp(`^${link}`) }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(heading);
    // Every page leads back home through the top bar
    await page.getByRole('link', { name: 'Study Buddy Matcher' }).click();
    await expect(page).toHaveURL(/\/home$/);
  }
}

// D3: a student's Home lists the six student pages and each one opens
test("a student's Home links to every student page", async ({ page }) => {
  await signUp(page, 'Hal Home');
  await followEveryLink(page, [
    ['My profile', 'My profile'], ['Find study buddies', 'Find study buddies'], ['Match requests', 'Match requests'],
    ['Study buddies', 'Study buddies'], ['Study groups', 'Study groups'], ['Courses', 'Courses'],
  ]);
  await expect(page.getByRole('link', { name: /^User accounts/ })).toHaveCount(0);
});

// D3: an admin's Home lists only the three admin pages and each one opens
test("an admin's Home links to every admin page", async ({ page, browser }) => {
  await newAdmin(page, browser);
  await followEveryLink(page, [
    ['User accounts', 'User accounts'], ['Courses', 'Courses'], ['Matching settings', 'Matching settings'],
  ]);
  await expect(page.getByRole('link', { name: /^Find study buddies/ })).toHaveCount(0);
});
