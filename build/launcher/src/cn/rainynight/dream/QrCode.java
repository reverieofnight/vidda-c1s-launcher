package cn.rainynight.dream;

import java.io.UnsupportedEncodingException;

/**
 * 极简 QR 码编码器（byte 模式，版本 1–10，四个纠错等级），纯 Java、零依赖。
 *
 * 只做「把一段 UTF-8 文本变成布尔点阵」这一件事，渲染交给调用方
 * （LauncherActivity 里用 Bitmap 按整数倍放大，保证边缘锐利）。
 *
 * 算法按 ISO/IEC 18004 实现：字节模式分段 → Reed-Solomon 纠错 →
 * 交织 → 矩阵放置（定位/校正/定时图形）→ 掩码与罚分 → 格式/版本信息。
 */
public final class QrCode {

    public static final int EC_L = 0;
    public static final int EC_M = 1;
    public static final int EC_Q = 2;
    public static final int EC_H = 3;

    /** 版本 1..10 的 RS 分块表：[version][ecLevel] → 扁平的 (块数, 每块总码字, 每块数据码字) 三元组。 */
    private static final int[][][] RS_BLOCK = {
        null,
        { // v1
            { 1, 26, 19 },
            { 1, 26, 16 },
            { 1, 26, 13 },
            { 1, 26, 9 },
        },
        { // v2
            { 1, 44, 34 },
            { 1, 44, 28 },
            { 1, 44, 22 },
            { 1, 44, 16 },
        },
        { // v3
            { 1, 70, 55 },
            { 1, 70, 44 },
            { 2, 35, 17 },
            { 2, 35, 13 },
        },
        { // v4
            { 1, 100, 80 },
            { 2, 50, 32 },
            { 2, 50, 24 },
            { 4, 25, 9 },
        },
        { // v5
            { 1, 134, 108 },
            { 2, 67, 43 },
            { 2, 33, 15, 2, 34, 16 },
            { 2, 33, 11, 2, 34, 12 },
        },
        { // v6
            { 2, 86, 68 },
            { 4, 43, 27 },
            { 4, 43, 19 },
            { 4, 43, 15 },
        },
        { // v7
            { 2, 98, 78 },
            { 4, 49, 31 },
            { 2, 32, 14, 4, 33, 15 },
            { 4, 39, 13, 1, 40, 14 },
        },
        { // v8
            { 2, 121, 97 },
            { 2, 60, 38, 2, 61, 39 },
            { 4, 40, 18, 2, 41, 19 },
            { 4, 40, 14, 2, 41, 15 },
        },
        { // v9
            { 2, 146, 116 },
            { 3, 58, 36, 2, 59, 37 },
            { 4, 36, 16, 4, 37, 17 },
            { 4, 36, 12, 4, 37, 13 },
        },
        { // v10
            { 2, 86, 68, 2, 87, 69 },
            { 4, 69, 43, 1, 70, 44 },
            { 6, 43, 19, 2, 44, 20 },
            { 6, 43, 15, 2, 44, 16 },
        },
    };

    /** 校正图形中心坐标（版本 1 没有）。 */
    private static final int[][] ALIGN = {
        null,
        { },
        { 6, 18 },
        { 6, 22 },
        { 6, 26 },
        { 6, 30 },
        { 6, 34 },
        { 6, 22, 38 },
        { 6, 24, 42 },
        { 6, 26, 46 },
        { 6, 28, 50 },
    };

    private static final int MAX_VERSION = 10;

    /** 编码结果：version/size/mask + 点阵（true 表示黑）。 */
    public static final class Symbol {
        public final int version;
        public final int size;
        public final int mask;
        public final int ecLevel;
        public final boolean[][] modules;

        Symbol(int version, int mask, int ecLevel, boolean[][] modules) {
            this.version = version;
            this.size = modules.length;
            this.mask = mask;
            this.ecLevel = ecLevel;
            this.modules = modules;
        }
    }

    private QrCode() {
    }

    /** 四周留白（静区）的模块数，标准要求 >= 4。 */
    public static final int QUIET_ZONE = 4;

    /** 渲染成 ARGB 像素：白底黑块，按整数倍放大，保证边缘锐利。 */
    public static int[] renderPixels(Symbol s, int quietZone, int scale, int darkColor, int lightColor) {
        if (scale < 1) {
            throw new IllegalArgumentException("bad scale " + scale);
        }
        int modules = s.size + quietZone * 2;
        int px = modules * scale;
        int[] out = new int[px * px];
        for (int y = 0; y < px; y++) {
            int my = y / scale - quietZone;
            int rowBase = y * px;
            for (int x = 0; x < px; x++) {
                int mx = x / scale - quietZone;
                boolean dark = mx >= 0 && my >= 0 && mx < s.size && my < s.size && s.modules[my][mx];
                out[rowBase + x] = dark ? darkColor : lightColor;
            }
        }
        return out;
    }

