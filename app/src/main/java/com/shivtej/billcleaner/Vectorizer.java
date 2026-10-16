package com.shivtej.billcleaner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saaf Black &amp; White bill ko "vector" shapes mein badalta hai (marching squares).
 * Har akshar ki asli shape ki outline banti hai, text dobara type nahi hota, isliye
 * har number original rehta hai. PDF mein ye outline vector hoti hai, to kitna bhi
 * zoom karo, kinare kabhi nahi phatte.
 *
 * Pure Java (Android ke bina), isliye computer par bhi test ho sakta hai.
 */
public final class Vectorizer {
    private Vectorizer() {}

    /**
     * @param gray      0..255 har pixel (row-major, w*h). Kaala = 0.
     * @param level     is maan par kinara (128)
     * @param tolerance shape ko simple karne ki had (pixel)
     * @param minArea   isse chhoti shapes (dhool ke kan) hata do (pixel²)
     * @return band shapes; har ek {x0,y0,x1,y1,...} pixel mein
     */
    public static List<float[]> trace(int[] gray, int w, int h, float level, float tolerance, float minArea) {
        final int W = w + 2, H = h + 2; // safed border, taaki har shape band ho
        EdgeMap m = new EdgeMap(1 << 16);
        float[] rowA = new float[W], rowB = new float[W];
        fillRow(gray, w, h, 0, rowA);
        for (int y = 0; y < H - 1; y++) {
            fillRow(gray, w, h, y + 1, rowB);
            for (int x = 0; x < W - 1; x++) {
                float tl = rowA[x], tr = rowA[x + 1], br = rowB[x + 1], bl = rowB[x];
                int c = (tl < level ? 8 : 0) | (tr < level ? 4 : 0) | (br < level ? 2 : 0) | (bl < level ? 1 : 0);
                if (c == 0 || c == 15) continue;
                int top = 2 * (y * W + x), left = top + 1;
                int bottom = 2 * ((y + 1) * W + x), right = 2 * (y * W + x + 1) + 1;
                // Kinare ke point (border hata ke, isliye -1)
                switch (c) {
                    case 1: case 14: seg(m, left, x - 1, y - 1 + t(tl, bl, level), bottom, x - 1 + t(bl, br, level), y); break;
                    case 2: case 13: seg(m, bottom, x - 1 + t(bl, br, level), y, right, x, y - 1 + t(tr, br, level)); break;
                    case 3: case 12: seg(m, left, x - 1, y - 1 + t(tl, bl, level), right, x, y - 1 + t(tr, br, level)); break;
                    case 4: case 11: seg(m, top, x - 1 + t(tl, tr, level), y - 1, right, x, y - 1 + t(tr, br, level)); break;
                    case 6: case 9: seg(m, top, x - 1 + t(tl, tr, level), y - 1, bottom, x - 1 + t(bl, br, level), y); break;
                    case 7: case 8: seg(m, top, x - 1 + t(tl, tr, level), y - 1, left, x - 1, y - 1 + t(tl, bl, level)); break;
                    case 5: case 10: {
                        boolean centerIn = (tl + tr + br + bl) * 0.25f < level;
                        // 5: tr+bl andar, 10: tl+br andar
                        boolean joinTopLeft = (c == 5) == centerIn;
                        float tx = x - 1 + t(tl, tr, level), ly = y - 1 + t(tl, bl, level);
                        float bx = x - 1 + t(bl, br, level), ry = y - 1 + t(tr, br, level);
                        if (joinTopLeft) {
                            seg(m, top, tx, y - 1, left, x - 1, ly);
                            seg(m, right, x, ry, bottom, bx, y);
                        } else {
                            seg(m, top, tx, y - 1, right, x, ry);
                            seg(m, left, x - 1, ly, bottom, bx, y);
                        }
                        break;
                    }
                    default: break;
                }
            }
            float[] tmp = rowA; rowA = rowB; rowB = tmp;
        }
        return link(m, tolerance, minArea);
    }

    private static void fillRow(int[] gray, int w, int h, int py, float[] row) {
        int W = w + 2;
        if (py == 0 || py == h + 1) { Arrays.fill(row, 255f); return; }
        row[0] = 255f; row[W - 1] = 255f;
        int off = (py - 1) * w;
        for (int x = 0; x < w; x++) row[x + 1] = gray[off + x];
    }

    private static float t(float a, float b, float level) {
        return (level - a) / (b - a);
    }

    private static void seg(EdgeMap m, int e1, float x1, float y1, int e2, float x2, float y2) {
        int a = m.getOrAdd(e1, x1, y1);
        int b = m.getOrAdd(e2, x2, y2);
        m.connect(a, b);
        m.connect(b, a);
    }

