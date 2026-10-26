#!/usr/bin/env python3
"""
CarDash car art generator: one model -> Android VectorDrawables (app) + SVG (previews).

Top-down 2014-Accent-style car, original artwork (no badges), viewport 200 x 440, nose at the top.
Four layers, drawn in this order:
  tyres   fixed colours
  paint   greyscale shading; tinted at runtime with MULTIPLY so any paint colour keeps its shape
  gloss   clearcoat: white / black alpha highlights, never tinted (that's what makes it look wet)
  detail  glass, lamps, trims, outline (fixed colours)

Usage: tools/cargen.py <res/drawable dir> [<svg preview dir>]
"""
import os, sys

W, H = 200, 440

# ------------------------------------------------------------------ helpers

def mx(x): return W - x

def fmt(v):
    s = ('%.2f' % v).rstrip('0').rstrip('.')
    return s if s not in ('-0', '') else '0'

class P:
    """Tiny path builder (absolute M/L/C/Q/Z)."""
    def __init__(self): self.c = []
    def M(self, x, y): self.c.append(('M', [(x, y)])); return self
    def L(self, x, y): self.c.append(('L', [(x, y)])); return self
    def C(self, a, b, c, d, e, f): self.c.append(('C', [(a, b), (c, d), (e, f)])); return self
    def Q(self, a, b, c, d): self.c.append(('Q', [(a, b), (c, d)])); return self
    def Z(self): self.c.append(('Z', [])); return self
    def d(self):
        out = []
        for cmd, pts in self.c:
            out.append(cmd + ' '.join(fmt(x) + ',' + fmt(y) for x, y in pts))
        return ' '.join(out)
    def mirrored(self):
        p = P()
        p.c = [(cmd, [(mx(x), y) for x, y in pts]) for cmd, pts in self.c]
        return p

def sym(start, segs):
    """Closed shape symmetric about x=100. start=(100,y). segs go down the RIGHT side as
    ('C', c1, c2, p) / ('L', p) and must end on x=100. The left side is mirrored automatically."""
    p = P().M(*start)
    pts = [start]
    for s in segs:
        if s[0] == 'C':
            p.C(*s[1], *s[2], *s[3]); pts.append(s[3])
        else:
            p.L(*s[1]); pts.append(s[1])
    # back up the left side: reverse each segment, mirror x
    for i in range(len(segs) - 1, -1, -1):
        s = segs[i]; prev = pts[i]
        if s[0] == 'C':
            c1, c2 = s[1], s[2]
            p.C(mx(c2[0]), c2[1], mx(c1[0]), c1[1], mx(prev[0]), prev[1])
        else:
            p.L(mx(prev[0]), prev[1])
    return p.Z()

def pair(p):
    """A right-side shape plus its mirror, as one path."""
    q = P(); q.c = p.c + p.mirrored().c; return q

def lin(x1, y1, x2, y2, *stops): return ('linear', (x1, y1, x2, y2), stops)
def rad(cx, cy, r, *stops): return ('radial', (cx, cy, r), stops)

# colours are 0xAARRGGBB ints
def grey(v, a=255): v = int(v); return (a << 24) | (v << 16) | (v << 8) | v
def white(a): return (int(a * 255) << 24) | 0xFFFFFF
def black(a): return (int(a * 255) << 24)

# ------------------------------------------------------------------ the model

