import numpy as np, json, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
meta = json.load(open('out/meta.json'))
PX = 2.5
def load(n): return Image.open(f'out/rear2_{n}.png').convert('RGBA')
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf'
def plate(img, q, text):
    W, H = 400, 110
    t = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(t)
    f = ImageFont.truetype(FONT, 68); tw = d.textlength(text, font=f)
    if tw > W * 0.86: f = ImageFont.truetype(FONT, int(68 * W * 0.86 / tw)); tw = d.textlength(text, font=f)
    d.text(((W - tw) / 2, H / 2), text, font=f, fill=(242, 244, 247, 255), anchor='lm')
    x0, y0, x1, y1 = q[0] * PX, q[1] * PX, q[2] * PX, q[5] * PX
    t = t.resize((max(1, round(x1 - x0)), max(1, round(y1 - y0))), Image.LANCZOS)
    img.alpha_composite(t, (round(x0), round(y0)))
def comp(name, tint=None, lit=0.0, bg=(233, 236, 239), text='MAHABUB'):
    m = meta[name]
    base = load(f'{name}_base')
    out = Image.new('RGBA', base.size, bg + (255,))
    sh = Image.new('RGBA', base.size, (0, 0, 0, 0)); ImageDraw.Draw(sh).ellipse((14 * PX, 204 * PX, 286 * PX, 219 * PX), fill=(0, 0, 0, 120 if bg[0] > 100 else 220))
    out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(14)))
    out.alpha_composite(base)
    if tint:
        p = np.asarray(load('white_paint')).astype(np.float32)
        rgb = p[..., :3] * np.array(tint, np.float32) / 255
        out.alpha_composite(Image.fromarray(np.dstack([rgb, p[..., 3]]).astype(np.uint8), 'RGBA'))
    out.alpha_composite(load(f'{name}_gloss'))
    plate(out, m['plate'], text)
    if lit:
        l = np.asarray(load(f'{name}_lit')).astype(np.float32); l[..., 3] *= lit
        out.alpha_composite(Image.fromarray(l.astype(np.uint8), 'RGBA'))
    return out
tiles = [comp('white'), comp('white', lit=1, bg=(10, 11, 13)), comp('white', tint=(194, 36, 44)),
         comp('black'), comp('black', lit=1, bg=(10, 11, 13), text='CARDASH'), comp('white', tint=(46, 91, 184), lit=0.5, text='OX1DE')]
W, H = tiles[0].size
sheet = Image.new('RGB', (W * 3, H * 2), 'white')
for i, t in enumerate(tiles): sheet.paste(t.convert('RGB'), ((i % 3) * W, (i // 3) * H))
sheet.save('../art2_sheet.png'); print(sheet.size)
