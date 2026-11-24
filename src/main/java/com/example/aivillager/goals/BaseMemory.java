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

/** 一座落脚点：anchor=屋内中心地板格（脚踩的那格），builtAt=建成时刻，
 * bedPos=床脚格（fix56·D1，安置后才有，未安置为 null），
 * chestPos=箱子格（fix69，安置后才有，未安置为 null）。 */
public record Base(String world, BlockPos anchor, long builtAt, BlockPos bedPos,
BlockPos chestPos) {
}

/** 落盘 DTO（BlockPos 不直接交给 Gson）。 */
private static final class BaseData {
String world = "";
int anchorX;
int anchorY;
int anchorZ;
long builtAt;
/** 床脚格（fix56）：hasBed=false 时三个坐标无意义。 */
boolean hasBed;
int bedX;
int bedY;
int bedZ;
/** 箱子格（fix69）：hasChest=false 时三个坐标无意义；旧账本无此字段默认 false。 */
boolean hasChest;
int chestX;
int chestY;
int chestZ;
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
new BlockPos(data.anchorX, data.anchorY, data.anchorZ), data.builtAt,
data.hasBed ? new BlockPos(data.bedX, data.bedY, data.bedZ) : null,
data.hasChest ? new BlockPos(data.chestX, data.chestY, data.chestZ) : null);
} catch (Exception exception) {
LOG.warning("[AIV][BASE] 落脚点账本读取失败 " + npcName + ": " + exception.getMessage());
return null;
}
}

public static boolean hasBase(String npcName) {
return base(npcName)!= null;
}

/** 建成落盘。已安置过床时保留床字段（防重存把床账冲掉）。 */
public static void save(String npcName, BlockPos anchor) {
if (folder == null) {
return;
}
BaseData data = readData(npcName);
if (data == null ||!worldKey().equals(data.world)) {
data = new BaseData();
}
data.world = worldKey();
data.anchorX = anchor.getX();
data.anchorY = anchor.getY();
data.anchorZ = anchor.getZ();
data.builtAt = System.currentTimeMillis();
writeData(npcName, data);
}

/** 安置床后落盘（fix56）：只更新床字段，锚点/世界沿用现有记录。 */
public static void saveBed(String npcName, BlockPos bedFoot) {
if (folder == null) {
return;
}
BaseData data = readData(npcName);
if (data == null ||!worldKey().equals(data.world)) {
LOG.warning("[AIV][BASE] 安置床落盘失败：无本世界落脚点记录 " + npcName);
return;
}
data.hasBed = true;
data.bedX = bedFoot.getX();
data.bedY = bedFoot.getY();
data.bedZ = bedFoot.getZ();
writeData(npcName, data);
}

/** 床已不在时失效床账本（fix99）：farm59 实证床被自救挖掘挖掉后账本仍
 * 记着旧床位，tickSleep 对空气格 startSleepInBed 抛 IAE 卡死刷屏。
 * 只清 hasBed，锚点/箱子/世界沿用现有记录；床多半已回包，
 * 规划层凭 bedPos==null + 包里有床会重走 place_bed 安置。 */
public static void clearBed(String npcName) {
if (folder == null) {
return;
}
BaseData data = readData(npcName);
if (data == null ||!worldKey().equals(data.world)) {
LOG.warning("[AIV][BASE] 失效床账本失败：无本世界落脚点记录 " + npcName);
return;
}
data.hasBed = false;
writeData(npcName, data);
LOG.info("[AIV][BASE] 床账本已失效（床已不在） " + npcName);
}