def car(style):
    sedan = style == 'sedan'
    tail = 436 if sedan else 423
    S = {'tyres': [], 'paint': [], 'gloss': [], 'detail': [], 'lithead': [], 'littail': []}
    def add(layer, path, fill=None, stroke=None, sw=1.0, group=None):
        S[layer].append(dict(d=path.d() if isinstance(path, P) else path, fill=fill, stroke=stroke, sw=sw, group=group))

    # --- tyres: 185/65 R15-ish, just peeking out of the arches
    tyre = lambda y0: P().M(176, y0 + 6).Q(176, y0, 181, y0).L(182, y0).Q(186, y0, 186, y0 + 6).L(186, y0 + 50).Q(186, y0 + 56, 182, y0 + 56).L(181, y0 + 56).Q(176, y0 + 56, 176, y0 + 50).Z()
    for y0 in (70, 324):
        t = tyre(y0)
        add('tyres', pair(t), lin(176, 0, 186, 0, (0, 0xFF0B0C0E), (0.6, 0xFF2A2D33), (1, 0xFF0B0C0E)))

    # --- body silhouette
    rear = [('C', (181, 320), (182, 340), (182, 356))]
    if sedan:
        rear += [('C', (182, 392), (180, 418), (171, 428)), ('C', (157, 435), (128, 436), (100, 436))]
    else:
        rear += [('C', (182, 385), (179, 404), (168, 413)), ('C', (154, 421), (128, 423), (100, 423))]
    body = sym((100, 17), [
        ('C', (128, 17), (152, 21), (166, 31)),
        ('C', (176, 39), (180, 56), (181, 74)),
        ('C', (182, 90), (182, 108), (181, 126)),
        ('C', (180, 170), (179, 250), (180, 300))] + rear)
    # mirrors (paint coloured)
    mirror = P().M(176, 140).L(195, 146).C(198.5, 147, 199.5, 151, 198.5, 154.5).L(196.5, 160).C(195.5, 162.5, 193, 163.5, 190.5, 162.5).L(176, 156).Z()
    add('paint', pair(mirror), lin(176, 0, 199, 0, (0, grey(150)), (0.45, grey(238)), (1, grey(170))))
    # round body: dark flanks, bright crown
    add('paint', body, lin(18, 0, 182, 0, (0, grey(120)), (0.1, grey(200)), (0.26, grey(240)), (0.5, grey(255)),
                                  (0.74, grey(240)), (0.9, grey(200)), (1, grey(120))))
    # nose and tail fall away from the light
    add('paint', body, lin(0, 17, 0, tail, (0, grey(0, 70)), (0.06, grey(0, 0)), (0.93, grey(0, 0)), (1, grey(0, 90))))
    # wheel-arch haunches (subtle bulge shading)
    for cy in (98, 352):
        add('paint', P().M(160, cy - 40).C(176, cy - 40, 184, cy - 20, 184, cy).C(184, cy + 20, 176, cy + 40, 160, cy + 40).Z(),
            lin(160, 0, 184, 0, (0, grey(0, 0)), (1, grey(0, 40))))
        add('paint', P().M(40, cy - 40).C(24, cy - 40, 16, cy - 20, 16, cy).C(16, cy + 20, 24, cy + 40, 40, cy + 40).Z(),
            lin(40, 0, 16, 0, (0, grey(0, 0)), (1, grey(0, 40))))

    roof_end = 318 if sedan else 338
    # roof panel: slightly darker so the greenhouse reads
    roof = P().M(51, 188).C(75, 182, 125, 182, 149, 188).C(152, 240, 152, roof_end - 38, 149, roof_end) \
        .C(125, roof_end + 6, 75, roof_end + 6, 51, roof_end).C(48, roof_end - 38, 48, 240, 51, 188).Z()
    add('paint', roof, lin(51, 0, 149, 0, (0, grey(200)), (0.5, grey(250)), (1, grey(200))))
    # hood creases + shut lines (shadow lines on the paint)
    add('paint', P().M(72, 40).C(66, 70, 66, 100, 71, 124).M(128, 40).C(134, 70, 134, 100, 129, 124), stroke=grey(0, 40), sw=1.2)
    add('paint', P().M(56, 37).C(80, 31, 120, 31, 144, 37), stroke=grey(0, 55), sw=1.1)
    add('paint', pair(P().M(150, 38).C(160, 70, 162, 100, 158, 126)), stroke=grey(0, 45), sw=1.0)
    if sedan:
        add('paint', P().M(56, 366).C(80, 370, 120, 370, 144, 366), stroke=grey(0, 55), sw=1.1)       # trunk lid front edge
        add('paint', P().M(52, 426).C(80, 431, 120, 431, 148, 426), stroke=grey(0, 50), sw=1.0)        # trunk rear edge
        add('paint', pair(P().M(146, 366).C(150, 390, 150, 410, 148, 426)), stroke=grey(0, 40), sw=1.0)
    else:
        add('paint', P().M(50, 342).C(80, 349, 120, 349, 150, 342), stroke=grey(0, 60), sw=1.4)        # spoiler edge
        add('paint', P().M(60, 404).C(84, 410, 116, 410, 140, 404), stroke=grey(0, 40), sw=1.0)        # tailgate crease

    # --- clearcoat (never tinted)
    # left flank rim light, right flank shade (light comes from the upper left)
    add('gloss', P().M(26, 80).C(22, 160, 22, 280, 26, 360), stroke=white(0.55), sw=1.6)
    add('gloss', P().M(174, 80).C(178, 160, 178, 280, 174, 360), stroke=black(0.18), sw=2.2)
    # hood: broad sheen + sharp streak
    hood = P().M(58, 38).C(80, 32, 120, 32, 142, 38).C(150, 70, 152, 104, 150, 126).C(120, 118, 80, 118, 50, 126).C(48, 104, 50, 70, 58, 38).Z()
    add('gloss', hood, lin(50, 0, 150, 0, (0, white(0)), (0.18, white(0.10)), (0.34, white(0.50)), (0.42, white(0.12)), (0.62, white(0.0)), (0.85, white(0.08)), (1, white(0))))
    add('gloss', P().M(80, 42).C(78, 70, 78, 100, 82, 120).L(88, 120).C(85, 100, 85, 70, 86, 42).Z(),
        lin(0, 42, 0, 120, (0, white(0.75)), (0.5, white(0.35)), (1, white(0))))
    # roof: sky reflection fading backwards + diagonal light band
    add('gloss', roof, lin(0, 186, 0, roof_end, (0, white(0.55)), (0.35, white(0.12)), (1, white(0.02))))
    rb = 250 if not sedan else 240
    add('gloss', P().M(51, rb - 30).L(149, rb - 58).L(150, rb - 34).L(51, rb - 6).Z(),
        lin(0, rb - 60, 0, rb, (0, white(0)), (0.5, white(0.32)), (1, white(0))))
    ell = lambda cx, cy, rx, ry: P().M(cx - rx, cy).C(cx - rx, cy - ry * 0.55, cx - rx * 0.55, cy - ry, cx, cy - ry).C(cx + rx * 0.55, cy - ry, cx + rx, cy - ry * 0.55, cx + rx, cy) \
        .C(cx + rx, cy + ry * 0.55, cx + rx * 0.55, cy + ry, cx, cy + ry).C(cx - rx * 0.55, cy + ry, cx - rx, cy + ry * 0.55, cx - rx, cy).Z()
    add('gloss', ell(36, 72, 9, 22), rad(36, 72, 22, (0, white(0.55)), (0.5, white(0.18)), (1, white(0))))
    add('gloss', ell(32, 330, 8, 26), rad(32, 330, 26, (0, white(0.45)), (0.5, white(0.14)), (1, white(0))))
    add('gloss', ell(164, 78, 7, 18), rad(164, 78, 18, (0, white(0.22)), (1, white(0))))
    # mirror caps
    add('gloss', pair(P().M(178, 143).L(194, 148).C(196, 149, 196.5, 151, 196, 152).L(178, 147.5).Z()), white(0.55))
    # tail / trunk sheen
    ts = 392 if not sedan else 384
    add('gloss', P().M(56, ts).C(84, ts + 6, 116, ts + 6, 144, ts).L(142, ts + 8).C(116, ts + 13, 84, ts + 13, 58, ts + 8).Z(), white(0.28))

    # --- glass
    glass = lambda y1, y2: lin(0, y1, 0, y2, (0, 0xFF0A0D12), (0.55, 0xFF141A22), (1, 0xFF222A35))
    add('detail', P().M(46, 134).C(70, 120, 130, 120, 154, 134).L(149, 188).C(125, 182, 75, 182, 51, 188).Z(), glass(122, 188))
    side = P().M(46, 136).L(51, 188).C(48, 240, 48, roof_end - 38, 51, roof_end).L(47, roof_end + 10) \
        .C(41, roof_end - 40, 40, 230, 42, 160).Z()
    add('detail', pair(side.mirrored()), lin(40, 0, 52, 0, (0, 0xFF0A0D12), (1, 0xFF1E2530)))
    if sedan:
        rg = P().M(52, 322).C(76, 328, 124, 328, 148, 322).L(141, 362).C(122, 367, 78, 367, 59, 362).Z(); r1, r2 = 322, 366
    else:
        rg = P().M(52, 346).C(76, 352, 124, 352, 148, 346).L(144, 383).C(122, 389, 78, 389, 56, 383).Z(); r1, r2 = 346, 388
    add('detail', rg, glass(r1, r2))
    # glass reflections
    add('detail', P().M(58, 132).L(98, 124).L(80, 186).L(60, 188).Z(), lin(58, 130, 90, 186, (0, white(0.22)), (1, white(0.02))))
    add('detail', P().M(108, 124).L(118, 124).L(100, 184).L(92, 184).Z(), white(0.07))
    add('detail', P().M(62, r1 + 4).L(92, r1 + 6).L(80, r2 - 4).L(62, r2 - 6).Z(), lin(62, r1, 80, r2, (0, white(0.18)), (1, white(0.02))))
    if not sedan:
        add('detail', P().M(100, 385).L(122, 368), stroke=0xFF3A3F47, sw=2.2)          # rear wiper
    # front fascia slot + DRL bar line under the hood edge
    add('detail', P().M(76, 19).C(92, 17.4, 108, 17.4, 124, 19).L(121, 24.5).C(106, 23, 94, 23, 79, 24.5).Z(),
        lin(0, 18, 0, 25, (0, 0xFF2B3038), (1, 0xFF101317)))

    # --- headlamps: crystal lens, chrome reflector, projector, LED line
    lamp = P().M(136, 28).C(152, 26.5, 166, 32, 173, 42).C(178.5, 52, 179.5, 68, 177.5, 87).L(171, 85) \
        .C(170, 68, 167, 56, 160, 48).C(154, 41, 145, 36, 134, 35).Z()
    add('detail', pair(lamp), lin(136, 28, 176, 86, (0, 0xFFDDE6F0), (0.4, 0xFF98A9BC), (1, 0xFF4E5C6C)))
    add('detail', pair(lamp), stroke=black(0.45), sw=0.9)
    add('detail', pair(P().M(150, 36).C(160, 38, 168, 46, 172, 58).L(168, 60).C(164, 50, 158, 44, 149, 41).Z()), 0xFFF4F8FC)
    add('detail', pair(P().M(170, 62).C(173, 70, 174, 78, 173.5, 84).L(171.5, 84).C(171.5, 76, 170.5, 70, 168.5, 63).Z()), 0xFF2B3440)
    proj = lambda cx, cy, r: P().M(cx - r, cy).C(cx - r, cy - r * 0.55, cx - r * 0.55, cy - r, cx, cy - r).C(cx + r * 0.55, cy - r, cx + r, cy - r * 0.55, cx + r, cy) \
        .C(cx + r, cy + r * 0.55, cx + r * 0.55, cy + r, cx, cy + r).C(cx - r * 0.55, cy + r, cx - r, cy + r * 0.55, cx - r, cy).Z()
    add('detail', pair(proj(160, 45, 5.5)), rad(160, 45, 5.5, (0, 0xFFFFFFFF), (0.45, 0xFFB8C6D6), (1, 0xFF56677A)))
    add('detail', pair(P().M(139, 31).C(154, 31, 166, 38, 173, 52)), stroke=white(0.9), sw=1.4)
    add('detail', pair(P().M(141, 33).C(154, 33, 164, 39, 170, 50)), stroke=white(0.45), sw=0.8)

    # --- tail lamps: deep red with a bright core
    if sedan:
        tl = P().M(177, 400).C(179, 414, 176, 424, 167, 430).L(140, 434).L(140, 428).C(155, 427, 164, 421, 168, 408).Z()
        core = P().M(174, 406).C(175, 414, 172, 420, 165, 425).L(152, 427)
        tg = (170, 416, 172)
    else:
        tl = P().M(172, 366).C(178.5, 382, 178.5, 400, 168.5, 413).L(160, 416.5).C(168, 404, 170, 388, 166, 368).Z()
        core = P().M(173, 374).C(175, 388, 173, 400, 166, 408)
        tg = (172, 390, 176)
    add('detail', pair(tl), lin(tg[0] - 14, 0, tg[2], 0, (0, 0xFF6E0A10), (0.55, 0xFFC9202A), (1, 0xFFFF5A5F)))
    add('detail', pair(core), stroke=white(0.55), sw=1.0)

    # --- lamps switched on (drawn over the car at runtime with an animated alpha)
    add('lithead', pair(lamp), lin(136, 28, 176, 86, (0, 0xFFFFFFFF), (0.45, 0xFFE9F4FF), (1, 0xFF9CC4EE)))
    add('lithead', pair(P().M(139, 31).C(154, 31, 166, 38, 173, 52).C(176, 60, 177, 70, 176, 82)), stroke=0xFFFFFFFF, sw=2.6)  # LED signature
    add('lithead', pair(proj(160, 45, 6.5)), rad(160, 45, 6.5, (0, 0xFFFFFFFF), (0.6, 0xFFF2F8FF), (1, 0x00DDEEFF)))
    add('littail', pair(tl), lin(tg[0] - 14, 0, tg[2], 0, (0, 0xFFB3101A), (0.5, 0xFFFF2630), (1, 0xFFFF7A80)))
    add('littail', pair(core), stroke=0xFFFFD2D4, sw=1.8)

    # --- crisp outline so it reads on any background
    add('detail', body, stroke=black(0.35), sw=1.0)

    # light emitters for the runtime glow (viewport coords): headlamp centre + direction, tail lamp centre
    lights = dict(head=(158, 46), tail=(167, 420) if sedan else (171, 392), nose=17, tail_y=tail)
    return S, lights

