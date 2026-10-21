"""TV Remote & Cast logo (concept F): white remote in front of a light-turquoise TV, on turquoise.

One geometry -> adaptive icon vectors, in-app logo vector, legacy PNG mipmaps, Play Store PNG.
Coordinates are in the 108x108 adaptive-icon viewport.
"""
import os, sys
from PIL import Image, ImageDraw

root = sys.argv[1]
RES = os.path.join(root, 'app/src/main/res')

BG = '#0FA3A3'
TV = '#BFF3F0'
REMOTE = '#FFFFFF'
ACCENT = '#FFD166'

S = 0.40
CX, CY = 190.0, 128.0          # centre of the drawing in the concept's coordinates


def p(x, y):
    return 54 + (x - CX) * S, 54 + (y - CY) * S


def d(v):
    return v * S

# --- geometry (concept coordinates) ---
tv = dict(x=128, y=62, w=92, h=64, r=12, stroke=7)
play = [(162, 80), (184, 94), (162, 108)]
remote = dict(x=196, y=86, w=56, h=108, r=24, outline=6)
dpad = dict(cx=224, cy=122, r=15, stroke=5, dot=5)
buttons = [(214, 160), (234, 160)]
btn_r = 4.5
bar = dict(x=216, y=174, w=16, h=6, r=3)


def f(v):
    return f'{v:.2f}'.rstrip('0').rstrip('.')


def rrect_path(x, y, w, h, r):
    return (f'M{f(x + r)},{f(y)} H{f(x + w - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} '
            f'V{f(y + h - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} H{f(x + r)} '
            f'A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} V{f(y + r)} A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z')


def circle_path(cx, cy, r):
    return f'M{f(cx - r)},{f(cy)} A{f(r)},{f(r)} 0 1 0 {f(cx + r)},{f(cy)} A{f(r)},{f(r)} 0 1 0 {f(cx - r)},{f(cy)} Z'


def mapped():
    tx, ty = p(tv['x'], tv['y'])
    rx, ry = p(remote['x'], remote['y'])
    dx, dy = p(dpad['cx'], dpad['cy'])
    bx, by = p(bar['x'], bar['y'])
    return dict(
        tv=(tx, ty, d(tv['w']), d(tv['h']), d(tv['r']), d(tv['stroke'])),
        play=[p(*pt) for pt in play],
        remote=(rx, ry, d(remote['w']), d(remote['h']), d(remote['r']), d(remote['outline'])),
        dpad=(dx, dy, d(dpad['r']), d(dpad['stroke']), d(dpad['dot'])),
        buttons=[p(*b) for b in buttons],
        btn_r=d(btn_r),
        bar=(bx, by, d(bar['w']), d(bar['h']), d(bar['r'])),
    )


def vector_paths(m, mono=False):
    tx, ty, tw, th, tr, ts = m['tv']
    rx, ry, rw, rh, rr, ro = m['remote']
    dx, dy, dr, dst, ddot = m['dpad']
    bx, by, bw, bh, br = m['bar']
    tv_c = '#FFFFFF' if mono else TV
    rem_c = '#FFFFFF' if mono else REMOTE
    det_c = BG
    play_d = 'M{} L{} L{} Z'.format(*[f'{f(x)},{f(y)}' for x, y in m['play']])
    out = []
    out.append(f'<path android:strokeColor="{tv_c}" android:strokeWidth="{f(ts)}" android:fillColor="#00000000" android:pathData="{rrect_path(tx, ty, tw, th, tr)}"/>')
    out.append(f'<path android:fillColor="{tv_c}" android:pathData="{play_d}"/>')
    if not mono:
        # turquoise outline separates the remote from the TV behind it
        out.append(f'<path android:fillColor="{BG}" android:pathData="{rrect_path(rx - ro, ry - ro, rw + 2 * ro, rh + 2 * ro, rr + ro)}"/>')
    out.append(f'<path android:fillColor="{rem_c}" android:pathData="{rrect_path(rx, ry, rw, rh, rr)}"/>')
    if mono:
        return out  # themed icon: plain silhouette
    out.append(f'<path android:strokeColor="{det_c}" android:strokeWidth="{f(dst)}" android:fillColor="#00000000" android:pathData="{circle_path(dx, dy, dr)}"/>')
    out.append(f'<path android:fillColor="{ACCENT}" android:pathData="{circle_path(dx, dy, ddot)}"/>')
    for cx, cy in m['buttons']:
        out.append(f'<path android:fillColor="{det_c}" android:pathData="{circle_path(cx, cy, m["btn_r"])}"/>')
    out.append(f'<path android:fillColor="{det_c}" android:pathData="{rrect_path(bx, by, bw, bh, br)}"/>')
    return out


