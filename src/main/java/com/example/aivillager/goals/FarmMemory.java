package com.example.aivillager.goals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 农田账本（第四十九轮 fix49，Fong D11 种田）：NPC 开过的田必须落盘记账——
 * 锚点 + 每格土坐标与作物，重启/换目标都不丢；田的位置是它日后收获补种的
 * 唯一依据（玩家靠记忆，NPC 靠账本，口径同 OreMemory 记矿账本）。
 *
 * <p>世界标识与 OreMemory 同公式（种子+维度）：账本文件在插件目录、跨世界
 * 共用，异世界的田坐标在本世界毫无意义，读时按串账剔除（fix48 教训）。
 *
 * <p>本类只管记账与按活方块盘点（scan），锄/种/收的动作在 GoalExecutor。
 */
public final class FarmMemory {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 一格田：土方块坐标（作物长在其上方一格）+ 作物种类（wheat/carrot/potato，空串=空耕地）。 */
    public record Cell(int x, int y, int z, String crop) {
        public BlockPos soil() {
            return new BlockPos(x, y, z);
        }
    }

    /** 一块田：世界标识、锚点（第一格土）、开田时间、格子清单。 */
    public record Farm(String world, int anchorX, int anchorY, int anchorZ,
                       long establishedAt, List<Cell> cells) {
        public BlockPos anchor() {
            return new BlockPos(anchorX, anchorY, anchorZ);
        }
    }

    /** 按活方块盘点的田况：成熟/生长中/空耕地/已损坏（土没了或变回普通土）格数 + 各作物在田株数。 */
    public record Scan(int mature, int growing, int emptyFarmland, int lost,
                       int plantedWheat, int plantedCarrot, int plantedPotato) {
        public int plantedTotal() {
            return plantedWheat + plantedCarrot + plantedPotato;
        }

        public boolean anyWork() {
            return mature > 0 || emptyFarmland > 0 || lost > 0;
        }
    }

    private static File dataDir;
    private static final Map<String, Farm> BY_NPC = new HashMap<>();
    private static final java.util.Set<String> LOADED = new java.util.HashSet<>();
    private static volatile String currentWorld;

    private FarmMemory() {
    }

    /** 插件启用时调用一次：指定落盘目录。 */
    public static void init(File dir) {
        dataDir = dir;
    }

    /** 由持有 NPC 的调用方在记账/读账前绑定当前世界（公式与 OreMemory 一致）。 */
    public static void bindWorld(Level level) {
        if (level == null || currentWorld != null) {
            return;
        }
        long seed = level instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel.getSeed() : 0L;
        currentWorld = seed + "/" + level.dimension();
    }

    /** 当前世界标识（未绑定时 null）。 */
    public static String worldKey() {
        return currentWorld;
    }

    /** 本 NPC 在当前世界的田；没有/异世界返回 null。 */
    public static synchronized Farm farm(String npcName) {
        Farm farm = load(npcName);
        if (farm == null) {
            return null;
        }
        if (currentWorld != null && !currentWorld.equals(farm.world())) {
            return null;
        }
        return farm;
    }

    /** 是否已在本世界开田（生存层留种守卫用：判断快、盘点走 scan）。 */
    public static boolean hasFarm(String npcName) {
        return farm(npcName) != null;
    }

