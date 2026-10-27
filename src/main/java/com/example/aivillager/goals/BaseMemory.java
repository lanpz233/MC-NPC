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
* 落脚点账本：NPC 亲手建成的火柴盒小屋位置落盘
* （base-memory/&lt;NPC&gt;.json）。
*
* <p>为什么要落盘：小屋是后续一串功能的锚点——D1 床放屋里、箱子存贵重物、
* D3 死后回程认这个点。服务器重启、NPC 远行下矿后都得能找回"家在哪"，
* 不能只存内存里。世界标识公式与 OreMemory/FarmMemory 完全一致
* （fix48 口径）：换世界后旧账本自动失效，绝不串账。
*/
public final class BaseMemory {

private static final Logger LOG = Logger.getLogger("AIVillager");
private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

private static Path folder;
private static String boundWorldKey = "";

/** 一座落脚点：anchor=屋内中心地板格（脚踩的那格），builtAt=建成时刻。 */
public record Base(String world, BlockPos anchor, long builtAt) {
}

/** 落盘 DTO（BlockPos 不直接交给 Gson）。 */
private static final class BaseData {
String world = "";
int anchorX;
int anchorY;
int anchorZ;
long builtAt;
}

private BaseMemory() {
}

public static void init(java.io.File dataFolder) {
folder = dataFolder.toPath().resolve("base-memory");
try {
Files.createDirectories(folder);
} catch (IOException exception) {
LOG.warning("[AIV][BASE] 无法创建落脚点账本目录: " + exception.getMessage());
}
}

/** 记录当前世界标识；与 OreMemory/FarmMemory 同公式，见那边的注释。 */
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

/** 读本 NPC 在当前世界的落脚点；没有/串世界返回 null。 */
public static Base base(String npcName) {
if (folder == null) {
return null;
}
Path file = fileFor(npcName);
if (!Files.isRegularFile(file)) {
return null;
}
try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
BaseData data = GSON.fromJson(reader, BaseData.class);
if (data == null ||!worldKey().equals(data.world)) {
return null;
}
return new Base(data.world,
new BlockPos(data.anchorX, data.anchorY, data.anchorZ), data.builtAt);
} catch (Exception exception) {
LOG.warning("[AIV][BASE] 落脚点账本读取失败 " + npcName + ": " + exception.getMessage());
return null;
}
}

public static boolean hasBase(String npcName) {
return base(npcName)!= null;
}

/** 建成落盘。 */
public static void save(String npcName, BlockPos anchor) {
if (folder == null) {
return;
}
BaseData data = new BaseData();
data.world = worldKey();
data.anchorX = anchor.getX();
data.anchorY = anchor.getY();
data.anchorZ = anchor.getZ();
data.builtAt = System.currentTimeMillis();
try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
GSON.toJson(data, writer);
} catch (IOException exception) {
LOG.warning("[AIV][BASE] 落脚点账本保存失败 " + npcName + ": " + exception.getMessage());
}
}
}
