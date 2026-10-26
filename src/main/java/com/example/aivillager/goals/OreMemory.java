package com.example.aivillager.goals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 记矿账本（第四十轮 fix40，Fong 亲提）："见到挖不了的珍贵矿记坐标、备好
 * 工具回访"。只记**真实扫到的裸露矿**（记录点都接在现成的裸露扫描上，扫
 * 不到不记、不编造坐标）；账本落盘（插件数据目录 ore-memory/&lt;NPC&gt;.json），
 * 服务器重启不丢账。
 *
 * <p>收录口径：钻石/金（需铁镐级 tier 2）、铁/青金（需石镐级 tier 1），与
 * GoalExecutor.hasHarvestPickaxe 的级别表同口径。红石是政策跳过、煤是顺路
 * 型、铜当前用不上，都不记。防死锁三件套：条目上限 16（满了挤最旧）、
 * 2 小时过期、回访失败计次满 2 销账。去重：同矿种切比雪夫 4 格内视为
 * 同一处，只刷新时间不重复记。
 */
public final class OreMemory {

    private static final Logger LOG = Logger.getLogger("AIVillager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 16;
    private static final long EXPIRY_MILLIS = 2L * 60L * 60L * 1000L;
    private static final int MAX_ATTEMPTS = 2;
    private static final Set<String> TRACKED = Set.of(
            "diamond_ore", "gold_ore", "iron_ore", "lapis_ore");

    /** 一条记账：矿种（归一后的方块路径）、坐标、所需镐级别、记下时间、回访失败次数。 */
    public record Entry(String block, int x, int y, int z, int needTier, long seenAt, int attempts) {
    }

    private static File dataDir;
    private static final Map<String, List<Entry>> BY_NPC = new HashMap<>();
    private static final Set<String> LOADED = new HashSet<>();

    private OreMemory() {
    }

    /** 插件启用时调用一次：指定落盘目录。 */
    public static void init(File dir) {
        dataDir = dir;
    }

    /** 矿种 → 所需镐级别（与 hasHarvestPickaxe 同口径）；不收录的矿种返回 0。 */
    public static int needTierFor(String blockPath) {
        String base = normalize(blockPath);
        return switch (base) {
            case "diamond_ore", "gold_ore" -> 2;
            case "iron_ore", "lapis_ore" -> 1;
            default -> 0;
        };
    }

    private static String normalize(String blockPath) {
        return blockPath != null && blockPath.startsWith("deepslate_")
                ? blockPath.substring("deepslate_".length()) : blockPath;
    }

    /**
     * 记一笔。返回 true 表示新条目（已打日志）；同一处重复看见只刷新时间、
     * 返回 false 不刷屏。
     */
    public static synchronized boolean record(String npcName, String blockPath, BlockPos pos) {
        String base = normalize(blockPath);
        if (base == null || !TRACKED.contains(base) || pos == null) {
            return false;
        }
        List<Entry> entries = load(npcName);
        long now = System.currentTimeMillis();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.block().equals(base)
                    && Math.max(Math.abs(e.x() - pos.getX()),
                        Math.max(Math.abs(e.y() - pos.getY()), Math.abs(e.z() - pos.getZ()))) <= 4) {
                entries.set(i, new Entry(base, e.x(), e.y(), e.z(), e.needTier(), now, e.attempts()));
                save(npcName, entries);
                return false;
            }
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.sort(Comparator.comparingLong(Entry::seenAt));
            entries.remove(0);
        }
        entries.add(new Entry(base, pos.getX(), pos.getY(), pos.getZ(),
                needTierFor(base), now, 0));
        save(npcName, entries);
        LOG.info("[AIV][GOAL] " + npcName + " 记矿 | " + base + " @ "
                + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " needTier=" + needTierFor(base) + "（当时挖不了，备好工具回访）");
        return true;
    }

    /** 当前有效账本（已剔除过期与失败满额的），按记录时间升序。 */
    public static synchronized List<Entry> entries(String npcName) {
        List<Entry> entries = load(npcName);
        long now = System.currentTimeMillis();
        boolean purged = entries.removeIf(
                e -> now - e.seenAt() > EXPIRY_MILLIS || e.attempts() >= MAX_ATTEMPTS);
        if (purged) {
            save(npcName, entries);
        }
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingLong(Entry::seenAt));
        return copy;
    }

    /** 销账（挖到 / 矿已不在，都用它）。 */
    public static synchronized void remove(String npcName, String blockPath, int x, int y, int z) {
        String base = normalize(blockPath);
        List<Entry> entries = load(npcName);
        if (entries.removeIf(e -> e.block().equals(base) && e.x() == x && e.y() == y && e.z() == z)) {
            save(npcName, entries);
        }
    }

    /** 回访失败计次：满 MAX_ATTEMPTS 次自动销账（防死锁）。 */
    public static synchronized void noteAttempt(String npcName, String blockPath, int x, int y, int z) {
        String base = normalize(blockPath);
        List<Entry> entries = load(npcName);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.block().equals(base) && e.x() == x && e.y() == y && e.z() == z) {
                int attempts = e.attempts() + 1;
                if (attempts >= MAX_ATTEMPTS) {
                    entries.remove(i);
                    LOG.info("[AIV][GOAL] " + npcName + " 记矿销账（回访失败 " + attempts + " 次放弃） | "
                            + base + " @ " + x + "," + y + "," + z);
                } else {
                    entries.set(i, new Entry(base, x, y, z, e.needTier(), e.seenAt(), attempts));
                    LOG.info("[AIV][GOAL] " + npcName + " 回访失败计次 " + attempts + "/" + MAX_ATTEMPTS
                            + " | " + base + " @ " + x + "," + y + "," + z);
                }
                save(npcName, entries);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 落盘

    private static File fileFor(String npcName) {
        if (dataDir == null) {
            return null;
        }
        String safe = npcName.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return new File(new File(dataDir, "ore-memory"), safe + ".json");
    }

    private static List<Entry> load(String npcName) {
        List<Entry> cached = BY_NPC.get(npcName);
        if (cached != null) {
            return cached;
        }
        List<Entry> entries = new ArrayList<>();
        if (LOADED.add(npcName)) {
            File file = fileFor(npcName);
            if (file != null && file.isFile()) {
                try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    Type type = new TypeToken<List<Entry>>() {
                    }.getType();
                    List<Entry> parsed = GSON.fromJson(reader, type);
                    if (parsed != null) {
                        entries.addAll(parsed);
                    }
                } catch (Exception e) {
                    LOG.warning("[AIV][GOAL] 记矿账本读取失败（按空账本继续） " + npcName + " | " + e.getMessage());
                }
            }
        }
        BY_NPC.put(npcName, entries);
        return entries;
    }

    private static void save(String npcName, List<Entry> entries) {
        File file = fileFor(npcName);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
        } catch (Exception e) {
            LOG.warning("[AIV][GOAL] 记矿账本写入失败 " + npcName + " | " + e.getMessage());
        }
    }
}
