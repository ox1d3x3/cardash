"""
CarDash rear-view art from the owner's straight-on design (src.jpg: white car left, black car right).
Turns the sketchy illustration into a clean, glossy, semi-realistic render:
  cut-out -> logo marks removed -> perfectly symmetric (left half mirrored) -> denoised ->
  ink lines softened -> tone (brighter, punchier) -> lamps rebuilt (clean off / on versions) ->
  procedural clearcoat reflections on their own layer.
Outputs (viewport 300 x 220 units, ground y = 214, PX px per unit):
  rear2_<car>_base   car, lamps off, no reflections
  rear2_<car>_gloss  clearcoat reflections (white, alpha) - drawn over the paint, never tinted
  rear2_<car>_lit    lamps on (alpha)
  rear2_white_paint  greyscale paint (alpha) for MULTIPLY tinting to other colours
and meta.json (plate quad, lamp ends, lamp band) for CarArt.java.
"""
import json, sys
import numpy as np, cv2
from PIL import Image
from scipy import ndimage as nd

PX = 2.5
VW, VH, GROUND = 300, 220, 214
SRC = np.asarray(Image.open('src.jpg').convert('RGB')).astype(np.float32)
CARS = {'white': dict(x0=96, x1=572, axis=332.5), 'black': dict(x0=666, x1=1160, axis=918.0)}
Y0, Y1 = 300, 660            # crop rows (source px)
S = 2                         # processing scale
OUT = 'out'

def lumf(a): return a @ np.array([.299, .587, .114], np.float32)
def smooth01(x, a, b): t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)

