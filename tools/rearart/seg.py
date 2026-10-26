import numpy as np
from PIL import Image
from scipy import ndimage as nd
A = np.asarray(Image.open('src.jpg').convert('RGB')).astype(np.float32)
H, W = A.shape[:2]
lum = A @ np.array([.299, .587, .114], np.float32)
sat = A.max(2) - A.min(2)
yy = np.mgrid[0:H, 0:W][0]
out = {}
for name, (x0, x1) in {'white': (90, 580), 'black': (660, 1170)}.items():
    reg = np.zeros((H, W), bool); reg[280:700, x0:x1] = True
    bgl = ((lum > 178) & (sat < 26)) | ((lum > 80) & (lum < 150) & (sat < 18) & (yy > 585))
    bgl &= reg
    bgl |= ~reg
    lab, n = nd.label(bgl)
    border = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    bg = np.isin(lab, list(border))
    fg = ~bg & reg
    fg = nd.binary_opening(fg, iterations=1)
    lab2, n2 = nd.label(fg); sizes = nd.sum(fg, lab2, range(1, n2 + 1))
    fg = nd.binary_fill_holes(lab2 == 1 + int(np.argmax(sizes)))
    ys, xs = np.nonzero(fg)
    print(name, 'bbox x', xs.min(), xs.max(), 'y', ys.min(), ys.max())
    # symmetry axis from row extents (body rows only)
    cs = []
    for y in range(ys.min() + 5, ys.max() - 5):
        r = np.nonzero(fg[y])[0]
        if len(r): cs.append((r.min() + r.max()) / 2)
    print('  axis median', np.median(cs), 'p10/p90', np.percentile(cs, 10), np.percentile(cs, 90))
    np.save(f'fg_{name}.npy', fg)
    vis = A.copy(); vis[~fg] = [255, 0, 255]
    Image.fromarray(vis[280:700, x0:x1].astype(np.uint8)).save(f'seg_{name}.png')
