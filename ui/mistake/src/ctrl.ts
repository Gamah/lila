import { Result as Res } from '@badrap/result';
import type { DrawShape } from '@lichess-org/chessground/draw';
import { uciToMove } from '@lichess-org/chessground/util';
import { Chess, normalizeMove } from 'chessops/chess';
import { makeFen } from 'chessops/fen';
import { makeSanAndPlay, parseSan } from 'chessops/san';
import type { Move, Role } from 'chessops/types';
import { parseSquare, makeUci, opposite } from 'chessops/util';

import { prop, type Prop, type Toggle, toggle } from 'lib';
import { beatsBaseline, bestMoveMargin, CevalCtrl, isCevalReady } from 'lib/ceval';
import type { CevalHandler, ExternalEngineInfo } from 'lib/ceval/types';
import { type WithGround } from 'lib/game/ground';
import { fetchMasterOpening, playedByMasters } from 'lib/game/masterOpening';
import { PromotionCtrl } from 'lib/game/promotion';
import { makeShapesFromUci, playedMoveBrush, playedMoveModifiers } from 'lib/game/shapes';
import { pubsub } from 'lib/pubsub';
import { completeNode } from 'lib/tree/node';
import type { TreeNode } from 'lib/tree/types';
import { toggleZenMode } from 'lib/view/zen';

import type { Feedback, MistakeData, MistakeOpts, MistakePosition, Result } from './interfaces';
import * as xhr from './xhr';

export default class MistakeCtrl implements CevalHandler {
  data: MistakeData;
  ceval: CevalCtrl;
  ground: Prop<CgApi> = prop<CgApi | undefined>(undefined) as Prop<CgApi>;
  promotion: PromotionCtrl;
  feedback: Prop<Feedback> = prop<Feedback>('find');
  flipped: Toggle;
  loading = false;

  /* Revealed only once the position has been answered, and only because the
   * server chose to send it. There is nothing to "reveal" beyond it: knowing
   * where the position came from doesn't tell you anything about it. */
  gameUrl?: string;

  // the position before the mistake, and the move being tried in it
  private root!: TreeNode;
  private tried?: TreeNode;
  private failed = false;
  private recorded = false;
  // the in flight record POST, if any. Dealing again must wait for it.
  private pending: Promise<void> = Promise.resolve();
  // moves masters play in this position, once the explorer has answered
  private openingUcis: Uci[] = [];
  private readonly explorerEndpoint: string;

  constructor(
    opts: MistakeOpts,
    readonly redraw: Redraw,
  ) {
    this.data = opts;
    this.explorerEndpoint = opts.explorerEndpoint;
    this.flipped = toggle(false, redraw);
    this.promotion = new PromotionCtrl(this.withGround, () => this.setGround(), redraw);
    this.ceval = new CevalCtrl({
      redraw,
      variant: { short: 'Std', name: 'Standard', key: 'standard' },
      initialFen: undefined,
      emit: (ev, meta) => {
        const node = meta.path === 'try' ? this.tried : this.root;
        if (!node) return;
        if (!node.ceval || node.ceval.depth <= ev.depth) node.ceval = ev;
        this.checkCeval();
        redraw();
      },
      onUciHover: () => {},
    });
    this.initiate();
    pubsub.on('zen', toggleZenMode);
    $('body').addClass('playing'); // for zen
    $('#zentog').on('click', () => pubsub.emit('zen'));
  }

  get position(): MistakePosition | undefined {
    return this.data.mistake;
  }

  get node(): TreeNode {
    return this.tried ?? this.root;
  }

  get orientation(): Color {
    const color = this.position?.color ?? 'white';
    return this.flipped() ? opposite(color) : color;
  }

  // ---------------------------------------------------------------- lifecycle

  private initiate(): void {
    this.feedback('find');
    this.tried = undefined;
    this.failed = false;
    this.recorded = false;
    this.gameUrl = undefined;
    this.openingUcis = [];
    this.ceval.reset();
    // an empty deck still needs a root, so nothing downstream has to null check
    const fen = this.position?.fen ?? initialFen;
    this.root = completeNode('standard')({ ply: plyOfFen(fen), fen, children: [] });
    this.askExplorer();
  }