# ------------------------------------------------------------------ writers

def argb_hex(c): return '#%08X' % (c & 0xFFFFFFFF)

def svg_color(c): return '#%06X' % (c & 0xFFFFFF), ((c >> 24) & 255) / 255.0

def to_vd(shapes):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- generated by tools/cargen.py: edit the generator, not this file -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
           '    android:width="%ddp" android:height="%ddp" android:viewportWidth="%d" android:viewportHeight="%d">' % (W, H, W, H)]
    for s in shapes:
        attrs = ['android:pathData="%s"' % s['d']]
        inner = ''
        f = s['fill']
        if isinstance(f, int):
            attrs.append('android:fillColor="%s"' % argb_hex(f))
        elif isinstance(f, tuple):
            kind, geo, stops = f
            g = '<gradient android:type="%s" ' % kind
            if kind == 'linear':
                g += 'android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s"' % tuple(fmt(v) for v in geo)
            else:
                g += 'android:centerX="%s" android:centerY="%s" android:gradientRadius="%s"' % tuple(fmt(v) for v in geo)
            g += ' android:tileMode="clamp">' + ''.join('<item android:offset="%s" android:color="%s"/>' % (fmt(o), argb_hex(c)) for o, c in stops) + '</gradient>'
            inner = '<aapt:attr name="android:fillColor">%s</aapt:attr>' % g
        if s['stroke'] is not None:
            attrs.append('android:strokeColor="%s" android:strokeWidth="%s" android:strokeLineCap="round" android:strokeLineJoin="round"' % (argb_hex(s['stroke']), fmt(s['sw'])))
        out.append('    <path %s%s' % (' '.join(attrs), '>' + inner + '</path>' if inner else ' />'))
    out.append('</vector>')
    return '\n'.join(out) + '\n'

