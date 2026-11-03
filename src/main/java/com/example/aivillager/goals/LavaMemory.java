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
 * 已知岩浆点账本（fix195，PORTAL 路线案 A）：NPC 旅途中/挖矿中真实扫描见到的
 * 露面岩浆源落盘（lava-memory/&lt;NPC&gt;.json），黑曜石目标优先直奔最近的
 * 已知岩浆点，不再从当前位置盲凿竖井（第五十三棒五轮实证：盲凿撞全淹
 * 滴石洞群、逐环皆败）。记账纪律与 fix40 记矿账本一致：只记真实见到的、
 * 世界标识隔离（与 PortalMemory 同公式，换世界旧账自动失效）、到点落空
 * 记失败、2 次销账。
 */
public final class LavaMemory {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 同一岩浆湖的去重半径（格）：湖面源方块成片，只记一笔。 */
    private static final double DEDUPE_RADIUS_SQ = 12.0D * 12.0D;
    /** 账本上限：够覆盖一片矿区见过的湖，超出丢最旧。 */
    private static final int MAX_ENTRIES = 12;
    /** 到点落空几次销账（同 OreMemory 的 2 次口径）。 */
    private static final int MAX_ATTEMPTS = 2;

    private static Path folder;
    private static String boundWorldKey = "";

    /** 一笔岩浆点：坐标、目击时刻、到点落空次数。 */
    public record Entry(int x, int y, int z, long seenAt, int attempts) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** 落盘 DTO（BlockPos 不直接交给 Gson）。 */
    private static final class EntryData {
        int x;
        int y;
        int z;
        long seenAt;
        int attempts;
    }

    private static final class LedgerData {
        String world = "";
        List<EntryData> entries = new ArrayList<>();
    }

    private LavaMemory() {
    }

    public static void init(java.io.File dataFolder) {
        folder = dataFolder.toPath().resolve("lava-memory");
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            LOG.warning("[AIV][PORTAL] 无法创建岩浆账本目录: " + exception.getMessage());
        }
    }

    /** 记录当前世界标识；与 PortalMemory/BaseMemory 同公式。 */
    public static void bindWorld(Level level) {
        if (level instanceof ServerLevel server) {
            boundWorldKey = server.getSeed() + "/" + level.dimension().identifier();
        }
    }

    private static Path fileFor(String npcName) {
        return folder.resolve(npcName.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }

    private static LedgerData load(String npcName) {
        LedgerData empty = new LedgerData();
        empty.world = boundWorldKey;
        if (folder == null) {
            return empty;
        }
        Path file = fileFor(npcName);
        if (!Files.isRegularFile(file)) {
            return empty;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LedgerData data = GSON.fromJson(reader, LedgerData.class);
            if (data == null || !boundWorldKey.equals(data.world)) {
                return empty;
            }
            if (data.entries == null) {
                data.entries = new ArrayList<>();
            }
            return data;
        } catch (Exception exception) {
            LOG.warning("[AIV][PORTAL] 岩浆账本读取失败 " + npcName + ": " + exception.getMessage());
            return empty;
        }
    }

    private static void save(String npcName, LedgerData data) {
        if (folder == null) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException exception) {
            LOG.warning("[AIV][PORTAL] 岩浆账本保存失败 " + npcName + ": " + exception.getMessage());
        }
    }

    /** 记一笔真实目击：12 格内已有同湖账目则只刷新时刻，不重复记。 */
    public static synchronized void record(String npcName, BlockPos pos) {
        if (folder == null || pos == null) {
            return;
        }
        LedgerData data = load(npcName);
        long now = System.currentTimeMillis();
        for (EntryData entry : data.entries) {
            double dx = entry.x - pos.getX();
            double dy = entry.y - pos.getY();
            double dz = entry.z - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= DEDUPE_RADIUS_SQ) {
                entry.seenAt = now;
                save(npcName, data);
                return;
            }
        }
        EntryData fresh = new EntryData();
        fresh.x = pos.getX();
        fresh.y = pos.getY();
        fresh.z = pos.getZ();
        fresh.seenAt = now;
        fresh.attempts = 0;
        data.entries.add(fresh);
        while (data.entries.size() > MAX_ENTRIES) {
            int oldest = 0;
            for (int i = 1; i < data.entries.size(); i++) {
                if (data.entries.get(i).seenAt < data.entries.get(oldest).seenAt) {
                    oldest = i;
                }
            }
            data.entries.remove(oldest);
        }
        save(npcName, data);
    }

    /** 本世界全部账目（旧→新）。 */
    public static synchronized List<Entry> entries(String npcName) {
        LedgerData data = load(npcName);
        List<Entry> out = new ArrayList<>();
        for (EntryData entry : data.entries) {
            out.add(new Entry(entry.x, entry.y, entry.z, entry.seenAt, entry.attempts));
        }
        return out;
    }

    /**
     * 黑曜石旅途选点：优先没被判过落空的条目（attempts==0）里取 3D
     * 最近；全有失败记录时才回落全体最近。
     * 【fix212】旧口径纯 3D 最近：覆盖死点 (404,1,-246) 因 Y 高 61 格，
     * 从任何真实派单位置都系统性近于已实证的深湖点 (472,-60,-245)，
     * 六轮清一色选死点、湖点从未被选（六轮日志钉正）。玩家口径同理：
     * 去过一次证明不行的点先划到一边，先试没试过的点。
     */
    public static synchronized Entry nearest(String npcName, BlockPos from) {
        Entry best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entry entry : entries(npcName)) {
            if (entry.attempts() > 0) {
                continue;
            }
            double sq = entry.pos().distSqr(from);
            if (sq < bestSq) {
                bestSq = sq;
                best = entry;
            }
        }
        if (best != null) {
            return best;
        }
        for (Entry entry : entries(npcName)) {
            double sq = entry.pos().distSqr(from);
            if (sq < bestSq) {
                bestSq = sq;
                best = entry;
            }
        }
        return best;
    }

    /**
     * 到点落空记一次失败：找到 12 格内的账目 attempts+1，
     * 达到上限即销账。返回 true 表示已销账。
     */
    public static synchronized boolean noteFailed(String npcName, BlockPos pos) {
        if (folder == null || pos == null) {
            return false;
        }
        LedgerData data = load(npcName);
        EntryData hit = null;
        for (EntryData entry : data.entries) {
            double dx = entry.x - pos.getX();
            double dy = entry.y - pos.getY();
            double dz = entry.z - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= DEDUPE_RADIUS_SQ) {
                hit = entry;
                break;
            }
        }
        if (hit == null) {
            return false;
        }
        hit.attempts++;
        boolean removed = hit.attempts >= MAX_ATTEMPTS;
        if (removed) {
            data.entries.remove(hit);
        }
        save(npcName, data);
        return removed;
    }
}