    /** 落盘一块田（开田完成/维护后格子变更都走这里）。 */
    public static synchronized void save(String npcName, Farm farm) {
        BY_NPC.put(npcName, farm);
        File file = fileFor(npcName);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(farm, writer);
            }
        } catch (Exception e) {
            LOG.warning("[AIV][GOAL] 农田账本写入失败 " + npcName + " | " + e.getMessage());
        }
    }

    /** 注销一块田（fix161 全毁重开）：清缓存 + 删账本文件，下轮起按无田走开田分支。 */
    public static synchronized void clear(String npcName) {
        BY_NPC.remove(npcName);
        File file = fileFor(npcName);
        if (file != null && file.isFile()) {
            try {
                Files.delete(file.toPath());
            } catch (Exception e) {
                LOG.warning("[AIV][GOAL] 农田账本删除失败 " + npcName + " | " + e.getMessage());
            }
        }
    }

    /** 作物方块 → 作物种类；不是作物返回 null。 */
    public static String cropOf(Block block) {
        if (block == Blocks.WHEAT) {
            return "wheat";
        }
        if (block == Blocks.CARROTS) {
            return "carrot";
        }
        if (block == Blocks.POTATOES) {
            return "potato";
        }
        return null;
    }

    /** 作物种类 → 作物方块；未知返回 null。 */
    public static Block blockOfCrop(String crop) {
        return switch (crop == null ? "" : crop) {
            case "wheat" -> Blocks.WHEAT;
            case "carrot" -> Blocks.CARROTS;
            case "potato" -> Blocks.POTATOES;
            default -> null;
        };
    }

    /** 作物种类 → 播种消耗的物品（小麦用种子，胡萝卜/土豆用产物本身兼种）。 */
    public static String seedItemOf(String crop) {
        return switch (crop == null ? "" : crop) {
            case "wheat" -> "wheat_seeds";
            case "carrot" -> "carrot";
            case "potato" -> "potato";
            default -> null;
        };
    }

    /** 作物成熟年龄（小麦/胡萝卜/土豆同为 AGE_7 口径，Wiki：8 个生长阶段）。 */
    public static int cropAge(BlockState state) {
        if (state.hasProperty(BlockStateProperties.AGE_7)) {
            return state.getValue(BlockStateProperties.AGE_7);
        }
        return -1;
    }

    /**
     * 按活方块盘点田况：账本只记坐标与意向作物，长没长、熟没熟以世界现状为准
     * （作物可能被怪踩、被水冲、耕地干回泥土——账本说了不算，盘点说了算）。
     */
    public static Scan scan(Level level, String npcName) {
        Farm farm = farm(npcName);
        if (farm == null || level == null) {
            return new Scan(0, 0, 0, 0, 0, 0, 0);
        }
        int mature = 0;
        int growing = 0;
        int empty = 0;
        int lost = 0;
        int wheat = 0;
        int carrot = 0;
        int potato = 0;
        for (Cell cell : farm.cells()) {
            BlockPos soil = cell.soil();
            BlockState soilState = level.getBlockState(soil);
            BlockState cropState = level.getBlockState(soil.above());
            String liveCrop = cropOf(cropState.getBlock());
            if (liveCrop != null) {
                int age = cropAge(cropState);
                if (age >= 7) {
                    mature++;
                } else {
                    growing++;
                }
                switch (liveCrop) {
                    case "wheat" -> wheat++;
                    case "carrot" -> carrot++;
                    case "potato" -> potato++;
                    default -> {
                    }
                }
            } else if (soilState.is(Blocks.FARMLAND)) {
                empty++;
            } else {
                lost++;
            }
        }
        return new Scan(mature, growing, empty, lost, wheat, carrot, potato);
    }

    // ---------------------------------------------------------------- 落盘

    private static File fileFor(String npcName) {
        if (dataDir == null) {
            return null;
        }
        String safe = npcName.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return new File(new File(dataDir, "farm-memory"), safe + ".json");
    }

    private static Farm load(String npcName) {
        Farm cached = BY_NPC.get(npcName);
        if (cached != null) {
            return cached;
        }
        Farm farm = null;
        if (LOADED.add(npcName)) {
            File file = fileFor(npcName);
            if (file != null && file.isFile()) {
                try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    farm = GSON.fromJson(reader, Farm.class);
                } catch (Exception e) {
                    LOG.warning("[AIV][GOAL] 农田账本读取失败（按无田继续） " + npcName
                            + " | " + e.getMessage());
                }
            }
        }
        if (farm != null) {
            BY_NPC.put(npcName, farm);
        }
        return farm;
    }
}