    /** 把给定的目标像素尺寸换算成整数放大倍率（不足最小 3 倍时按最小算）。 */
    public static int scaleFor(Symbol s, int targetPx, int quietZone) {
        int modules = s.size + quietZone * 2;
        return Math.max(3, targetPx / modules);
    }

    /** 自动选版本、自动选掩码。 */
    public static Symbol encode(String text, int ecLevel) {
        return encode(text, ecLevel, -1, -1);
    }

    /**
     * @param forceVersion 1..10，传 -1 表示按内容自动选最小版本
     * @param forceMask    0..7，传 -1 表示按罚分自动选
     */
    public static Symbol encode(String text, int ecLevel, int forceVersion, int forceMask) {
        if (ecLevel < 0 || ecLevel > 3) {
            throw new IllegalArgumentException("bad ec level " + ecLevel);
        }
        byte[] data = utf8(text);
        int version = forceVersion;
        if (version < 0) {
            version = -1;
            for (int v = 1; v <= MAX_VERSION; v++) {
                if (byteCapacity(v, ecLevel) >= data.length) {
                    version = v;
                    break;
                }
            }
            if (version < 0) {
                throw new IllegalArgumentException("text too long for version <= " + MAX_VERSION + ": " + data.length);
            }
        } else if (byteCapacity(version, ecLevel) < data.length) {
            throw new IllegalArgumentException("text too long for v" + version + " ev" + ecLevel);
        }

        int[] codewords = buildCodewords(data, version, ecLevel);
        int size = 17 + 4 * version;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];
        drawFunctionPatterns(modules, isFunction, version);

