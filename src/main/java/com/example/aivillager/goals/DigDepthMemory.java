package com.example.aivillager.goals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
* 钻石挖掘目标层：渐进下探（Fong 2026-10-28 拍板"挖钻深度不许写死
* 一层，正常玩家都是越挖越深的"）。
*
* <p>旧口径：descend/strip 恒定去 Y=-54。Wiki 口径：钻石生成于 -64~16、越往下
* 越多、最密在 Y=-59（基岩出现前一层）。新口径：目标层是按 NPC 持久化的
* 状态，起始 -54；每次因钻石连败换区（=新开井群）下探 2 层
* （-54→-56→-58→-59），-59 封底——再往下是基岩混层、矿道本身挖不成形，
* 属物理封底；封底后换区只横移、不回浅层。进行中的井群不废弃：下探只
* 作用于换区后的下一次挖钻派单。
*
* <p>落盘口径与 LootMemory/DeathMemory 一致：{@code dig-depth/<NPC 名>.json}，
* 世界标识同公式（bindWorld 绑定），换世界从 -54 重新起步。
*/
public final class DigDepthMemory {

/** 起始层（旧恒定值）。 */
public static final int START_DEPTH = -54;
/** 封底层（Wiki 钻石最密层）。 */
public static final int FLOOR_DEPTH = -59;
/** 每次换区下探层数。 */
public static final int STEP = 2;

private static final Logger LOG = Logger.getLogger("AIVillager");
private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

private static Path folder;
private static String boundWorldKey = "";

/** 落盘 DTO。 */
private static final class DepthData {
String world = "";
int depth = START_DEPTH;
/** 已下探次数（=钻石换区序号，日志对账用）。 */
int changes = 0;
}

private DigDepthMemory() {
}

public static void init(java.io.File dataFolder) {
folder = dataFolder.toPath().resolve("dig-depth");
try {
Files.createDirectories(folder);
} catch (IOException exception) {
LOG.warning("[AIV][DIGDEPTH] 无法创建深度账本目录: " + exception.getMessage());
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

/** 当前钻石目标层。无存档/换世界 = 起始层 -54。 */
public static int depth(String npcName) {
DepthData d = readCurrent(npcName);
return d == null? START_DEPTH: clamp(d.depth);
}

/**
* 执行一次下探并落盘，返回 {from, to, changes}。
* 已封底时 from==to==-59、changes 不再增长，调用方据此打"深度维持"日志。
*/
public static int[] deepen(String npcName) {
DepthData d = readCurrent(npcName);
if (d == null) {
d = new DepthData();
d.world = worldKey();
}
int from = clamp(d.depth);
int to = Math.max(FLOOR_DEPTH, from - STEP);
if (to!= from) {
d.depth = to;
d.changes = Math.max(0, d.changes) + 1;
writeData(npcName, d);
}
return new int[]{from, to, Math.max(0, d.changes)};
}

private static int clamp(int depth) {
return Math.max(FLOOR_DEPTH, Math.min(START_DEPTH, depth));
}

/** 读存档并校验世界：世界不符视为无账（新世界重新起步）。 */
private static DepthData readCurrent(String npcName) {
if (folder == null) {
return null;
}
Path file = fileFor(npcName);
if (!Files.isRegularFile(file)) {
return null;
}
try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
DepthData data = GSON.fromJson(reader, DepthData.class);
if (data == null ||!worldKey().equals(data.world)) {
return null;
}
return data;
} catch (Exception exception) {
LOG.warning("[AIV][DIGDEPTH] 深度账本读取失败 " + npcName + ": " + exception.getMessage());
return null;
}
}

private static void writeData(String npcName, DepthData data) {
try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
GSON.toJson(data, writer);
} catch (IOException exception) {
LOG.warning("[AIV][DIGDEPTH] 深度账本保存失败 " + npcName + ": " + exception.getMessage());
}
}
}
