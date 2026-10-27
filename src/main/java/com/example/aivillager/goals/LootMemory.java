package com.example.aivillager.goals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 已搜箱子账本（D12 村庄搜刮）：搜过的世界箱子位置落盘
 * （loot-memory/&lt;NPC&gt;.json），同一个箱子不重复触发搜刮目标。
 *
 * <p>为什么落盘：搜箱是顺路行为，NPC 会在同一片区域反复经过——不记账
 * 就会对着同一个空箱子无限派单。成功搜完与"到不了放弃"都记账（到不了
 * 多半是永久几何，记下别再试）。世界标识公式与 BaseMemory 同（fix48
 * 口径），换世界旧账自动失效。条目上限 300 条滚动（村庄箱子数量级远
 * 小于此，滚动只防账本无限膨胀）。
 */
public final class LootMemory {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 300;

    private static Path folder;
    private static String boundWorldKey = "";

    /** 落盘 DTO：箱位以 "x,y,z" 字符串存（集合语义、便于读账）。 */
    private static final class LootData {
        String world = "";
        List<String> chests = new ArrayList<>();
    }

    private LootMemory() {
    }

    public static void init(java.io.File dataFolder) {
        folder = dataFolder.toPath().resolve("loot-memory");
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            LOG.warning("[AIV][LOOTMEMO] 无法创建搜箱账本目录: " + exception.getMessage());
        }
    }

    /** 记录当前世界标识；与 BaseMemory/OreMemory 同公式。 */
    public static void bindWorld(Level level) {
        if (level instanceof ServerLevel server) {
            boundWorldKey = server.getSeed() + "/" + level.dimension().identifier();
        }
    }

    private static String worldKey() {
        return boundWorldKey;
    }

    private static Path fileFor(String npcName) {
        return folder.resolve(npcName.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 这个箱子在本世界搜过（或放弃过）没有。 */
    public static boolean isLooted(String npcName, BlockPos pos) {
        if (folder == null) {
            return false;
        }
        LootData data = readData(npcName);
        return data != null && worldKey().equals(data.world)
                && data.chests.contains(key(pos));
    }

    /** 记账：该箱子已处理，别再派单。 */
    public static void markLooted(String npcName, BlockPos pos) {
        if (folder == null) {
            return;
        }
        LootData data = readData(npcName);
        if (data == null || !worldKey().equals(data.world)) {
            data = new LootData();
            data.world = worldKey();
        }
        String k = key(pos);
        if (!data.chests.contains(k)) {
            data.chests.add(k);
            while (data.chests.size() > MAX_ENTRIES) {
                data.chests.remove(0);
            }
            writeData(npcName, data);
        }
    }

    private static LootData readData(String npcName) {
        Path file = fileFor(npcName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, LootData.class);
        } catch (Exception exception) {
            LOG.warning("[AIV][LOOTMEMO] 搜箱账本读取失败 " + npcName + ": " + exception.getMessage());
            return null;
        }
    }

    private static void writeData(String npcName, LootData data) {
        try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException exception) {
            LOG.warning("[AIV][LOOTMEMO] 搜箱账本保存失败 " + npcName + ": " + exception.getMessage());
        }
    }
}
