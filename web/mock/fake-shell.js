// SHELL-W1 dev tooling: a fake `window.KotobayaShell` so shell mode runs in a desktop browser under KOTOBAYA_MOCK.
// Injected as a classic inline script at the top of <head> by mock/demo-api.ts, so it exists before any SPA script
// (as the real WebMessageListener object does). Never part of the built SPA.
//
// On: `KOTOBAYA_MOCK_SHELL=1`, or per browser with GET /__mock/shell/on (cookie kb-mock-shell=1); off: /__mock/shell/off.
// `/api/v1/*` requests are forwarded to the demo API (answers arrive in random order, like the real bridge);
// `/shell/v1/*` is answered from in-memory device state. Console helpers: `__kbShell.setOnline(false)`,
// `__kbShell.unpair()`, `__kbShell.navigate('/review')`, `__kbShell.event(type, data)`.
/* eslint-disable */
(function (forced) {
	if (window.KotobayaShell) return;
	var cookie = /(?:^|;\s*)kb-mock-shell=(\w+)/.exec(document.cookie);
	if (!forced && !(cookie && cookie[1] === '1')) return;

	var NOW = function () {
		return new Date().toISOString();
	};
	var state = {
		paired: true,
		online: true,
		info: function () {
			return {
				shell_version: '0.0.0-mock',
				shell_version_code: 1,
				local_api_version: 1,
				paired: state.paired,
				auth_required: false,
				server_url: 'http://localhost:3000/api/v1',
				device_id: '01931f3e-0000-7000-8000-0000000000d4',
				device_name: 'Mock shell',
				online: state.online,
				capabilities: ['sync', 'pairing', 'dict_install', 'offline_storage', 'notifications', 'haptics'],
				web_version: '0.0.0-dev',
				web_source: 'bundled'
			};
		},
		sync: function () {
			return {
				phase: state.online ? 'idle' : 'offline',
				paired: state.paired,
				online: state.online,
				pending_ops: 0,
				rejected_ops: 0,
				last_sync_at: NOW(),
				stale: false,
				pull_seq: 0,
				clock_warning: false,
				minting_blocked: false,
				progress: null,
				last_error: null,
				consecutive_failures: 0
			};
		},
		dict: { installed: null, scope: 'top60k', state: 'idle', done_bytes: 0, total_bytes: 0, last_result: null, last_error: null, server_release_id: null, gap_fill: { cached: 0, missing: 0 } },
		storage: { card_audio_bytes: 0, card_audio_count: 0, media_bytes: 0, cover_bytes: 0, dictionary_bytes: 0, total_bytes: 0, prefetch_days: 7, budget_mb: 500, allow_metered: false },
		notifications: { permission: 'not_requested', can_request: true, nudges_enabled: false, nudge_threshold: 20, evening_hour: null },
		push: { state: 'off', has_endpoint: false },
		ankiconnect: { enabled: false, port: 8765, api_key: '', origins: [], status: 'stopped', status_message: null }
	};
	// Operations a device token cannot call or the shell replaces (ops.json `hidden`), by path prefix; enough for dev.
	var HIDDEN = ['/api/v1/auth/login', '/api/v1/auth/logout', '/api/v1/tokens', '/api/v1/devices/pair', '/api/v1/sync/', '/api/v1/events', '/api/v1/import', '/api/v1/ankiconnect'];
	// Server-only reads (ops.json `proxy`), by path prefix: answered 503 shell_offline while offline.
	var PROXY = ['/api/v1/stats', '/api/v1/media', '/api/v1/grammar', '/api/v1/sentences', '/api/v1/parse', '/api/v1/jobs', '/api/v1/confusables'];

	var shell = { onmessage: null, postMessage: receive };
	var post = function (msg) {
		setTimeout(function () {
			if (typeof shell.onmessage === 'function') shell.onmessage({ data: JSON.stringify(msg) });
		}, Math.floor(Math.random() * 40));
	};
	var res = function (id, status, body, extra) {
		var headers = Object.assign({ 'kotobaya-local': '1', 'x-request-id': id }, extra || {});
		if (body !== null) headers['content-type'] = status >= 400 ? 'application/problem+json' : 'application/json';
		post({ v: 1, t: 'res', id: id, status: status, headers: headers, body: body === null ? null : JSON.stringify(body) });
	};
	var problem = function (id, status, code, path) {
		res(id, status, { type: 'urn:kotobaya:problem:' + code, title: code, status: status, code: code, instance: path, request_id: id });
	};
	var event = function (type, data) {
		post({ v: 1, t: 'event', event: { type: type, at: NOW(), data: data || {} } });
	};
	var startsWith = function (path, list) {
		return list.some(function (p) {
			var base = p.replace(/\/$/, '');
			return path === base || path.indexOf(base + '/') === 0;
		});
	};

	function shellOp(req) {
		var body = req.body ? JSON.parse(req.body) : {};
		var key = req.method + ' ' + req.path;
		if (key === 'GET /shell/v1/info') return res(req.id, 200, state.info());
		if (key === 'POST /shell/v1/pair') {
			state.paired = true;
			return res(req.id, 200, state.info());
		}
		if (!state.paired) return problem(req.id, 401, 'unauthenticated', req.path);
		switch (key) {
			case 'POST /shell/v1/unpair':
				state.paired = false;
				return res(req.id, 200, state.info());
			case 'GET /shell/v1/sync':
				return res(req.id, 200, state.sync());
			case 'POST /shell/v1/sync':
				if (!state.online) return problem(req.id, 503, 'shell_offline', req.path);
				return res(req.id, 202, state.sync());
			case 'GET /shell/v1/dict':
				return res(req.id, 200, state.dict);
			case 'POST /shell/v1/dict/install':
				if (!state.online) return problem(req.id, 503, 'shell_offline', req.path);
				return res(req.id, 202, state.dict);
			case 'GET /shell/v1/storage':
				return res(req.id, 200, state.storage);
			case 'PATCH /shell/v1/storage':
				Object.assign(state.storage, body);
				return res(req.id, 200, state.storage);
			case 'GET /shell/v1/notifications':
			case 'POST /shell/v1/notifications/permission':
				return res(req.id, 200, state.notifications);
			case 'PATCH /shell/v1/notifications':
				Object.assign(state.notifications, body);
				return res(req.id, 200, state.notifications);
			case 'GET /shell/v1/push':
				return res(req.id, 200, state.push);
			case 'PATCH /shell/v1/push':
				Object.assign(state.push, body);
				return res(req.id, 200, state.push);
			case 'GET /shell/v1/ankiconnect':
				return res(req.id, 200, state.ankiconnect);
			case 'PATCH /shell/v1/ankiconnect':
				Object.assign(state.ankiconnect, body);
				return res(req.id, 200, state.ankiconnect);
		}
		return problem(req.id, 404, 'not_found', req.path);
	}

	function apiOp(req) {
		if (!state.paired) return problem(req.id, 401, 'unauthenticated', req.path);
		if (startsWith(req.path, HIDDEN)) return problem(req.id, 403, 'insufficient_scope', req.path);
		if (!state.online && startsWith(req.path, PROXY)) return problem(req.id, 503, 'shell_offline', req.path);
		var headers = Object.assign({}, req.headers, { 'kotobaya-mock-shell': 'paired' });
		fetch(req.path + (req.query ? '?' + req.query : ''), { method: req.method, headers: headers, body: req.body })
			.then(function (r) {
				return r.text().then(function (text) {
					var h = {};
					r.headers.forEach(function (v, k) {
						if (k === 'content-type' || k === 'retry-after' || k === 'content-location' || k === 'kotobaya-seq') h[k] = v;
					});
					h['x-request-id'] = req.id;
					post({ v: 1, t: 'res', id: req.id, status: r.status, headers: h, body: text === '' ? null : text });
				});
			})
			.catch(function () {
				problem(req.id, 503, 'shell_offline', req.path);
			});
	}

	var seen = {};
	var ready = false;
	function receive(text) {
		var msg;
		try {
			msg = JSON.parse(text);
		} catch (e) {
			return console.warn('[fake shell] not JSON', text);
		}
		if (!msg || msg.v !== 1) return;
		if (msg.t === 'req') {
			if (seen[msg.id]) return; // duplicate id: dropped (SHELL.md 2.5)
			seen[msg.id] = true;
			return msg.path.indexOf('/shell/v1/') === 0 ? shellOp(msg) : apiOp(msg);
		}
		if (msg.t === 'ready') {
			if (ready) return console.warn('[fake shell] second ready');
			ready = true;
			return event('hello', { high_water_seq: 0, log_epoch: '00000000-0000-0000-0000-000000000000', server_time: NOW() });
		}
		console.debug('[fake shell]', msg);
	}

	window.KotobayaShell = shell;
	window.__kbShell = {
		state: state,
		setOnline: function (online) {
			state.online = !!online;
			event('shell.connectivity', { online: state.online });
		},
		unpair: function () {
			state.paired = false;
		},
		navigate: function (path) {
			post({ v: 1, t: 'navigate', path: path });
		},
		event: event
	};
	console.info('[fake shell] window.KotobayaShell installed (mock). Helpers: window.__kbShell');
})(__KB_FORCED__);
