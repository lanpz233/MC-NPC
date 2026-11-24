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

    /** 读账本原始记录（不做世界校验）；没有/解析失败返回 null。 */
    private static PortalData readRaw(String npcName) {
        if (folder == null) {
            return null;
        }
        Path file = fileFor(npcName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, PortalData.class);
        } catch (Exception exception) {
            LOG.warning("[AIV][PORTAL] 传送门账本读取失败 " + npcName + ": " + exception.getMessage());
            return null;
        }
    }

    /** 世界标识的种子部分（维度不参与"同一存档"判定）。 */
    private static String seedPart(String world) {
        if (world == null) {
            return "";
        }
        int slash = world.indexOf('/');
        return slash >= 0 ? world.substring(0, slash) : world;
    }

    /** 世界标识的维度部分。 */
    private static String dimensionPart(String world) {
        if (world == null) {
            return "";
        }
        int slash = world.indexOf('/');
        return slash >= 0 ? world.substring(slash + 1) : "";
    }

    /** 读本 NPC 在当前世界的门账；没有/串世界返回 null。门位口径保持
     * 维度全等校验：坐标是维度内的，绝不跨维度串用（fix303 不动此口径）。 */
    public static Portal portal(String npcName) {
        PortalData data = readRaw(npcName);
        if (data == null || !worldKey().equals(data.world)) {
            return null;
        }
        return new Portal(data.world, new BlockPos(data.x, data.y, data.z), data.builtAt);
    }

    /** 【fix303·153 棒】"门已建成"事实判定：账本有记录且种子相同即成立，
     * 维度不同不推翻事实。原口径借 portal() 的维度全等校验判事实——NPC
     * 进下界后在下界侧落账（回程门坐标＋下界标识）覆盖主世界账本，回主
     * 世界后 isBuilt 恒 false：portalBuiltKnown/fix274 让位/fix288/298b
     * 让位/13.5 走近门支全灭，下界链自回主世界起零点火（正式服实证：
     * 05:40 入界落账被冲、05:55 重启回主世界后「前往下界要塞」再未
     * 出现）。换存档（种子不同）仍判未建成，防串账本意不变。 */
    public static boolean isBuilt(String npcName) {
        PortalData data = readRaw(npcName);
        return data != null && !seedPart(data.world).isEmpty()
                && seedPart(data.world).equals(seedPart(worldKey()));
    }

    /** 门建成落盘（同维度重复落盘以最新坐标为准）。
     * 【fix303·153 棒】防跨维度覆盖：账本已有异维度记录时不落盘——进
     * 下界后在下界侧记账（回程门）不许冲掉主世界门账（冲掉后回主世界
     * 事实与门位双失，见 isBuilt 注）。同维度/无记录照常写入。 */
    public static void markBuilt(String npcName, BlockPos pos) {
        if (folder == null || pos == null) {
            return;
        }
        PortalData existing = readRaw(npcName);
        if (existing != null && !dimensionPart(existing.world).isEmpty()
                && !dimensionPart(existing.world).equals(dimensionPart(worldKey()))) {
            LOG.info("[AIV][PORTAL] " + npcName
                    + " 传送门账本防跨维度覆盖（fix303） | 已有记录维度=" + existing.world
                    + " 当前维度=" + worldKey() + "，保留原账不落盘");
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

    /** 【fix349】清账本：记录的门位已证实无门（账本失活）时删除文件，
     * portal()/isBuilt() 即判无记录。只由规划层失活校验调用。 */
    public static void clear(String npcName) {
        if (folder == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(npcName));
        } catch (IOException exception) {
            LOG.warning("[AIV][PORTAL] 传送门账本清除失败 " + npcName + ": " + exception.getMessage());
        }
    }
}
