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
import java.util.logging.Logger;

/**
 * 死亡点账本（D3 死后回程）：NPC 最近一次死亡的格子落盘
 * （death-memory/&lt;NPC&gt;.json），复活后规划层读账回现场续干。
 *
 * <p>为什么要落盘：死亡发生在生存层、回程决策在规划层，且中间隔着复活与
 * 可能的服务器重启——只存内存里，重启一次账就没了。世界标识公式与
 * BaseMemory/OreMemory 完全一致（fix48 口径）：换世界旧账自动失效。
 * 有效期 2 小时（与记矿账本同口径）：现场的那摊活早被别的安排顶掉时，
 * 别再为一具旧尸跑一趟。
 */
public final class DeathMemory {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 死亡账有效期：2 小时（照 OreMemory 记账矿口径）。 */
    private static final long VALID_MILLIS = 2L * 60L * 60L * 1000L;

    private static Path folder;
    private static String boundWorldKey = "";

    /** 一条死亡账：pos=死亡格，diedAt=死亡时刻（毫秒）。 */
    public record Death(String world, BlockPos pos, long diedAt) {
    }

    /** 落盘 DTO（BlockPos 不直接交给 Gson）。 */
    private static final class DeathData {
        String world = "";
        boolean hasDeath;
        int x;
        int y;
        int z;
        long diedAt;
    }

    private DeathMemory() {
    }

    public static void init(java.io.File dataFolder) {
        folder = dataFolder.toPath().resolve("death-memory");
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            LOG.warning("[AIV][DEATHMEMO] 无法创建死亡账本目录: " + exception.getMessage());
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

    /** 读本 NPC 在当前世界未过期的死亡账；没有/串世界/已过期返回 null。 */
    public static Death death(String npcName) {
        if (folder == null) {
            return null;
        }
        DeathData data = readData(npcName);
        if (data == null || !data.hasDeath || !worldKey().equals(data.world)) {
            return null;
        }
        if (System.currentTimeMillis() - data.diedAt > VALID_MILLIS) {
            return null;
        }
        return new Death(data.world, new BlockPos(data.x, data.y, data.z), data.diedAt);
    }

    /** 死亡瞬间记账（生存层调用）：覆盖上一笔（只认最近一具尸）。 */
    public static void saveDeath(String npcName, BlockPos pos) {
        if (folder == null) {
            return;
        }
        DeathData data = new DeathData();
        data.world = worldKey();
        data.hasDeath = true;
        data.x = pos.getX();
        data.y = pos.getY();
        data.z = pos.getZ();
        data.diedAt = System.currentTimeMillis();
        writeData(npcName, data);
        LOG.info("[AIV][DEATHMEMO] " + npcName + " 死亡点记账 | pos="
                + pos.toShortString() + " world=" + data.world);
    }

    /** 回到现场（或人工判定作废）后销账。 */
    public static void clear(String npcName) {
        if (folder == null) {
            return;
        }
        DeathData data = readData(npcName);
        if (data == null) {
            return;
        }
        data.hasDeath = false;
        writeData(npcName, data);
    }

    private static DeathData readData(String npcName) {
        Path file = fileFor(npcName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, DeathData.class);
        } catch (Exception exception) {
            LOG.warning("[AIV][DEATHMEMO] 死亡账本读取失败 " + npcName + ": " + exception.getMessage());
            return null;
        }
    }

    private static void writeData(String npcName, DeathData data) {
        try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException exception) {
            LOG.warning("[AIV][DEATHMEMO] 死亡账本保存失败 " + npcName + ": " + exception.getMessage());
        }
    }
}
