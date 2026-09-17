package dev.dhvk.mesh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CPU 二进制贪心 mesher（规格 §6；算法 = 笔记 §10.7 参照仓库 cgerikj/binary-greedy-meshing
 * 的 MC 化移植：六方向面平面内合并 + 类型检查钩子 + 邻采样 margin 规则）。
 *
 * <p>三步：(1) occupancy = {@link BlockTileView#isSolid}（邻 tile 缺失按空气，笔记 §10.1）；
 * (2) 隐藏面剔除 = 面方向邻非实心才露面，六方向全出面（R-f：崖壁/悬挑/洞口天然可读）；
 * (3) 贪心合并 = bitmask 行扫描，面平面内仅同 (spriteID, tint) 合并，四边形展三角汤。
 *
 * <p>输出 = SoA 28B 子集（R-e v1 形）：pos f32×3（cell 相对）+ color f32×4
 *（spike 期精灵基色 = 白，直乘 tint；任务3 换官方图集链）+ IBO 顺序 u32（复用 S1
 * drawIndexed 路径，渲染器零改）。三角形/顶点数写日志（R-c 预算实测数据源）。
 *
 * <p>四边形约定（对拍基准）：(x,y,z) = 自该角沿 uAxis/vAxis 方向增长 w/h 的起点角；
 * 六方向 (u,v) 轴 = +y:(+x,-z) -y:(+x,+z) +x:(+z,-y) -x:(-z,-y) +z:(+x,-y) -z:(-x,+y)
 *（cross(u,v) = 外法线，正面 CCW）。
 */
public final class CpuGreedyMeshExtractor implements CellMeshExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CpuGreedyMeshExtractor.class);

    /** 轴码：1=+x 2=+y 3=+z；负号 = 反方向。 */
    public static final int AX = 1;
    public static final int AY = 2;
    public static final int AZ = 3;

    /** 面 0..5 = +y -y +x -x +z -z；法线方向。 */
    private static final int[][] NORMAL =
        {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

    /** 各面 u 轴（合并长边方向）。 */
    private static final int[] U_AXIS = {AX, AX, AZ, -AZ, AX, -AX};

    /** 各面 v 轴（cross(u,v) = 外法线）。 */
    private static final int[] V_AXIS = {-AZ, AZ, -AY, -AY, -AY, AY};

    /** 贪心合并后的面四边形（v2 打包 quad 输出源；S4 GPU 路径届时复用）。 */
    public record Quad(int x, int y, int z, int w, int h,
            int uAxis, int vAxis, int spriteId, int tintRgb) {
    }

    /** CPU 侧几何提取（ground truth 对拍面 + 写出 SoA 前的中间结果）。 */
    public List<Quad> quads(BlockTileView tile) {
        List<Quad> out = new ArrayList<>();
        if (!tile.hasData()) {
            return out;
        }
        int[] mask = new int[BlockTileView.SIDES];
        for (int face = 0; face < 6; face++) {
            int ndx = NORMAL[face][0];
            int ndy = NORMAL[face][1];
            int ndz = NORMAL[face][2];
            for (int layer = 0; layer < BlockTileView.SIDES; layer++) {
                for (int a = 0; a < BlockTileView.SIDES; a++) {
                    int bits = 0;
                    for (int b = 0; b < BlockTileView.SIDES; b++) {
                        int x = voxelX(face, layer, a, b);
                        int y = voxelY(face, layer, a, b);
                        int z = voxelZ(face, layer, a, b);
                        if (tile.isSolid(x, y, z)
                                && !neighborSolid(tile, x, y, z, ndx, ndy, ndz)) {
                            bits |= 1 << b;
                        }
                    }
                    mask[a] = bits;
                }
                mergeFace(tile, face, layer, mask, out);
            }
        }
        return out;
    }

    /** 单层单面 bitmask 贪心合并（长边沿 a 轴、宽沿 b 轴；仅同类型可并）。 */
    private static void mergeFace(BlockTileView tile, int face, int layer,
            int[] mask, List<Quad> out) {
        for (int a = 0; a < BlockTileView.SIDES; a++) {
            int bits = mask[a];
            while (bits != 0) {
                int b = Integer.numberOfTrailingZeros(bits);
                int sprite = tile.spriteId(voxelX(face, layer, a, b),
                        voxelY(face, layer, a, b), voxelZ(face, layer, a, b));
                int tint = tile.tintRgb(voxelX(face, layer, a, b),
                        voxelY(face, layer, a, b), voxelZ(face, layer, a, b));
                int w = 1;
                while (a + w < BlockTileView.SIDES && bitSet(mask, a + w, b)
                        && sameType(tile, face, layer, a + w, b, sprite, tint)) {
                    w++;
                }
                int h = 1;
                while (b + h < BlockTileView.SIDES) {
                    boolean ok = true;
                    for (int da = 0; da < w; da++) {
                        if (!bitSet(mask, a + da, b + h)
                                || !sameType(tile, face, layer, a + da, b + h, sprite, tint)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        break;
                    }
                    h++;
                }
                int clear = h >= BlockTileView.SIDES ? -1 : ((1 << h) - 1) << b;
                for (int da = 0; da < w; da++) {
                    mask[a + da] &= ~clear;
                }
                out.add(new Quad(originX(face, layer, a, b, w),
                        originY(face, layer, a, b, h), originZ(face, layer, a, b, w, h),
                        w, h, U_AXIS[face], V_AXIS[face], sprite, tint));
                bits = mask[a];
            }
        }
    }

    private static boolean bitSet(int[] mask, int a, int b) {
        return (mask[a] & (1 << b)) != 0;
    }

    /**
     * 面方向邻是否实心（margin 裁决，笔记 §10.1）：tile 内直接查
     * (x+dx, y+dy, z+dz)；越界 = 邻 tile 对应局部坐标（32→0 / -1→31），
     * 邻 tile 缺失（未构建 / 从未产生）= 空气。
     */
    private static boolean neighborSolid(BlockTileView tile, int x, int y, int z,
            int dx, int dy, int dz) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;
        if (nx >= 0 && nx < BlockTileView.SIDES && ny >= 0 && ny < BlockTileView.SIDES
                && nz >= 0 && nz < BlockTileView.SIDES) {
            return tile.isSolid(nx, ny, nz);
        }
        return tile.isSolidNeighbor(dx, dy, dz,
                nx < 0 ? BlockTileView.SIDES - 1 : 0,
                ny < 0 ? BlockTileView.SIDES - 1 : 0,
                nz < 0 ? BlockTileView.SIDES - 1 : 0);
    }

    private static boolean sameType(BlockTileView tile, int face, int layer,
            int a, int b, int sprite, int tint) {
        int x = voxelX(face, layer, a, b);
        int y = voxelY(face, layer, a, b);
        int z = voxelZ(face, layer, a, b);
        return tile.spriteId(x, y, z) == sprite && tile.tintRgb(x, y, z) == tint;
    }

    /** 面 (layer, a, b) 的体素 X 坐标（±y 面: a=x,b=z；±x 面: a=z,b=y；±z 面: a=x,b=y）。 */
    private static int voxelX(int face, int layer, int a, int b) {
        if (face == 2 || face == 3) {
            return layer;
        }
        return a;
    }

    /** 面 (layer, a, b) 的体素 Y 坐标。 */
    private static int voxelY(int face, int layer, int a, int b) {
        if (face == 0 || face == 1) {
            return layer;
        }
        return b;
    }

    /** 面 (layer, a, b) 的体素 Z 坐标。 */
    private static int voxelZ(int face, int layer, int a, int b) {
        if (face == 0 || face == 1) {
            return b;
        }
        if (face == 2 || face == 3) {
            return a;
        }
        return layer;
    }

    /** 四边形起点角 X（六方向公式，见类 javadoc 轴表）。 */
    private static int originX(int face, int layer, int a, int b, int w) {
        switch (face) {
            case 0:
            case 1:
            case 4:
                return a;
            case 2:
                return layer + 1;
            case 3:
                return layer;
            default:
                return a + w;
        }
    }

    /** 四边形起点角 Y。 */
    private static int originY(int face, int layer, int a, int b, int h) {
        switch (face) {
            case 0:
                return layer + 1;
            case 1:
                return layer;
            case 2:
            case 3:
            case 4:
                return b + h;
            default:
                return b;
        }
    }

    /** 四边形起点角 Z（六方向公式：+y:b+h -y:b +x:a -x:a+w +z:layer+1 -z:layer）。 */
    private static int originZ(int face, int layer, int a, int b, int w, int h) {
        switch (face) {
            case 0:
                return b + h;
            case 1:
                return b;
            case 2:
                return a;
            case 3:
                return a + w;
            case 4:
                return layer + 1;
            case 5:
            default:
                return layer;
        }
    }

    @Override
    public void extract(CellKey key, BlockTileView tile, HeapSubregion out) {
        List<Quad> quads = quads(tile);
        if (quads.isEmpty()) {
            return;
        }
        ByteBuffer win = out.writeWindow();
        long base = out.baseOffset();
        int posB = (int) (out.posOffset() - base);
        int colB = (int) (out.colorOffset() - base);
        int idxB = (int) (out.indexOffset() - base);
        int v = 0;
        int i = 0;
        for (Quad q : quads) {
            float ox = q.x();
            float oy = q.y();
            float oz = q.z();
            float p1x = ox + axis(q.uAxis(), 0) * q.w();
            float p1y = oy + axis(q.uAxis(), 1) * q.w();
            float p1z = oz + axis(q.uAxis(), 2) * q.w();
            float p2x = p1x + axis(q.vAxis(), 0) * q.h();
            float p2y = p1y + axis(q.vAxis(), 1) * q.h();
            float p2z = p1z + axis(q.vAxis(), 2) * q.h();
            float p3x = ox + axis(q.vAxis(), 0) * q.h();
            float p3y = oy + axis(q.vAxis(), 1) * q.h();
            float p3z = oz + axis(q.vAxis(), 2) * q.h();
            writeVertex(win, posB, colB, v++, ox, oy, oz, q.tintRgb());
            writeVertex(win, posB, colB, v++, p1x, p1y, p1z, q.tintRgb());
            writeVertex(win, posB, colB, v++, p2x, p2y, p2z, q.tintRgb());
            writeVertex(win, posB, colB, v++, ox, oy, oz, q.tintRgb());
            writeVertex(win, posB, colB, v++, p2x, p2y, p2z, q.tintRgb());
            writeVertex(win, posB, colB, v++, p3x, p3y, p3z, q.tintRgb());
            for (int k = 0; k < 6; k++) {
                win.putInt(idxB + (i + k) * 4, i + k);
            }
            i += 6;
        }
        if (v > out.vertexCapacity()) {
            LOGGER.warn("[dhvk] R-c 超预算: cell={} vertex={} > capacity={}",
                    key, v, out.vertexCapacity());
        }
        LOGGER.info("[dhvk] mesh cell {}: quads={} tris={} verts={}",
                key, quads.size(), quads.size() * 2, v);
    }

    /** 轴码 → 单位向量的第 c 分量（0=x 1=y 2=z）。 */
    public static float unitAxis(int code, int c) {
        return axis(code, c);
    }

    /**
     * 四边形三角汤 6 顶点角点序列（cell 相对 xyz，18 float；序 = p0,p1,p2,p0,p2,p3，
     * 与 extract() 的 SoA 顶点序一致）。调用方加 tile 原点偏移得世界坐标。
     */
    public static float[] quadCorners(Quad q) {
        float ox = q.x();
        float oy = q.y();
        float oz = q.z();
        float p1x = ox + unitAxis(q.uAxis(), 0) * q.w();
        float p1y = oy + unitAxis(q.uAxis(), 1) * q.w();
        float p1z = oz + unitAxis(q.uAxis(), 2) * q.w();
        float p2x = p1x + unitAxis(q.vAxis(), 0) * q.h();
        float p2y = p1y + unitAxis(q.vAxis(), 1) * q.h();
        float p2z = p1z + unitAxis(q.vAxis(), 2) * q.h();
        float p3x = ox + unitAxis(q.vAxis(), 0) * q.h();
        float p3y = oy + unitAxis(q.vAxis(), 1) * q.h();
        float p3z = oz + unitAxis(q.vAxis(), 2) * q.h();
        return new float[] {
            ox, oy, oz,
            p1x, p1y, p1z,
            p2x, p2y, p2z,
            ox, oy, oz,
            p2x, p2y, p2z,
            p3x, p3y, p3z,
        };
    }

    /** 轴码 → 单位向量的第 c 分量（0=x 1=y 2=z）。 */
    private static float axis(int code, int c) {
        int a = Math.abs(code);
        int s = code >= 0 ? 1 : -1;
        if (a == AX) {
            return c == 0 ? (float) s : 0f;
        }
        if (a == AY) {
            return c == 1 ? (float) s : 0f;
        }
        return c == 2 ? (float) s : 0f;
    }

    /** SoA 28B 子集顶点写入：pos f32×3 + color f32×4（tint 0 = 白色基色）。 */
    private static void writeVertex(ByteBuffer win, int posB, int colB, int v,
            float x, float y, float z, int tint) {
        win.putFloat(posB + v * 12, x);
        win.putFloat(posB + v * 12 + 4, y);
        win.putFloat(posB + v * 12 + 8, z);
        if (tint == 0) {
            win.putFloat(colB + v * 16, 1f);
            win.putFloat(colB + v * 16 + 4, 1f);
            win.putFloat(colB + v * 16 + 8, 1f);
        } else {
            win.putFloat(colB + v * 16, ((tint >> 16) & 0xFF) / 255f);
            win.putFloat(colB + v * 16 + 4, ((tint >> 8) & 0xFF) / 255f);
            win.putFloat(colB + v * 16 + 8, (tint & 0xFF) / 255f);
        }
        win.putFloat(colB + v * 16 + 12, 1f);
    }
}
