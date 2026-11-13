// Pure helpers shared by the token generator and its tests (UI-DS). No dependencies, no IO.

/** @param {string} hex `#rrggbb` or `#rrggbbaa` @returns {[number, number, number, number]} r, g, b in 0..255 and alpha in 0..1 */
export function parseHex(hex) {
	const m = /^#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$/.exec(hex);
	if (!m) throw new Error(`not a #rrggbb[aa] colour: ${hex}`);
	const n = parseInt(m[1], 16);
	const a = m[2] === undefined ? 1 : parseInt(m[2], 16) / 255;
	return [(n >> 16) & 255, (n >> 8) & 255, n & 255, a];
}

/** WCAG 2.x relative luminance of an opaque colour. @param {string} hex */
export function luminance(hex) {
	const [r, g, b, a] = parseHex(hex);
	if (a !== 1) throw new Error(`luminance of a translucent colour is undefined: ${hex}`);
	const lin = (/** @type {number} */ c) => {
		const s = c / 255;
		return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
	};
	return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** WCAG 2.x contrast ratio (1..21). @param {string} fg @param {string} bg */
export function contrast(fg, bg) {
	const a = luminance(fg);
	const b = luminance(bg);
	return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

export const THEMES = /** @type {const} */ (['light', 'dark']);

/**
 * Resolves every semantic colour of the token file to a hex value per theme.
 * A value is a palette key, another semantic colour's name prefixed with `@`, or a literal `#hex`.
 * @param {any} tokens
 * @returns {Record<'light' | 'dark', Record<string, string>>}
 */
export function resolveColors(tokens) {
	/** @type {Record<'light' | 'dark', Record<string, string>>} */
	const out = { light: {}, dark: {} };
	for (const theme of THEMES) {
		/** @param {string} name @param {string[]} seen @returns {string} */
		const resolve = (name, seen) => {
			if (seen.includes(name)) throw new Error(`colour reference cycle: ${[...seen, name].join(' -> ')}`);
			const def = tokens.color[name];
			if (!def) throw new Error(`unknown colour token: ${name}`);
			const v = def[theme];
			if (typeof v !== 'string') throw new Error(`colour ${name} has no ${theme} value`);
			return value(v, [...seen, name]);
		};
		/** @param {string} v @param {string[]} seen @returns {string} */
		const value = (v, seen) => {
			if (v.startsWith('#')) {
				parseHex(v);
				return v.toUpperCase();
			}
			if (v.startsWith('@')) return resolve(v.slice(1), seen);
			const p = tokens.palette[v];
			if (!p) throw new Error(`unknown palette key "${v}" (in ${seen.join(' -> ')})`);
			parseHex(p);
			return p.toUpperCase();
		};
		for (const name of Object.keys(tokens.color)) out[theme][name] = resolve(name, []);
	}
	return out;
}

/**
 * Every fg/bg pair the token file declares, with its ratio per theme.
 * @param {any} tokens
 * @returns {{ theme: string, fg: string, bg: string, ratio: number, min: number, kind: string }[]}
 */
export function contrastReport(tokens) {
	const colors = resolveColors(tokens);
	const rows = [];
	for (const rule of tokens.contrast) {
		for (const theme of THEMES) {
			for (const fg of rule.fg) {
				for (const bg of rule.bg) {
					const ratio = contrast(colors[theme][fg], colors[theme][bg]);
					rows.push({ theme, fg, bg, ratio, min: rule.min, kind: rule.kind });
				}
			}
		}
	}
	return rows;
}

/** `clamp()` that grows linearly from `minRem` at `fromPx` viewport width to `maxRem` at `toPx`. */
export function fluid(/** @type {number} */ minRem, /** @type {number} */ maxRem, /** @type {number} */ fromPx, /** @type {number} */ toPx) {
	if (minRem === maxRem) return `${fmt(minRem)}rem`;
	const slope = ((maxRem - minRem) * 16) / (toPx - fromPx); // px per px of viewport
	const interceptRem = minRem - (slope * fromPx) / 16;
	return `clamp(${fmt(minRem)}rem, ${fmt(interceptRem)}rem + ${fmt(slope * 100)}vw, ${fmt(maxRem)}rem)`;
}

/** Shortest decimal for CSS / Kotlin (max 4 places). @param {number} n */
export function fmt(n) {
	const r = Math.round(n * 10000) / 10000;
	return Object.is(r, -0) ? '0' : String(r);
}

/** kebab-case → camelCase (Kotlin identifiers). Digits after a dash keep a separating underscore when needed. */
export function camel(/** @type {string} */ s) {
	const c = s.replace(/[-.]([a-z0-9])/g, (_, ch) => (/[0-9]/.test(ch) ? `_${ch}` : ch.toUpperCase()));
	return /^[0-9]/.test(c) ? `x${c}` : c;
}
