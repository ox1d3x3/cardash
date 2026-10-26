#!/usr/bin/env python3
"""
CarDash rear-view ("chase cam") car art: the car seen from behind and a little above.
Original artwork shaped after a 2014 Accent hatch / sedan, no badges. Viewport 300 x 220, ground at y = 214.

Layers (drawn in this order at runtime):
  tyres     tyres + underbody (fixed colours)
  paint     greyscale shading, MULTIPLY-tinted with the paint colour
  gloss     clearcoat highlights (never tinted)
  detail    glass, lamps (off), plate, bumper, outline
  littail   tail lamps switched on (drawn with an animated alpha)
  litbrake  high-mounted stop lamp switched on (brake only)
The number plate text is drawn at runtime into the plate box (see PLATE below).

Usage: tools/reargen.py <res/drawable dir> [<svg preview dir>]
"""
import os, sys
import cargen
from cargen import P, sym, pair, lin, rad, grey, white, black, to_vd, to_svg_group

cargen.W, cargen.H = 300, 220      # sym()/pair() mirror about x = 150, to_vd writes this viewport
W, H = 300, 220
GROUND = 214
PLATE = (112, 142, 188, 168)       # plate box (x0, y0, x1, y1), same for both bodies


def ell(cx, cy, rx, ry):
    return P().M(cx - rx, cy).C(cx - rx, cy - ry * 0.55, cx - rx * 0.55, cy - ry, cx, cy - ry) \
        .C(cx + rx * 0.55, cy - ry, cx + rx, cy - ry * 0.55, cx + rx, cy) \
        .C(cx + rx, cy + ry * 0.55, cx + rx * 0.55, cy + ry, cx, cy + ry) \
        .C(cx - rx * 0.55, cy + ry, cx - rx, cy + ry * 0.55, cx - rx, cy).Z()


def rrect(x0, y0, x1, y1, r):
    return P().M(x0 + r, y0).L(x1 - r, y0).Q(x1, y0, x1, y0 + r).L(x1, y1 - r).Q(x1, y1, x1 - r, y1) \
        .L(x0 + r, y1).Q(x0, y1, x0, y1 - r).L(x0, y0 + r).Q(x0, y0, x0 + r, y0).Z()