def to_svg_group(shapes, gid):
    defs, body = [], []
    for i, s in enumerate(shapes):
        a = ['d="%s"' % s['d']]
        f = s['fill']
        if isinstance(f, int):
            c, o = svg_color(f); a.append('fill="%s" fill-opacity="%.3f"' % (c, o))
        elif isinstance(f, tuple):
            kind, geo, stops = f
            gid2 = '%s_g%d' % (gid, i)
            st = ''.join('<stop offset="%s" stop-color="%s" stop-opacity="%.3f"/>' % ((fmt(o),) + svg_color(c)) for o, c in stops)
            if kind == 'linear':
                defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>' % ((gid2,) + tuple(fmt(v) for v in geo) + (st,)))
            else:
                defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>' % ((gid2,) + tuple(fmt(v) for v in geo) + (st,)))
            a.append('fill="url(#%s)"' % gid2)
        else:
            a.append('fill="none"')
        if s['stroke'] is not None:
            c, o = svg_color(s['stroke']); a.append('stroke="%s" stroke-opacity="%.3f" stroke-width="%s" stroke-linecap="round" stroke-linejoin="round"' % (c, o, fmt(s['sw'])))
        body.append('<path %s/>' % ' '.join(a))
    return '<defs>%s</defs>%s' % (''.join(defs), ''.join(body))

def main():
    res = sys.argv[1]
    svgdir = sys.argv[2] if len(sys.argv) > 2 else None
    for old in ('car_tyres.xml', 'car_body.xml', 'car_detail.xml'):
        p = os.path.join(res, old)
        if os.path.exists(p): os.remove(p)
    for style in ('hatch', 'sedan'):
        S, lights = car(style)
        for layer, shapes in S.items():
            with open(os.path.join(res, 'car_%s_%s.xml' % (style, layer)), 'w') as f:
                f.write(to_vd(shapes))
        if svgdir:
            os.makedirs(svgdir, exist_ok=True)
            for layer, shapes in S.items():
                with open(os.path.join(svgdir, '%s_%s.svg' % (style, layer)), 'w') as f:
                    f.write('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">%s</svg>' % (W, H, W, H, to_svg_group(shapes, style + layer)))
        print(style, {k: len(v) for k, v in S.items()}, lights)

if __name__ == '__main__':
    main()
