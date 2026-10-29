"""
Removes the light halo along the rear art's cut-out edges (tyres, bumper underside, body outline).

The car was cut out of a drawing on a white background: the semi-transparent pixels along the
silhouette (and the first opaque one or two) still carry that white in their colour. On the light
day road it doesn't show; on the dark night road it reads as a white outline round the tyres.

Fix: every edge pixel takes the colour of the solid surface just inside it (a local average of the
fully opaque pixels nearby), but only where the edge is lighter than that surface, so genuinely
light edges (the white car's body) are left alone. Alpha is untouched, so the outline, its smooth
anti-aliasing and the soft underbody shade stay exactly as they were. Fully transparent pixels next
to the car get the same colour too, so the lossy WebP encoder has no white-to-dark jump to ring on.

usage: python3 defringe.py <in.webp|png> [<out.webp>]        (in place when out is omitted)
also used by build.py before saving each base / paint layer.
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage as nd


def lum(rgb):
    return rgb @ np.array([.299, .587, .114], np.float32)


def defringe(rgba, inset=2, reach=8, sigma=1.6, margin=6.0):
    """rgba: HxWx4 uint8 or float array. Returns a new uint8 array."""
    a = np.asarray(rgba).astype(np.float32)
    rgb, al = a[..., :3], a[..., 3]
    solid = al >= 250
    # the surface proper: opaque and not right on the edge (the first opaque pixels are tinted too)
    interior = nd.binary_erosion(solid, iterations=inset)
    if not interior.any():
        return np.clip(a, 0, 255).astype(np.uint8), 0
    # local average of interior colours (normalised convolution), then nearest interior colour
    # where no interior pixel is close enough for the average
    w = nd.gaussian_filter(interior.astype(np.float32), sigma)
    avg = np.stack([nd.gaussian_filter(rgb[..., k] * interior, sigma) for k in range(3)], -1)
    avg = avg / np.maximum(w, 1e-6)[..., None]
    dist, (iy, ix) = nd.distance_transform_edt(~interior, return_indices=True)
    nearest = rgb[iy, ix]
    src = np.where((w > 0.02)[..., None], avg, nearest)

    rim = (al > 0) & ~interior & (dist <= reach)
    lighter = lum(rgb) > lum(src) + margin
    fix = rim & lighter
    out = rgb.copy()
    out[fix] = src[fix]
    # transparent pixels around the car: same colour, so compression has no edge to ring on
    near = (al == 0) & (dist <= reach)
    out[near] = src[near]
    res = np.dstack([out, al])
    return np.clip(np.round(res), 0, 255).astype(np.uint8), int(fix.sum())


def main():
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else src
    im = np.asarray(Image.open(src).convert('RGBA'))
    out, n = defringe(im)
    Image.fromarray(out, 'RGBA').save(dst, 'WEBP', quality=92, method=6, alpha_quality=100, exact=True)
    print(f'{src}: {n} edge pixels recoloured -> {dst}')


if __name__ == '__main__':
    main()
