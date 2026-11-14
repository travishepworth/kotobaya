// Deep link into the notes screen: `/notes?note=<id>[&field=<key>]` opens that note's editor.

export function noteHref(noteId: string, field?: string): string {
	const base = `/notes?note=${encodeURIComponent(noteId)}`;
	return field ? `${base}&field=${encodeURIComponent(field)}` : base;
}

/**
 * The list after a (re)load, keeping the deep-linked note present. The linked note is fetched separately and may
 * not be on the first page; if the page replaced the list without it the open editor would vanish.
 */
export function withPinned<T extends { id: string }>(items: T[], pinned: T | null): T[] {
	if (pinned === null || items.some((n) => n.id === pinned.id)) return items;
	return [pinned, ...items];
}
