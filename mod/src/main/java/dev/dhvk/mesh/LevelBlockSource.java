package dev.dhvk.mesh;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangroveLeavesBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.ShortDryGrassBlock;
import net.minecraft.world.level.block.TallDryGrassBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TintedParticleLeavesBlock;
import net.minecraft.world.level.block.UntintedParticleLeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 生产方块数据源（世界 block 坐标）：包官方 26.2 数据链（笔记 §10.6 定谳 API）。
 *
 * <p>state → 精灵 = {@link BlockStateModelSet#getParticleMaterial(BlockState)} 的
 * {@code Material.Baked.sprite()}（官方网格链粒子材质代表精灵；任务3 换按面精灵细分 +
 * SpriteRectTable 全局表）；染色 = 逐体素 {@code level.getBlockTint}（spike 启发式：
 * 草/叶/枯草类按方块类分 resolver，余为白）；hasData = {@code level.isLoaded}
 *（未加载 = 从未产生，规格 §4.2 缺数据规则）。
 *
 * <p>线程契约：构建期一次性只读（render 线程独占调用，mesher 不并发）。
 */
public final class LevelBlockSource implements BlockSource {

    private final ClientLevel level;
    private final BlockStateModelSet modelSet;
    private final IdentityHashMap<BlockState, Integer> stateIds = new IdentityHashMap<>();
    private final ArrayList<BlockState> states = new ArrayList<>();
    private final IdentityHashMap<BlockState, Integer> spriteCache = new IdentityHashMap<>();
    private final IdentityHashMap<TextureAtlasSprite, Integer> spriteOrdinals = new IdentityHashMap<>();
    private final ArrayList<TextureAtlasSprite> sprites = new ArrayList<>();
    private int nextSpriteId = 1; // 0 = air 惯例
    /** 26.2 定谳(笔记 §10.6 增补): isLoaded = 区块存在(任意 status), 未完成区块的
     *  getBlockState 返回空气 → hasData/实心判定必须按 ChunkStatus.FULL(vanilla loadedAnd*
     *  同型: getChunk(sx, sz, FULL, false) != null)。每 (sx,sz) 结果 memo(带区 ≤4096 chunk)。 */
    private final java.util.HashMap<Long, Boolean> chunkFullMemo = new java.util.HashMap<>();

    public LevelBlockSource(ClientLevel level, BlockStateModelSet modelSet) {
        this.level = level;
        this.modelSet = modelSet;
    }

    /** state 紧凑标识（interned 实例首遇序；调用方约定 0 = air 由 isAir 分支保证）。 */
    private int stateId(BlockState state) {
        Integer id = stateIds.get(state);
        if (id != null) {
            return id;
        }
        int n = states.size();
        states.add(state);
        stateIds.put(state, n);
        return n;
    }

    /** spike 染色启发式：26.2 无 TintColor 枚举 → 按方块类归 resolver（任务3 换官方链）。
     *  必须返回 ClientLevel 预注册的官方常量(26.2: tintCaches 以 resolver 为键, 自造 lambda
     *  查表落空 → getBlockTint NPE, run52 定谳); 用官方常量还顺带复用 vanilla calculateBlockTint
     *  的完整染色计算(比 lambda 更准)。 */
    private static ColorResolver resolverFor(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof GrassBlock || b instanceof TallGrassBlock || b instanceof SeagrassBlock) {
            return BiomeColors.GRASS_COLOR_RESOLVER;
        }
        if (b instanceof ShortDryGrassBlock || b instanceof TallDryGrassBlock) {
            return BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER;
        }
        if (b instanceof LeavesBlock || b instanceof MangroveLeavesBlock
                || b instanceof TintedParticleLeavesBlock || b instanceof UntintedParticleLeavesBlock) {
            return BiomeColors.FOLIAGE_COLOR_RESOLVER;
        }
        return null;
    }

    @Override
    public int blockStateId(int x, int y, int z) {
        return isAir(x, y, z) ? 0 : stateId(level.getBlockState(new BlockPos(x, y, z)));
    }

    /** 区块 (sx,sz) 是否已生成到 FULL(memo 化)。 */
    private boolean chunkFull(int x, int z) {
        long key = (((long) (x >> 4)) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        Boolean memo = chunkFullMemo.get(key);
        if (memo != null) {
            return memo;
        }
        boolean full = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
        chunkFullMemo.put(key, full);
        return full;
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        if (!chunkFull(x, z)) {
            return true;
        }
        return level.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    @Override
    public int spriteId(int x, int y, int z) {
        if (isAir(x, y, z)) {
            return 0;
        }
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        Integer cached = spriteCache.get(state);
        if (cached != null) {
            return cached;
        }
        var baked = modelSet.getParticleMaterial(state);
        int id = (baked == null || baked.sprite() == null)
                ? 0 : spriteOrdinal(baked.sprite());
        spriteCache.put(state, id);
        return id;
    }

    /** 精灵全局序号 = 首见序（spriteID 跨 tile 稳定编号；任务3 由 SpriteRectTable 接管）。 */
    private int spriteOrdinal(TextureAtlasSprite sprite) {
        Integer o = spriteOrdinals.get(sprite);
        if (o != null) {
            return o;
        }
        int n = nextSpriteId++;
        sprites.add(sprite);
        spriteOrdinals.put(sprite, n);
        return n;
    }

    @Override
    public int tintRgb(int x, int y, int z) {
        if (isAir(x, y, z)) {
            return 0;
        }
        BlockPos pos = new BlockPos(x, y, z);
        ColorResolver resolver = resolverFor(level.getBlockState(pos));
        if (resolver == null) {
            return 0;
        }
        int rgb = level.getBlockTint(pos, resolver);
        return rgb == -1 ? 0 : rgb;
    }

    @Override
    public boolean hasData(int x, int y, int z) {
        return chunkFull(x, z);
    }
}