def car(style):
    sedan = style == 'sedan'
    S = {k: [] for k in ('tyres', 'paint', 'gloss', 'detail', 'littail', 'litbrake')}

    def add(layer, path, fill=None, stroke=None, sw=1.0):
        S[layer].append(dict(d=path.d(), fill=fill, stroke=stroke, sw=sw, group=None))

    # ---------------- tyres + underbody
    add('tyres', P().M(62, 196).L(238, 196).L(238, 210).L(62, 210).Z(), 0xFF0E1013)
    tyre = rrect(232, 182, 270, GROUND, 7)
    add('tyres', pair(tyre), lin(232, 0, 270, 0, (0, 0xFF0A0B0D), (0.45, 0xFF2A2D33), (0.8, 0xFF16181C), (1, 0xFF0A0B0D)))
    add('tyres', pair(P().M(238, 190).L(264, 190)), stroke=0x30FFFFFF, sw=1.0)  # sidewall glint

    # ---------------- body
    roof_y = 30 if sedan else 24
    if sedan:
        upper = [('C', (182, 30), (208, 32), (220, 38)),
                 ('C', (228, 46), (234, 66), (240, 86)),
                 ('C', (246, 92), (258, 98), (270, 104)),
                 ('C', (279, 112), (281, 126), (281, 142))]
    else:
        upper = [('C', (186, 24), (214, 26), (226, 32)),
                 ('C', (234, 37), (240, 58), (247, 92)),
                 ('C', (249, 98), (262, 103), (272, 110)),
                 ('C', (279, 116), (281, 128), (281, 142))]
    body = sym((150, roof_y), upper + [
        ('L', (280, 176)),
        ('C', (279, 190), (275, 200), (266, 203)),
        ('C', (240, 206), (190, 207), (150, 207))])

    # mirrors (paint coloured), sticking out of the greenhouse
    my = 84 if sedan else 88
    mirror = P().M(244, my + 1).L(262, my - 3).C(268, my - 4, 272, my - 1, 272, my + 3).L(271, my + 8) \
        .C(270, my + 10.5, 267, my + 11.5, 264, my + 11.5).L(246, my + 11).Z()
    add('paint', pair(mirror), lin(0, my - 5, 0, my + 13, (0, grey(250)), (0.5, grey(215)), (1, grey(140))))

    # base shading: lit roof, upward-facing deck/shoulders bright, vertical tailgate mid, dark low bumper
    deck = 98 if sedan else 110
    add('paint', body, lin(0, roof_y, 0, 207, (0, grey(252)), ((44 - roof_y) / (207 - roof_y), grey(238)),
                           ((deck - 12 - roof_y) / (207 - roof_y), grey(214)), ((deck - roof_y) / (207 - roof_y), grey(246)),
                           ((deck + 30 - roof_y) / (207 - roof_y), grey(222)), ((172 - roof_y) / (207 - roof_y), grey(196)),
                           ((176 - roof_y) / (207 - roof_y), grey(232)), (1, grey(120))))
    # flanks fall away to the sides
    add('paint', body, lin(19, 0, 281, 0, (0, grey(0, 120)), (0.09, grey(0, 25)), (0.2, grey(0, 0)), (0.8, grey(0, 0)),
                            (0.91, grey(0, 25)), (1, grey(0, 120))))
    # bumper crease (shadow line + a lit edge just under it)
    add('paint', P().M(30, 172).C(90, 177, 210, 177, 270, 172), stroke=grey(0, 70), sw=1.4)
    add('paint', P().M(32, 175).C(90, 180, 210, 180, 268, 175), stroke=grey(255, 120), sw=1.0)
    # tailgate / trunk shut lines
    if sedan:
        add('paint', P().M(100, 102).C(96, 128, 98, 150, 104, 170).L(196, 170).C(202, 150, 204, 128, 200, 102), stroke=grey(0, 55), sw=1.1)
    else:
        add('paint', P().M(98, 112).C(95, 138, 97, 156, 102, 170).L(198, 170).C(203, 156, 205, 138, 202, 112), stroke=grey(0, 55), sw=1.1)
    # plate recess
    x0, y0, x1, y1 = PLATE
    add('paint', rrect(x0 - 6, y0 - 5, x1 + 6, y1 + 5, 6), lin(0, y0 - 5, 0, y1 + 5, (0, grey(0, 70)), (0.25, grey(0, 25)), (1, grey(0, 10))))

    # ---------------- clearcoat
    if sedan:
        roofband = P().M(150, 31).C(182, 31, 206, 33, 218, 39).L(214, 48).C(200, 45, 176, 44, 150, 44).C(124, 44, 100, 45, 86, 48).L(82, 39).C(94, 33, 118, 31, 150, 31).Z()
    else:
        roofband = P().M(150, 25).C(186, 25, 212, 27, 224, 33).L(220, 42).C(204, 39, 178, 38, 150, 38).C(122, 38, 96, 39, 80, 42).L(76, 33).C(88, 27, 114, 25, 150, 25).Z()
    add('gloss', roofband, lin(0, roof_y, 0, roof_y + 18, (0, white(0.75)), (0.6, white(0.25)), (1, white(0.05))))
    # shoulder tops catch the sky
    sh = P().M(244 if not sedan else 238, deck - 12).C(258, deck - 7, 270, deck - 1, 278, deck + 8).L(276, deck + 12) \
        .C(266, deck + 4, 254, deck - 2, 240 if not sedan else 234, deck - 6).Z()
    add('gloss', pair(sh), white(0.55))
    # deck / tailgate top sheen and a crisp horizontal reflection across the tailgate
    add('gloss', P().M(96, deck + 2).C(130, deck - 1, 170, deck - 1, 204, deck + 2).L(204, deck + 9).C(170, deck + 6, 130, deck + 6, 96, deck + 9).Z(),
        lin(0, deck, 0, deck + 9, (0, white(0.5)), (1, white(0.0))))
    add('gloss', P().M(40, 150).C(100, 147, 200, 147, 260, 150).L(260, 153).C(200, 150, 100, 150, 40, 153).Z(), white(0.22))
    # rim light on the left flank, shade on the right (light from upper left, like the top view)
    add('gloss', P().M(24, 128).C(21, 150, 21, 170, 24, 190), stroke=white(0.45), sw=1.6)
    add('gloss', P().M(276, 128).C(279, 150, 279, 170, 276, 190), stroke=black(0.18), sw=2.0)
    # mirror caps
    add('gloss', pair(P().M(248, my + 2).L(262, my - 1).C(266, my - 2, 269, my, 269, my + 2).L(248, my + 5).Z()), white(0.5))
    # ground occlusion low on the bumper
    add('gloss', body, lin(0, 184, 0, 207, (0, black(0)), (1, black(0.35))))

    # ---------------- glass
    if sedan:
        gl = P().M(150, 46).C(178, 46, 202, 48, 214, 53).C(220, 64, 225, 78, 229, 90).C(204, 95, 176, 97, 150, 97) \
            .C(124, 97, 96, 95, 71, 90).C(75, 78, 80, 64, 86, 53).C(98, 48, 122, 46, 150, 46).Z()
        g0, g1 = 46, 97
    else:
        gl = P().M(150, 42).C(182, 42, 208, 44, 220, 49).C(226, 62, 231, 84, 236, 102).C(206, 108, 176, 110, 150, 110) \
            .C(124, 110, 94, 108, 64, 102).C(69, 84, 74, 62, 80, 49).C(92, 44, 118, 42, 150, 42).Z()
        g0, g1 = 42, 110
    add('detail', gl, lin(0, g0, 0, g1, (0, 0xFF0B0E13), (0.6, 0xFF151B23), (1, 0xFF232B36)))
    add('detail', P().M(100, g0 + 4).L(128, g0 + 3).L(110, g1 - 3).L(80, g1 - 6).Z(), lin(100, g0, 100, g1, (0, white(0.20)), (1, white(0.03))))
    add('detail', P().M(136, g0 + 3).L(144, g0 + 3).L(128, g1 - 2).L(120, g1 - 3).Z(), white(0.06))
    add('detail', gl, lin(0, g0, 0, g0 + 14, (0, black(0.55)), (1, black(0))))   # roof / spoiler shadow
    add('detail', gl, stroke=black(0.5), sw=1.0)
    if not sedan:
        add('detail', P().M(150, 106).L(118, 95), stroke=0xFF3A3F47, sw=2.0)   # rear wiper
        add('detail', ell(150, 106, 2.5, 2.5), 0xFF2A2E35)
    # high-mounted stop lamp (off)
    cy = 48.5 if sedan else 42.5
    chmsl = rrect(128, cy - 2, 172, cy + 2, 2)
    add('detail', chmsl, lin(0, cy - 2, 0, cy + 2, (0, 0xFF3A0A0E), (1, 0xFF6A1018)))

    # tail lamps (off): red with a clear reversing section
    if sedan:
        tl = P().M(226, 104).C(246, 106, 266, 110, 278, 118).C(281, 128, 281, 136, 280, 144).C(262, 144, 242, 142, 222, 136).C(220, 124, 221, 112, 226, 104).Z()
        rev = P().M(224, 126).C(236, 130, 246, 132, 256, 132).L(256, 140).C(244, 139, 232, 137, 223, 134).Z()
        led1 = P().M(230, 112).C(246, 113, 262, 117, 275, 124)
        led2 = P().M(228, 138).C(244, 141, 262, 142, 278, 140)
    else:
        tl = P().M(236, 100).C(250, 102, 266, 108, 276, 116).C(280, 124, 281, 134, 280, 146).C(266, 148, 246, 146, 232, 140).C(230, 128, 231, 112, 236, 100).Z()
        rev = P().M(233, 126).C(242, 130, 250, 132, 258, 132).L(258, 141).C(248, 140, 240, 139, 232, 137).Z()
        led1 = P().M(240, 109).C(254, 111, 266, 116, 276, 123)
        led2 = P().M(236, 143).C(252, 146, 266, 146, 279, 144)
    add('detail', pair(tl), lin(220, 0, 281, 0, (0, 0xFF4A080D), (0.5, 0xFF8E141D), (1, 0xFFB82A33)))
    add('detail', pair(led2), stroke=0x66200206, sw=1.4)
    add('detail', pair(led1), stroke=0x55FFB0B4, sw=1.0)
    add('detail', pair(tl), stroke=black(0.5), sw=0.9)
    # lamp lens highlight
    add('detail', pair(P().M(240 if not sedan else 230, 104 if not sedan else 108).C(254, 106, 266, 111, 274, 117)), stroke=white(0.5), sw=1.0)

    # chrome garnish between the lamps (hatch) / trunk lip (sedan)
    gy = 128 if not sedan else 124
    add('detail', P().M(102, gy).C(130, gy + 1.5, 170, gy + 1.5, 198, gy).L(198, gy + 3).C(170, gy + 4.5, 130, gy + 4.5, 102, gy + 3).Z(),
        lin(0, gy, 0, gy + 4.5, (0, 0xFFF2F4F7), (0.5, 0xFF9AA1AB), (1, 0xFF4E545D)))

    # number plate (text is drawn at runtime)
    add('detail', rrect(x0, y0, x1, y1, 3), lin(0, y0, 0, y1, (0, 0xFFFBFBFC), (1, 0xFFDDE0E4)))
    add('detail', rrect(x0, y0, x1, y1, 3), stroke=0xFF2B2F36, sw=1.3)
    add('detail', rrect(x0 + 2.5, y0 + 2.5, x1 - 2.5, y1 - 2.5, 2), stroke=0x302B2F36, sw=0.8)

    # bumper: diffuser, reflectors, exhaust
    add('detail', P().M(88, 190).C(120, 193, 180, 193, 212, 190).L(218, 202).C(180, 205.5, 120, 205.5, 82, 202).Z(),
        lin(0, 190, 0, 205, (0, 0xFF2A2E35), (1, 0xFF101215)))
    add('detail', pair(rrect(240, 181, 262, 186, 2)), 0xFF5A1016)
    add('detail', ell(104, 201, 8, 3), 0xFF07080A)
    add('detail', ell(104, 200.4, 6.5, 1.8), 0xFF3A3F47)

    # crisp outline
    add('detail', body, stroke=black(0.35), sw=1.0)

    # ---------------- lamps on
    add('littail', pair(tl), lin(220, 0, 281, 0, (0, 0xFFB0101A), (0.5, 0xFFFF2630), (1, 0xFFFF6A72)))
    add('littail', pair(led1), stroke=0xFFFFD6D8, sw=2.2)
    add('littail', pair(led2), stroke=0xFFFF9FA4, sw=1.6)
    add('litbrake', chmsl, lin(0, cy - 2, 0, cy + 2, (0, 0xFFFF7A80), (1, 0xFFFF1F2A)))

    lamps = dict(tail=(258, 124) if not sedan else (252, 124), chmsl=(150, cy), plate=PLATE, roof=roof_y)
    return S, lamps


def main():
    res = sys.argv[1]
    svgdir = sys.argv[2] if len(sys.argv) > 2 else None
    for style in ('hatch', 'sedan'):
        S, lamps = car(style)
        for layer, shapes in S.items():
            with open(os.path.join(res, 'rear_%s_%s.xml' % (style, layer)), 'w') as f:
                f.write(to_vd(shapes))
            if svgdir:
                os.makedirs(svgdir, exist_ok=True)
                with open(os.path.join(svgdir, 'rear_%s_%s.svg' % (style, layer)), 'w') as f:
                    f.write('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">%s</svg>'
                            % (W, H, W, H, to_svg_group(shapes, 'r' + style + layer)))
        print(style, {k: len(v) for k, v in S.items()}, lamps)


if __name__ == '__main__':
    main()