  /* Retro asks the opening explorer two things of every position it offers
   * (retroCtrl.ts), and so does the deck: a move masters play counts as having
   * found something better, and a position whose blunder is itself book is not
   * worth asking about at all. It matters more here, where positions come from
   * anywhere in a game rather than being walked in order.
   *
   * The deck is standard games only, so retro's variant gate is implicit. */
  private askExplorer(): void {
    const pos = this.position;
    if (!pos) return;
    const faultPly = this.root.ply + 1; // retro gates on the ply of the mistake
    if (pos.divisionMiddle !== undefined && faultPly >= pos.divisionMiddle) return;
    fetchMasterOpening(this.explorerEndpoint, pos.fen)
      .then(res => {
        if (pos !== this.position) return; // dealt again while we were waiting
        const ucis = playedByMasters(res);
        // book. Deal again rather than record anything: it was never asked.
        if (ucis.includes(pos.played.uci)) this.next();
        else this.openingUcis = ucis;
      })
      .catch(() => {});
  }

  private load = (data: MistakeData): void => {
    this.data = data;
    this.loading = false;
    this.initiate();
    this.setGround();
    this.redraw();
  };

  next = (): void => {
    this.loading = true;
    this.redraw();
    this.pending.then(xhr.next).then(this.load);
  };

  reset = (): void => {
    this.loading = true;
    this.redraw();
    xhr.reset().then(this.load);
  };

  // ------------------------------------------------------------------ solving

  userMove = (orig: Key, dest: Key): void => {
    if (!this.isSolving()) return;
    if (!this.promotion.start(orig, dest, { submit: this.playUserMove })) this.playUserMove(orig, dest);
  };

  private playUserMove = (orig: Key, dest: Key, promotion?: Role): void =>
    this.tryMove({ from: parseSquare(orig)!, to: parseSquare(dest)!, promotion });

  private tryMove(move: Move): void {
    const pos = this.position;
    if (!pos) return;
    const chess = this.rootPosition();
    if (!chess) return;
    const normalized = normalizeMove(chess, move);
    const uci = makeUci(normalized);
    const san = makeSanAndPlay(chess, normalized);
    this.tried = completeNode('standard')({
      ply: this.root.ply + 1,
      fen: makeFen(chess.toSetup()),
      uci,
      san,
      children: [],
      pos: () => Res.ok(chess),
    });
    this.setGround();

    // the move that was actually played in the game. Retro spots this the same
    // way: the node it walks onto already carries a server eval.
    if (uci === pos.played.uci) return this.onFail();
    // mate ends the game, the engine's own move needs no second opinion, and
    // neither does a move masters play here
    if (san.endsWith('#') || san === pos.solution[0] || this.openingUcis.includes(uci)) return this.onWin();

    this.feedback('eval');
    this.startCeval();
    this.redraw();
  }

  private checkCeval = (): void => {
    const pos = this.position;
    if (!pos || this.feedback() !== 'eval' || !this.tried) return;
    if (!isCevalReady(this.tried.ceval)) return;
    /* Measured against the best move - the eval of the position before the
     * mistake - so falling slightly short still passes. Exactly retro's test,
     * exactly retro's slack (lib/ceval/judge.ts). */
    const baseline = pos.prevEval;
    if (!baseline) return this.onWin(); // nothing to measure against, be generous
    if (beatsBaseline(pos.color, this.tried.ceval!, baseline, bestMoveMargin)) this.onWin();
    else this.onFail();
  };

  private onWin(): void {
    this.ceval.reset();
    this.feedback('win');
    this.finish('win');
  }

  private onFail(): void {
    this.ceval.reset();
    this.failed = true;
    this.feedback('fail');
    // back to the position, so another move can be tried
    this.tried = undefined;
    this.setGround();
    this.redraw();
  }

