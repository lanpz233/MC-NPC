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
 * 传送门账本（fix160，Fong 2026-10-31 06:46 拍板「先建门」）：NPC 在本世界
 * 建成过下界传送门这一事实落盘（portal-memory/&lt;NPC&gt;.json）——步 13.1
 * 钻石甲维护的让位/恢复判定需要一个跨重启不丢的「门已建成」事实，原版
 * 账本里没有此项（nearbyPortal 只测当下身边，不测历史）。世界标识公式与
 * BaseMemory/FarmMemory 完全一致：换世界后旧账自动失效，绝不串账。
 */
public final class PortalMemory {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path folder;
    private static String boundWorldKey = "";

    /** 一座已建成的门：世界标识、门原点坐标、建成时刻。 */
    public record Portal(String world, BlockPos pos, long builtAt) {
    }

    /** 落盘 DTO（BlockPos 不直接交给 Gson）。 */
    private static final class PortalData {
        String world = "";
        int x;
        int y;
        int z;
        long builtAt;
    }

    private PortalMemory() {
    }

    public static void init(java.io.File dataFolder) {
        folder = dataFolder.toPath().resolve("portal-memory");
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            LOG.warning("[AIV][PORTAL] 无法创建传送门账本目录: " + exception.getMessage());
        }
    }

    /** 记录当前世界标识；与 BaseMemory 同公式。 */
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

    /** 读本 NPC 在当前世界的门账；没有/串世界返回 null。 */
    public static Portal portal(String npcName) {
        if (folder == null) {
            return null;
        }
        Path file = fileFor(npcName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PortalData data = GSON.fromJson(reader, PortalData.class);
            if (data == null || !worldKey().equals(data.world)) {
                return null;
            }
            return new Portal(data.world, new BlockPos(data.x, data.y, data.z), data.builtAt);
        } catch (Exception exception) {
            LOG.warning("[AIV][PORTAL] 传送门账本读取失败 " + npcName + ": " + exception.getMessage());
            return null;
        }
    }

    public static boolean isBuilt(String npcName) {
        return portal(npcName) != null;
    }

    /** 门建成落盘（重复落盘以最新坐标为准）。 */
    public static void markBuilt(String npcName, BlockPos pos) {
        if (folder == null || pos == null) {
            return;
        }
        PortalData data = new PortalData();
        data.world = worldKey();
        data.x = pos.getX();
        data.y = pos.getY();
        data.z = pos.getZ();
        data.builtAt = System.currentTimeMillis();
        try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException exception) {
            LOG.warning("[AIV][PORTAL] 传送门账本保存失败 " + npcName + ": " + exception.getMessage());
        }
    }
}
