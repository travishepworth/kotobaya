export type ToastKind = 'error' | 'info';
export interface Toast {
	id: number;
	kind: ToastKind;
	message: string;
}

let nextId = 1;

/** Reactive toast list shared by the whole app. */
export const toasts = $state<{ items: Toast[] }>({ items: [] });

export function dismissToast(id: number): void {
	toasts.items = toasts.items.filter((x) => x.id !== id);
}

/** Show a toast; it disappears on its own after `ttlMs` (0 keeps it until dismissed). */
export function pushToast(message: string, kind: ToastKind = 'info', ttlMs = 6000): void {
	// Collapse an identical message already on screen (e.g. repeated network failures).
	if (toasts.items.some((x) => x.message === message && x.kind === kind)) return;
	const id = nextId++;
	toasts.items = [...toasts.items, { id, kind, message }];
	if (ttlMs > 0) setTimeout(() => dismissToast(id), ttlMs);
}