    private static List<float[]> link(EdgeMap m, float tolerance, float minArea) {
        List<float[]> out = new ArrayList<>();
        boolean[] seen = new boolean[m.count];
        FloatList poly = new FloatList();
        for (int s = 0; s < m.count; s++) {
            if (seen[s]) continue;
            poly.clear();
            int prev = -1, cur = s;
            while (true) {
                seen[cur] = true;
                poly.add(m.px[cur], m.py[cur]);
                int next = (m.n1[cur] != prev) ? m.n1[cur] : m.n2[cur];
                if (next < 0 || next == s || seen[next]) break;
                prev = cur;
                cur = next;
            }
            if (poly.size < 6) continue;
            float[] pts = poly.toArray();
            if (Math.abs(area(pts)) < minArea) continue;
            float[] simple = simplifyClosed(pts, tolerance);
            if (simple.length >= 6) out.add(simple);
        }
        return out;
    }

    private static float area(float[] p) {
        double a = 0;
        int n = p.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            a += (double) p[2 * j] * p[2 * i + 1] - (double) p[2 * i] * p[2 * j + 1];
        }
        return (float) (a / 2);
    }

    /** Douglas-Peucker: seedhi line par pade faltu point hatao (shape wahi rehti hai). */
    private static float[] simplifyClosed(float[] p, float tol) {
        int n = p.length / 2;
        if (n < 4 || tol <= 0) return p;
        // 0 se sabse door wala point; do hisson mein simplify
        int far = 0; double best = -1;
        for (int i = 1; i < n; i++) {
            double dx = p[2 * i] - p[0], dy = p[2 * i + 1] - p[1];
            double d = dx * dx + dy * dy;
            if (d > best) { best = d; far = i; }
        }
        boolean[] keep = new boolean[n + 1];
        keep[0] = true; keep[far] = true; keep[n] = true;
        dp(p, n, 0, far, tol, keep);
        dp(p, n, far, n, tol, keep);
        FloatList out = new FloatList();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(p[2 * i], p[2 * i + 1]);
        return out.toArray();
    }

    private static void dp(float[] p, int n, int start, int end, float tol, boolean[] keep) {
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = start; stack[sp++] = end;
        double tol2 = (double) tol * tol;
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            if (b - a < 2) continue;
            float ax = p[2 * (a % n)], ay = p[2 * (a % n) + 1];
            float bx = p[2 * (b % n)], by = p[2 * (b % n) + 1];
            double vx = bx - ax, vy = by - ay, len2 = vx * vx + vy * vy;
            int idx = -1; double max = -1;
            for (int i = a + 1; i < b; i++) {
                double wx = p[2 * i] - ax, wy = p[2 * i + 1] - ay, d;
                if (len2 < 1e-12) d = wx * wx + wy * wy;
                else {
                    double cr = vx * wy - vy * wx;
                    d = cr * cr / len2;
                }
                if (d > max) { max = d; idx = i; }
            }
            if (max > tol2) {
                keep[idx] = true;
                if (sp + 4 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                stack[sp++] = a; stack[sp++] = idx;
                stack[sp++] = idx; stack[sp++] = b;
            }
        }
    }

    // ---------- chhote helper ----------

    /** Kinare ka id -> point (x, y) aur uske do padosi. Open-addressing hash (int key). */
    private static final class EdgeMap {
        int[] keys, vals;
        int mask;
        float[] px = new float[1024], py = new float[1024];
        int[] n1 = new int[1024], n2 = new int[1024];
        int count = 0;

        EdgeMap(int cap) {
            keys = new int[cap]; vals = new int[cap];
            Arrays.fill(keys, -1);
            mask = cap - 1;
        }

        int getOrAdd(int key, float x, float y) {
            int i = mix(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == key) return vals[i];
                if (k == -1) break;
                i = (i + 1) & mask;
            }
            if (count == px.length) {
                int c = px.length * 2;
                px = Arrays.copyOf(px, c); py = Arrays.copyOf(py, c);
                n1 = Arrays.copyOf(n1, c); n2 = Arrays.copyOf(n2, c);
            }
            int idx = count++;
            px[idx] = x; py[idx] = y; n1[idx] = -1; n2[idx] = -1;
            keys[i] = key; vals[i] = idx;
            if (count * 2 > keys.length) grow();
            return idx;
        }

        void connect(int a, int b) {
            if (n1[a] == -1) n1[a] = b; else n2[a] = b;
        }

        private void grow() {
            int[] ok = keys, ov = vals;
            keys = new int[ok.length * 2]; vals = new int[ok.length * 2];
            Arrays.fill(keys, -1);
            mask = keys.length - 1;
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] == -1) continue;
                int i = mix(ok[j]) & mask;
                while (keys[i] != -1) i = (i + 1) & mask;
                keys[i] = ok[j]; vals[i] = ov[j];
            }
        }

        private static int mix(int k) {
            int h = k * 0x9E3779B1;
            return h ^ (h >>> 16);
        }
    }

    private static final class FloatList {
        float[] a = new float[256];
        int size = 0;
        void add(float x, float y) {
            if (size + 2 > a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = x; a[size++] = y;
        }
        void clear() { size = 0; }
        float[] toArray() { return Arrays.copyOf(a, size); }
    }
}