  /* Mimics retro: viewing the solution and skipping both retire the position,
   * exactly as retroCtrl's skip() and viewSolution() both call solveCurrent(). */
  viewSolution = (): void => {
    const pos = this.position;
    if (!pos) return;
    this.ceval.reset();
    this.feedback('view');
    const chess = this.rootPosition();
    const first = chess && pos.solution[0] && parseSan(chess, pos.solution[0]);
    if (chess && first) {
      const san = makeSanAndPlay(chess, first);
      this.tried = completeNode('standard')({
        ply: this.root.ply + 1,
        fen: makeFen(chess.toSetup()),
        uci: makeUci(first),
        san,
        children: [],
        pos: () => Res.ok(chess),
      });
    }
    this.setGround();
    this.finish('fail');
  };

  skip = (): void => {
    this.ceval.reset();
    // record before dealing again: the server resolves "which position" from
    // the deal it last handed out, so the two must not overlap
    this.finish(this.failed ? 'fail' : 'skip');
    this.next();
  };

  private finish(result: Result): Promise<void> {
    if (this.recorded) {
      this.redraw();
      return Promise.resolve();
    }
    this.recorded = true;
    this.redraw();
    this.pending = xhr.record(result).then(res => {
      this.gameUrl = res.game;
      this.redraw();
    });
    return this.pending;
  }

  isSolving = (): boolean => ['find', 'fail'].includes(this.feedback());

  // ------------------------------------------------------------------- board

  withGround: WithGround = f => {
    const g = this.ground();
    return g && f(g);
  };

  setChessground = (cg: CgApi): void => {
    this.ground(cg);
    this.setGround();
  };

  setGround = (): void => this.withGround(g => g.set(this.cgConfig()));

  cgConfig = (): CgConfig => {
    const pos = this.position;
    const node = this.node;
    const solving = this.isSolving();
    return {
      fen: node.fen,
      orientation: this.orientation,
      turnColor: node.ply % 2 === 0 ? 'white' : 'black',
      check: node.check(),
      lastMove: node.uci ? uciToMove(node.uci) : undefined,
      movable: {
        color: solving ? pos?.color : undefined,
        dests: solving ? node.dests() : new Map(),
      },
      premovable: { enabled: false },
      drawable: { autoShapes: this.autoShapes() },
    };
  };

  /* The move that was played, drawn exactly as the analyse board draws it for
   * "learn from your mistakes" - same helper, same brush, same line width.
   * Here it is the whole question rather than an annotation. */
  autoShapes = (): DrawShape[] => {
    const pos = this.position;
    if (!pos || this.tried) return [];
    return makeShapesFromUci(pos.color, pos.played.uci, playedMoveBrush, playedMoveModifiers);
  };

  private rootPosition(): Chess | undefined {
    const res = this.root.pos();
    return res.isOk ? (res.unwrap() as Chess).clone() : undefined;
  }

  // ------------------------------------------------------- CevalHandler

  startCeval = (): void => {
    if (!this.tried) return;
    this.ceval.start('try', [this.root, this.tried], undefined, false);
  };
  clearCeval = (): void => this.ceval.reset();
  cevalEnabled = (): boolean | 'force' => (this.feedback() === 'eval' ? 'force' : false);
  getNode = (): TreeNode => this.node;
  getOrientation = (): Color => this.orientation;
  nextNodeBest = (): string | undefined => undefined;
  get ongoing(): boolean {
    return false;
  }
  playUciList = (): void => {};
  showEvalGauge: Prop<boolean> = prop(false);
  showEvaluation = (): boolean => false;
  threatMode = (): boolean => false;
  toggleThreatMode = (): void => {};
  externalEngines = (): ExternalEngineInfo[] | undefined => undefined;
}

const initialFen: FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

/* Derived from the FEN rather than sent separately. The move number is visible
 * in the FEN anyway, and it is not what the exercise is hiding. */
const plyOfFen = (fen: FEN): number => {
  const parts = fen.split(' ');
  const fullmoves = parseInt(parts[5] ?? '1', 10) || 1;
  return 2 * (fullmoves - 1) + (parts[1] === 'b' ? 1 : 0);
};
