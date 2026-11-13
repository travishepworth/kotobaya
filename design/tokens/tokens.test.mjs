// Token tests (UI-DS): `node --test design/tokens/` (run by `make check MODULE=web` and `make tokens-check`).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, OUT, drift, generateAll, loadTokens, compose, webCss } from './generate.mjs';
import { camel, contrast, contrastReport, fluid, resolveColors } from './lib.mjs';

const tokens = loadTokens();

test('generated outputs are in sync with tokens.json (web CSS, card.css block, styleguide table, Compose theme)', () => {
	assert.deepEqual(drift(tokens), [], 'run `make tokens` and commit the outputs');
});

test('every declared fg/bg pair meets WCAG AA in light and dark', () => {
	const failures = contrastReport(tokens)
		.filter((r) => r.ratio < r.min)
		.map((r) => `${r.theme}: ${r.fg} on ${r.bg} = ${r.ratio.toFixed(2)} < ${r.min} (${r.kind})`);
	assert.deepEqual(failures, []);
});

test('every semantic foreground/background pairing is declared for the contrast check', () => {
	const names = new Set(Object.keys(tokens.color));
	const declared = new Set();
	for (const r of tokens.contrast) for (const fg of r.fg) for (const bg of r.bg) declared.add(`${fg}|${bg}`);
	const missing = [];
	for (const n of names) {
		// on-X is read on X
		if (n.startsWith('on-') && names.has(n.slice(3)) && !declared.has(`${n}|${n.slice(3)}`)) missing.push(`${n} on ${n.slice(3)}`);
		// X-container carries X (chips, grade buttons) or on-X-container
		if (n.endsWith('-container') && !n.startsWith('on-')) {
			const base = n.slice(0, -'-container'.length);
			const fg = names.has(`on-${n}`) ? `on-${n}` : base;
			if (names.has(fg) && !declared.has(`${fg}|${n}`)) missing.push(`${fg} on ${n}`);
		}
	}
	for (const fg of ['text', 'text-muted']) for (const bg of ['bg', 'surface', 'surface-raised', 'surface-sunken']) {
		if (!declared.has(`${fg}|${bg}`)) missing.push(`${fg} on ${bg}`);
	}
	assert.deepEqual(missing, []);
});

test('every colour has a light and a dark value and every reference resolves', () => {
	const c = resolveColors(tokens);
	assert.deepEqual(Object.keys(c.light), Object.keys(tokens.color));
	assert.deepEqual(Object.keys(c.dark), Object.keys(tokens.color));
	for (const n of Object.keys(tokens.color)) assert.match(c.dark[n], /^#[0-9A-F]{6}([0-9A-F]{2})?$/, n);
});

test('the resolver rejects missing themes, unknown keys and cycles', () => {
	const base = { palette: { a: '#000000' }, color: {} };
	assert.throws(() => resolveColors({ ...base, color: { x: { light: 'a' } } }), /no dark value/);
	assert.throws(() => resolveColors({ ...base, color: { x: { light: 'nope', dark: 'a' } } }), /unknown palette key/);
	assert.throws(() => resolveColors({ ...base, color: { x: { light: '@y', dark: 'a' }, y: { light: '@x', dark: 'a' } } }), /cycle/);
	assert.throws(() => resolveColors({ ...base, color: { x: { light: '#12', dark: 'a' } } }), /not a #rrggbb/);
	assert.deepEqual(resolveColors({ ...base, color: { x: { light: '@y', dark: '#ffffff' }, y: { light: 'a', dark: 'a' } } }).light.x, '#000000');
});

test('contrast() matches the WCAG reference values', () => {
	assert.equal(contrast('#000000', '#FFFFFF'), 21);
	assert.equal(contrast('#FFFFFF', '#FFFFFF'), 1);
	assert.ok(Math.abs(contrast('#767676', '#FFFFFF') - 4.54) < 0.01);
});

test('fluid() hits its endpoints at the fluid range and stays rem-based (scales with the user font size)', () => {
	const css = fluid(1, 2, 360, 1280);
	const m = /^clamp\(1rem, (-?[\d.]+)rem \+ ([\d.]+)vw, 2rem\)$/.exec(css);
	assert.ok(m, css);
	const at = (/** @type {number} */ px) => Number(m[1]) * 16 + (Number(m[2]) / 100) * px;
	assert.ok(Math.abs(at(360) - 16) < 0.01);
	assert.ok(Math.abs(at(1280) - 32) < 0.01);
	assert.equal(fluid(1, 1, 360, 1280), '1rem');
});

test('accessibility floors: motion < 250 ms, touch targets, smallest text, furigana', () => {
	for (const [k, ms] of Object.entries(tokens.motion.duration)) assert.ok(ms < 250, `duration ${k} = ${ms}`);
	assert.ok(tokens.size['touch-min'].px >= 44 && tokens.size['touch-min'].dp >= 48);
	for (const k of ['control', 'grade-button']) assert.ok(tokens.size[k].px >= 44 && tokens.size[k].dp >= 48, k);
	for (const [k, r] of Object.entries(tokens.type.roles)) {
		assert.ok(r.min * 16 >= 12 && r.sp >= 12, `type ${k} below 12 px/sp`);
		assert.ok(r.min <= r.max, `type ${k}: min > max`);
	}
	assert.ok(tokens.type.ruby.minRem * 16 >= 10 && tokens.type.ruby.minSp >= 10);
	assert.match(webCss(tokens), /prefers-reduced-motion: reduce\) \{\n\t:root \{\n\t\t--kb-distance-sm: 0px;/);
});

test('card.css: every --kb-card-* variable a rule uses is generated, light and dark', () => {
	const css = generateAll(tokens)[OUT.cardCss];
	const used = new Set([...css.matchAll(/var\((--kb-card-[a-z-]+)\)/g)].map((m) => m[1]));
	const defined = new Set([...css.matchAll(/(--kb-card-[a-z-]+):/g)].map((m) => m[1]));
	assert.deepEqual([...used].filter((v) => !defined.has(v)), []);
	assert.match(css, /prefers-color-scheme: dark/);
	assert.match(css, /\[data-theme="dark"\]/);
	assert.match(css, /--kb-font: "Noto Sans JP Variable", "Noto Sans JP"/);
});

test('web CSS: themes are switchable per subtree and keep the legacy POC tokens unchanged', () => {
	const css = readFileSync(join(ROOT, OUT.webCss), 'utf8');
	assert.match(css, /^\[data-theme='dark'\] \{$/m);
	assert.match(css, /^\[data-theme='light'\] \{$/m);
	const legacy = readFileSync(join(ROOT, 'design/tokens/web-legacy.css'), 'utf8');
	assert.ok(css.endsWith(legacy.trimEnd() + '\n'), 'legacy block must be carried verbatim (screens are not restyled)');
	for (const n of Object.keys(tokens.color)) assert.ok(css.includes(`--kb-color-${n}:`), n);
});

test('Compose output: unique, valid identifiers and both colour sets complete', () => {
	const kt = compose(tokens);
	const ids = Object.keys(tokens.color).map(camel);
	assert.equal(new Set(ids).size, ids.length);
	for (const id of ids) assert.match(id, /^[a-z][A-Za-z0-9_]*$/);
	for (const set of ['KbColorsLight', 'KbColorsDark']) {
		const start = kt.indexOf(`val ${set}`);
		const block = kt.slice(start, kt.indexOf('\n)\n', start));
		for (const id of ids) assert.ok(block.includes(`    ${id} = Color(0x`), `${set}.${id}`);
	}
	assert.match(kt, /^package kotobaya\.android\.ui\.theme$/m);
});
