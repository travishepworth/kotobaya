// Bridge messages between the SPA and the Android shell: contracts/api/shell/bridge.schema.json, SHELL.md 2.2-2.3.
// Pure: types and validators only. Patterns are copied from the schema; the contract example files are the test.

/** Lowest LOCAL_API_VERSION this bundle works with (SHELL.md 7.2); equals MIN_LOCAL_API of scripts/shell-manifest.ts. */
export const MIN_LOCAL_API = 2;

export type HeaderMap = Record<string, string>;
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface ReqMessage {
	v: 1;
	t: 'req';
	id: string;
	method: HttpMethod;
	path: string;
	query: string;
	headers: HeaderMap;
	body: string | null;
}
export interface ResMessage {
	v: 1;
	t: 'res';
	id: string;
	status: number;
	headers: HeaderMap;
	body: string | null;
}
export interface BridgeEvent {
	type: string;
	at: string;
	data: Record<string, unknown>;
}
export interface EventMessage {
	v: 1;
	t: 'event';
	event: BridgeEvent;
}
export interface NavigateMessage {
	v: 1;
	t: 'navigate';
	path: string;
}
export interface BackMessage {
	v: 1;
	t: 'back';
	id: string;
}
export interface ReadyMessage {
	v: 1;
	t: 'ready';
	web_version: string;
	min_local_api: number;
}
export interface BackResultMessage {
	v: 1;
	t: 'back.result';
	id: string;
	handled: boolean;
}
export interface BackStateMessage {
	v: 1;
	t: 'back.state';
	can_handle: boolean;
}
export interface HapticMessage {
	v: 1;
	t: 'haptic';
	kind: 'tick' | 'confirm' | 'reject';
}
export interface ThemeMessage {
	v: 1;
	t: 'theme';
	dark: boolean;
	surface: string;
}

export type NativeMessage = ResMessage | EventMessage | NavigateMessage | BackMessage;
export type WebMessage = ReqMessage | ReadyMessage | BackResultMessage | BackStateMessage | HapticMessage | ThemeMessage;

/** `ok`: a valid message for the SPA; `unknown`: valid but of a type it does not know (ignored); `invalid`: dropped. */
export type ParseResult = { kind: 'ok'; msg: NativeMessage } | { kind: 'unknown' } | { kind: 'invalid' };

type Obj = Record<string, unknown>;

