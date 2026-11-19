import { CardsApi, JobsApi, NotesApi, NoteTypesApi, PresetsApi, ReviewApi, SettingsApi, WordsApi } from '../api/generated';
import { apiConfig, quietApiConfig } from '../api/client';

export const reviewApi = new ReviewApi(apiConfig);
export const cardsApi = new CardsApi(apiConfig);
export const notesApi = new NotesApi(apiConfig);
export const wordsApi = new WordsApi(apiConfig);
export const presetsApi = new PresetsApi(apiConfig);
export const noteTypesApi = new NoteTypesApi(apiConfig);
export const settingsApi = new SettingsApi(apiConfig);
// A5: a sound that cannot be had is ignored, so the speech lookup raises no error toast.
export const speechApi = new JobsApi(quietApiConfig);
