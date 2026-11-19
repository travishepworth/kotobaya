declare global {
	namespace App {
		/** History-entry state of the review route: ties the entry to the kept session (ADR-048). */
		interface PageState {
			reviewKey?: string;
		}
	}
}
export {};
