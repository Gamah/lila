import { readNdJson } from '@/xhr';

/* The one opening explorer question that isn't about browsing the explorer:
 * "do masters play this?". "Learn from your mistakes" asks it of every position
 * it offers, on the analyse page (ui/analyse/src/retrospect/retroCtrl.ts) and
 * in the /mistakes deck alike, so it lives here rather than in either of them.
 */

export interface MasterMove {
  uci: Uci;
  white: number;
  draws: number;
  black: number;
}

export interface MasterOpening {
  moves: MasterMove[];
}

interface Filters {
  since?: string; // year
  until?: string; // year
}

export const fetchMasterOpening = async (
  endpoint: string,
  fen: FEN,
  filters: Filters = {},
): Promise<MasterOpening> => {
  const url = new URL('./masters', endpoint);
  const params = url.searchParams;
  params.set('variant', 'standard');
  params.set('fen', fen);
  params.set('play', '');
  if (filters.since) params.set('since', filters.since);
  if (filters.until) params.set('until', filters.until);
  params.set('topGames', '0');
  params.set('recentGames', '0');
  params.set('source', 'analysis');

  const res = await fetch(url.href, {
    cache: 'default',
    headers: {}, // avoid default headers for cors
    credentials: 'include',
  });

  let opening: MasterOpening = { moves: [] };
  await readNdJson<MasterOpening>(res, data => {
    opening = data;
  });
  return opening;
};

/* Moves masters actually play here, ie ones with more than a single game
 * behind them. Playing one of these is credited as finding something better. */
export const playedByMasters = (opening: MasterOpening): Uci[] =>
  opening.moves.filter(m => m.white + m.draws + m.black > 1).map(m => m.uci);
