import cv2, numpy as np, sys
src, out = sys.argv[1], sys.argv[2]
rng = np.random.default_rng(11)
img = cv2.resize(cv2.imread(src), None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
h, w = g.shape
sm = cv2.bilateralFilter(g, 9, 40, 9)
bold = cv2.Canny(cv2.GaussianBlur(sm, (0, 0), 3), 15, 45)
fine = cv2.Canny(sm, 35, 100)
shadow = (g > 150) & (np.arange(h)[:, None] > h * 0.80)
bold[shadow] = 0; fine[shadow] = 0
S = 4  # supersample for smooth anti-aliased strokes
ink = np.zeros((h * S, w * S), np.float32)

def stroke(pts, width, alpha):
    q = np.round(pts * S).astype(np.int32)
    pad = int(width * S) + 4
    x0, y0 = np.maximum(q.min(0) - pad, 0); x1, y1 = np.minimum(q.max(0) + pad, (ink.shape[1], ink.shape[0]))
    if x1 <= x0 or y1 <= y0: return
    layer = np.zeros((y1 - y0, x1 - x0), np.float32)  # only the stroke's own box, for speed
    cv2.polylines(layer, [q - (x0, y0)], False, 1.0, max(1, int(width * S)), cv2.LINE_AA)
    r = ink[y0:y1, x0:x1]
    r += layer * alpha * (1 - r)  # graphite builds up, never past 1

def draw_edges(edges, min_len, width, passes, alpha, wobble):
    cs, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    for c in cs:
        if cv2.arcLength(c, False) < min_len: continue
        poly = cv2.approxPolyDP(c, 1.8, False)[:, 0, :].astype(np.float32)
        half = max(2, len(poly) // 2 + 1)
        poly = poly[:half]                   # contour of a thin line goes there and back; keep one way
        i = 0
        while i < len(poly) - 1:             # break into separate hand strokes
            n = int(rng.integers(3, 8))
            seg = poly[i:i + n + 1].copy()
            i += n
            if len(seg) < 2: continue
            for _ in range(passes if rng.random() < 0.7 else passes + 1):
                p = seg + rng.normal(0, wobble, seg.shape) + rng.normal(0, wobble * 1.2, 2)
                # overshoot: let the line run past both ends
                for a, b in ((0, 1), (-1, -2)):
                    d = p[a] - p[b]; d /= (np.linalg.norm(d) + 1e-6)
                    p[a] = p[a] + d * rng.uniform(2, 10)
                stroke(p, width * rng.uniform(0.7, 1.4), alpha * rng.uniform(0.5, 1.0))

draw_edges(bold, 40, 1.5, 2, 0.8, 0.9)   # main outline, gone over twice
draw_edges(fine, 30, 0.9, 1, 0.5, 0.7)   # lighter detail lines

# loose hatching: short wobbly diagonal strokes in the darker body areas
dark = np.clip((150 - cv2.GaussianBlur(g, (0, 0), 6).astype(np.float32)) / 110, 0, 1)
body = cv2.erode((g < 238).astype(np.uint8), np.ones((21, 21), np.uint8))
for _ in range(2600):
    x, y = rng.integers(0, w), rng.integers(0, h)
    if not body[y, x] or rng.random() > dark[y, x]: continue
    L = rng.uniform(14, 34); ang = np.deg2rad(rng.normal(-55, 6))
    d = np.array([np.cos(ang), np.sin(ang)]) * L / 2
    mid = np.array([x, y], np.float32)
    pts = np.array([mid - d, mid + rng.normal(0, 1.2, 2), mid + d], np.float32)
    stroke(pts, 0.8, rng.uniform(0.12, 0.28))

ink = cv2.resize(ink, (w, h), interpolation=cv2.INTER_AREA)
grain = np.clip(0.85 + rng.standard_normal((h, w)).astype(np.float32) * 0.18, 0, 1)
ink = np.clip(ink * grain, 0, 1)
rgba = np.zeros((h, w, 4), np.uint8)
rgba[..., :3] = (180, 203, 216)  # BGR sand #D8CBB4
rgba[..., 3] = (ink * 255).astype(np.uint8)
rgba = rgba[164:164 + 627, 81:81 + 1102]  # same crop as before so door outlines line up
cv2.imwrite(out, rgba)
print(rgba.shape)
