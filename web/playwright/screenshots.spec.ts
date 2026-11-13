// Screenshot matrix: every route × widths 360/768/1280/1920 × light/dark × font scale 1.0/2.0, plus the
// empty-account and 900-due scenarios for the screens they change and a few interaction states.
// Output: screenshots/<scenario>/<route>/<width>-<theme>-<scale>.png (gitignored).
import { test, type Page } from '@playwright/test';
import accountJson from '../../fixtures/demo/account.json' with { type: 'json' };
import learningJson from '../../fixtures/demo/learning.json' with { type: 'json' };
import mediaJson from '../../fixtures/demo/media.json' with { type: 'json' };

const WIDTHS = [360, 768, 1280, 1920] as const;
const THEMES = ['light', 'dark'] as const;
const SCALES = [1, 2] as const;
/** The dataset's "now" (fixtures/demo/build.py ANCHOR): due dates and countdowns render the same every run. */
const NOW = new Date('2026-11-13T09:00:00Z');

const deckId = learningJson.decks[0].id;
const listDeckId = learningJson.decks.find((d) => d.type === 'list')?.id ?? deckId;
const mediaId = mediaJson.media[0].id;
const customTypeId = accountJson.note_types[accountJson.note_types.length - 1].id;
const hotNoteId = learningJson.hot_words[0].note_id;

interface Shot {
	/** Folder name. */
	name: string;
	path: string;
	scenarios?: string[];
	/** Brings the screen into the state to capture (after it settled). */
	prepare?: (page: Page) => Promise<void>;
	/** Shown logged out. */
	loggedOut?: boolean;
}

const SHOTS: Shot[] = [
	{ name: 'home', path: '/', scenarios: ['default', 'empty'] },
	{ name: 'login', path: '/login', loggedOut: true },
	{ name: 'review', path: '/review', scenarios: ['default', 'due900', 'empty'] },
	{
		name: 'review--back',
		path: '/review',
		prepare: async (p) => {
			await p.getByRole('button', { name: 'Show answer' }).click();
			await p.locator('.grade').first().waitFor();
		}
	},
	{ name: 'review--drill', path: '/review?drill=leeches' },
	{ name: 'dictionary', path: '/dictionary' },
	{
		name: 'dictionary--entry',
		path: '/dictionary',
		prepare: async (p) => {
			await p.getByRole('searchbox').first().fill('to');
			await p.locator('button.result').first().waitFor();
			await p.locator('button.result', { hasText: '取る' }).click();
			await p.getByRole('heading', { name: '取る' }).waitFor();
		}
	},
	{ name: 'notes', path: '/notes', scenarios: ['default', 'empty'] },
	{
		name: 'notes--editor',
		path: '/notes',
		prepare: async (p) => {
			// The note label is a button (the POC layout can squeeze it to nothing): dispatch the click.
			await p.locator('[role="row"][aria-rowindex="2"] button.link').first().dispatchEvent('click');
			await p.getByRole('heading', { name: 'Edit note' }).waitFor();
		}
	},
	{ name: 'cards', path: '/cards', scenarios: ['default', 'empty'] },
	{
		name: 'cards--preview',
		path: '/cards',
		prepare: async (p) => {
			await p.locator('[role="row"][aria-rowindex="2"]').first().click();
		}
	},
	{ name: 'decks', path: '/decks', scenarios: ['default', 'empty'] },
	{ name: 'decks-new', path: '/decks/new' },
	{ name: 'deck', path: `/decks/${deckId}` },
	{ name: 'deck-list', path: `/decks/${listDeckId}` },
	{ name: 'note-types', path: '/note-types' },
	{ name: 'note-types-new', path: '/note-types/new' },
	{ name: 'note-type', path: `/note-types/${customTypeId}` },
	{ name: 'media', path: '/media', scenarios: ['default', 'empty'] },
	{ name: 'media-new', path: '/media/new' },
	{ name: 'media-coverage', path: `/media/${mediaId}` },
	{ name: 'media-read', path: `/media/${mediaId}/read` },
	{ name: 'media-sentences', path: `/media/${mediaId}/sentences` },
	{ name: 'import', path: '/import' },
	{ name: 'import-mapping', path: '/import/mapping' },
	{ name: 'struggle', path: '/struggle', scenarios: ['default', 'empty'] },
	{ name: 'struggle-leeches', path: '/struggle/leeches' },
	{ name: 'struggle-confusables', path: '/struggle/confusables' },
	{ name: 'struggle-word', path: `/struggle/word/${hotNoteId}` },
	{ name: 'stats', path: '/stats', scenarios: ['default', 'due900', 'empty'] },
	{ name: 'settings', path: '/settings' },
	{ name: 'settings-attribution', path: '/settings/attribution' }
];

/** Waits until no API request (other than the event stream) has been in flight for 400 ms. */
async function settle(page: Page, inflight: Set<unknown>): Promise<void> {
	await page.waitForLoadState('load');
	let quietSince = Date.now();
	const deadline = Date.now() + 15_000;
	while (Date.now() < deadline) {
		if (inflight.size > 0) quietSince = Date.now();
		else if (Date.now() - quietSince >= 400) break;
		await page.waitForTimeout(50);
	}
	await page.evaluate(() => document.fonts.ready.then(() => undefined));
}

for (const shot of SHOTS) {
	for (const scenario of shot.scenarios ?? ['default']) {
		for (const theme of THEMES) {
			for (const scale of SCALES) {
				test(`${scenario}/${shot.name} ${theme} ×${scale}`, async ({ page, context }) => {
					await page.clock.setFixedTime(NOW);
					await context.addCookies([
						{ name: 'kb-mock-scenario', value: scenario, url: test.info().project.use.baseURL ?? 'http://localhost' }
					]);
					await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
					if (shot.loggedOut) {
						await page.route('**/api/v1/auth/session', (r) =>
							r.fulfill({
								status: 401,
								contentType: 'application/problem+json',
								body: JSON.stringify({ code: 'unauthenticated', status: 401, title: 'unauthenticated', type: 'about:blank', request_id: 'demo' })
							})
						);
					}
					const inflight = new Set<unknown>();
					page.on('request', (r) => {
						if (r.url().includes('/api/') && !r.url().includes('/api/v1/events')) inflight.add(r);
					});
					const done = (r: unknown): void => void inflight.delete(r);
					page.on('requestfinished', done);
					page.on('requestfailed', done);

					await page.setViewportSize({ width: WIDTHS[WIDTHS.length - 1], height: 900 });
					await page.goto(shot.path);
					// Font scale 2.0 ≈ the user's browser/OS text size doubled: the root font size the rem-based UI scales from.
					if (scale !== 1) await page.addStyleTag({ content: `html { font-size: ${scale * 100}% !important; }` });
					await settle(page, inflight);
					if (shot.prepare) {
						await shot.prepare(page);
						await settle(page, inflight);
					}
					for (const width of WIDTHS) {
						await page.setViewportSize({ width, height: 900 });
						// Let layout and paint catch up with the resize (two frames), or the capture can come out blank.
						await page.evaluate(
							() => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())))
						);
						await page.waitForTimeout(150);
						await page.screenshot({
							path: `screenshots/${scenario}/${shot.name}/${width}-${theme}-${scale.toFixed(1)}.png`,
							fullPage: true,
							animations: 'disabled'
						});
					}
				});
			}
		}
	}
}
