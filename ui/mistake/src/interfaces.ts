/* The payload is deliberately thin. There is no game id, no opponent, no date
 * and no result in here: the server hands the game back only once the position
 * has been answered. See modules/mistake/src/main/MistakeJson.scala. */

export interface MistakePrefs {
  coords: number;
  rookCastle: boolean;
  destination: boolean;
  moveEvent: number;
  highlight: boolean;
  is3d: boolean;
  animation: number;
}

export interface Played {
  uci: Uci;
  san: San;
  eval?: EvalScore;
}

export interface MistakePosition {
  fen: FEN;
  color: Color;
  played: Played;
  prevEval?: EvalScore;
  solution: San[];
}

export interface MistakeData {
  mistake?: MistakePosition;
  nbGames: number;
  nbSolved: number;
  nbQueued?: number;
  pref: MistakePrefs;
}

export type MistakeOpts = MistakeData;

/* Same states as retro, minus 'offTrack' - there is nowhere to browse away to. */
export type Feedback = 'find' | 'eval' | 'win' | 'fail' | 'view';

export type Result = 'win' | 'fail' | 'skip';