/**
 * 【fix265】床账重验（三处读者共用：复活判读/睡觉/安置床）：把「验真→自愈→
 * 才失效」收敛成一个入口，取代旧版各读者单次读到非床就直接 clearBed 的
 * 口径——116 棒定谳：farm276 一笔 state=air 单次判读即清账、其后两 boot
 * 无人重建、两次死亡白白回落世界出生点（58/59 验）。流程：账本床格强制
 * 加载区块后验真，在→原样返回；不在（被拆/坏读数/账本已清）→扫锚点近傍
 * 现成床（fix174 扫描口径自 place_bed 前置到判定层）重新登记；扫不到且
 * 账本原有床才失效账本。返回有效床脚格，无床返回 null。
 */
public static BlockPos revalidateBed(ServerLevel level, String npcName) {
bindWorld(level);
Base base = base(npcName);
if (base == null) {
return null;
}
BlockPos bed = base.bedPos();
if (bed != null) {
if (!level.hasChunkAt(bed)) {
level.getChunk(bed);
}
if (level.getBlockState(bed).getBlock()
instanceof net.minecraft.world.level.block.BedBlock) {
return bed;
}
}
BlockPos anchor = base.anchor();
if (!level.hasChunkAt(anchor)) {
level.getChunk(anchor);
}
BlockPos foundFoot = null;
scan:
for (int dx = -3; dx <= 3; dx++) {
for (int dz = -3; dz <= 3; dz++) {
for (int dy = -1; dy <= 1; dy++) {
BlockPos cell = anchor.offset(dx, dy, dz);
net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cell);
if (!(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
continue;
}
foundFoot = state.getValue(net.minecraft.world.level.block.BedBlock.PART)
== net.minecraft.world.level.block.state.properties.BedPart.FOOT
? cell : cell.relative(state.getValue(
net.minecraft.world.level.block.BedBlock.FACING).getOpposite());
break scan;
}
}
}
if (foundFoot != null) {
if (!foundFoot.equals(bed)) {
saveBed(npcName, foundFoot);
LOG.info("[AIV][BASE] 床账重验：现成床重新登记（fix265） " + npcName
+ " | bed=" + foundFoot.toShortString());
}
return foundFoot;
}
if (bed != null) {
// 【fix276】失效诊断（128 棒：fresh144 的失效行当时只留一行结论、
// 跨 boot 回溯才拼出全貌）：清账前把账本床格的实读方块与扫描口径
// 一并留痕，下次失效不必再靠多 boot 日志拼图。原失效行口径不动。
LOG.info("[AIV][BASE] 床账失效诊断（fix276） " + npcName
+ " | 账本床格=" + bed.toShortString()
+ " 实读=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
.getKey(level.getBlockState(bed).getBlock())
+ " 锚点=" + anchor.toShortString() + " 扫描=锚点±3/dy±1 命中=0");
clearBed(npcName);
}
return null;
}

/** 安置箱子后落盘（fix69）：只更新箱子字段，锚点/床/世界沿用现有记录。 */
public static void saveChest(String npcName, BlockPos chestPos) {
if (folder == null) {
return;
}
BaseData data = readData(npcName);
if (data == null ||!worldKey().equals(data.world)) {
LOG.warning("[AIV][BASE] 安置箱子落盘失败：无本世界落脚点记录 " + npcName);
return;
}
data.hasChest = true;
data.chestX = chestPos.getX();
data.chestY = chestPos.getY();
data.chestZ = chestPos.getZ();
writeData(npcName, data);
}

private static BaseData readData(String npcName) {
Path file = fileFor(npcName);
if (!Files.isRegularFile(file)) {
return null;
}
try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
return GSON.fromJson(reader, BaseData.class);
} catch (Exception exception) {
LOG.warning("[AIV][BASE] 落脚点账本读取失败 " + npcName + ": " + exception.getMessage());
return null;
}
}

private static void writeData(String npcName, BaseData data) {
try (Writer writer = Files.newBufferedWriter(fileFor(npcName), StandardCharsets.UTF_8)) {
GSON.toJson(data, writer);
} catch (IOException exception) {
LOG.warning("[AIV][BASE] 落脚点账本保存失败 " + npcName + ": " + exception.getMessage());
}
}
}