        int mask = forceMask >= 0 ? forceMask : chooseMask(modules, isFunction, version, ecLevel, codewords);
        placeData(modules, isFunction, codewords, mask);
        drawFormatInfo(modules, ecLevel, mask);
        if (version >= 7) {
            drawVersionInfo(modules, version);
        }
        return new Symbol(version, mask, ecLevel, modules);
    }

    // ---------------------------------------------------------------- 编码

    private static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int[] rsSpec(int version, int ecLevel) {
        return RS_BLOCK[version][ecLevel];
    }

    /** 该版本 + 纠错等级能放多少个字节（byte 模式）。 */
    public static int byteCapacity(int version, int ecLevel) {
        int[] spec = rsSpec(version, ecLevel);
        int dataCodewords = 0;
        for (int i = 0; i < spec.length; i += 3) {
            dataCodewords += spec[i] * spec[i + 2];
        }
        int countBits = version <= 9 ? 8 : 16;
        return (dataCodewords * 8 - 4 - countBits) / 8;
    }

    private static int[] buildCodewords(byte[] data, int version, int ecLevel) {
        int[] spec = rsSpec(version, ecLevel);
        int blockCount = 0;
        int totalData = 0;
        int totalEcc = 0;
        for (int i = 0; i < spec.length; i += 3) {
            blockCount += spec[i];
            totalData += spec[i] * spec[i + 2];
            totalEcc += spec[i] * (spec[i + 1] - spec[i + 2]);
        }
        int[] out = new int[totalData + totalEcc];

        BitWriter bw = new BitWriter(totalData);
        bw.append(0x4, 4);                       // 字节模式
        bw.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) {
            bw.append(b & 0xFF, 8);
        }
        int capacityBits = totalData * 8;
        int remaining = capacityBits - bw.size();
        bw.append(0, Math.min(4, remaining));
        while (bw.size() % 8 != 0) {
            bw.append(0, 1);
        }
        int pad = 0xEC;
        while (bw.size() < capacityBits) {
            bw.append(pad, 8);
            pad = pad == 0xEC ? 0x11 : 0xEC;
        }

        int[] all = bw.toCodewords(totalData);
        int[][] dataBlocks = new int[blockCount][];
        int[][] eccBlocks = new int[blockCount][];
        int idx = 0;
        int bi = 0;
        for (int i = 0; i < spec.length; i += 3) {
            int count = spec[i];
            int perBlockTotal = spec[i + 1];
            int perBlockData = spec[i + 2];
            int eccLen = perBlockTotal - perBlockData;
            for (int b = 0; b < count; b++) {
                int[] block = new int[perBlockData];
                System.arraycopy(all, idx, block, 0, perBlockData);
                idx += perBlockData;
                dataBlocks[bi] = block;
                eccBlocks[bi] = rsEcc(block, eccLen);
                bi++;
            }
        }

        int maxData = 0;
        int maxEcc = 0;
        for (int i = 0; i < blockCount; i++) {
            maxData = Math.max(maxData, dataBlocks[i].length);
            maxEcc = Math.max(maxEcc, eccBlocks[i].length);
        }
        int pos = 0;
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blockCount; b++) {
                if (i < dataBlocks[b].length) {
                    out[pos++] = dataBlocks[b][i];
                }
            }
        }
        for (int i = 0; i < maxEcc; i++) {
            for (int b = 0; b < blockCount; b++) {
                if (i < eccBlocks[b].length) {
                    out[pos++] = eccBlocks[b][i];
                }
            }
        }
        return out;
    }

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            GF_EXP[i] = GF_EXP[i - 255];
        }
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static int[] rsEcc(int[] data, int eccLen) {
        int[] divisor = generatorPoly(eccLen);
        int[] result = new int[eccLen];
        for (int value : data) {
            int factor = value ^ result[0];
            System.arraycopy(result, 1, result, 0, eccLen - 1);
            result[eccLen - 1] = 0;
            for (int i = 0; i < eccLen; i++) {
                result[i] ^= gfMul(divisor[i], factor);
            }
        }
        return result;
    }

    private static int[] generatorPoly(int degree) {
        int[] poly = new int[degree];
        poly[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                poly[j] = gfMul(poly[j], root);
                if (j + 1 < degree) {
                    poly[j] ^= poly[j + 1];
                }
            }
            root = gfMul(root, 2);
        }
        return poly;
    }

    private static final class BitWriter {
        private final byte[] buf;
        private int bitLen;

        BitWriter(int capacityBytes) {
            buf = new byte[capacityBytes + 1];
        }

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                int idx = bitLen >> 3;
                if ((value >>> i & 1) != 0) {
                    buf[idx] |= (byte) (0x80 >>> (bitLen & 7));
                }
                bitLen++;
            }
        }

        int size() {
            return bitLen;
        }

        int[] toCodewords(int count) {
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = buf[i] & 0xFF;
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- 矩阵

    private static void drawFunctionPatterns(boolean[][] modules, boolean[][] isFunction, int version) {
        int size = modules.length;

        // 定位图形 + 分隔符
        for (int i = 0; i < size; i++) {
            setFunction(modules, isFunction, 6, i, i % 2 == 0);
            setFunction(modules, isFunction, i, 6, i % 2 == 0);
        }
        drawFinder(modules, isFunction, 3, 3);
        drawFinder(modules, isFunction, size - 4, 3);
        drawFinder(modules, isFunction, 3, size - 4);

        // 校正图形
        int[] centers = ALIGN[version];
        int n = centers.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int cy = centers[i];
                int cx = centers[j];
                boolean nearFinder = (cy <= 8 && cx <= 8)
                        || (cy <= 8 && cx >= size - 9)
                        || (cy >= size - 9 && cx <= 8);
                if (nearFinder) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int dist = Math.max(Math.abs(dy), Math.abs(dx));
                        setFunction(modules, isFunction, cy + dy, cx + dx, dist != 1);
                    }
                }
            }
        }

        // 预留格式信息区域
        for (int i = 0; i <= 8; i++) {
            reserve(modules, isFunction, 8, i);
            reserve(modules, isFunction, i, 8);
        }
        for (int i = 0; i < 8; i++) {
            reserve(modules, isFunction, size - 1 - i, 8);
            reserve(modules, isFunction, 8, size - 1 - i);
        }
        // 固定黑点
        setFunction(modules, isFunction, 8, size - 8, true);

        // 版本信息区域（v7+）
        if (version >= 7) {
            for (int i = 0; i < 18; i++) {
                int a = size - 11 + i % 3;
                int b = i / 3;
                reserve(modules, isFunction, a, b);
                reserve(modules, isFunction, b, a);
            }
        }
    }

    private static void drawFinder(boolean[][] modules, boolean[][] isFunction, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= modules.length || y >= modules.length) {
                    continue;
                }
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(modules, isFunction, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static void setFunction(boolean[][] modules, boolean[][] isFunction, int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private static void reserve(boolean[][] modules, boolean[][] isFunction, int x, int y) {
        isFunction[y][x] = true;
    }

    /** 把 interleaved 码字按之字形放进矩阵，并对非功能区应用掩码。 */
    private static void placeData(boolean[][] modules, boolean[][] isFunction, int[] codewords, int mask) {
        int size = modules.length;
        int totalBits = codewords.length * 8;
        int bitIndex = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (isFunction[y][x]) {
                        continue;
                    }
                    boolean dark = false;
                    if (bitIndex < totalBits) {
                        dark = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) != 0;
                        bitIndex++;
                    }
                    // 余数模块（remainder bits）同样要参与掩码，否则与标准实现对不上
                    if (maskBit(mask, x, y)) {
                        dark = !dark;
                    }
                    modules[y][x] = dark;
                }
            }
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            case 7: return ((x + y) % 2 + x * y % 3) % 2 == 0;
            default: throw new IllegalArgumentException("bad mask " + mask);
        }
    }

    private static int chooseMask(boolean[][] baseModules, boolean[][] baseFunction, int version,
                                  int ecLevel, int[] codewords) {
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] modules = new boolean[baseModules.length][];
            boolean[][] isFunction = new boolean[baseFunction.length][];
            for (int i = 0; i < baseModules.length; i++) {
                modules[i] = baseModules[i].clone();
                isFunction[i] = baseFunction[i].clone();
            }
            placeData(modules, isFunction, codewords, mask);
            drawFormatInfo(modules, ecLevel, mask);
            if (version >= 7) {
                drawVersionInfo(modules, version);
            }
            int penalty = penalty(modules);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }
        }
        return bestMask;
    }

    private static int penalty(boolean[][] m) {
        int size = m.length;
        int score = 0;
        // 规则 1：同色连续 >= 5
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x < size; x++) {
                if (m[y][x] == m[y][x - 1]) {
                    run++;
                } else {
                    if (run >= 5) {
                        score += 3 + (run - 5);
                    }
                    run = 1;
                }
            }
            if (run >= 5) {
                score += 3 + (run - 5);
            }
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            for (int y = 1; y < size; y++) {
                if (m[y][x] == m[y - 1][x]) {
                    run++;
                } else {
                    if (run >= 5) {
                        score += 3 + (run - 5);
                    }
                    run = 1;
                }
            }
            if (run >= 5) {
                score += 3 + (run - 5);
            }
        }
        // 规则 2：2x2 同色块
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) {
                    score += 3;
                }
            }
        }
        // 规则 3：类似定位图形的 1:1:3:1:1
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size - 10; x++) {
                if (m[y][x] && !m[y][x + 1] && m[y][x + 2] && m[y][x + 3] && m[y][x + 4]
                        && !m[y][x + 5] && m[y][x + 6]) {
                    score += 40;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size - 10; y++) {
                if (m[y][x] && !m[y + 1][x] && m[y + 2][x] && m[y + 3][x] && m[y + 4][x]
                        && !m[y + 5][x] && m[y + 6][x]) {
                    score += 40;
                }
            }
        }
        // 规则 4：黑块占比偏离 50%
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean cell : row) {
                if (cell) {
                    dark++;
                }
            }
        }
        int total = size * size;
        int percent = dark * 200 / total;
        score += Math.abs(percent - 100) / 5 * 10;
        return score;
    }

    private static void drawFormatInfo(boolean[][] modules, int ecLevel, int mask) {
        int size = modules.length;
        int ecBits;
        switch (ecLevel) {
            case EC_L: ecBits = 1; break;
            case EC_M: ecBits = 0; break;
            case EC_Q: ecBits = 3; break;
            default: ecBits = 2; break;
        }
        int data = (ecBits << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) {
            modules[i][8] = bit(bits, i);
        }
        modules[7][8] = bit(bits, 6);
        modules[8][8] = bit(bits, 7);
        modules[8][7] = bit(bits, 8);
        for (int i = 9; i < 15; i++) {
            modules[8][14 - i] = bit(bits, i);
        }
        for (int i = 0; i < 8; i++) {
            modules[8][size - 1 - i] = bit(bits, i);
        }
        for (int i = 8; i < 15; i++) {
            modules[size - 15 + i][8] = bit(bits, i);
        }
        modules[size - 8][8] = true;
    }

    private static void drawVersionInfo(boolean[][] modules, int version) {
        int size = modules.length;
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = bit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            modules[b][a] = bit;
            modules[a][b] = bit;
        }
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }
}
