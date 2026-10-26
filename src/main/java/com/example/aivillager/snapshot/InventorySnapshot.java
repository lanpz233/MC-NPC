package com.example.aivillager.snapshot;

import com.example.aivillager.npc.FakePlayerBody;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
* 家底盘点（Fong 19:50 拍板）：一条日志打全 NPC 的物资现状，
* 以后问"他有什么"直接 grep [AIV][INVENTORY] 最新一条回答，不再靠
* PLAN-DEBUG / tool-use / 存量复查多处侧漏拼凑。
*
* <p>格式约定（日志详细化标准第 5 条）：分类标记固定 [AIV][INVENTORY]，
* 关键量一律 key=value；背包按工具武器/护甲/食物/矿物/材料/杂物分组，
* 工具与护甲带剩余耐久（remaining/max），穿在身上的护甲标 (worn)；
* 末尾 summary 单列关键资源（食物总营养值、皮革、生铁、铁锭、煤、钻石）。
*
* <p>触发（防刷屏）：阶段切换、约 5 分钟定时、合成/冶炼一批完成、死亡复活。
* 除复活（force）外统一受 {@link #MIN_INTERVAL_MS} 节流：一小时十几条以内。
* 物品分类与后续"背包处置/开箱搜刮"的价值规则共用，先在此定名。
*/
public final class InventorySnapshot {

private static final Logger LOG = Logger.getLogger("AIVillager");
private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
/** 非强制盘点的最小间隔：3 分钟（定时器本身 5 分钟，这里只作叠加触发的闸门）。 */
private static final long MIN_INTERVAL_MS = 180_000L;

private InventorySnapshot() {
}

public static long lastSnapshotAt(String npcName) {
return LAST.getOrDefault(npcName, 0L);
}

/**
* 打一条盘点。force=true（复活）时绕过节流——死亡/复活永不抽样（标准第 6 条）。
*
* @return 是否真的打了（被节流跳过返回 false）
*/
public static boolean log(FakePlayerBody npc, String reason, String stage, boolean force) {
long now = System.currentTimeMillis();
Long last = LAST.get(npc.npcName());
if (!force && last!= null && now - last < MIN_INTERVAL_MS) {
return false;
}
LAST.put(npc.npcName(), now);

Map<String, List<String>> groups = new LinkedHashMap<>();
groups.put("tools", new ArrayList<>());
groups.put("armor", new ArrayList<>());
groups.put("food", new ArrayList<>());
groups.put("minerals", new ArrayList<>());
groups.put("materials", new ArrayList<>());
groups.put("misc", new ArrayList<>());
Map<String, Integer> aggregated = new LinkedHashMap<>();
int foodNutrition = 0;
int leather = 0;
int rawIron = 0;
int ironIngot = 0;
int coal = 0;
int diamond = 0;

int slots = npc.getInventory().getContainerSize();
for (int slot = 0; slot < slots; slot++) {
ItemStack stack = npc.getInventory().getItem(slot);
if (stack.isEmpty()) {
continue;
}
String path = pathOf(stack);
int count = stack.getCount();
var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
if (food!= null && food.nutrition() > 0) {
foodNutrition += food.nutrition() * count;
}
switch (path) {
case "leather" -> leather += count;
case "raw_iron" -> rawIron += count;
case "iron_ingot" -> ironIngot += count;
case "coal" -> coal += count;
case "diamond" -> diamond += count;
default -> {
}
}
String category = category(path, stack);
if ("tools".equals(category) || "armor".equals(category)) {
// 工具/护甲逐件列（同种不同耐久不能合并）：名字 剩余/总耐久，穿着的标 worn。
String entry = path + durabilitySuffix(stack) + (count > 1? " x" + count: "")
+ (slot >= 36 && slot <= 39? "(worn)": "");
groups.get(category).add(entry);
} else {
aggregated.merge(category + "|" + path, count, Integer::sum);
}
}
for (Map.Entry<String, Integer> e: aggregated.entrySet()) {
String[] parts = e.getKey().split("\\|", 2);
groups.get(parts[0]).add(parts[1] + " x" + e.getValue());
}

StringBuilder line = new StringBuilder();
line.append("[AIV][INVENTORY] ").append(npc.npcName())
.append(" reason=").append(reason)
.append(" stage=").append(stage == null || stage.isEmpty()? "-": stage)
.append(String.format(Locale.ROOT, " hp=%.1f", npc.getHealth()))
.append(" food=").append(npc.getFoodData().getFoodLevel())
.append(" armor=").append(npc.getArmorValue())
.append(" pos=").append(npc.getBlockX()).append(',')
.append(npc.getBlockY()).append(',').append(npc.getBlockZ());
for (Map.Entry<String, List<String>> group: groups.entrySet()) {
line.append(" | ").append(group.getKey()).append("=[")
.append(group.getValue().isEmpty()? "-": String.join(", ", group.getValue()))
.append(']');
}
line.append(" | summary foodNutrition=").append(foodNutrition)
.append(" leather=").append(leather)
.append(" rawIron=").append(rawIron)
.append(" ironIngot=").append(ironIngot)
.append(" coal=").append(coal)
.append(" diamond=").append(diamond);
LOG.info(line.toString());
return true;
}

private static String durabilitySuffix(ItemStack stack) {
if (!stack.isDamageableItem()) {
return "";
}
return " " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage();
}

private static String pathOf(ItemStack stack) {
var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
return id == null? "": id.getPath();
}

/** 物品分类（与后续背包处置/开箱价值规则共用同一套口径）。 */
private static String category(String path, ItemStack stack) {
if (path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_axe")
|| path.endsWith("_sword") || path.endsWith("_hoe")
|| path.equals("bow") || path.equals("crossbow") || path.equals("shield")
|| path.equals("fishing_rod") || path.equals("flint_and_steel")
|| path.equals("shears") || path.equals("mace") || path.equals("trident")) {
return "tools";
}
if (path.endsWith("_helmet") || path.endsWith("_chestplate")
|| path.endsWith("_leggings") || path.endsWith("_boots")
|| path.equals("elytra") || path.equals("turtle_helmet")) {
return "armor";
}
var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
if (food!= null && food.nutrition() > 0) {
return "food";
}
if (path.equals("coal") || path.equals("raw_iron") || path.equals("iron_ingot")
|| path.equals("raw_copper") || path.equals("copper_ingot")
|| path.equals("raw_gold") || path.equals("gold_ingot")
|| path.equals("diamond") || path.equals("emerald")
|| path.equals("lapis_lazuli") || path.equals("redstone")
|| path.equals("nether_quartz") || path.equals("ancient_debris")
|| path.equals("netherite_scrap") || path.equals("netherite_ingot")
|| path.equals("amethyst_shard")) {
return "minerals";
}
if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_planks")
|| path.endsWith("_wool") || path.equals("stick") || path.equals("leather")
|| path.equals("rabbit_hide") || path.equals("string") || path.equals("bone")
|| path.equals("feather") || path.equals("cobblestone") || path.equals("stone")
|| path.equals("cobbled_deepslate") || path.equals("deepslate")
|| path.equals("dirt") || path.equals("gravel") || path.equals("sand")
|| path.equals("glass") || path.equals("charcoal") || path.equals("torch")
|| path.equals("crafting_table") || path.equals("furnace")
|| path.equals("chest") || path.equals("bucket") || path.equals("bamboo")
|| path.equals("gunpowder") || path.equals("paper") || path.equals("book")) {
return "materials";
}
return "misc";
}
}
