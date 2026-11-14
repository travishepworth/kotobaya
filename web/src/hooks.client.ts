import { installGlobalErrorHandlers, reportException } from './lib/errors';

installGlobalErrorHandlers();

// Errors SvelteKit catches itself (load functions, navigation) never reach window.onerror.
export function handleError({ error }: { error: unknown }): void {
	reportException(error);
}
