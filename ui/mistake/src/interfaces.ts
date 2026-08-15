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
}

export interface MistakePosition {
  fen: FEN;
  color: Color;
  played: Played;
  // eval of the position before the mistake, ie of the best move: the baseline
  prevEval?: EvalScore;
  solution: San[];
  // ply the opening ends at, if the game had one
  divisionMiddle?: Ply;
}

/* No counts in here. How much of the deck is left and how you have been doing
 * are the landing page's job, and it can say them without spending a position
 * to find out. */
export interface MistakeData {
  mistake?: MistakePosition;
  pref: MistakePrefs;
}

/* The page carries two things the later positions don't: where to ask the
 * opening explorer, and which window we are drawing from. Neither changes
 * while the board is open. */
export interface MistakeOpts extends MistakeData {
  explorerEndpoint: string;
  window: string;
}

/* Same states as retro, minus 'offTrack' - there is nowhere to browse away to. */
export type Feedback = 'find' | 'eval' | 'win' | 'fail' | 'view';

export type Result = 'win' | 'fail' | 'skip';