const TYPE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$/;
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HEADER_NAME = /^[a-z0-9!#$%&'*+.^_`|~-]+$/;
// eslint-disable-next-line no-control-regex -- the schema forbids NUL in header values
const HEADER_VALUE = /^[^\r\n\u0000]*$/;
const REQ_PATH = /^\/(api|shell)\/v1\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*$/;
const DOT_SEGMENT = /(^|\/)\.{1,2}(\/|$)/;
const ENCODED_FORBIDDEN = /%(2[eEfF]|5[cC]|00)/;
const QUERY = /^([A-Za-z0-9._~!$&'()*+,;=:@%/-][A-Za-z0-9._~!$&'()*+,;=:@%/?-]*)?$/;
const AT = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/;
const NAV_PATH = /^\/[A-Za-z0-9\-._~/?&=%+]*$/;
const WEB_VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]*$/;
const SURFACE = /^#[0-9a-fA-F]{6}$/;
const METHODS: readonly string[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const HAPTICS: readonly string[] = ['tick', 'confirm', 'reject'];
/** Every `t` the root schema defines; any other well-formed `t` is a future type (ignored, not invalid). */
const KNOWN_TYPES: readonly string[] = ['req', 'res', 'ready', 'event', 'navigate', 'back', 'back.result', 'back.state', 'haptic', 'theme'];
const NATIVE_TYPES: readonly string[] = ['res', 'event', 'navigate', 'back'];
const WEB_TYPES: readonly string[] = ['req', 'ready', 'back.result', 'back.state', 'haptic', 'theme'];
const MAX_BODY_CHARS = 4194304;

const isObj = (x: unknown): x is Obj => typeof x === 'object' && x !== null && !Array.isArray(x);
const isStr = (x: unknown, max = Infinity, re?: RegExp): x is string =>
	typeof x === 'string' && x.length <= max && (re === undefined || re.test(x));
const isInt = (x: unknown): x is number => typeof x === 'number' && Number.isInteger(x);
const isBool = (x: unknown): x is boolean => typeof x === 'boolean';
const isId = (x: unknown): x is string => isStr(x, 36, ID);
const isBody = (x: unknown): x is string | null => x === null || typeof x === 'string';

export function isHeaders(x: unknown): x is HeaderMap {
	if (!isObj(x)) return false;
	const entries = Object.entries(x);
	if (entries.length > 64) return false;
	return entries.every(([k, v]) => isStr(k, 64, HEADER_NAME) && isStr(v, 8192, HEADER_VALUE));
}

export function isReqPath(p: unknown): p is string {
	return isStr(p, 2048, REQ_PATH) && !p.includes('//') && !DOT_SEGMENT.test(p) && !ENCODED_FORBIDDEN.test(p);
}

export function isReqQuery(q: unknown): q is string {
	return isStr(q, 8192, QUERY);
}

/** The envelope (`v`, `t`) of the root schema. */
function envelope(m: unknown): m is Obj & { v: 1; t: string } {
	return isObj(m) && m.v === 1 && isStr(m.t, 40, TYPE);
}

/** Field checks of one known message type (the `$defs` entry for it). */
function validBody(m: Obj): boolean {
	switch (m.t) {
		case 'req':
			return (
				isId(m.id) &&
				typeof m.method === 'string' &&
				METHODS.includes(m.method) &&
				isReqPath(m.path) &&
				isReqQuery(m.query) &&
				isHeaders(m.headers) &&
				'body' in m &&
				(m.body === null || isStr(m.body, MAX_BODY_CHARS))
			);
		case 'res':
			return isId(m.id) && isInt(m.status) && m.status >= 200 && m.status <= 599 && isHeaders(m.headers) && 'body' in m && isBody(m.body);
		case 'ready':
			return isStr(m.web_version, 64, WEB_VERSION) && m.web_version.length >= 1 && isInt(m.min_local_api) && m.min_local_api >= 1;
		case 'event': {
			const e = m.event;
			return isObj(e) && isStr(e.type, 40, TYPE) && isStr(e.at, Infinity, AT) && isObj(e.data);
		}
		case 'navigate':
			return isStr(m.path, 2048, NAV_PATH) && !m.path.includes('//');
		case 'back':
			return isId(m.id);
		case 'back.result':
			return isId(m.id) && isBool(m.handled);
		case 'back.state':
			return isBool(m.can_handle);
		case 'haptic':
			return typeof m.kind === 'string' && HAPTICS.includes(m.kind);
		case 'theme':
			return isBool(m.dark) && isStr(m.surface, 7, SURFACE);
		default:
			return true;
	}
}

/** Parse one message received from native (`event.data`). Never throws. */
export function parseNativeMessage(data: unknown): ParseResult {
	if (typeof data !== 'string') return { kind: 'invalid' };
	let m: unknown;
	try {
		m = JSON.parse(data);
	} catch {
		return { kind: 'invalid' };
	}
	if (!envelope(m)) return { kind: 'invalid' };
	if (!KNOWN_TYPES.includes(m.t)) return { kind: 'unknown' };
	// A known type native may not send (a `req` echoed back, a `ready`…) violates NativeToWeb: invalid.
	if (!NATIVE_TYPES.includes(m.t) || !validBody(m)) return { kind: 'invalid' };
	return { kind: 'ok', msg: m as unknown as NativeMessage };
}

/** Whether `m` is a message the SPA may send (`#/$defs/WebToNative`). */
export function validateWebMessage(m: unknown): m is WebMessage {
	return envelope(m) && WEB_TYPES.includes(m.t) && validBody(m);
}
