// The KU Volleyball mark, in one place: a white volleyball on KU blue.
//
// Two generators draw from this — make-icons.mjs for the Android launcher
// (vector drawables plus legacy webp) and make-web-icons.mjs for the
// dashboard's home-screen icons. The geometry lived in the first of those and
// was about to be copied into the second, which is how two icons of the same
// app start to differ by a stroke width nobody notices.

export const KU_BLUE = '#0051BA';
export const WHITE = '#FFFFFF';

// Canvas is 108x108 to match Android's adaptive-icon viewport; the web sizes
// scale it. C is the centre, R the ball radius, SW the seam stroke.
export const C = 54, R = 34, SW = 4, SPAN = 150, RF = 1.25, ROT = 30;

// Endpoints pull in by half the stroke so round caps land exactly on the rim
// instead of poking out as nubs.
const RIM = (R - SW / 2) / R;
const rad = (d) => (d * Math.PI) / 180;
const pt = (a) => [
  (C + R * RIM * Math.cos(rad(a))).toFixed(2),
  (C + R * RIM * Math.sin(rad(a))).toFixed(2),
];
function seamPath(a1) {
  const [x1, y1] = pt(a1), [x2, y2] = pt(a1 + SPAN), r = (R * RF).toFixed(2);
  return `M${x1},${y1} A${r},${r} 0 0,0 ${x2},${y2}`;
}
export const SEAMS = [0, 120, 240].map((k) => seamPath(90 + ROT + k));

// Circle as two arcs, for VectorDrawable fills/clips.
export const circlePath = (r) =>
  `M${C},${C - r} A${r},${r} 0 1,0 ${C},${C + r} A${r},${r} 0 1,0 ${C},${C - r}Z`;

/**
 * The mark as an SVG document.
 *
 * Shapes:
 *   square    rounded tile — Android's legacy launcher raster
 *   round     circular tile — Android's round launcher raster
 *   full      plain square, no rounding — iOS masks apple-touch-icon itself,
 *             so any rounding we add shows up as a corner artefact
 *   maskable  plain square with the ball inside the inner 80%, for a
 *             manifest maskable icon that a launcher may crop to any outline
 */
export function svgIcon(shape, size = 108) {
  // A maskable icon can be cropped to a circle inscribed in the middle 80%,
  // so the ball shrinks to stay clear of whatever the launcher trims.
  const scale = shape === 'maskable' ? 0.72 : 1;
  const r = R * scale;
  const clip = shape === 'round'
    ? `<circle cx="54" cy="54" r="54"/>`
    : `<rect x="0" y="0" width="108" height="108" rx="${shape === 'square' ? 20 : 0}" ry="${shape === 'square' ? 20 : 0}"/>`;
  // Scaling the group would scale the seam stroke with it, so the stroke is
  // divided back out and the seams stay the same weight at every size.
  const seamPaths = (sw) => SEAMS.map((d) =>
    `<path d="${d}" fill="none" stroke="${KU_BLUE}" stroke-width="${sw}" stroke-linecap="round"/>`).join('');
  const seams = scale === 1
    ? seamPaths(SW)
    : `<g transform="translate(${C} ${C}) scale(${scale}) translate(${-C} ${-C})">`
      + `${seamPaths(SW / scale)}</g>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 108 108">
    <defs><clipPath id="m">${clip}</clipPath></defs>
    <g clip-path="url(#m)"><rect width="108" height="108" fill="${KU_BLUE}"/></g>
    <circle cx="54" cy="54" r="${r}" fill="${WHITE}"/>${seams}</svg>`;
}