meta = {}
for name, c in CARS.items():
    a = SRC[Y0:Y1, c['x0']:c['x1']].copy()
    fg = np.load(f'fg_{name}.npy')[Y0:Y1, c['x0']:c['x1']]
    ax = c['axis'] - c['x0']                       # axis in crop px
    # ---------------------------------------------------------------- 1. remove the bumper mark
    m = np.zeros(fg.shape, np.uint8)
    cx = int(round(ax))
    m[516 - Y0:531 - Y0, cx - 12:cx + 13] = 255
    a = cv2.inpaint(np.clip(a, 0, 255).astype(np.uint8), m, 4, cv2.INPAINT_TELEA).astype(np.float32)
    # ---------------------------------------------------------------- 2. upscale x2
    h, w = fg.shape
    a = cv2.resize(a, (w * S, h * S), interpolation=cv2.INTER_LANCZOS4)
    al = cv2.resize(fg.astype(np.float32), (w * S, h * S), interpolation=cv2.INTER_LINEAR)
    al = cv2.GaussianBlur(al, (0, 0), 0.8)
    ax2 = ax * S
    # ---------------------------------------------------------------- 3. symmetric: mirror the left half
    H2, W2 = al.shape
    xs = np.arange(W2, dtype=np.float32)
    mapx = np.where(xs < ax2, xs, 2 * ax2 - xs).astype(np.float32)
    mapx = np.repeat(mapx[None, :], H2, 0)
    mapy = np.repeat(np.arange(H2, dtype=np.float32)[:, None], W2, 1)
    a = cv2.remap(a, mapx, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    al = cv2.remap(al, mapx, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    # ---------------------------------------------------------------- 4. denoise (keeps edges)
    u8 = np.clip(a, 0, 255).astype(np.uint8)
    u8 = cv2.bilateralFilter(u8, 9, 28, 5)
    u8 = cv2.bilateralFilter(u8, 9, 22, 4)
    a = u8.astype(np.float32)
    L = lumf(a)
    # ---------------------------------------------------------------- 5. soften the ink lines
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    closed = cv2.morphologyEx(a, cv2.MORPH_CLOSE, k)
    bh = lumf(closed) - L                           # how much darker than the surroundings
    line = smooth01(bh, 14, 50) * smooth01(lumf(closed), 70, 120)   # only lines drawn on lighter surfaces
    edge = (al > 0.5) & (cv2.erode((al > 0.5).astype(np.uint8), np.ones((9, 9), np.uint8)) == 0)
    keep = np.where(edge, 0.55, 0.62)                               # fraction of the line we remove
    a = a + (closed - a) * (line * keep)[..., None]
    L = lumf(a)

    # rows of the key features (source px -> processing px)
    R = lambda y: (y - Y0) * S
    yy, xx = np.mgrid[0:H2, 0:W2].astype(np.float32)
    dxa = np.abs(xx - ax2)                                          # distance from the centre line
    sat = a.max(2) - a.min(2)
    red = a[..., 0] - np.maximum(a[..., 1], a[..., 2])

    # ---------------------------------------------------------------- 6. lamps: mask, then clean off / on renders
    lampzone = (yy > R(418)) & (yy < R(462))
    lamp = smooth01(red, 10, 34) * lampzone * (al > 0.5)
    lamp = cv2.morphologyEx(lamp.astype(np.float32), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    lamp = cv2.GaussianBlur(lamp, (0, 0), 0.9)
    lamp = np.clip(lamp * 1.2, 0, 1)
    # bar centreline per column: weighted mean row of the lamp
    wsum = lamp.sum(0) + 1e-6
    cyc = (lamp * yy).sum(0) / wsum
    valid = lamp.sum(0) > 2
    cyc_f = np.interp(xs, xs[valid], cyc[valid]) if valid.any() else np.full(W2, R(440))
    cyc_f = nd.gaussian_filter1d(cyc_f, 6)
    half = np.maximum(1.5, lamp.sum(0) / 2)                          # half thickness per column
    half = nd.gaussian_filter1d(half, 4)
    v = (yy - cyc_f[None, :]) / half[None, :]                        # -1 top .. +1 bottom inside the lamp
    end = smooth01(dxa, (c['axis'] - c['x0']) * 0 + 70 * S, 125 * S)  # 0 middle .. 1 outer clusters
    # OFF: deep smoked ruby, darker at the bottom, glossy top edge, faint inner graphic
    off = np.zeros_like(a)
    off[..., 0] = 92 - 34 * np.clip(v, -1, 1) * 0.5 - 18 * (v > 0.2)
    off[..., 1] = 14 + 4 * (v < -0.55)
    off[..., 2] = 22 + 6 * (v < -0.55)
    gl_top = smooth01(-v, 0.45, 0.9)
    off += gl_top[..., None] * np.array([60, 48, 52], np.float32)
    guide = np.exp(-((v + 0.05) / 0.22) ** 2) * (0.35 + 0.65 * end)     # LED light guide (off: faint)
    off[..., 0] += 26 * guide
    # ON: hot red, pink-white light guide, brighter clusters
    on = np.zeros_like(a)
    on[..., 0] = 250
    on[..., 1] = 30 + 18 * end
    on[..., 2] = 40 + 14 * end
    core = np.exp(-((v + 0.05) / 0.28) ** 2) * (0.75 + 0.25 * end)
    on = on + core[..., None] * (np.array([255, 214, 218], np.float32) - on) * 0.85
    on[..., 0] *= (0.82 + 0.18 * smooth01(1 - np.abs(v), 0, 0.6))
    # small bumper reflectors (red strips by the plate): keep shape, render off / on
    refl = smooth01(red, 8, 30) * (yy > R(530)) * (yy < R(552)) * (al > 0.5)
    refl = cv2.GaussianBlur(refl.astype(np.float32), (0, 0), 0.7)
    base = a * (1 - lamp[..., None]) + off * lamp[..., None]
    base = base * (1 - refl[..., None]) + np.array([86, 16, 22], np.float32) * refl[..., None]
    lit_rgb = on * lamp[..., None] + np.array([255, 40, 48], np.float32) * refl[..., None]
    bloom = cv2.GaussianBlur(np.maximum(lamp, refl), (0, 0), 3.5 * S)
    lit_a = np.clip(np.maximum(lamp, refl) + 0.55 * bloom * (1 - np.maximum(lamp, refl)), 0, 1)
    lit_rgb = np.where(np.maximum(lamp, refl)[..., None] > 0.02, lit_rgb, np.array([255, 36, 44], np.float32))

    # ---------------------------------------------------------------- 7. tone: brighter, punchier paint; deeper glass
    Lb = lumf(base)
    glass = (yy > R(322)) & (yy < R(392)) & (Lb < 120)
    glass = cv2.GaussianBlur(glass.astype(np.float32), (0, 0), 1.2)
    paintish = smooth01(Lb, 70, 120) * (1 - lamp) * (1 - refl) * (1 - glass)
    # light fringe left over from the background at the silhouette: fade it out
    er = cv2.erode((al > 0.5).astype(np.uint8), np.ones((5, 5), np.uint8))
    fringe = (al > 0.02) & (er == 0) & (Lb > (150 if name == 'black' else 200))
    al = np.where(fringe, al * 0.15, al)
    if name == 'white':
        t = np.clip(Lb / 255, 0, 1)
        t2 = np.clip(1.04 * t ** 0.78, 0, 1)                         # lift the mid greys: reads as white paint
        t2 = t2 + 0.10 * np.sin(np.pi * t2) * (t2 - 0.5) * 2          # gentle S-curve
        gain = np.where(Lb > 1, (t2 * 255) / np.maximum(Lb, 1), 1)
        tone = base * gain[..., None]
        # cool, clean white: pull towards a neutral-blue white
        tone = tone * 0.92 + lumf(tone)[..., None] * np.array([0.995, 1.0, 1.03], np.float32) * 0.08
        base = base * (1 - paintish[..., None]) + tone * paintish[..., None]
    else:
        # black paint: local contrast (CLAHE on lightness) so the panels read as glossy curved surfaces
        lab = cv2.cvtColor(np.clip(base, 0, 255).astype(np.uint8), cv2.COLOR_RGB2LAB)
        cl = cv2.createCLAHE(clipLimit=2.2, tileGridSize=(6, 6)).apply(lab[..., 0])
        lab2 = lab.copy(); lab2[..., 0] = cl
        boosted = cv2.cvtColor(lab2, cv2.COLOR_LAB2RGB).astype(np.float32)
        body = (al > 0.5) * (1 - lamp) * (1 - refl) * (1 - glass)
        # only a touch of it (too much reads as grainy grey), plus a gentle deepening of the darks
        mixed = base * 0.82 + boosted * 0.18
        t = np.clip(lumf(mixed) / 255, 0, 1)
        gain = np.where(t > 0.004, np.clip(t ** 1.12 * 1.06, 0, 1) / np.maximum(t, 0.004), 1)
        base = base * (1 - body[..., None]) + (mixed * gain[..., None]) * body[..., None]
    gdeep = base * 0.62 + np.array([4, 7, 12], np.float32) * 0.38
    base = base * (1 - glass[..., None]) + gdeep * glass[..., None]

    # underbody: a soft dark shade between the tyres under the diffuser (not a slab)
    rows = (yy > R(588)) & (yy < R(632))
    gap = rows & (al < 0.5) & (dxa < 0.36 * W2)
    ramp = np.clip(1 - (yy - R(596)) / (R(628) - R(596)), 0, 1) * 0.88
    al = np.where(gap, np.maximum(al, ramp), al)
    base = np.where(gap[..., None], np.array([12, 13, 16], np.float32), base)
    base = np.clip(base, 0, 255)

    # ---------------------------------------------------------------- 8. paint mask (white car) for tinting
    paint_a = None
    if name == 'white':
        Lt = lumf(base)
        Lsm = cv2.GaussianBlur(Lt, (0, 0), 2.5)
        pm = smooth01(Lsm, 78, 118) * (1 - lamp) * (1 - refl) * (1 - glass) * (al > 0.5)
        pm = cv2.morphologyEx(pm.astype(np.float32), cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9)))
        pm = cv2.GaussianBlur(pm, (0, 0), 1.0) * (al > 0.5) * (1 - lamp) * (1 - glass)
        g = np.clip(Lt / np.percentile(Lt[pm > 0.6], 99.3), 0, 1)
        g = (g ** 1.9) * 255                       # more shading depth once it's a colour
        paint_rgb = np.repeat(g[..., None], 3, 2)
        paint_a = pm

    # ---------------------------------------------------------------- 9. clearcoat reflections (procedural)
    gA = np.zeros((H2, W2), np.float32)
    def band(y0, y1, peak, soft=0.5, wx=1.0, fall=0.0):
        """horizontal reflection band between rows y0..y1 (source px), fading towards the sides"""
        t = (yy - R(y0)) / (R(y1) - R(y0))
        prof = np.clip(1 - np.abs(t * 2 - 1) ** (1 / max(soft, 1e-3)), 0, 1) * ((t >= 0) & (t <= 1))
        side = 1 - smooth01(dxa / (0.5 * W2), wx * 0.55, wx * 0.98) * (1 - fall)
        return prof * side * peak
    paint_or_body = (al > 0.5) * (1 - lamp) * (1 - glass) * smooth01(lumf(base), 40, 90)
    # roof sheen
    gA += band(318, 330, 0.55, 0.6) * (al > 0.5)
    blk = 1.0 if name == 'black' else 0.0
    # boot top / ducktail: sky reflection with a crisp edge line
    gA += band(392, 406, 0.22 + 0.22 * blk, 0.6, wx=0.9) * paint_or_body
    gA += band(403.6, 405.4, 0.5 + 0.35 * blk, 0.3, wx=0.85) * paint_or_body
    # boot lid below the lamps: broad soft sky (and on black, a thin horizon line)
    gA += band(456, 490, 0.16 + 0.14 * blk, 0.9, wx=0.8) * paint_or_body
    gA += band(469, 471, 0.25 * blk, 0.3, wx=0.75) * paint_or_body
    # bumper top: horizon reflection, soft at the ends
    gA += band(510, 518, 0.26 + 0.3 * blk, 0.7, wx=0.85) * paint_or_body
    # shoulder highlights over the lamps (both sides)
    for sgn in (-1, 1):
        cxh = ax2 + sgn * 168 * S
        e = np.exp(-(((xx - cxh) / (30 * S)) ** 2 + ((yy - R(412)) / (8 * S)) ** 2))
        gA += 0.55 * e * paint_or_body
        e2 = np.exp(-(((xx - (ax2 + sgn * 190 * S)) / (8 * S)) ** 2 + ((yy - R(490)) / (40 * S)) ** 2))
        gA += 0.28 * e2 * paint_or_body                       # vertical sheen down the rear corners
    # glass: diagonal reflection streaks + sky at the top
    gl = (glass > 0.5)
    d = (xx - ax2) * 0.55 + (yy - R(356))
    streak = np.exp(-((d + 70 * S) / (16 * S)) ** 2) * 0.20 + np.exp(-((d + 18 * S) / (5 * S)) ** 2) * 0.10
    gA += streak * glass
    gA += band(323, 340, 0.16, 0.9) * glass
    # mirror caps
    for sgn in (-1, 1):
        gA += 0.45 * np.exp(-(((xx - (ax2 + sgn * 198 * S)) / (10 * S)) ** 2 + ((yy - R(403)) / (3 * S)) ** 2)) * (al > 0.5)
    gA = np.clip(gA, 0, 0.85) * (al > 0.5)
    gA = cv2.GaussianBlur(gA, (0, 0), 0.8)
    gloss_rgb = np.full_like(a, 255)

    # ---------------------------------------------------------------- 10. plate + lamp anchors
    # dark plate rectangle: centred, find its edges from the darkness in the diffuser
    plate_zone = (yy > R(530)) & (yy < R(575)) & (dxa < 70 * S)
    pz = (lumf(base) < 45) & plate_zone

    # ---------------------------------------------------------------- 11. map into the viewport and save
    ys_, xs_ = np.nonzero(al > 0.5)
    bx0, bx1, by1 = xs_.min(), xs_.max(), ys_.max()
    unit = min(272.0 / (bx1 - bx0), 194.0 / (by1 - ys_.min()))
    ox = 150 - ax2 * unit
    oy = GROUND - by1 * unit
    def tv(x, y): return [round(ox + x * unit, 2), round(oy + y * unit, 2)]
    Wc, Hc = int(VW * PX), int(VH * PX)
    def place(rgb, alpha, fname):
        img = np.dstack([np.clip(rgb, 0, 255), np.clip(alpha * 255, 0, 255)]).astype(np.uint8)
        im = Image.fromarray(img, 'RGBA')
        sw, sh = round(im.width * unit * PX), round(im.height * unit * PX)
        im = im.resize((sw, sh), Image.LANCZOS)
        if fname.endswith('_base'):                       # a little crispness after the downscale
            from PIL import ImageFilter
            r, g, b, a_ = im.split()
            rgb = Image.merge('RGB', (r, g, b)).filter(ImageFilter.UnsharpMask(radius=1.2, percent=55, threshold=2))
            im = Image.merge('RGBA', (*rgb.split(), a_))
        canvas = Image.new('RGBA', (Wc, Hc), (0, 0, 0, 0))
        canvas.alpha_composite(im, (round(ox * PX), round(oy * PX)))
        canvas.save(f'{OUT}/{fname}.webp', 'WEBP', quality=92, method=6, alpha_quality=100)
        canvas.save(f'{OUT}/{fname}.png')
    place(base, al, f'rear2_{name}_base')
    place(gloss_rgb, gA, f'rear2_{name}_gloss')
    place(lit_rgb, lit_a, f'rear2_{name}_lit')
    if paint_a is not None:
        place(paint_rgb, paint_a * np.clip(al, 0, 1), f'rear2_{name}_paint')
    p0x, p1x = ax2 - 50 * S, ax2 + 50 * S              # inner edge of the plate frame (measured)
    p0y, p1y = R(545), R(571.5)
    lampcols = np.nonzero(lamp.sum(0) > 2)[0]
    lx0, lx1 = lampcols.min(), lampcols.max()
    li = lx0 + 40 * S; ri = lx1 - 40 * S
    lit_rows = np.nonzero(lit_a.max(1) > 0.03)[0]
    meta[name] = dict(plate=tv(p0x, p0y) + tv(p1x, p0y) + tv(p1x, p1y) + tv(p0x, p1y),
                      lamp=tv(li, cyc_f[int(li)]) + tv(ri, cyc_f[int(ri)]),
                      band=[round(oy + lit_rows.min() * unit - 1, 1), round(oy + lit_rows.max() * unit + 1, 1)])
    print(name, meta[name])
json.dump(meta, open(f'{OUT}/meta.json', 'w'), indent=1)
