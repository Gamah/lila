import { licon } from 'lib/licon';
import { bind, hl, icon, type VNode } from 'lib/view';

import type MistakeCtrl from '../ctrl';

/* Deliberately the same markup, classes and wording as the retro box on the
 * analyse page (ui/analyse/src/retrospect/retroView.ts). Two views of the same
 * exercise should not look like two different features. */

const skipOrViewSolution = (ctrl: MistakeCtrl): VNode =>
  hl('div.choices', [
    hl('a', { hook: bind('click', ctrl.viewSolution, ctrl.redraw) }, i18n.site.viewTheSolution),
    hl('a', { hook: bind('click', ctrl.skip) }, i18n.site.skipThisMove),
  ]);

const jumpToNext = (ctrl: MistakeCtrl): VNode =>
  hl('a.half.continue', { hook: bind('click', ctrl.next) }, [icon(licon.PlayTriangle)(), i18n.site.next]);

/* The only thing the position gives up once it has been answered. There is no
 * reveal beyond this: knowing which game it came from tells you nothing you
 * needed while you were solving. */
const gameLink = (ctrl: MistakeCtrl): VNode | false =>
  !!ctrl.gameUrl &&
  hl(
    'a.mistake__game-link',
    { attrs: { href: ctrl.gameUrl, target: '_blank', rel: 'noopener' } },
    i18n.site.seeTheGameItCameFrom,
  );

const played = (ctrl: MistakeCtrl): VNode[] => [
  hl('div.no-square', hl('piece.king.' + ctrl.position!.color)),
  hl('div.instruction', [
    hl('strong', i18n.site.xWasPlayed.asArray(hl('move', ctrl.position!.played.san))),
    hl(
      'em',
      i18n.site[ctrl.position!.color === 'white' ? 'findBetterMoveForWhite' : 'findBetterMoveForBlack'],
    ),
    skipOrViewSolution(ctrl),
  ]),
];

// same shape as retroView's, and the same depth bounds
const minDepth = 8;
const maxDepth = 18;

const renderEvalProgress = (ctrl: MistakeCtrl): VNode => {
  const depth = ctrl.node.ceval?.depth;
  return hl(
    'div.progress',
    hl('div', {
      attrs: {
        style: `width: ${depth ? (100 * Math.max(0, depth - minDepth)) / (maxDepth - minDepth) + '%' : 0}`,
      },
    }),
  );
};

const feedback = {
  find: (ctrl: MistakeCtrl): VNode[] => [hl('div.player', played(ctrl))],

  eval: (ctrl: MistakeCtrl): VNode[] => [
    hl(
      'div.half.top',
      hl(
        'div.player.center',
        hl('div.instruction', [hl('strong', i18n.site.evaluatingYourMove), renderEvalProgress(ctrl)]),
      ),
    ),
  ],

  fail: (ctrl: MistakeCtrl): VNode[] => [
    hl('div.player', [
      hl('div.icon', '✗'),
      hl('div.instruction', [
        hl('strong', i18n.site.youCanDoBetter),
        hl(
          'em',
          i18n.site[ctrl.position!.color === 'white' ? 'tryAnotherMoveForWhite' : 'tryAnotherMoveForBlack'],
        ),
        skipOrViewSolution(ctrl),
      ]),
    ]),
  ],

  win: (ctrl: MistakeCtrl): VNode[] => [
    hl(
      'div.half.top',
      hl('div.player', [
        hl('div.icon', '✓'),
        hl('div.instruction', [hl('strong', i18n.study.goodMove), gameLink(ctrl)]),
      ]),
    ),
    jumpToNext(ctrl),
  ],

  view: (ctrl: MistakeCtrl): VNode[] => [
    hl(
      'div.half.top',
      hl('div.player', [
        hl('div.icon', '✓'),
        hl('div.instruction', [
          hl('strong', i18n.site.solution),
          hl('em', i18n.site.bestWasX.asArray(hl('strong', ctrl.position!.solution[0] ?? '?'))),
          gameLink(ctrl),
        ]),
      ]),
    ),
    jumpToNext(ctrl),
  ],
};

/* Nothing left in the window we draw from. The two things worth doing about
 * that - drawing from further back, and putting every position back - both live on
 * the landing page, so this offers the reset here and the window there. */
const empty = (ctrl: MistakeCtrl): VNode[] => [
  hl('div.player', [
    hl('div.no-square', hl('piece.king.white')),
    hl('div.instruction', [
      hl('em', i18n.site.queueIsEmpty),
      hl('div.choices.end', [
        hl('a', { hook: bind('click', ctrl.reset) }, i18n.site.doItAgain),
        hl('a', { attrs: { href: ctrl.homeUrl() } }, 'Older games'),
      ]),
    ]),
  ]),
];

export default function (ctrl: MistakeCtrl): VNode {
  const fb = ctrl.feedback();
  return hl('div.retro-box.mistake-box.training-box.sub-box', [
    hl('div.feedback.' + fb, ctrl.position ? feedback[fb](ctrl) : empty(ctrl)),
  ]);
}
