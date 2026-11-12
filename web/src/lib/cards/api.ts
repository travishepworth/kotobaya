import { CardsApi, DecksApi, NoteTypesApi } from '../api/generated';
import { apiConfig } from '../api/client';

export const cardsApi = new CardsApi(apiConfig);
export const decksApi = new DecksApi(apiConfig);
export const noteTypesApi = new NoteTypesApi(apiConfig);