def vector(paths, size_dp=108, viewport=108, background=None, corner=None):
    body = ''
    if background:
        if corner:
            body += f'    <path android:fillColor="{background}" android:pathData="{rrect_path(0, 0, viewport, viewport, corner)}"/>\n'
        else:
            body += f'    <path android:fillColor="{background}" android:pathData="M0,0h{viewport}v{viewport}h-{viewport}z"/>\n'
    body += ''.join('    ' + x + '\n' for x in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- TV Remote & Cast logo: generated, do not edit by hand. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
            f'{body}</vector>\n')


def write(rel, text):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w', encoding='utf-8', newline='\n').write(text)


m = mapped()
write('drawable/ic_launcher_foreground.xml', vector(vector_paths(m)))
write('drawable/ic_launcher_monochrome.xml', vector(vector_paths(m, mono=True)))
write('drawable/ic_launcher_background.xml', vector([], background=BG))
# In-app logo: the icon cropped to its visible part (18..90) on a rounded square.
crop = [x.replace('pathData="', 'pathData="') for x in vector_paths(m)]
logo = vector(vector_paths(m), size_dp=96, background=BG, corner=26)
logo = logo.replace('android:viewportWidth="108" android:viewportHeight="108"', 'android:viewportWidth="108" android:viewportHeight="108"')
write('drawable/app_logo.xml', logo)
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
write('mipmap-anydpi-v26/ic_launcher.xml', adaptive)
write('mipmap-anydpi-v26/ic_launcher_round.xml', adaptive)


# --- raster (legacy launchers on Android 7, Play Store) ---
def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def render(px, round_mask=False, square_radius=None):
    ss = 4
    n = px * ss
    k = n / 108.0
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    g = ImageDraw.Draw(img)
    if round_mask:
        g.ellipse([0, 0, n - 1, n - 1], fill=hexrgb(BG))
    else:
        g.rounded_rectangle([0, 0, n - 1, n - 1], radius=(square_radius or 22) * k, fill=hexrgb(BG))
    tx, ty, tw, th, tr, ts = m['tv']
    g.rounded_rectangle([tx * k, ty * k, (tx + tw) * k, (ty + th) * k], radius=tr * k, outline=hexrgb(TV), width=round(ts * k))
    g.polygon([(x * k, y * k) for x, y in m['play']], fill=hexrgb(TV))
    rx, ry, rw, rh, rr, ro = m['remote']
    g.rounded_rectangle([(rx - ro) * k, (ry - ro) * k, (rx + rw + ro) * k, (ry + rh + ro) * k], radius=(rr + ro) * k, fill=hexrgb(BG))
    g.rounded_rectangle([rx * k, ry * k, (rx + rw) * k, (ry + rh) * k], radius=rr * k, fill=hexrgb(REMOTE))
    dx, dy, dr, dst, ddot = m['dpad']
    g.ellipse([(dx - dr) * k, (dy - dr) * k, (dx + dr) * k, (dy + dr) * k], outline=hexrgb(BG), width=round(dst * k))
    g.ellipse([(dx - ddot) * k, (dy - ddot) * k, (dx + ddot) * k, (dy + ddot) * k], fill=hexrgb(ACCENT))
    for cx, cy in m['buttons']:
        r = m['btn_r']
        g.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=hexrgb(BG))
    bx, by, bw, bh, br = m['bar']
    g.rounded_rectangle([bx * k, by * k, (bx + bw) * k, (by + bh) * k], radius=br * k, fill=hexrgb(BG))
    return img.resize((px, px), Image.LANCZOS)


# Legacy icons show the full 108 viewport scaled to 48dp; crop to the 72-unit visible area first.
def legacy(px, round_mask):
    big = render(px * 3, round_mask=False, square_radius=0)
    c = big.crop((int(big.width * 18 / 108), int(big.height * 18 / 108), int(big.width * 90 / 108), int(big.height * 90 / 108)))
    c = c.resize((px, px), Image.LANCZOS)
    mask = Image.new('L', (px * 4, px * 4), 0)
    md = ImageDraw.Draw(mask)
    if round_mask:
        md.ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, px * 4 - 1, px * 4 - 1], radius=px * 4 * 0.22, fill=255)
    mask = mask.resize((px, px), Image.LANCZOS)
    out = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    out.paste(c, (0, 0), mask)
    return out


for folder, px in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    d_ = os.path.join(RES, f'mipmap-{folder}')
    for name in os.listdir(d_):
        if name.startswith('ic_launcher'):
            os.remove(os.path.join(d_, name))
    legacy(px, False).save(os.path.join(d_, 'ic_launcher.png'))
    legacy(px, True).save(os.path.join(d_, 'ic_launcher_round.png'))

store = legacy(512, False)
store.save(os.path.join(root, 'app/src/main/ic_launcher-playstore.png'))
store.save(sys.argv[2])
print('ok', m['tv'], m['remote'])
