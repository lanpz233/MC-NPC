package com.example.aivillager.goals;

import com.example.aivillager.AiVillagerPlugin;
import com.example.aivillager.intent.Intent;
import com.example.aivillager.npc.FakePlayerBody;
import com.example.aivillager.pathfinding.PathFinder;
import com.example.aivillager.skills.SkillRunner;
import com.example.aivillager.snapshot.InventorySnapshot;
import com.example.aivillager.survival.SurvivalController;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 目标层：把「像玩家一样发展」拆成一个个中期目标，由这里确定性执行。
 *
 * <p>为什么需要它：原来的自主决策每 20 秒问一次模型“现在做什么”，模型只能回一个原子动作
 * （走一步/挖一个方块），没法表达“砍 16 个木头→做木板→做木镐→挖石头→做石镐”这种多步计划。
 * 现在模型只负责<b>定目标</b>，执行、进度统计、失败重试全部在这里做，目标完成后再回到模型。
 *
 * <p>线程约定：所有方法都在主线程（周期任务/Brain 回调），不跨线程访问世界。
 */
public final class GoalExecutor {

    private static final Logger LOG = Logger.getLogger("AIVillager");

    /** 玩家背包主背包槽位数（0-8 快捷栏 + 9-35 主背包）；护甲槽不参与材料统计。 */
    private static final int MAIN_SLOTS = 36;
    /**
     * "数家底"要扫的槽位总数：0-35 主背包 + 36-39 装备槽 + 40 副手。
     * 只用于计数/判定（countItem/countPlanks/countItemsEndingWith/countItemFor）：
     * 穿在身上的甲必须算"有"，否则合成目标会把已穿的头盔再做一遍（第三轮修复 B）。
     * 真正动手存取的循环（consume/give/裁剪）仍只走 MAIN_SLOTS，绝不扣穿着的装备。
     */
    private static final int COUNT_SLOTS = 41;
    /** 采集时单次搜索半径；技能层内部还会再限制到 8 格。 */
    private static final int GATHER_RADIUS = 8;
    /** 连续多少次采集没有进度就判定这个目标做不到。 */
    private static final int MAX_STALE_CHECKS = 8;
    /** 连续多少次找不到目标方块就放弃（中间会先随机走走换位置）。 */
    private static final int MAX_GATHER_FAILS = 6;
    /** 盖一个 3x3 小屋需要的方块数：两层墙 16 + 屋顶 9。 */
    private static final int SHELTER_BLOCKS = 25;

    // ---- 目标总超时看门狗（第四轮修复 A）----
    // 第三轮长跑实测：挖铁目标被游泳层反复顶掉技能后既不完成也不放弃，
    // 规划器被冻结 31 分钟。这里给每个目标装两个上限：无进展超时（主判据）
    // 与总时长硬顶（兜底）。阈值按 tick（20/秒），进展判据见 observeGoalProgress。
    /** 无进展超时·默认：5 分钟没有任何目标相关进展就放弃。 */
    private static final int GOAL_NO_PROGRESS_TICKS_DEFAULT = 6_000;
    /** 无进展超时·挖矿：6 分钟。挖矿单步慢，但位置和产出应该持续变化。 */
    private static final int GOAL_NO_PROGRESS_TICKS_DIG = 7_200;
    /** 无进展超时·长目标：8 分钟。熔炼等待、长距离行进/屠龙用。 */
    private static final int GOAL_NO_PROGRESS_TICKS_LONG = 9_600;
    /** 总时长硬顶·默认：30 分钟。哪怕一直有零星进展也不许无限占着大脑。 */
    private static final int GOAL_HARD_CAP_TICKS_DEFAULT = 36_000;
    /** 总时长硬顶·挖矿：45 分钟。大批量挖矿（如钻石甲 24 颗料）合法耗时更长。 */
    private static final int GOAL_HARD_CAP_TICKS_DIG = 54_000;

    private static final String OK = "ok";

    private final AiVillagerPlugin plugin;
    private final FakePlayerBody npc;
    private final SkillRunner skills;
    private BukkitTask task;

    private boolean active;
    private String type = "-";
    private String targetId = "-";
    /** 【fix121】派单来源阶段名（规划层写入 intent 的 stage 参数；模型/手动派单为空）。入夜收工认来源豁免用。 */
    private String goalStage = "";
    /**
     * 目标开始时所处的维度。
     * 传送门会把 NPC 送到另一个维度，旧目标（挖矿/采集）在新维度里已经不成立，
     * 必须立即结束，让规划器按新维度重新排步骤。
     */
    private String goalDimension = "";
    private int targetCount;
    private int attempts;
    /** 【fix74】fillbucket 技能完整跑完一轮仍没装到水的次数。attempts 在派发成功时清零、
     * 只数派发被拒，数不到这种失败——farm33 实证无限重派白站 300 秒。 */
    private int fillFailedRuns;
    private boolean fillSkillActive;
    private int outcomeCount;
    private int lastProgress = -1;
    /**
     * gather 开始那一刻背包里已有的数量。
     * 进度只算"本轮新采到多少"，否则背包里本来就有 15 个竹子时，
     * 「采集 8 个」会在第一秒宣布完成，模型又下一遍同样的目标，NPC 看着就像站着不动。
     */
    private int baselineCount;
    /** 【第二十三轮】目标起止（供结束行打耗时与位移，日志标准第 2 条）。 */
    private long goalStartedMillis;
    private int goalStartX;
    private int goalStartY;
    private int goalStartZ;
    private int staleChecks;
    /** 【fix101】目标层镐子兜底放行日志每目标只打一次（tickDig 每 tick 都会路过）。 */
    private boolean digPickFallbackLogged;
    // 找资源扩围的方向惯性（fix18）：跨目标保留的航向/档位/锚点，故意不随
    // 目标重下而重置——旧逻辑每个新目标 attempts 归零、距离永远从 8 格起步，
    // 且每次都重挑"地表最低方向"，会在盆地里绕圈打转（长跑 #5 实测：
    // 无树平原开局 25 分钟、采集放弃 7 次、净位移个位数格，零原木）。
    // 改为：一个方向走得通就保持航向逐档走远；走不通（位移 <8 格）转 45°。
    private double gatherSearchAngle = -1;
    private int gatherSearchTier = 0;
    private BlockPos gatherSearchAnchor;
    /** 【第四十六轮】扩围扇区记账：8 个罗盘扇区各派过几跳 + 本轮盲跳计数，
     * 给 escalateSearch 的罗盘收敛用（恶地局盆地空转 40 分钟的对症药）。 */
    private final int[] gatherSectorHops = new int[8];
    private int gatherEscalateHops;
    /** dryland 连续失败次数：失败 3 次后跳过，避免无限重试泡在水里 20 秒又 20 秒。 */
    private int drylandFailures;
    private boolean exploreStarted;
    /**
     * 上一条矿道挖空了（一块矿没出，或被悬空/流体堵死）：下一轮掘进前先挪一段距离。
     * 在刚挖空的区域原地再下发 strip 只会重复同一条走廊
     * （实测 105,19,61 ↔ 112,19,61 往返 7 轮，步数凑满 24 但一块矿都没有）。
     */
    private boolean relocateBeforeStrip;
    /** 【第四十七轮】挖石头时 strip 连败计数：沼泽式地表无石时改下挖取石的触发量。 */
    /** 【fix104】strip 结局水位线（序号制，见 handledStripSeq 注释）。 */
    private int stoneStripFails;
    /** 【fix104】strip/浇铸结局水位线改序号制：旧字符串水位会被同文案
     * 结局击穿（同地点同死因逐字相同、第二份起被当已消费吞掉）。 */
    private int handledStripSeq = -1;
    private int handledObsidianSeq = -1;
    /** 【fix100】浇铸目标内 descend 连续失败计数：攒满 3 次即收场交规划层补镐。 */
    private int obsidianDescendFails;
    /** 【fix142】dig/revisit 目标内 descend「无实际下降」结局的序号水位线与
     * 连败计数：坡面幻影步（步数涨、精确 Y 零下降）旧版无人消费结局，dig 靠
     * stale 计数约 30 分钟才收场、revisit 靠 attempts 与看门狗磨满超时。 */
    private int handledDigDescendSeq = -1;
    private int digDescendNoDropFails;
    /** 【fix282】moveToStand 竖直对齐的 descend 结局消费水位与无下降连败：
     * descend 以「无实际下降」收场后，旧版调用方下一 tick 原样重派同一
     * descend（fresh151 柱台顶 79→81→80→79 振荡十二分钟净零、克隆 130 棒
     * 91 秒/派发悬挂空转实证）。改为无下降后先 goto 侧移换列再续 descend
     * （玩家换个位置再挖），侧移最多两次、之后照旧由调用方兜底口径收口。 */
    private int handledMtsDescendSeq = -1;
    private int mtsDescendDispatchSeq = -1;
    private int mtsNoDropFails;
    private BlockPos mtsStandKey;
    private boolean mtsSidestepPending;
    /** 【fix284】moveToStand descend 派发登记（忙位守卫用）：fix282 的结局
     * 消费等 descend 自收，而台阶慢步形态下步数以约 30 秒/步假进展慢增、
     * 两条自收死线都被无限推迟（farm309 段 3 实证：95 秒 steps 0→2、净降
     * 为负，目标先被 fix134 收走、结局永不到达消费面）。守卫在运行期按
     * 墙钟＋精确净降干预；登记只认自家派发——派发水位/起始精确 Y/时刻/
     * 目标 type/日志 tag 五项一并记，守卫五项全对上才许停技能，防 mts 的
     * descend 被静默顶掉后误停他家 descend（dig/revisit 各有消费面）。 */
    private long mtsDescendStartTick = -1L;
    private double mtsDescendStartY = Double.NaN;
    private String mtsDescendGoalType;
    private String mtsDescendTag;
    /** 【fix285】moveToStand 自家 travel 的爆表结局消费（133 棒定谳）：
     * 基址口袋地形里 travel 的 A* 深扫确定性爆表（「长途行进受阻：超出
     * 时间预算 100ms／节点上限」约 6 秒一轮，129/130 棒同签名），旧版
     * 无人消费该结局——moveToStand 只按几何（distH²>36 或 stuck≥6）
     * 恒选 travel、goto 永不重试，fresh161 睡觉接近实证同站位连爆约
     * 35 次零位移直到目标放弃。改为登记自家 travel 派发（水位/起点/
     * 站位），爆表且净位移 <2 格计数；同站位连爆 ≥2 次后本站位改派
     * goto（本地寻路、与 travel 不同引擎），换走法不换目标。 */
    private int handledMtsTravelSeq = -1;
    private int mtsTravelDispatchSeq = -1;
    /** 【fix320】分级守卫让位接棒守卫已处理的结局序号（防同一让位重复补镐）。 */
    private int handledTierYieldSeq = -1;
    private double mtsTravelStartX = Double.NaN;
    private double mtsTravelStartZ = Double.NaN;
    private int mtsTravelBlowups;
    /** 【fix287】moveToStand 自家 ascend 的净升记账＋无上升侧移（135 棒
     * 定谳，照 fix282 descend 消费范式对称）：ascend 结局此前无人消费——
     * moveToStand 派出的 ascend 以 timeout/步数=0 收场（单步悬置烧满
     * 120 秒看门狗、净升可为负）后，下一 tick 按同几何原样重派，睡觉
     * 接近实证连环十余派（fresh161/164）。改为登记自家派发（水位/起始
     * 精确 Y，随站位 key 绑定清零），结局按精确净升结算：到达类清零、
     * 净升 <1.0 记无上升连败；连败 1..2 时下一派前先 goto 侧移换列
     * （与 descend 共用 findMtsSidestepCell，pending 旗独立），其后回落
     * 调用方既有兜底（275a/285a 尝试结算）。fix245 死带 ascend 不登记：
     * 其派发受调用方连卡计数节制，技能内病型由 fix287a/287b 覆盖。 */
    private int handledMtsAscendSeq = -1;
    private int mtsAscendDispatchSeq = -1;
    private double mtsAscendStartY = Double.NaN;
    /** 【fix319】自家 ascend 的日志 tag（竖直对齐闭环守卫收场行用，照
     * descend 的 mtsDescendTag 对称补齐；fix287 登记原本不存 tag）。 */
    private String mtsAscendTag;
    private int mtsNoRiseFails;
    private boolean mtsAscendSidestepPending;
    private int handledRevisitDescendSeq = -1;
    private int revisitDescendNoDropFails;
    /** 【fix144】过境记矿扫描节流（travel/revisit 共用，约 20 秒一次）。 */
    private int transitOreScanCooldown;
    /** 【fix111】找岩浆走廊方向（弧度，NaN=本目标未定）：随机换位互相
     * 抵消（farm73 实证整轮净位移约 2 格），改每目标定一个方向沿走廊
     * 直进、新目标重新定方向（等效换区）。 */
    private double lavaSearchDir = Double.NaN;
    /** 【fix124】黑曜石换区（回地表换位再下井）状态：0=不在换区，
     * 1=ascend 回地表中，2=地面 goto 换位中。井底是封闭口袋时走廊 goto
     * 目标全「不可站立」、直线兜底零位移（farm87 实证 518 秒净位移约
     * 15 格判败）——地下没有路时唯一的玩家解法是回地表换个地方下井。
     * 每个黑曜石目标只换一次，换位后仍找不到才诚实判败。 */
    private int obsidianRelocPhase;
    private boolean obsidianRelocated;
    private int obsidianRelocAscendTries;
    private int obsidianRelocGotoTries;
    private double obsidianRelocFromX;
    private double obsidianRelocFromZ;
    /** 【fix112】坏岩浆提示点（本目标内）：目标层扫描只认岩浆源方块、
     * 不问露面与落脚，技能层到场才发现挖不了/站不住（farm74 实证同一
     * 坏点连喂 6 次、3 秒烧光两路连败计数）。技能以「未挖到/没有落脚点」
     * 失败时把派单提示点拉黑，扫描跳过它换下一个源；有进账清账。 */
    private final java.util.Set<BlockPos> badLavaHints = new java.util.HashSet<>();
    private BlockPos lastObsidianHint;
    /** 【fix105】黑曜石路线：false=挖矿主路（mineobsidian），true=浇铸兜底（castobsidian）。 */
    private boolean obsidianRouteCasting;
    private int obsidianRouteFails;
    private boolean obsidianRouteLogged;
    // 【fix195】岩浆账本旅途段：本目标选定的已知岩浆点、是否已消费账本
    // （抵近/无账/放弃均算消费）、goto 真实派发次数、到点落空是否已记失败。
    private BlockPos obsidianLavaTarget;
    private boolean obsidianLavaTripDone;
    /** 【fix212】本目标是否真抵达过账本点（选点 ≤20 格或旅途 ≤16 格）：
     * 销账的资格凭据——抵达且整轮零产出才记账本失败，未抵达不记
     * （fix195d 旅途放弃不销账的口径靠它与「结束位置漂移」解耦）。 */
    private boolean obsidianLavaArrived;
    /** 【fix241】本目标已选过的账本点：到点落空换点时排除这些点，
     * 账本点全部试完才许收场（78 棒实证：首点落空销账后即收工，
     * 账本里的深点簇从未被访）。 */
    private final java.util.Set<BlockPos> obsidianLavaTried = new java.util.HashSet<>();
    /** 【fix213】旅途下地子段是否已执行（一次性）：高处出发先 descend
     * 到地面再派水平跳，只试一次，成败都交回原派跳/放弃口径。 */
    private boolean obsidianLavaGroundDone;
    /** 【fix214】旅途绕行级数：正射线一跳无推进即逐级偏角
     * （+45°/−45°/+90°/−90°），有推进清零回正射线。 */
    private int obsidianLavaDetour;
    /** 【fix215·备修未部署】descend 礼让待交接：礼让结局一到即置位，
     * tickObsidian 消费后直接 finish 交规划层补镐，不再原地重派 descend
     * 空转（farm189 实证 290+ 次 ~1 秒节律重派、靠看门狗 300 秒才收场）。 */
    private boolean obsidianPickYieldPending;
    private int obsidianLavaGotoTries;
    private boolean obsidianLavaStruck;
    private BlockPos lastLavaLedgerPos;
    // 【fix195b】旅途段结算状态：启程时刻、当前跳起点、是否有跳在飞。
    private long obsidianLavaTripStartMillis;
    private double obsidianLavaHopX;
    private double obsidianLavaHopZ;
    private boolean obsidianLavaHopInFlight;
    /** 16 格探矿扫描的节流计数：全扫一次约 1.4 万格，只在技能空闲的决策点扫、且至少隔 4 次决策。 */
    private int oreScanCooldown;
    /** 上次探矿扫描选中的目标格：单格复核（方块还在、仍然裸露）通过就直接复用，省掉重复全扫。 */
    private BlockPos oreScanCachePos;
    private String oreScanCacheBlock = "";
    /** 上次探矿扫描发现的裸露候选矿总数（统计口径：ore-scan 日志里的 found）。 */
    private int lastOreScanFound;
    /** 【fix302】探矿跳过表：mine verdict「候选全部不可达」的目标格，60 秒内不再入选。
     *  key=目标格，value=解禁时刻（System.currentTimeMillis）。上限 8 格，超限删最旧。
     *  只记 mine 真试过且全灭的目标——扫描层自己不判可达，可达性 verdict 只来自 mine。 */
    private final java.util.LinkedHashMap<BlockPos, Long> oreScanSkipUntil = new java.util.LinkedHashMap<>();
    private String status = "无目标";
    private String lastOutcome = "-";
    /** 挖掘类目标的资源定义（方块 ID、掉落物、目标深度）；其他目标为 null。 */
    private OreTarget oreTarget;
    /** 【fix40】记矿回访目标坐标（revisit 目标专用）。 */
    private int revisitX;
    private int revisitY;
    private int revisitZ;
    /** 熔炼时放下的熔炉位置；null 表示还没放置。 */
    private BlockPos furnacePos;
    /**
     * 【第四十一轮·工作站回收】自己亲手放下的那台熔炉的位置（跨目标保留）。
     * 复用世界里找到的熔炉不登记——只收自己放的（Fong 04:11 规格：工作台/
     * 熔炉随身带、用完挖回背包）。冶炼收尾 drainFurnace 后按此位拆炉回收。
     */
    private BlockPos placedFurnacePos;
    private int smeltTicks;
    private int smeltBaseline;
    // 【fix197】冶炼停滞探测计数（无燃料/无原料且炉冷时累计，见 tickSmelt）。
    private int smeltStallTicks;
    /** 狩猎目标开始时的肉类数量，进度只看本轮新打到的。 */
    private int huntBaseline;
    private int huntAttempts;
    /** 【第十五轮】连续整轮狩猎失败次数 + 远走找猎的固定航向：跨目标保留，
     * 让找吃的搬迁距离逐轮放大、方向不回头（治无动物区原地小步打转饿死循环）。 */
    private int huntFailStreak;
    private double huntHeading = Double.NaN;
    /** 【fix267】有效扫描记账：收场判定只数「距上个已计扫描锚 ≥16 格、或其间
     * 有战利品增量」的无获扫描。旧账法按 huntAttempts 派单次数一刀切——坑洼
     * 地形里 goto 10 秒级中断、单跳净位移 3 格也烧掉一次额度，hunt 在真正
     * 离开贫瘠区之前就被判放弃（118 棒实证：8 次/87.5 秒/净位移仅 22 格，
     * 而基址最近可猎动物在约 118 格外）。 */
    private int huntScansCounted;
    private double huntScanAnchorX = Double.NaN;
    private double huntScanAnchorZ = Double.NaN;
    private int huntGainAtAnchor = -1;
    /** 【第二十二轮】存量驱动狩猎：need=leather/food 时按背包存量收工，不数击杀。 */
    private String huntNeed = "";
    private int huntStockTarget;
    private int lastHuntStock = -1;
    /** 【fix346】羊群锚点：羊毛 hunt 杀羊得毛时记下的杀羊位（boot 内有效，见 tickHunt）。
     * 下次羊毛 hunt 派发时先赴锚点再本地扫描——盲搜在羊群离基址 60–90 格时 4/5 次
     * 120 秒 0 获（628373 boot 实证）；锚点处 8 次扫描零增即清除。 */
    private BlockPos flockAnchor;
    /** 本轮 hunt 是否已抵达羊群锚点（10 格内算抵达）。 */
    private boolean flockAnchorReached;
    /** 本轮赴锚点开始时刻（毫秒），60 秒不到作废落回盲搜。 */
    private long flockAnchorSince;
    /** 本轮赴锚点 goto 连续派发失败次数（v2）：陆上连续 3 次派不上即判锚点不可达、清锚点落回盲搜。 */
    private int flockAnchorGotoFails;
    /** 本轮羊毛 hunt 开始时的羊毛总量（全部颜色合计），用于捕捉杀羊信号：
     * bedWoolStock 只取单色最大值，杀黑羊（白为最大色时）不涨存量、锚点记不上。 */
    private int lastWoolTotal = -1;
    // ---- 种田（第四十九轮 fix49，D11）----
    /** 田块目标格数：一块小田 8 格，够验证循环种植、也不贪大。 */
    private static final int FARM_PLOT_CELLS = 8;
    /** 【fix290·138 棒】扩田目标格数：补格（fix262/264/270）与 tend 缺格触发
     * 以此数为目标——8 格田每 tend 仅产 2 面包（10 营养），产量≈消耗、
     * 营养恒 0（新图 fresh177 实证）。24 格时每 tend 约 6–8 面包
     * （30–40 营养），才有净积累。FARM_PLOT_CELLS=8 保留给开田攒种
     * 门槛与选址簇下限（新田仍以 8 格起步）；补格找不到合格候选时
     * 自然只补到可行格数、不硬扩。生产侧地形上限约 16 格（填水格 8），
     * 目标 24 在生产侧自然封顶、无负面影响。 */
    public static final int FARM_TARGET_CELLS = 24;
    /** 留种地板：小麦种子背包存量低于此数不许再扩种（收获补种不受此限，见 canPlantCrop）。 */
    private static final int FARM_SEED_FLOOR = 4;
    /** 胡萝卜/土豆留苗地板：背包+在田总量低于此数时不许吃、不许烤（生存层守卫同口径）。 */
    private static final int FARM_STOCK_FLOOR = 8;
    /** farm 目标模式（establish/tend/auto）与阶段机。 */
    private String farmMode = "";
    private int farmPhase;
    /** 待办动作队列：锄/种/收/骨粉逐个执行（动作本身是瞬时直改，移动靠 goto 技能）。 */
    private final java.util.List<FarmAction> farmActions = new java.util.ArrayList<>();
    /** 选址结果（土格清单）；null 表示还没选到址。 */
    private java.util.List<BlockPos> farmSiteCells;
    private int farmSiteAttempts;
    /** 【fix168】攒种子结局回读：已消费的技能结局序号 / 连续失败收场数 / 是否在等自家 mine 收场。 */
    private int farmGatherSeq = -1;
    private int farmGatherNoGain;
    private boolean farmGatherAwaiting;
    /** 【fix168】上次派 mine 时的种子存量：收场时涨了才算真进账、清 attempts。 */
    private int farmGatherStockMark;
    /** 同一动作连续走不到位的次数：超限丢弃该动作，防一块田卡死整个目标。 */
    private int farmActionStuck;
    /** 【fix60】本目标内被跳过的动作数：tend 全军覆没要如实报失败，不许冒充完成。 */
    private int farmSkipped;
    /**
     * 【fix246】本目标内被执行前复核判失效、静默丢弃的动作数。88 棒新图实证：
     * 圆石压住田格上方，播种动作每轮排队又每轮被复核丢弃——旧版不计数、
     * 不打日志，收尾 farmSkipped=0 冒充「完成」，规划层零退避 2.7 秒重派
     * 169 轮。丢弃必须留痕计数并计入失败口径（见 tickFarmTend 收尾）。
     */
    private int farmDropped;
    /** 【fix246】本目标内真生效的动作数（锄/种/收/骨粉实际改了世界才算）：收尾零生效零收获 = 空转，判失败退避。 */
    private int farmEffected;
    /** 【fix307】本轮取土源干/湿计数（-1=本轮未算源表）：补格评估行留痕，生产验证用。 */
    private int farmLastSoilDry = -1;
    private int farmLastSoilWet = -1;
    private String farmLastActionKey = "";
    /** 【fix271】站位候选表（按动作 key 缓存）：放宽站位面与换站位出口用。 */
    private String farmStandKey = "";
    private java.util.List<BlockPos> farmStandList = java.util.List.of();
    /** 【fix271】本动作接近已达的最近水平距（进度记账）：改善 ≥1 格清 stuck。 */
    private double farmApproachBestDist = Double.MAX_VALUE;
    /** 【fix273】死 goto 守望首见 tick（-1=未见）：游泳 swimTo 静默清回调后 goto 永不自收。 */
    private long farmDeadGotoSinceTick = -1L;
    /** 【fix273】本动作落水扰动记账（按动作 key，换动作清零）：第一次落水即轮换站位。 */
    private String farmWaterStrikeKey = "";
    private int farmWaterStrikes;
    /** 【fix275】回田相（phase 0）接近尝试计数：一次派发（sortie）结束仍未到达即 +1，
     *  到达/目标复位清零。用于到达站位轮换、moveToStand 连卡口径与诚实收场上限。 */
    private int farmPhase0Attempts;
    /** 【fix275】回田相已派出一趟接近技能、尚未结算（回 idle 时结算进尝试计数）。 */
    private boolean farmPhase0Sortie;
    /** 【fix294b】回田相分段走途经点：非 null 表示正在分段，null 表示未分段。 */
    private BlockPos farmPhase0Stage;
    /** 【fix294b】当前分段失败次数（派发被拒/到点未达）：≥3 放弃本段。 */
    private int farmPhase0StageFails;
    /** 【fix294b】本 tend 已用分段次数（上限 2）。 */
    private int farmPhase0Stagings;
    /** 【fix320】goto 失败记忆（逐动作接近）：上一个 goto 以「直线行走放弃」
     * 收场的动作 key——本动作内不再原样重派 goto，直落 moveToStand（travel 搭桥／
     * ascend 爬升）换法；有进展（farmActionStuck==0）或换动作时清死，goto 带重开。 */
    private String farmGotoDeadKey;
    private int farmGotoFailSeq = -1;
    /** 【fix323】守卫受阻轮换（逐动作接近）：本站位连续被农田守卫挡住
     * （身体层救援跳过侧挖，farmGuardRescueSkips 递增）的次数——不随
     * 微进展清零（farmActionStuck 会被 travel 搭桥的位移反复清零，
     * 202 棒 400 秒原地打转即因此）。满 2 即换站位（rotations＋1）；
     * 换动作清零。 */
    private String farmGuardBlockKey = "";
    private int farmGuardBlockStreak;
    private int farmGuardRotations;
    private int farmGuardSkipSeq = -1;
    /** 【fix327】(C) 守卫受阻站位记死（逐动作接近）：轮换时记死上次派发的站位，
     * 下轮候选表过滤记死站位、不再原样重派（旧版下标顶满时两腿目标完全相同、
     * 换了等于没换——203 生产实证）；全部记死时不再派发、由上层 stuck 计数
     * 走满后按既有口径诚实跳过该动作。换动作清零。 */
    private BlockPos farmGuardLastStand;
    private final java.util.Set<BlockPos> farmGuardDeadStands = new java.util.HashSet<>();
    private String farmGuardAllDeadKey = "";
    /** 【fix320】goto 失败记忆（回田相 phase 0）：上一个 goto 以「直线行走放弃」
     * 收场的回田站位——本站位内不再原样重派 goto，直落 moveToStand；站位变或
     * attempts==0 时清死。 */
    private BlockPos farmPhase0GotoDeadStand;
    /** 本访已收获的土格（拾取阶段要挨个走过田面收掉落物）。 */
    private final java.util.List<BlockPos> farmHarvestedSoils = new java.util.ArrayList<>();
    private int farmPickupIndex;
    /** 骨粉阶段是否已排过队（维护访内只催一轮，防骨粉烧光）。 */
    private boolean farmBonemealQueued;
    /** 本目标内动作队列是否已构建（establish 的锄种队列 / tend 的补种队列各建一次）。 */
    private boolean farmQueueBuilt;
    /**
     * 【fix262】本访补格扩田选中的候选格：tend 收尾落盘时按活耕地追加登记
     * （见 tickFarmTend phase 5）。113 棒定谳：注册格集只减不增——tend 收尾
     * 对土格离开可耕集的格静默销账，部分格损无任何恢复路径，田 7→6→4→3
     * 一路缩水（establish 只在田全毁/无田时才点火）。补格沿 establish 簇几何
     * 与湿润/见天/坑底判据向田边扩，不挪田、不改锚点、不碰留种数值。
     */
    private final java.util.List<BlockPos> farmExpansionCells = new java.util.ArrayList<>();
    /** 骨粉后复查的第二轮收获是否已走过（防收获↔骨粉来回横跳）。 */
    private boolean farmSecondPass;
    /** 维护访开始时的农产存量基线，收尾按差额记账（资源流水）。 */
    private int farmBaseWheat;
    private int farmBaseSeeds;
    private int farmBaseCarrot;
    private int farmBasePotato;

    /** 一个田间动作：kind=till/plant/harvest/bonemeal；pos 语义见各动作实现（till/plant=土格，harvest/bonemeal=作物格）。 */
    private record FarmAction(String kind, BlockPos pos, String crop) {
    }
    /** 定点猎杀：目标实体 ID、对应掉落物、开始时背包里的掉落数量。 */
    private String slayEntity = "";
    private String slayDrop = "string";
    private int slayBaseline;
    private int slayAttempts;
    /** 【fix324】定点猎杀连续无目标次数（run 返回 false 即 +1、见怪即清零）；≥8 即换层。 */
    private int slayNoTargetStreak;
    /** 【fix106】追杀时限（Fong 2026-10-29 拍板）：猎杀类目标 120 秒无新战利品即放弃。 */
    private static final long CHASE_NO_GAIN_LIMIT_MILLIS = 120_000L;
    /** 追杀进度基线：目标开始时的进度量（与 hunt/slay 各自基线同源，见 start()）。 */
    private int chaseBaseline;
    /** 本目标已见到的最大战利品增量；增量上涨即重置无战利品计时。 */
    private int chaseGainSeen;
    /** 上次战利品增量上涨的时刻（目标开始时初始化为目标开始时刻）。 */
    private long chaseLastGainMillis;
    /** 【fix134】中途撤退本地冷却：触发后 120 秒内不再触发（防到家后同目标
     * 立刻重派→秒断的暖循环；撤退已把人带回安全区，冷却内归规划层处置）。 */
    private long vitalRetreatCooldownUntilTick;
    /** 长途行进的目标坐标（travel 目标使用）。 */
    private double travelX;
    private double travelY;
    private double travelZ;
    /** 【fix170】到达验收 Y 上限（仅带 arriveYCap 参数的 travel 生效，NaN=不限）：
     * 回小屋 travel 的到达包络 |dy|≤3 会把屋顶（基座+3）判成到达（#36），
     * 带 cap 时 tickTravel 到达判定与 fix162 抢先判定都要求 npcY ≤ cap，
     * 登顶态落回竖直对齐带下到基座层，不假到达。 */
    private double travelArriveYCap = Double.NaN;
    /** endportal 目标：0 前往房间、1 镶满眼睛、2 走进传送门。 */
    private int endPhase;
    /** 末地屠龙：0 拆水晶、1 打龙。 */
    private int dragonPhase;
    private int dragonAttempts;
    /** 目标总超时看门狗状态（第四轮修复 A）：目标开始 tick、最后一次有进展的 tick。 */
    private long goalStartTick;
    private long lastProgressTick;
    /** 周期裁剪节流计数（第三十三轮）：safeTick 每 10 tick 采样一次，本计数每 10 次≈5 秒触发一轮裁剪。 */
    private int trimTickCounter;
    // 【fix380】顺路捡远处有用掉落物（Fong 个人版开关 pickup.detour，默认关）：
    // 扫描节流计数 + 上次绕行目标（防同一件物品反复重发步行）。
    private int detourTickCounter;
    private int lastDetourItemId = -1;
    private long lastDetourTick;
    private static boolean detourPickupEnabled;

    public static void setDetourPickupEnabled(boolean enabled) {
        detourPickupEnabled = enabled;
    }
    /** 【fix71·D5】插火把节流计数（目标 tick 基准，约每 10 次查一次）。 */
    private int torchTickCounter;
    /** 上次观察到的"目标相关产出计数"，变化即算进展（见 goalMetric）。 */
    private int progressMetric;
    /** 上次有进展时的位置锚点：离开锚点 6 格以上也算进展（长距离移动/下矿途中）。 */
    private BlockPos progressAnchor;
    /**
     * 【fix107】travel/revisit 竖向对齐死线状态：进入「水平已近（≤6 格）但
     * 高差 >3」的对齐带时锚定 tick 与当时高差；高差改善 ≥4 格重新锚定，
     * 连续 300 秒（6000 tick）无改善即诚实收场，不再干等 LONG 看门狗的
     * 480 秒位置处决（farm67 山顶边缘悬挂实证）。0=不在对齐带。
     */
    private long alignBandSinceTick;
    private double alignBestAbsDelta = Double.MAX_VALUE;
    /**
     * 【fix156·起点侧】travel 高空起点死线状态：连续派单间水平推进 <2 格的
     * 次数 + 上次派单时的水平距与目标点（目标点变化即清账，见 tickTravel）。
     */
    private int travelStallCount;
    /** 【fix186】平地停滞计数：与 fix156 同一无推进口径但不带高差条件
     * （宽河岸平地热循环无人收场的兜底），见 tickTravel。 */
    private int travelStallAnyCount;
    private double travelStallLastHorizontal = -1.0D;
    /** 【fix192】上次派单时 NPC 高度：高空起点死线（fix156）的高度收敛口径——
     * 高出目标层 >12 时，两派单间高度没降 ≥2 格即视为高度不收敛，水平推进
     * 不许清高空停滞账（高空搭桥棘轮实证，见 tickTravel）。 */
    private double travelStallLastY = -1.0D;
    private double travelStallTargetX = Double.NaN;
    private double travelStallTargetZ = Double.NaN;
    /**
     * 【fix208】近终点竖向收尾宽带（fix156，水平 4–12 格）的无下降记账：
     * 同站位连续派 ascend/descend 后高度差改善 &lt;2 格的次数 + 上次派单的
     * 高度差与站位。fix107 死线只管 ≤6 格带、fix186/156 停滞账排在宽带
     * 分支之后够不着——宽带内技能幻影收场（如楼梯块两级台面间 blockY
     * 振荡喂出的假步数、靠 fix113 真降看门狗 90 秒一轮收场）后本层无记账
     * 原样重派，farm178 山脊顶实证同点三轮空转、白烧应急口粮。
     */
    private int wideAlignFailCount;
    private double wideAlignLastAbsDelta = -1.0D;
    private int wideAlignLastX = Integer.MIN_VALUE;
    private int wideAlignLastZ = Integer.MIN_VALUE;
    /** 传送门选址结果：origin 是框架左下角，alongX 表示门面横向沿 X 轴。 */
    private BlockPos portalOrigin;
    private boolean portalAlongX = true;
    /** portal 目标的阶段：0 选址建造、1 等建造完成、2 进入、3 等传送结果。 */
    private int portalPhase;
    private int enterAttempts;
    /** 【fix313】进门前站位接近（approachPortal）的连卡计数与站位记账：
     * 进度型连卡——距站位最优距离不改善 ≥0.5 即累计（两格间弹跳也算卡，
     * 165 实验场 v2 实证：按格子变化清零会被弹跳永远重置，死带 ascend
     * 与久攻回落都点不着）；站位变或就位即清零。 */
    private int portalApproachStuck;
    private BlockPos portalApproachStand;
    private double portalApproachBestDist = Double.MAX_VALUE;
    /** 【fix355】垫脚柱底平移 goto 是否已打过去程行：站位变或就位即清零，
     * 避免每 tick 重打日志行。 */
    private boolean portalPillarBaseGotoActive;
    /** 【fix359】沿柱直上是否已打过开始行：站位变或就位即清零，
     * 避免每 tick 重打日志行。 */
    private boolean portalPillarClimbActive;

    /** 一种可挖掘资源：挖什么方块、掉什么、挖到哪一层。 */
    private record OreTarget(String block, String drop, int y) {
    }

    /**
     * 资源别名表。玩家说"挖铁"时，目标层要知道对应方块、掉落物和合理深度：
     * 铁在 Y=16 附近最密集，钻石起始层 Y=-54（fix81 起按 NPC 渐进下探至 -59，
     * 实际层位由 DigDepthMemory 在 accept 时覆盖），煤在浅层 Y=48（fix308，见下），
     * 石头随处都是。
     */
    private static OreTarget oreTargetOf(String resource) {
        return switch (resource) {
            case "stone", "cobblestone", "石头" -> new OreTarget("stone", "cobblestone", 50);
            // 【fix308·158 棒】煤层 16→48。旧口径与铁同层（Y=16）系误判：
            // 查 Wiki 矿石分布——煤主批三角形 Y0–192、峰值 Y96，Y16 仅约
            // 峰值 1/6 密度（16 恰是铁主批的峰值层，不是煤的）；第二批
            // Y136–319 只落山地；Y<0 无煤。实测入石最密层 ≈Y45。正式服
            // 实证：燃料告急挖煤派单 31 次、完成 0 次——Y16 strip 走满
            // 24 步已挖=0 是常态。早年间 Y48 试挖失败是另一回事：那是丛林
            // 测试地形里从深矿 ascend 三十多级去「地表层」的通勤问题，且
            // 当时坏地形没有快速收场；如今 strip 停滞看门狗（fix308）＋
            // descend 坏地形一次换区（fix308）已补齐消费口径。正式线派单
            // 都从地表出发，layerY 又被地表钳制（min(48, 地表-1)），实际
            // 只需 descend 约 15 级（原 47 级），顺带少烧石镐、少磨坡面。
            case "coal", "coal_ore", "煤" -> new OreTarget("coal_ore", "coal", 48);
            case "iron", "iron_ore", "铁", "raw_iron" -> new OreTarget("iron_ore", "raw_iron", 16);
            case "gold", "gold_ore", "金" -> new OreTarget("gold_ore", "raw_gold", 16);
            case "diamond", "diamond_ore", "钻石" -> new OreTarget("diamond_ore", "diamond", -54);
            case "emerald", "emerald_ore" -> new OreTarget("emerald_ore", "emerald", 32);
            case "redstone", "redstone_ore" -> new OreTarget("redstone_ore", "redstone", -40);
            case "lapis", "lapis_ore" -> new OreTarget("lapis_ore", "lapis_lazuli", 16);
            case "copper", "copper_ore" -> new OreTarget("copper_ore", "raw_copper", 48);
            default -> null;
        };
    }

    public GoalExecutor(AiVillagerPlugin plugin, FakePlayerBody npc, SkillRunner skills) {
        this.plugin = plugin;
        this.npc = npc;
        this.skills = skills;
    }

    public void start() {
        // 0.5 秒检查一次：足够跟上技能结束，又不会在主线程上空转。
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::safeTick, 20L, 10L);
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标层启动");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active = false;
        type = "-";
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标层停止");
    }

    public boolean active() {
        return active;
    }

    public String status() {
        return active ? status : "无目标";
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    /** 模型/玩家给出的动作是不是一个中期目标。 */
    public static boolean isGoalAction(String action) {
        return "gather".equals(action) || "craft".equals(action)
                || "build".equals(action) || "explore".equals(action)
                || "dig".equals(action) || "smelt".equals(action) || "hunt".equals(action)
                || "flint".equals(action) || "obsidian".equals(action) || "portal".equals(action)
                || "enterportal".equals(action) || "slay".equals(action) || "travel".equals(action)
                || "revisit".equals(action) || "farm".equals(action) || "base".equals(action)
                || "endportal".equals(action) || "dragon".equals(action);
    }

    /**
     * 开始一个新目标。返回 false 表示参数不合法（不会改变当前目标）。
     */
    public boolean start(Intent intent) {
        String action = intent.action();
        String newTarget;
        int newCount;
        switch (action) {
            case "gather" -> {
                newTarget = normalizeBlock(intent.param("block", ""));
                if (newTarget.isEmpty()) {
                    lastOutcome = "gather 缺少 block 参数";
                    return false;
                }
                newCount = clamp(intent.intParam("count", 8), 1, 64);
            }
            case "craft" -> {
                newTarget = normalizeItem(intent.param("item", ""));
                if (newTarget.isEmpty()) {
                    lastOutcome = "craft 缺少 item 参数";
                    return false;
                }
                newCount = clamp(intent.intParam("count", 1), 1, 16);
            }
            case "build" -> {
                newTarget = intent.param("structure", "shelter");
                newCount = 1;
            }
            case "explore" -> {
                newTarget = "explore";
                newCount = clamp(intent.intParam("distance", 32), 16, 48);
            }
            case "dig" -> {
                newTarget = normalizeBlock(intent.param("resource", ""));
                OreTarget target = oreTargetOf(newTarget);
                if (target == null) {
                    lastOutcome = "dig 不支持的资源 " + newTarget;
                    return false;
                }
                if ("diamond_ore".equals(target.block())) {
                    // 【fix81】钻石目标层渐进下探：深度按 NPC 持久化（DigDepthMemory），
                    // 连败换区时下探、-59 封底；layerY/descend/strip 全链都读
                    // oreTarget.y，在这里换掉 y 即全链生效。revisit 走记账点
                    // 坐标、不经此覆盖。
                    DigDepthMemory.bindWorld(npc.level());
                    target = new OreTarget(target.block(), target.drop(),
                            DigDepthMemory.depth(npc.npcName()));
                }
                oreTarget = target;
                digPickFallbackLogged = false;
                newTarget = target.block();
                newCount = clamp(intent.intParam("count", 8), 1, 64);
                if ("diamond_ore".equals(oreTarget.block())) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 挖钻开工 | depth=" + oreTarget.y()
                            + " 目标=" + newCount);
                }
            }
            case "revisit" -> {
                // 【fix40】记矿回访：参数带记账点坐标与矿种，oreTarget 复用
                // dig 的矿种表（掉落计数/级别门都现成）。
                OreTarget target = oreTargetOf(normalizeBlock(intent.param("resource", "")));
                if (target == null) {
                    lastOutcome = "revisit 不支持的资源 " + intent.param("resource", "");
                    return false;
                }
                oreTarget = target;
                revisitX = intent.intParam("x", 0);
                revisitY = intent.intParam("y", 0);
                revisitZ = intent.intParam("z", 0);
                newTarget = "revisit";
                newCount = 1;
            }
            case "smelt" -> {
                newTarget = normalizeItem(intent.param("item", ""));
                if (smeltInputFor(newTarget) == null) {
                    lastOutcome = "smelt 不支持的产品 " + newTarget;
                    return false;
                }
                newCount = clamp(intent.intParam("count", 8), 1, 64);
            }
            case "hunt" -> {
                newTarget = "hunt";
                newCount = clamp(intent.intParam("count", 3), 1, 8);
                // 【第二十二轮】存量驱动模式：need=leather/food + stock=目标存量。
                // 给了 need 就不再数"打死几只"，改由 tickHunt 每轮复查背包存量。
                huntNeed = intent.param("need", "");
                huntStockTarget = intent.intParam("stock",
                        "leather".equals(huntNeed) ? 4 : "wool".equals(huntNeed) ? 3 : 24);
                lastHuntStock = -1;
            }
            case "farm" -> {
                // 【fix49·种田】mode=establish 开田 / tend 维护（收获补种）；
                // 缺省 auto：有田维护、无田开田，tickFarm 按账本现状分流。
                farmMode = intent.param("mode", "auto");
                newTarget = "farm:" + farmMode;
                newCount = 1;
            }
            case "base" -> {
                // 【fix51·D2 落脚点】建火柴盒小屋（选址→砌筑队列→自审落盘）。
                newTarget = "base";
                newCount = 1;
            }
            case "place_bed" -> {
                // 【fix56·D1 床】把包里的床安置进落脚点屋内并登记重生点。
                newTarget = "place_bed";
                newCount = 1;
            }
            case "clear_bed" -> {
                // 【fix227】清理床边：床在但复活站位全堵时，到基址挖掉床边堵块。
                newTarget = "clear_bed";
                newCount = 1;
                clearBedSkipped.clear();
                clearBedTries = 0;
                clearBedStuck = 0;
                clearBedLastKey = null;
            }
            case "sleep" -> {
                // 【fix56·D1 床】夜里回床真睡到天亮（原版睡眠跳夜、防幻翼）。
                newTarget = "sleep";
                newCount = 1;
            }
            case "store_loot" -> {
                // 【fix69·箱子】回落脚点把贵重物存进箱子（v1 只存不取）。
                newTarget = "store_loot";
                newCount = 1;
            }
            case "loot_chest" -> {
                // 【fix80·D12】搜刮世界箱子（规划层扫到的未搜箱位）。
                newTarget = "loot_chest";
                newCount = 1;
                lootX = intent.intParam("x", 0);
                lootY = intent.intParam("y", 0);
                lootZ = intent.intParam("z", 0);
                lootStuck = 0;
                lootStandPos = null;
            }
            case "flint" -> {
                newTarget = "flint";
                newCount = clamp(intent.intParam("count", 1), 1, 8);
            }
            case "fillbucket" -> {
                // 【fix70·D4】装满水桶（常备保命水）：技能层已有 fillbucket。
                newTarget = "fillbucket";
                newCount = 1;
            }
            case "obsidian" -> {
                newTarget = "obsidian";
                newCount = clamp(intent.intParam("count", 10), 1, 32);
            }
            case "portal" -> {
                newTarget = "portal";
                newCount = 1;
            }
            case "enterportal" -> {
                // 已经点亮的门只需要走进去；坐标由 tickEnterPortal 每轮重新扫描，
                // 这里不接受外部坐标参数（玩家/模型给的坐标常常对不准门中心）。
                newTarget = "enterportal";
                newCount = 1;
            }
            case "slay" -> {
                newTarget = normalizeEntity(intent.param("entity", ""));
                if (newTarget.isEmpty()) {
                    lastOutcome = "slay 缺少 entity 参数";
                    return false;
                }
                newCount = clamp(intent.intParam("count", 3), 1, 64);
            }
            case "travel" -> {
                newTarget = "travel";
                newCount = 1;
                travelX = intent.doubleParam("x", npc.getX());
                travelY = intent.doubleParam("y", npc.getY());
                travelZ = intent.doubleParam("z", npc.getZ());
                // 【fix170】到达 Y 上限（回小屋专用，缺省不限、每单重读防串）。
                travelArriveYCap = intent.doubleParam("arriveYCap", Double.NaN);
                // 【fix156·起点侧】新 travel 目标清死线记账，防跨目标误触发。
                travelStallCount = 0;
                travelStallLastHorizontal = -1.0D;
                travelStallTargetX = Double.NaN;
                travelStallTargetZ = Double.NaN;
                // 【fix208】宽带无下降记账同清，防跨目标误触发。
                wideAlignFailCount = 0;
                wideAlignLastAbsDelta = -1.0D;
            }
            case "endportal" -> {
                newTarget = "endportal";
                newCount = 1;
                // 规划器会把要塞传送门房间的坐标传进来；缺省则原地扫描。
                travelX = intent.doubleParam("x", npc.getX());
                travelY = intent.doubleParam("y", npc.getY());
                travelZ = intent.doubleParam("z", npc.getZ());
            }
            case "dragon" -> {
                newTarget = "dragon";
                newCount = 1;
            }
            default -> {
                return false;
            }
        }

        // 新目标覆盖旧目标：同时取消旧动作，避免走到一半还按旧路径继续走。
        if (active) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标被替换 | old=" + type + " new=" + action);
            skills.stop();
        }
        type = action;
        goalStage = intent.param("stage", "");
        goalStartedMillis = System.currentTimeMillis();
        goalStartX = npc.getBlockX();
        goalStartY = npc.getBlockY();
        goalStartZ = npc.getBlockZ();
        if (!"hunt".equals(type)) {
            huntNeed = "";
        }
        targetId = newTarget;
        targetCount = newCount;
        attempts = 0;
        fillFailedRuns = 0;
        fillSkillActive = false;
        staleChecks = 0;
        lastProgress = -1;
        // 必须在 targetId 赋值之后算：gatherCount() 要看目标物品。
        baselineCount = "gather".equals(type) ? gatherCount()
                : ("dig".equals(type) || "revisit".equals(type)) ? countItem(oreTarget.drop()) : 0;
        smeltTicks = 0;
        smeltStallTicks = 0;
        smeltBaseline = "smelt".equals(type) ? countItem(targetId) : 0;
        furnacePos = null;
        huntBaseline = "hunt".equals(type) ? countHuntLoot() : 0;
        huntAttempts = 0;
        huntScansCounted = 0;
        flockAnchorReached = false;
        flockAnchorSince = 0;
        flockAnchorGotoFails = 0;
        lastWoolTotal = "hunt".equals(type) && "wool".equals(huntNeed) ? woolTotal() : -1;
        huntScanAnchorX = Double.NaN;
        huntScanAnchorZ = Double.NaN;
        huntGainAtAnchor = -1;
        // 定点猎杀按"对应掉落物"统计进度：杀 1 只掉 0 个的随机性很大，
        // 按击杀数算会让目标在掉落没涨时误判完成。
        slayEntity = "slay".equals(type) ? targetId : "";
        slayDrop = "slay".equals(type) ? dropOfEntity(targetId) : "string";
        slayBaseline = "slay".equals(type) ? countItem(slayDrop) : 0;
        slayAttempts = 0;
        slayNoTargetStreak = 0;
        // 【fix106】追杀时限记账复位：进度基线与 hunt/slay 各自的完成判定同源——
        // 存量驱动看存量（皮革数/食物营养）、计数狩猎看 huntBaseline、定点猎杀看 slayBaseline。
        chaseBaseline = "hunt".equals(type)
                ? (!huntNeed.isEmpty() ? huntNeedStock() : huntBaseline)
                : "slay".equals(type) ? slayBaseline : 0;
        chaseGainSeen = 0;
        chaseLastGainMillis = goalStartedMillis;
        endPhase = 0;
        dragonPhase = 0;
        dragonAttempts = 0;
        // 【fix49】种田状态机复位（farmMode 已在解析段按参数设好）。
        farmPhase = 0;
        farmActions.clear();
        farmExpansionCells.clear();
        farmSiteCells = null;
        farmSiteAttempts = 0;
        farmGatherSeq = -1;
        farmGatherNoGain = 0;
        farmGatherAwaiting = false;
        farmGatherStockMark = 0;
        farmActionStuck = 0;
        farmPhase0Attempts = 0;
        farmPhase0Sortie = false;
        farmPhase0Stage = null;
        farmPhase0StageFails = 0;
        farmPhase0Stagings = 0;
        farmSkipped = 0;
        farmDropped = 0;
        farmEffected = 0;
        farmLastActionKey = "";
        farmHarvestedSoils.clear();
        farmPickupIndex = 0;
        farmBonemealQueued = false;
        farmQueueBuilt = false;
        farmSecondPass = false;
        farmBaseWheat = countItem("wheat");
        farmBaseSeeds = countItem("wheat_seeds");
        farmBaseCarrot = countItem("carrot");
        farmBasePotato = countItem("potato");
        // 【fix51】落脚点状态机复位。
        basePhase = 0;
        baseQueue.clear();
        baseCenter = null;
        baseActionStuck = 0;
        baseLastActionKey = "";
        basePlacedCount = 0;
        baseTotalCells = 0;
        baseQueueBuilt = false;
        // 【fix56】床状态机复位。
        bedPhase = 0;
        bedFootPos = null;
        bedFacing = null;
        bedItemPath = null;
        bedActionStuck = 0;
        bedLastActionKey = "";
        bedPadAttempted = false;
        bedBasePadCells = new java.util.ArrayList<>();
        bedSorties = 0;
        bedStandCandidates = null;
        sleepPhase = 0;
        obsidianDescendFails = 0;
        lavaSearchDir = Double.NaN;
        obsidianRelocPhase = 0;
        obsidianRelocated = false;
        obsidianRelocAscendTries = 0;
        obsidianRelocGotoTries = 0;
        badLavaHints.clear();
        lastObsidianHint = null;
        obsidianRouteCasting = false;
        obsidianRouteFails = 0;
        obsidianRouteLogged = false;
        obsidianLavaTarget = null;
        obsidianLavaTried.clear();
        obsidianLavaTripDone = false;
        obsidianLavaArrived = false;
        obsidianLavaGroundDone = false;
        obsidianLavaDetour = 0;
        obsidianPickYieldPending = false;
        obsidianLavaGotoTries = 0;
        obsidianLavaStruck = false;
        obsidianLavaTripStartMillis = 0L;
        obsidianLavaHopInFlight = false;
        handledObsidianSeq = skills.outcomeSeq();
        // 【fix142】descend 结局消费水位线随新目标复位（同 handledObsidianSeq 口径）。
        handledDigDescendSeq = skills.outcomeSeq();
        digDescendNoDropFails = 0;
        handledRevisitDescendSeq = skills.outcomeSeq();
        revisitDescendNoDropFails = 0;
        sleepStartMillis = 0L;
        sleepDayTime0 = -1L;
        sleepActionStuck = 0;
        sleepLastActionKey = "";
        // 【fix285】睡觉接近出击/轮换与 travel 爆表消费水位随新目标复位。
        sleepApproachSortie = false;
        sleepApproachAttempts = 0;
        sleepChosenStand = null;
        handledMtsTravelSeq = skills.outcomeSeq();
        mtsTravelDispatchSeq = -1;
        mtsTravelBlowups = 0;
        // 【fix69】存箱状态机复位。
        storePhase = 0;
        storeChestPos = null;
        storeActionStuck = 0;
        storeLastActionKey = "";
        // 【fix251】接近预算与礼让消费水位随新目标复位。
        storePhase1StartTick = -1L;
        handledStoreSeq = skills.outcomeSeq();
        portalOrigin = null;
        portalPhase = 0;
        enterAttempts = 0;
        // 【fix313】进门接近记账随新目标复位。
        portalApproachStuck = 0;
        portalApproachStand = null;
        portalApproachBestDist = Double.MAX_VALUE;
        exploreStarted = false;
        active = true;
        // 目标总超时看门狗从新目标开始重新计时（第四轮修复 A）。
        goalStartTick = npc.tickCount;
        lastProgressTick = npc.tickCount;
        progressMetric = goalMetric();
        progressAnchor = npc.blockPosition();
        alignBandSinceTick = 0;
        alignBestAbsDelta = Double.MAX_VALUE;
        goalDimension = npc.level().dimension().identifier().toString();
        status = describeStart();
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 接受目标 | " + status);
        return true;
    }

    private String describeStart() {
        return switch (type) {
            case "gather" -> "采集 " + targetId + " " + targetCount + " 个";
            case "craft" -> "合成 " + targetId + " x" + targetCount;
            case "build" -> "建造 " + targetId;
            case "dig" -> "挖矿 " + targetId + " → " + oreTarget.drop()
                    + " x" + targetCount + "（Y≈" + oreTarget.y() + "）";
            case "smelt" -> "熔炼 " + targetId + " x" + targetCount;
            case "hunt" -> "狩猎 " + targetCount + " 只动物";
            case "farm" -> "种田 " + farmMode;
            case "base" -> "建落脚点（火柴盒小屋）";
            case "place_bed" -> "安置床（落脚点）";
            case "clear_bed" -> "清理床边（挖掉床边堵块）";
            case "sleep" -> "回床睡觉";
            case "store_loot" -> "回落脚点存贵重物（箱子）";
            case "loot_chest" -> "搜刮箱子（村庄/结构）";
            case "flint" -> "找燧石 x" + targetCount;
            case "fillbucket" -> "装满水桶（常备）";
            case "obsidian" -> "浇铸黑曜石 x" + targetCount;
            case "portal" -> "建造并点燃下界传送门";
            case "enterportal" -> "走进已点亮的传送门";
            case "slay" -> "猎杀 " + targetId + " → " + slayDrop + " x" + targetCount;
            case "travel" -> String.format(Locale.ROOT, "长途行进 → (%.0f, %.0f, %.0f)",
                    travelX, travelY, travelZ);
            case "endportal" -> "找到并激活末地传送门";
            case "dragon" -> "击杀末影龙（通关）";
            default -> "探索 " + targetCount + " 格";
        };
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable throwable) {
            // 周期任务一旦抛异常会被 Bukkit 静默取消，目标层就永远不动了；这里必须自己兜住。
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][GOAL] " + npc.npcName() + " tick 异常", throwable);
        }
    }

    private void tick() {
        if (!active) {
            return;
        }
        if (!npc.isAlive()) {
            // 死亡等复活的几秒不算"无进展"：把进展时钟顶到当前，免得复活后被误杀。
            lastProgressTick = npc.tickCount;
            return;
        }
        // 维度变了（穿过传送门）：旧目标里的坐标、资源层高度全部作废，立即结束。
        if (!goalDimension.isEmpty()
                && !goalDimension.equals(npc.level().dimension().identifier().toString())) {
            finish("维度切换 " + goalDimension + " → " + npc.level().dimension().identifier()
                    + "，旧目标中止");
            return;
        }
        // 目标总超时看门狗（第四轮修复 A）：必须赶在下面"技能忙就等"的早退之前——
        // 旧结构里各 tick* 的放弃计数只在技能 idle 的间隙累加，技能被生存层游泳
        // 反复顶掉/重发时永远轮不到计数，目标就无限期挂死（长跑实测冻结 31 分钟）。
        // 这里以"目标相关产出变化 / 位置有效移动 / 熔炉正在烧"为进展信号，
        // 无进展超限或总时长超硬顶就停技能、放弃目标，把大脑还给规划器。
        observeGoalProgress();
        long noProgressTicks = npc.tickCount - lastProgressTick;
        long totalTicks = npc.tickCount - goalStartTick;
        if (noProgressTicks > noProgressLimitTicks() || totalTicks > hardCapTicks()) {
            String reason = "goal-timeout type=" + type + " target=" + targetId
                    + " noProgressSec=" + (noProgressTicks / 20)
                    + " totalSec=" + (totalTicks / 20);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + reason);
            skills.stop();
            if ("hunt".equals(type) && chaseGainSeen == 0) {
                rotateHuntHeading();
            }
            finish(reason + " 目标超时放弃，交回规划器");
            return;
        }
        // 【fix106】追杀时限（Fong 2026-10-29 拍板 120 秒，审计提案①）：猎杀类目标
        // "一直在追、就是杀不死"时（farm67 湖汊猎鸡 goto 连环超时空转 10 分钟+），
        // 看门狗的"无进展"口径会被位置移动一直喂饱、永远不触发——本预算只看
        // 战利品增量，120 秒无新增即放弃交规划器。与看门狗同置于"技能忙"早退
        // 之前：追杀空转恰恰发生在技能连环重派之间，放在后面会被饿死（同第四轮
        // 修复 A 的教训）。生存层贴身自卫不是目标层追杀，不受此预算约束。
        if (("hunt".equals(type) || "slay".equals(type)) && chaseNoGainExpired()) {
            long chasedSec = (System.currentTimeMillis() - goalStartedMillis) / 1000;
            String chaseTarget = "slay".equals(type) ? slayEntity
                    : !huntNeed.isEmpty() ? "need:" + huntNeed : "animals";
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 追杀放弃 | type=" + type
                    + " target=" + chaseTarget + " 已追秒=" + chasedSec
                    + " 无战利品秒=120 gain=" + chaseGainSeen + "（fix106 追杀时限）");
            skills.stop();
            if ("hunt".equals(type) && chaseGainSeen == 0) {
                rotateHuntHeading();
            }
            finish("追杀放弃：120 秒无新战利品（fix106 追杀时限）type=" + type
                    + " target=" + chaseTarget + " 已追 " + chasedSec + " 秒");
            return;
        }
        // 【fix162】到达抢先判定（仅 travel）：tickTravel 的到达判定只在
        // 技能 idle 时才跑，fix107 死线却挂在忙位早退之前——farm127 实证：
        // 人已落在终点到达包络内（水平 2 格/高差 3~4），descend 在洼地唇口
        // 悬挂复位长占忙位，tickTravel 永远跑不到，死线 300 秒抢先收场、
        // 记一笔冤枉失败。忙位之前先按 tickTravel 同一口径问一句：已在
        // 包络内就停技能按到达收场；死线只管真到不了的僵局。revisit 的
        // 完成口径是挖到矿而非位置到达，不在此列。
        if ("travel".equals(type)) {
            double arriveDx = travelX - npc.getX();
            double arriveDz = travelZ - npc.getZ();
            if (arriveDx * arriveDx + arriveDz * arriveDz <= 16.0D
                    && Math.abs(travelY - npc.getY()) <= 3.0D
                    && (Double.isNaN(travelArriveYCap)
                        || npc.getY() <= travelArriveYCap)) {
                skills.stop();
                finish("到达目的地");
                return;
            }
        }
        // 【fix107】竖向对齐死线（travel/revisit）：水平已到位、高差悬殊时
        // descend/ascend 若在边缘悬挂等状态里空转，技能会长占忙位、目标层
        // 只能干等 LONG 看门狗 480 秒位置处决（farm67 实证）。与 fix106
        // 同置于"技能忙"早退之前检查：对齐带内 300 秒高差无改善即收场。
        if (checkVerticalAlignDeadline()) {
            return;
        }
        // 【fix121】入夜收工（夜间低口粮死因专案病灶 2）：与 fix106/107 同
        // 置于"技能忙"早退之前——野外目标入夜时恰恰是技能连环执行中，
        // 放在后面会被饿死。
        if (checkNightAbort()) {
            return;
        }
        // 【fix134】中途撤退线：与 fix121 同置——血/饭磨穿恰恰发生在技能
        // 连环执行中，放在后面同样会被饿死。
        if (checkVitalRetreat()) {
            return;
        }
        // 【fix251】存箱接近忙位守卫：与 fix106/107 同置于忙位早退之前——
        // store 接近技能（travel/goto）全程占忙位，礼让结局与接近预算若只
        // 在 tickStoreLoot 的 phase 1 里查，技能不收场就永远轮不到（98 棒
        // 实验场实证：travel 假到达 1 秒一轮连环 71 次、phase 1 的 90 计数
        // 与预算双双饿死、最终仍走既有放弃计数才收场）。
        if (tickStoreBusyGuard()) {
            return;
        }
        // 【fix273】种田忙位守卫：与 fix251 同置于忙位早退之前——farm 的
        // 逐动作接近只在技能 idle 的间隙被 tickFarm 调用，死 goto 挂住期
        // 间 tickFarm 根本轮不到，守卫放目标内会被饿死（125 棒首验实证：
        // 初版放 processFarmActions，两轮死 goto 仍各烧满 40 秒）。
        if (tickFarmBusyGuard()) {
            return;
        }
        // 【fix284】moveToStand descend 慢步守卫：与 fix273 同置于忙位
        // 早退之前——descend 运行期目标层整层早退，守卫放 moveToStand
        // 里永远轮不到（同 125 棒定谳六）。
        if (tickMtsDescendGuard()) {
            return;
        }
        // 【fix319】moveToStand 竖直对齐闭环守卫：与 fix284 同置于忙位
        // 早退之前——ascend/descend 运行期目标层整层早退，不在此拦，
        // 对齐技能永远跑到地形决定的自收点（过冲/挖穿入洼，187 棒定谳）。
        if (tickMtsAlignGuard()) {
            return;
        }
        // 【fix320】分级守卫让位接棒守卫：与 fix284/fix319 同置于忙位早退之前——
        // 移动技能以 fix251 让位收场时目标层消费面不认该结局、原样重派；守卫在此
        // 就地补镐，不等目标边界（191 棒定谳）。不拦 tick（补镐后继续派下一步）。
        tickTierYieldGuard();
        // 技能还在执行（走路/挖矿）就等它结束，目标层只负责“下一步做什么”。
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        // 生存层正在逃跑：这时推进目标会把逃跑路径冲掉，等于让 NPC 站着挨打。
        SurvivalController survival = plugin.survival(npc.npcName());
        if (survival != null && survival.isFleeing()) {
            return;
        }
        // 5 格内有敌对生物且生存层已经接管：把移动权交给 KillAura 追击。
        // 否则目标层每 0.5 秒重新下发 descend/strip，和生存层的追击互相打断，
        // 实测夜间日志成对出现"descend 开始 / 中断技能自卫"，NPC 既不下矿也不打怪。
        // 用 survival.isEngaged() 而不是自己数怪：它会把"打不到的怪"排除掉，
        // 避免一只卡在墙后的僵尸把目标层永久暂停。
        if (survival != null ? survival.isEngaged() : hostileNearby(5)) {
            return;
        }
        // 惜命：血量低（≤35%）且包里有吃的时先停目标回血——生存层会吃东西触发原版回血，
        // 血没恢复前不推进任何目标，更不会推进猎杀类目标去送死（口径与规划器一致）。
        // 没吃的时不拦：找吃的本身就是当前目标（hunt/gather），拦了反而回不了血。
        if (survival != null && survival.isLowHealth() && survival.hasEdibleFood()) {
            status = "血量过低，先吃东西回血";
            return;
        }
        // 先收掉落物再推进目标：原版拾取要求碰撞箱相交，
        // 伪玩家挖掉邻格方块后掉落物落在 1 格外，不主动收就会一直"挖了但数量不涨"。
        pickupDrops();
        // 【fix380】脚边 3 格内的上面已收；更远的有用掉落物在技能空档时主动
        // 走过去捡（Fong 口径：看到对它有用的东西要跑过去捡）。逃跑/接战/低血
        // 回血的 tick 已在上方 return，不会绕路送命。
        if (detourPickupEnabled && ++detourTickCounter % 2 == 0) {
            detourToUsefulDrop();
        }
        // 背包维护：每 5 秒裁一次"总量超限"的堆积（圆石 >128、泥巴/树苗等）。
        // 不能只在"背包全满"时才裁：实测囤到 836 个圆石占了 14 格，
        // 等满的时候合成产物已经没地方放，变成"空槽=0"的假缺料死循环。
        // 【第三十三轮】节流改用本地计数：旧写法 npc.tickCount % 100 == 0，而 tick 由
        // Bukkit 调度每 10 tick 采样一次，采样值恒为同一模 10 余数——相位不对齐时
        // 永远碰不到 %100，长跑#10 整局周期裁剪零触发、退役镐壳堆了 4 把没人清，
        // 只有拾取腾格的 dropJunk 偶尔裁一次。本地计数与采样步长无关，钉死每 10 次 tick 一裁。
        if (++trimTickCounter % 10 == 0) {
            trimOverflow();
        }
        // 【fix309】farmWorkPermit 已删除：许可制在 tend 全程敞开，流体脱困/
        // stuck-rescue 趁 NPC 田边落水之机挖掉自家耕地（159 棒生产实证
        // tend-1 扩 2 格毁 2 格）。收获挖的是作物格（土格上方一格，本就
        // 不是田格），不受守卫拦；农田守卫现无条件，见 FakePlayerBody.mine()。
        switch (type) {
            case "gather" -> tickGather();
            case "craft" -> tickCraft();
            case "build" -> tickBuild();
            case "explore" -> tickExplore();
            case "dig" -> tickDig();
            case "revisit" -> tickRevisit();
            case "smelt" -> tickSmelt();
            case "hunt" -> tickHunt();
            case "farm" -> tickFarm();
            case "base" -> tickBase();
            case "place_bed" -> tickPlaceBed();
            case "clear_bed" -> tickClearBed();
            case "sleep" -> tickSleep();
            case "store_loot" -> tickStoreLoot();
            case "loot_chest" -> tickLootChest();
            case "flint" -> tickFlint();
            case "fillbucket" -> tickFillBucket();
            case "obsidian" -> tickObsidian();
            case "portal" -> tickPortal();
            case "enterportal" -> tickEnterPortal();
            case "slay" -> tickSlay();
            case "travel" -> tickTravel();
            case "endportal" -> tickEndPortal();
            case "dragon" -> tickDragon();
            default -> finish("未知目标类型 " + type);
        }
    }

    /**
     * 收掉附近属于自己挖出来的掉落物（Wurst 风格的自动拾取）。
     *
     * <p>只碰 owner 为空的掉落物：玩家主动丢出的物品 ItemEntity 会记录 owner，
     * 这样 NPC 不会把别人丢在地上的装备顺手牵走。
     * 必须等原版拾取延迟（掉落后 10 tick）走完才能触碰，所以每个 tick 尝试一次即可。
     */
    private void pickupDrops() {
        List<Entity> drops = npc.level().getEntities(npc, npc.getBoundingBox().inflate(3.0D),
                entity -> entity instanceof ItemEntity item
                        && item.isAlive()
                        && !item.hasPickUpDelay()
                        && item.getOwner() == null);
        // 背包没有空槽时先丢一件杂物：掉落物留在原地会消失，挖矿的产出就白费了。
        // 只在真的有好东西可捡时才丢，避免在空地上每秒丢一次。
        if (!drops.isEmpty() && !hasEmptyMainSlot()) {
            dropJunk(1);
        }
        for (Entity drop : drops) {
            ItemEntity item = (ItemEntity) drop;
            String path = pathOf(item.getItem());
            int amount = item.getItem().getCount();
            item.playerTouch(npc);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 拾取掉落 | " + path + " x" + amount
                    + " 剩余=" + (item.isAlive() ? item.getItem().getCount() : 0));
        }
    }

    /**
     * 【fix380】顺路捡远处的有用掉落物（Fong 口径：看到对它有用的东西要跑过去捡）。
     *
     * <p>pickupDrops 只收 3 格内、原地触碰；本方法在技能空档时扫 12 格内的有用
     * 掉落物，挑最近的一件走过去（进入 3 格后由 pickupDrops 正常收）。只捡
     * owner 为空的（不顺玩家丢的东西）；自己丢弃的物品拾取延迟是原版永久值
     * 32767，按"延迟 &gt;200"一律排除，不会把自己扔的垃圾捡回来。
     */
    private void detourToUsefulDrop() {
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        List<ItemEntity> drops = npc.level().getEntitiesOfClass(
                ItemEntity.class,
                npc.getBoundingBox().inflate(12.0D),
                item -> item.isAlive()
                        && item.getOwner() == null
                        && item.pickupDelay <= 200
                        && isUsefulDrop(pathOf(item.getItem())));
        ItemEntity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (ItemEntity item : drops) {
            double sq = npc.distanceToSqr(item);
            if (sq < nearestSq) {
                nearestSq = sq;
                nearest = item;
            }
        }
        if (nearest == null || nearestSq <= 2.5D * 2.5D) {
            return; // 没有，或已在脚边（pickupDrops 每 tick 在收）
        }
        // 同一件物品 3 秒内不重复下发步行（走过去要时间，别每秒重发把路冲掉）。
        if (nearest.getId() == lastDetourItemId && npc.tickCount - lastDetourTick < 60L) {
            return;
        }
        lastDetourItemId = nearest.getId();
        lastDetourTick = npc.tickCount;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 顺路捡掉落 | " + pathOf(nearest.getItem())
                + " x" + nearest.getItem().getCount()
                + " 距离=" + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(nearestSq)));
        npc.walkTo(nearest.position(), false, null);
    }

    /** 掉落物是否有用：只排除纯垃圾（土/沙/砾石/杂草野花/雪球/羽毛等），其余都捡。 */
    private static boolean isUsefulDrop(String path) {
        return switch (path) {
            case "dirt", "coarse_dirt", "rooted_dirt", "gravel", "sand", "red_sand",
                    "clay_ball", "short_grass", "tall_grass", "fern", "large_fern",
                    "dead_bush", "poppy", "dandelion", "cornflower", "azure_bluet",
                    "oxeye_daisy", "lily_of_the_valley", "sunflower", "lilac",
                    "rose_bush", "peony", "snowball", "feather", "spider_eye",
                    "poisonous_potato", "egg" -> false;
            default -> true;
        };
    }

    // ---------------------------------------------------------------- 采集

    private void tickGather() {
        boolean anyLog = isAnyLogTarget();
        // 进度按"本轮新采到多少"算，而不是背包总量：
        // 否则背包里已有 9 个竹子时，"采集 8 个"第一秒就宣布完成，
        // NPC 会不停重复同一个目标却什么都没做，看起来像站着发呆。
        int absolute = gatherCount();
        int have = Math.max(0, absolute - baselineCount);
        status = "采集 " + targetId + " 新采 " + have + "/" + targetCount + "（背包共 " + absolute + "）";
        if (have >= targetCount) {
            finish("采集完成 " + targetId + " 新采 " + have + "/" + targetCount
                    + "，背包共 " + absolute);
            return;
        }

        // 技能结束后的空档才检查进度；连续多次没涨说明这个目标当前做不到（比如附近没树了）。
        // 走路/砍树技能本身可能要跑十几秒，那期间没有新掉落是正常的。
        if ("idle".equals(skills.currentSkill())) {
            if (have == lastProgress) {
                if (++staleChecks >= MAX_STALE_CHECKS) {
                    finish("采集放弃：连续 " + MAX_STALE_CHECKS + " 次没有进展 " + have + "/" + targetCount);
                    return;
                }
            } else {
                lastProgress = have;
                staleChecks = 0;
            }
        }

        // 采集原木时直接用通配符交给技能层：附近有什么树就砍什么树（丛林里就是丛林木），
        // 不再先解析成某个具体树种，避免"只认橡木"。
        String mineBlock = anyLog ? "*_log" : targetId;
        JsonObject params = new JsonObject();
        params.addProperty("block", mineBlock);
        params.addProperty("radius", GATHER_RADIUS);
        boolean started = skills.run(new Intent("", "mine", params), "目标");
        if (started) {
            // 资源已经进探测范围：扩围航行结束，清掉航向状态，下次从头挑方向。
            gatherSearchAngle = -1;
            gatherSearchTier = 0;
            gatherSearchAnchor = null;
            gatherEscalateHops = 0;
            java.util.Arrays.fill(gatherSectorHops, 0);
            attempts = 0;
            return;
        }
        // 原木常长在陡坡/树冠里，A* 找不到落脚点；死磕会连续放弃。
        // 附近有竹子就改用竹子：原版配方里 9 竹子→竹块→竹木板，一样能继续发展。
        if (anyLog && attempts >= 2 && nearbyBlockPos("bamboo") != null) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 原木不可达，改采附近竹子");
            targetId = "bamboo";
            // 换目标等于换计数口径：以切换时的竹子数量作为新基线，否则会把旧竹子算成本轮进度。
            baselineCount = countItem("bamboo");
            lastProgress = -1;
            staleChecks = 0;
            attempts = 0;
            return;
        }
        // 附近找不到目标方块：逐步扩大搜索并往低处走，而不是原地小范围打转。
        attempts++;
        if (attempts >= MAX_GATHER_FAILS) {
            finish("采集放弃：附近找不到 " + targetId);
            return;
        }
        // 长距离找树（第六轮修复）：沙漠/海岸这类无树地形里，朝最低处扩围只会
        // 沿海岸线/沙漠边缘打转（fix6 长跑实测 17 分钟零原木、徒步 140 格没出沙漠）。
        // 先"远眺"一圈：只读已加载区块的地表高度图，顶格是原木/树叶的列就是
        // 肉眼看得见的树，朝它跋涉（goto 单次最远 48 格，多轮接力）。
        // 这不是透视：只看地表、只限已加载区块；地下找矿的 isExposed 规矩不受影响。
        if (anyLog && trekToVisibleTree()) {
            return;
        }
        escalateSearch();
    }

    /**
     * 朝远处看得见的树跋涉一步（第六轮修复）。成功下发 goto 返回 true。
     * 地下不远眺（高度图看到的是头顶地表，跋涉无从走起），交给 ascend/扩围处理。
     */
    private boolean trekToVisibleTree() {
        BlockPos me = npc.blockPosition();
        int groundAtMe = npc.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                me.getX(), me.getZ());
        if (me.getY() < groundAtMe - 8) {
            return false;
        }
        BlockPos tree = findVisibleTreeColumn(80);
        if (tree == null) {
            return false;
        }
        // 太近的树 mine 技能自己会处理，跋涉只管 8 格半径之外的。
        if (me.distSqr(tree) <= 100.0D) {
            return false;
        }
        // 瞄准点别选树干列本身（整列没有落脚格，goto 的终点解析必失败）：
        // 朝 NPC 方向回退 3 格，落在树旁的开阔地上。
        double dx = me.getX() - tree.getX();
        double dz = me.getZ() - tree.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        double aimX = tree.getX() + 0.5D + dx / len * 3.0D;
        double aimZ = tree.getZ() + 0.5D + dz / len * 3.0D;
        JsonObject params = new JsonObject();
        params.addProperty("x", aimX);
        params.addProperty("z", aimZ);
        if (skills.run(new Intent("", "goto", params), "目标")) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " trek-to-tree | tree="
                    + tree.toShortString() + " dist="
                    + String.format(Locale.ROOT, "%.0f", Math.sqrt(me.distSqr(tree))));
            return true;
        }
        // 【第四十六轮补2】分段推进：整跳在台地地形里必死——落点回退到 24~32 格后
        // A* 仍要跨断崖，节点/时间预算内找不到路（恶地复跑实证：树在 93 格外、
        // 高出 40 格的台顶，整跳与回退跳全灭）。改沿同一射线只走 16 格一小段：
        // 小范围 A* 在台地坡面通常有路（escalate 的 8 格跳在本局能走动为证），
        // 采集失败轮次会反复调用本步、逐轮向树收敛，进 mine 半径后技能接管。
        double hopLen = Math.min(12.0D, len - 3.0D);
        if (hopLen > 4.0D) {
            JsonObject hop = new JsonObject();
            hop.addProperty("x", me.getX() + 0.5D - dx / len * hopLen);
            hop.addProperty("z", me.getZ() + 0.5D - dz / len * hopLen);
            if (skills.run(new Intent("", "goto", hop), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " trek-to-tree 分段推进 | tree="
                        + tree.toShortString() + " dist="
                        + String.format(Locale.ROOT, "%.0f", Math.sqrt(me.distSqr(tree)))
                        + " hop=" + String.format(Locale.ROOT, "%.0f", hopLen));
                return true;
            }
        }
        // 瞄到了树但走不过去也要留痕，否则日志上和"没找到树"分不出来
        // （恶地复跑靠 goto 侧流水才定位到落点判死）。
        LOG.info("[AIV][GOAL] " + npc.npcName() + " trek-to-tree 未能出发 | tree="
                + tree.toShortString() + " dist="
                + String.format(Locale.ROOT, "%.0f", Math.sqrt(me.distSqr(tree)))
                + " outcome=" + skills.lastOutcome());
        return false;
    }

    /**
     * 在已加载区块里找最近的"地表有树"列：高度图（WORLD_SURFACE，含树冠）
     * 顶格是原木或树叶即是；未加载区块一律跳过——绝不为了找树去加载/生成新区块。
     *
     * <p>【第四十六轮】采样步长 3→2、顶格下探放宽到 3 格：林地恶地的橡树稀疏
     * 矮小，步长 3 的采样网格大概率整棵漏过（恶地局实证：树在 45 格外、
     * trek 全程 0 命中、41 分钟卡死木头线）；顶格偶被藤蔓等盖住时，
     * 下探范围内的原木/树叶也算树列。半径 80、步长 2 约 6.5k 列采样/次，
     * 只在采集失败轮次调用，高度图读已加载区块，成本可控。
     */
    private BlockPos findVisibleTreeColumn(int maxRadius) {
        if (!(npc.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -maxRadius; dx <= maxRadius; dx += 2) {
            for (int dz = -maxRadius; dz <= maxRadius; dz += 2) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (serverLevel.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    continue;
                }
                int topY = serverLevel.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                if (topY <= serverLevel.getMinY() + 4) {
                    continue;
                }
                boolean treeTop = false;
                for (int dy = 1; dy <= 3 && !treeTop; dy++) {
                    net.minecraft.world.level.block.state.BlockState st =
                            serverLevel.getBlockState(new BlockPos(x, topY - dy, z));
                    treeTop = st.is(net.minecraft.tags.BlockTags.LOGS)
                            || st.is(net.minecraft.tags.BlockTags.LEAVES);
                }
                if (!treeTop) {
                    continue;
                }
                BlockPos cand = new BlockPos(x, topY, z);
                double sq = center.distSqr(cand);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = cand;
                }
            }
        }
        return best;
    }

    /**
     * 找资源失败时的扩围移动（第三轮修复 E 后半）：第 n 次失败就走得更远，
     * 并且优先挑比当前位置更低的方向——长跑实测 NPC 在没树的山顶上方圆 8 格
     * 反复打转、采集放弃 19 次；树一般长在山坡和谷地，下山找树才是玩家的直觉。
     *
     * <p>8 个方向各采样一次目标列的地表高度（MOTION_BLOCKING_NO_LEAVES，
     * 和规划器判"在不在地下"用的是同一张高度图），选最低的方向下 goto；
     * 所有方向都走不通时退回原来的小范围 wander()。
     */
    private void escalateSearch() {
        int[] distances = {8, 16, 24, 32, 48};
        BlockPos me = npc.blockPosition();
        int currentY = me.getY();
        // 未加载/未生成区块的高度图会返回世界底线值（-64）：不排除的话它永远"最低"，
        // NPC 会一路朝没生成的地形狂奔（短验实测 10 分钟跑出 100 多格还没找到树）。
        int invalidY = npc.level().getMinY() + 4;
        // 【第四十六轮】罗盘收敛（恶地局定因）：旧逻辑只有"下坡首跳 + 惯性保持/
        // 中断转 45°"，在盆地里"走得动"会被惯性判为方向可行、绕着盆地空转，
        // 40 分钟活动范围没出出生点 ±120 格（航向角一路累加到 3465°）。
        // 改：盲跳满 6 跳仍无果，强制把航向换到"累计派跳最少的扇区"（扇区记账
        // 跨目标保留、找到资源才清零）——8 个扇区轮着搜，最多 48 跳必扫完一圈，
        // 杜绝在同一片空地里重复兜圈。下坡首跳与惯性仍管前 6 跳的玩家式直觉。
        if (gatherEscalateHops >= 6) {
            int bestSector = 0;
            for (int s = 1; s < 8; s++) {
                if (gatherSectorHops[s] < gatherSectorHops[bestSector]) {
                    bestSector = s;
                }
            }
            gatherSearchAngle = bestSector * Math.PI / 4.0D;
            gatherSearchAnchor = null;
            gatherEscalateHops = 0;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " search-compass | 扩围盲跳 6 次无果，"
                    + "强制换向最少搜过扇区 heading=" + (bestSector * 45) + "° sectorHops="
                    + java.util.Arrays.toString(gatherSectorHops));
        }
        // 方向惯性（fix18）：先结算上一跳的实际水平位移——走够 8 格说明这个
        // 方向可行，保持航向并升一档走更远；没走动（路径中断/目标不可站立）
        // 就把航向转 45° 换方向。玩家找树就是这么干的：认准一个方向走到底，
        // 而不是每分钟重挑一次"最低处"在盆地里兜圈。
        if (gatherSearchAnchor != null && gatherSearchAngle >= 0) {
            double movedX = me.getX() - gatherSearchAnchor.getX();
            double movedZ = me.getZ() - gatherSearchAnchor.getZ();
            if (Math.sqrt(movedX * movedX + movedZ * movedZ) >= 8) {
                gatherSearchTier = Math.min(gatherSearchTier + 1, distances.length - 1);
            } else {
                gatherSearchAngle += Math.PI / 4.0D;
            }
        }
        if (gatherSearchAngle < 0) {
            // 首次扩围没有航向：沿用旧规矩，8 个方向采样地表、挑最低的起步。
            int probe = distances[gatherSearchTier];
            int bestSurfaceY = Integer.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0D;
                int tx = (int) Math.floor(npc.getX() + Math.cos(angle) * probe);
                int tz = (int) Math.floor(npc.getZ() + Math.sin(angle) * probe);
                int surfaceY = npc.level().getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
                if (surfaceY <= invalidY) {
                    continue;
                }
                if (surfaceY < bestSurfaceY) {
                    bestSurfaceY = surfaceY;
                    gatherSearchAngle = angle;
                }
            }
            if (gatherSearchAngle < 0) {
                wander();
                return;
            }
        }
        // 沿航向前进：正前方目标列未生成（高度图无效）时依次转 45° 找可用方向，
        // 采纳的方向成为新航向（不然下一步的位移结算会把航向搅乱）。
        int distance = distances[gatherSearchTier];
        for (int tries = 0; tries < 8; tries++) {
            double angle = gatherSearchAngle + tries * Math.PI / 4.0D;
            int tx = (int) Math.floor(npc.getX() + Math.cos(angle) * distance);
            int tz = (int) Math.floor(npc.getZ() + Math.sin(angle) * distance);
            int surfaceY = npc.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
            if (surfaceY <= invalidY) {
                continue;
            }
            gatherSearchAngle = angle;
            gatherSearchAnchor = me;
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * distance);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                // 【第四十六轮】扇区记账：这一跳派给了哪个罗盘扇区。
                gatherEscalateHops++;
                double norm = ((angle % (Math.PI * 2.0D)) + Math.PI * 2.0D) % (Math.PI * 2.0D);
                gatherSectorHops[(int) Math.round(norm / (Math.PI / 4.0D)) % 8]++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 找不到 " + targetId
                        + "，扩围找资源 | 第" + attempts + "次 距离=" + distance
                        + " 航向=" + Math.round(Math.toDegrees(angle)) + "° 档位=" + gatherSearchTier
                        + " 当前Y=" + currentY + " 目标方向地表Y=" + surfaceY);
                return;
            }
            break;
        }
        wander();
    }

    private boolean isAnyLogTarget() {
        return "*_log".equals(targetId) || "log".equals(targetId) || "wood".equals(targetId)
                || targetId.endsWith("_log");
    }

    /**
     * 当前采集目标在背包里的总量。
     * 目标是"任意原木"时按所有 *_log 合计：丛林里可能同时有丛林木、橡木、深色橡木，
     * 只统计某一种会让进度一直不动。
     */
    private int gatherCount() {
        if (isAnyLogTarget()) {
            return countItemsEndingWith("_log");
        }
        return countItem(itemPathForBlock(targetId));
    }

    /** 扫描范围内是否有指定方块（按方块 ID 精确匹配），返回最近的位置；没有则 null。 */
    private BlockPos nearbyBlockPos(String expectedPath) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-GATHER_RADIUS, -3, -GATHER_RADIUS),
                center.offset(GATHER_RADIUS, 3, GATHER_RADIUS))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!expectedPath.equals(path)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 附近没有目标方块时换个位置找。
     *
     * <p>只试一个 8-14 格的方向在丛林里几乎必失败：树冠和陡坡会把 A* 的节点预算吃光
     * （日志里的「超出节点上限」就是这么来的），NPC 于是一直站在原地。
     * 改成多试几个更近的方向（4-9 格），总有一个方向能走出去。
     */
    private void wander() {
        String reason = "附近没有 " + targetId + "，换个位置寻找";
        for (int attempt = 0; attempt < 5; attempt++) {
            double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D;
            double distance = 4.0D + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 5.0D;
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * distance);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + reason + " accepted=true"
                        + " 距离=" + String.format(Locale.ROOT, "%.1f", distance));
                return;
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " " + reason + " accepted=false（5 个方向都走不通）");
    }

    /** 方块挖掉后掉落的物品 ID。大多数同名，少数需要映射。 */
    private static String itemPathForBlock(String blockPath) {
        return switch (blockPath) {
            case "stone" -> "cobblestone";
            case "deepslate" -> "cobbled_deepslate";
            case "grass_block" -> "dirt";
            case "coal_ore", "deepslate_coal_ore" -> "coal";
            case "iron_ore", "deepslate_iron_ore" -> "raw_iron";
            case "copper_ore", "deepslate_copper_ore" -> "raw_copper";
            case "gold_ore", "deepslate_gold_ore" -> "raw_gold";
            case "diamond_ore", "deepslate_diamond_ore" -> "diamond";
            case "emerald_ore", "deepslate_emerald_ore" -> "emerald";
            case "lapis_ore", "deepslate_lapis_ore" -> "lapis_lazuli";
            case "redstone_ore", "deepslate_redstone_ore" -> "redstone";
            // 树叶、草这类会掉落随机种子/树苗，不适合按数量采集，保持同名让模型看到 0 进度后自己换目标。
            default -> blockPath;
        };
    }

    // ---------------------------------------------------------------- 合成

    private void tickCraft() {
        String counted = countPath(targetId);
        int have = countItemFor(counted);
        status = "合成 " + targetId + " " + have + "/" + targetCount;
        if (have >= targetCount) {
            finish("合成完成 " + targetId + " " + have + "/" + targetCount);
            return;
        }

        // 合成前先保证有落脚槽位：递归合成会产出木板/木棍/成品三次，
        // 背包满时第一步就失败，看起来像"缺材料"，实际是没地方放。
        // 【fix129】门槛由 ≥1 格改 ≥3 格：中途产物要占缓冲格（原木→木板 1 格
        // →木棍 1 格→成品 1 格），旧版只保 1 格时链做到一半产物无处落、整链
        // 回滚，结局文案却报回滚后的空槽数（1~2 格），看着像有空位实则中途
        // 峰值不够（farm93 实证：stone_pickaxe 放不下|空槽=2 等同型 14 次）。
        // 玩家口径：做东西前先腾够地方再上台。腾不动（全是必需品）就维持
        // 现状失败+退避，靠退避让位与回锚点存箱自解，不硬扔名单外物品。
        {
            int emptyBefore = countEmptyMainSlots();
            for (int guard = 0; guard < 4 && countEmptyMainSlots() < 3; guard++) {
                if (dropJunk(3 - countEmptyMainSlots()) <= 0) {
                    break;
                }
            }
            int emptyAfter = countEmptyMainSlots();
            if (emptyAfter != emptyBefore) {
                LOG.info("[AIV][PLAN] " + npc.npcName() + " 合成前腾格 | 空槽 "
                        + emptyBefore + "→" + emptyAfter + "（递归合成要 3 格缓冲）阶段=" + targetId);
            }
        }

        // 一次 tick 内尽量把整批做完；有上限，避免配方写错时死循环。
        for (int guard = 0; guard < 64 && countItemFor(counted) < targetCount; guard++) {
            String result = craftOne(targetId);
            if (!OK.equals(result)) {
                // 把空槽数一起打出来：合成失败有时不是缺材料，而是产物没地方放（give 失败）。
                finish("合成失败：" + result + " | 空槽=" + countEmptyMainSlots());
                return;
            }
        }
        int now = countItemFor(counted);
        if (now >= targetCount) {
            finish("合成完成 " + targetId + " " + now + "/" + targetCount);
        } else {
            finish("合成失败：" + targetId + " 只做到 " + now + "/" + targetCount);
        }
    }

    /** 合成链最大递归深度：原木→木板→木棍→石镐 最多 4 层，留点余量同时防止配方环。 */
    private static final int MAX_RECIPE_DEPTH = 6;

    /** 从服务端配方表里选出来的一条可执行配方：slots 每格代表消耗一份材料。 */
    private record AutoRecipe(java.util.List<org.bukkit.inventory.RecipeChoice> slots, int outputCount) {
    }

    /**
     * 合成一次。配方<b>不写死</b>，而是查服务端的原版配方表（Bukkit#getRecipesFor），
     * 所以 原木→木板、竹子→竹块→竹木板、木板+木棍→木镐 全都能走通；
     * 模型以后说任何原版物品（包括数据包新增的配方）都能做。
     */
    private String craftOne(String itemPath) {
        // 为什么失败要回滚：递归合成会先把木板做成木棍、再回头发现剑身没料了，
        // 一次失败就把整批木料吃干净；下一次重试从更差的库存出发，永远补不回来。
        // 合成必须是"要么全成、要么原样不动"，玩家也是先把材料点够再一次性放上台面。
        java.util.List<ItemStack> before = snapshotInventory();
        String result = craftOneRaw(itemPath);
        if (!OK.equals(result)) {
            restoreInventory(before);
        }
        return result;
    }

    /** 工作台 36 格主背包的快照（不含护甲槽，合成只动主背包）。 */
    private java.util.List<ItemStack> snapshotInventory() {
        java.util.List<ItemStack> copy = new java.util.ArrayList<>(MAIN_SLOTS);
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            copy.add(npc.getInventory().getItem(slot).copy());
        }
        return copy;
    }

    private void restoreInventory(java.util.List<ItemStack> snapshot) {
        for (int slot = 0; slot < MAIN_SLOTS && slot < snapshot.size(); slot++) {
            npc.getInventory().setItem(slot, snapshot.get(slot).copy());
        }
    }

    private String craftOneRaw(String itemPath) {
        // "planks"/"oak_planks" 都按“任意木板”处理：模型指定树种时不该被附近没有该树种卡死。
        if ("planks".equals(itemPath) || itemPath.endsWith("_planks")) {
            for (org.bukkit.Material material : org.bukkit.Material.values()) {
                if (!material.name().endsWith("_PLANKS") || !material.isItem()) {
                    continue;
                }
                String candidate = material.getKey().getKey();
                if (OK.equals(craftFromRecipe(candidate, 0, new java.util.HashSet<>()))) {
                    return OK;
                }
            }
            return "没有任何木板能做（缺少原木或竹子）";
        }
        return craftFromRecipe(itemPath, 0, new java.util.HashSet<>());
    }

    /**
     * 递归合成：先查目标物品的配方，缺哪一格材料就先合成那格，最后统一扣料。
     * 【fix244】同一产物有多条配方时逐条兜底尝试：首选配方补料失败就换下一条，
     * 全败才按第一条的错误收场。起因是 bone_meal：bestRecipe 的「非纯采集物优先」
     * 把骨块配方排在骨头直合之前，而骨块只能用骨粉合（原版可逆对），链首步就
     * 回指产物本身、必判配方循环，包里有骨头也永不尝试第二条——新图实证热循环
     * 132 次确定性失败、establish 卡约 1.5 小时。
     *
     * @param visiting 当前合成链上的物品，用于阻止 A 需要 B、B 又需要 A 的配方环
     */
    private String craftFromRecipe(String itemPath, int depth, java.util.Set<String> visiting) {
        if (depth >= MAX_RECIPE_DEPTH) {
            return "合成链太深（" + itemPath + "）";
        }
        if (!visiting.add(itemPath)) {
            return "配方循环：" + itemPath;
        }
        try {
            java.util.List<AutoRecipe> ranked = rankedRecipes(itemPath);
            if (ranked.isEmpty()) {
                LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                        + " 无配方！所有物品配方数=" + org.bukkit.Bukkit.getRecipesFor(
                                new org.bukkit.inventory.ItemStack(
                                        org.bukkit.Material.matchMaterial(itemPath) != null
                                                ? org.bukkit.Material.matchMaterial(itemPath)
                                                : org.bukkit.Material.AIR
                                )).size());
                return "没有 " + itemPath + " 的合成配方";
            }
            String firstError = null;
            for (int attempt = 0; attempt < ranked.size(); attempt++) {
                // 【fix316】产物循环配方（材料本身是用目标产物合的，如骨粉的骨块配方：
                // 骨块←9 骨粉）在产物不够时会被 visiting 判循环、正常回落直合配方；
                // 但产物一旦够合出材料（骨粉≥9），补料先把产物合成骨块再拆回骨粉，
                // 单次合成净变化 0 却返回 OK，tickCraft 误当进展烧光 64 次 guard，
                // 整单以「只做到 9/12」假失败收场（170 棒定谳：骨 x4 派单往返 61 次、
                // 第 4 根骨头始终没磨）。还有其他配方可试时跳过这类配方；只剩它
                // 一条时不跳（海块→小麦那类唯一配方仍要能走，保底不堵死）。
                if (ranked.size() > 1 && isProductCycleRecipe(itemPath, ranked.get(attempt))) {
                    LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                            + " 配方[" + attempt + "]是产物循环配方，跳过（fix316）");
                    if (firstError == null) {
                        firstError = "配方循环：" + itemPath;
                    }
                    continue;
                }
                String error = tryCraftWithRecipe(itemPath, ranked.get(attempt), depth, visiting);
                if (OK.equals(error)) {
                    return OK;
                }
                if (firstError == null) {
                    firstError = error;
                }
                if (attempt + 1 < ranked.size()) {
                    LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                            + " 配方[" + attempt + "]失败：" + error + "，换下一条配方重试");
                }
            }
            return firstError;
        } finally {
            visiting.remove(itemPath);
        }
    }

    /**
     * 【fix316】这条配方是不是「产物循环配方」：某个材料候选 C 的配方里用到目标产物
     * 本身（C 由产物合成，如骨块的配方用骨粉），或配方直接以产物本身为材料。
     * 只查一跳：C 的配方用到了 itemPath 即算，不再向下递归。
     */
    private boolean isProductCycleRecipe(String itemPath, AutoRecipe recipe) {
        for (org.bukkit.inventory.RecipeChoice choice : recipe.slots()) {
            for (String candidate : candidatePaths(choice)) {
                if (candidate.equals(itemPath)) {
                    return true;
                }
                for (AutoRecipe sub : rankedRecipes(candidate)) {
                    for (org.bukkit.inventory.RecipeChoice subChoice : sub.slots()) {
                        if (candidatePaths(subChoice).contains(itemPath)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** 用指定的一条配方实际合成一次：整表复核补齐材料后统一扣料、产出；成功返回 OK，失败返回错误文案。 */
    private String tryCraftWithRecipe(String itemPath, AutoRecipe recipe, int depth,
                                      java.util.Set<String> visiting) {
            LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                    + " 配方=" + recipe.slots().size() + "格");
            // 相同材料格要合并计数：3 块圆石占 3 格，不能按"有 1 块就够"处理。
            java.util.Map<org.bukkit.inventory.RecipeChoice, Integer> required = new java.util.LinkedHashMap<>();
            for (org.bukkit.inventory.RecipeChoice choice : recipe.slots()) {
                required.merge(choice, 1, Integer::sum);
            }
            // 材料之间会互相消耗：补木棍要吃掉木板，补完回头看木板又不够了。
            // 只按顺序对一遍就扣料，木镐这种"3 木板+2 木棍"的配方永远差一块木板，
            // 再被 craftOne 的失败回滚还原成 1 根原木，规划器只能无限重试（实测连续失败 7+ 次）。
            // 改为整表复核：任何一次补料后都从头再对，直到一整轮都不用补，才算同时够数。
            for (int pass = 0; pass < 8; pass++) {
                boolean craftedAny = false;
                for (java.util.Map.Entry<org.bukkit.inventory.RecipeChoice, Integer> entry : required.entrySet()) {
                    int need = entry.getValue();
                    LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                            + " 需要 " + need + "×" + describeChoice(entry.getKey())
                            + " 现有=" + countMatching(entry.getKey()));
                    int guard = 0;
                    while (countMatching(entry.getKey()) < need) {
                        if (++guard > 16) {
                            return "补料多次仍凑不齐：" + describeChoice(entry.getKey());
                        }
                        String error = craftMissing(entry.getKey(), depth + 1, visiting);
                        if (error != null) {
                            LOG.info("[AIV][GOAL-DEBUG] craftFromRecipe item=" + itemPath + " depth=" + depth
                                    + " craftMissing 返回 " + error);
                            return error;
                        }
                        craftedAny = true;
                    }
                }
                if (!craftedAny) {
                    break;
                }
                if (pass == 7) {
                    return "材料互相消耗，凑不齐：" + itemPath;
                }
            }
            for (java.util.Map.Entry<org.bukkit.inventory.RecipeChoice, Integer> entry : required.entrySet()) {
                if (!consumeMatching(entry.getKey(), entry.getValue())) {
                    return "材料不足：" + describeChoice(entry.getKey());
                }
            }
            String given = give(itemPath, recipe.outputCount());
            if (!OK.equals(given)) {
                return given;
            }
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 合成 " + itemPath + " x" + recipe.outputCount());
            return OK;
    }

    /** 某一格材料背包里没有时，从候选物品里挑一个能合成的递归做出来。 */
    private String craftMissing(org.bukkit.inventory.RecipeChoice choice, int depth,
                                java.util.Set<String> visiting) {
        String lastError = null;
        // 【fix126】「背包已满」优先报真因：farm89 实证——木板候选里橡木/白桦
        // 明明有原木、真因是背包已满产物放不下，其余树种真缺料；旧版返回
        // 最后一个候选的错误（樱花原木），把真因掩盖成「缺少材料」，规划层
        // 按文案触发的退避（fix116）随之落空、主线热循环。任一候选报背包
        // 已满就优先返回第一个该错误，其余维持 lastError 口径。
        String firstFullError = null;
        for (String candidate : candidatePaths(choice)) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(candidate);
            if (material == null || !material.isItem()) {
                continue;
            }
            // 该物品本身没有合成配方（只能通过采集/冶炼获取）：跳过，
            // 背包里本来就没有它，craftFromRecipe 会报"没有合成配方"，
            // 这不是真正的失败——真正的失败是所有候选都试过了。
            if (org.bukkit.Bukkit.getRecipesFor(new org.bukkit.inventory.ItemStack(material)).isEmpty()) {
                continue;
            }
            LOG.info("[AIV][GOAL-DEBUG] craftMissing 尝试 candidate=" + candidate
                    + " depth=" + depth + " visiting=" + visiting);
            String error = craftFromRecipe(candidate, depth, visiting);
            LOG.info("[AIV][GOAL-DEBUG] craftMissing candidate=" + candidate
                    + " error=" + error);
            if (OK.equals(error)) {
                return null;
            }
            if (firstFullError == null && error != null && error.contains("背包已满")) {
                firstFullError = error;
            }
            lastError = error;
        }
        if (firstFullError != null) {
            return firstFullError;
        }
        return lastError != null ? lastError : "缺少材料：" + describeChoice(choice);
    }

    /** 判断配方是不是"纯采集物配方"——所有槽的候选都是无合成配方的采集物（竹子、甘蔗等）。 */
    private boolean isRawRecipe(AutoRecipe recipe) {
        for (org.bukkit.inventory.RecipeChoice choice : recipe.slots()) {
            for (String candidate : candidatePaths(choice)) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(candidate);
                if (material != null && material.isItem()
                        && !org.bukkit.Bukkit.getRecipesFor(new org.bukkit.inventory.ItemStack(material)).isEmpty()) {
                    // 只要有一个候选本身有合成配方，就不是纯采集物配方
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 在服务端配方表里挑一条目标物品的配方。
     * 同一产物常有多条配方（不同树种木板、火把用煤或木炭），
     * 选"背包现有材料覆盖最多"的那条，避免拿着白桦原木去做橡木木板。
     */
    private AutoRecipe bestRecipe(String itemPath) {
        java.util.List<AutoRecipe> ranked = rankedRecipes(itemPath);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * 【fix244】把目标物品的全部候选配方按原 bestRecipe 的比较口径排序返回（首选在前），
     * 供 craftFromRecipe 逐条兜底尝试；bestRecipe 只取第一条。
     */
    private java.util.List<AutoRecipe> rankedRecipes(String itemPath) {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(itemPath);
        if (material == null || !material.isItem()) {
            return java.util.List.of();
        }
        java.util.List<org.bukkit.inventory.Recipe> recipes = Bukkit.getServer()
                .getRecipesFor(new org.bukkit.inventory.ItemStack(material));
        LOG.info("[AIV][GOAL-DEBUG] bestRecipe item=" + itemPath + " 找到配方数=" + recipes.size());
        java.util.List<ScoredRecipe> scored = new java.util.ArrayList<>();
        for (org.bukkit.inventory.Recipe bukkitRecipe : recipes) {
            AutoRecipe candidate = toAutoRecipe(bukkitRecipe);
            if (candidate == null) {
                continue;
            }
            boolean complete = true;
            int score = 0;
            for (org.bukkit.inventory.RecipeChoice choice : candidate.slots()) {
                if (countMatching(choice) > 0) {
                    score++;
                } else {
                    complete = false;
                }
            }
            boolean candIsRaw = isRawRecipe(candidate);
            LOG.info("[AIV][GOAL-DEBUG] bestRecipe item=" + itemPath
                    + " complete=" + complete + " score=" + score
                    + " slots=" + candidate.slots().size() + " raw=" + candIsRaw
                    + " 材料=" + describeChoice(candidate.slots().get(0))
                    + (candidate.slots().size() > 1 ? "+" + describeChoice(candidate.slots().get(1)) : ""));
            // 同一产物常有多条配方（木棍既可用 2 块木板、也可用 2 根竹子）。
            // 先比"是不是纯采集物配方"（竹子/甘蔗→木棍）：纯采集物配方是死胡同——
            // 竹子没有合成链、只能硬凑数量；木板有合成链（原木→木板→木棍），
            // 哪怕现在没木板但有原木就能补。所以只要同时存在非纯采集物配方，
            // 不管纯采集物是不是"刚好凑齐"都先选非纯采集物的——
            // 实测 1 根竹子能凑齐 bamboo_stick 配方的 1/2 格（score=1 complete=false），
            // 甚至 2 根竹子能凑齐（complete=true），但 NPC 有 3 棵原木能走木板路线，
            // 硬吃竹子配方只会让 1 根竹子变不成 2 根、永远卡着。
            // 只有"所有配方都是纯采集物"或"非纯采集物路线真的走不通"时才退回采集物配方。
            scored.add(new ScoredRecipe(candidate, complete, score, candIsRaw));
        }
        // 排序口径与原逐个比较完全一致：非纯采集物优先 → 材料齐优先 →
        // 都齐时格数少优先 → 都不齐时覆盖格数多优先 → 格数少优先。
        // 稳定排序保证完全同分时维持配方表原序（与原「先到者胜」一致）。
        scored.sort((a, b) -> {
            if (a.raw() != b.raw()) {
                return a.raw() ? 1 : -1;
            }
            if (a.complete() != b.complete()) {
                return a.complete() ? -1 : 1;
            }
            if (a.complete()) {
                return Integer.compare(a.recipe().slots().size(), b.recipe().slots().size());
            }
            if (a.score() != b.score()) {
                return Integer.compare(b.score(), a.score());
            }
            return Integer.compare(a.recipe().slots().size(), b.recipe().slots().size());
        });
        java.util.List<AutoRecipe> ranked = new java.util.ArrayList<>(scored.size());
        for (ScoredRecipe entry : scored) {
            ranked.add(entry.recipe());
        }
        return ranked;
    }

    /** rankedRecipes 内部排序用的一条候选配方的评估结果。 */
    private record ScoredRecipe(AutoRecipe recipe, boolean complete, int score, boolean raw) {
    }

    private AutoRecipe toAutoRecipe(org.bukkit.inventory.Recipe bukkitRecipe) {
        java.util.List<org.bukkit.inventory.RecipeChoice> slots = new java.util.ArrayList<>();
        int outputCount = bukkitRecipe.getResult() == null ? 0 : bukkitRecipe.getResult().getAmount();
        if (bukkitRecipe instanceof org.bukkit.inventory.ShapedRecipe shaped) {
            java.util.Map<Character, org.bukkit.inventory.RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (int index = 0; index < row.length(); index++) {
                    char symbol = row.charAt(index);
                    if (symbol == ' ') {
                        continue;
                    }
                    org.bukkit.inventory.RecipeChoice choice = choices.get(symbol);
                    if (choice != null) {
                        slots.add(choice);
                    }
                }
            }
        } else if (bukkitRecipe instanceof org.bukkit.inventory.ShapelessRecipe shapeless) {
            slots.addAll(shapeless.getChoiceList());
        } else {
            // 熔炉/切石机等需要工作站，目标层暂不模拟；只做工作台配方。
            return null;
        }
        if (slots.isEmpty() || outputCount <= 0) {
            return null;
        }
        return new AutoRecipe(slots, outputCount);
    }

    /** 一格里允许放哪些物品（配方用标签时会给出多个候选）。 */
    private java.util.List<String> candidatePaths(org.bukkit.inventory.RecipeChoice choice) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        if (choice instanceof org.bukkit.inventory.RecipeChoice.MaterialChoice materialChoice) {
            for (org.bukkit.Material material : materialChoice.getChoices()) {
                paths.add(material.getKey().getKey());
            }
        } else if (choice instanceof org.bukkit.inventory.RecipeChoice.ExactChoice exactChoice) {
            for (org.bukkit.inventory.ItemStack stack : exactChoice.getChoices()) {
                paths.add(stack.getType().getKey().getKey());
            }
        } else if (choice instanceof org.bukkit.inventory.RecipeChoice.ItemTypeChoice itemTypeChoice) {
            for (io.papermc.paper.registry.TypedKey<org.bukkit.inventory.ItemType> key
                    : itemTypeChoice.itemTypes().values()) {
                paths.add(key.value());
            }
        }
        return paths;
    }

    /** 背包里能匹配这一格材料的物品总数（按原版标签判断，比如任意木板/任意原木）。 */
    private int countMatching(org.bukkit.inventory.RecipeChoice choice) {
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (choice.test(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean consumeMatching(org.bukkit.inventory.RecipeChoice choice, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!choice.test(org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(stack))) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) {
                npc.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        return remaining == 0;
    }

    private String describeChoice(org.bukkit.inventory.RecipeChoice choice) {
        java.util.List<String> paths = candidatePaths(choice);
        return paths.isEmpty() ? "未知材料"
                : String.join("|", paths.subList(0, Math.min(4, paths.size())));
    }

    // ---------------------------------------------------------------- 挖矿

    /**
     * 挖矿目标：先找附近暴露的目标方块，找不到就阶梯下挖到资源层，再水平掘进找矿。
     *
     * <p>这是"从地表发展成玩家"的关键一步：木头→石镐→石头→煤→铁→钻石，
     * 全部靠玩家式的挖掘方式获取，不凭空生成物品。
     */
    /**
     * 【fix71·D5】矿道插火把：规格"只插矿道/矿洞、间隔 8~11、省着用"。
     * 只在 dig 目标的技能空闲间隙执行（不打断掘进）：脚下真黑（块光 ≤2，
     * 有光/贴岩浆不插）且立体 8 格内没有既有火把时，在脚格插一支
     * （原版口径：敌对怪只在块光 0 生成，间距 8 已足够压住整段矿道）。
     */
    private void maybePlaceTorch() {
        if (++torchTickCounter % 10 != 0 || !"idle".equals(skills.currentSkill())) {
            return;
        }
        if (countItem("torch") <= 0) {
            return;
        }
        net.minecraft.core.BlockPos feet = npc.blockPosition();
        // 只插地下：BLOCK 光照在地表白天也是 0，单靠光照会在地表乱插；
        // 先按高度表判地下（与规划层 underground() 同口径），再看块光。
        int surface = npc.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(), feet.getZ());
        if (feet.getY() >= surface - 3) {
            return;
        }
        int light = npc.level().getBrightness(
                net.minecraft.world.level.LightLayer.BLOCK, feet);
        if (light > 2) {
            return;
        }
        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(
                feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            var s = npc.level().getBlockState(p);
            if (s.is(net.minecraft.world.level.block.Blocks.TORCH)
                    || s.is(net.minecraft.world.level.block.Blocks.WALL_TORCH)) {
                return;
            }
        }
        var feetState = npc.level().getBlockState(feet);
        if (!feetState.isAir() && !feetState.canBeReplaced()) {
            return;
        }
        net.minecraft.core.BlockPos below = feet.below();
        if (npc.level().getBlockState(below)
                .getCollisionShape(npc.level(), below).isEmpty()) {
            return;
        }
        if (!consume("torch", 1)) {
            return;
        }
        npc.level().setBlockAndUpdate(feet,
                net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 插火把 | pos=" + feet.toShortString()
                + " 光照=" + light + " 余量=" + countItem("torch"));
    }

    private void tickDig() {
        if (oreTarget == null) {
            finish("挖矿失败：缺少资源定义");
            return;
        }
        int absolute = countItem(oreTarget.drop());
        int have = Math.max(0, absolute - baselineCount);
        status = "挖矿 " + targetId + " 新采 " + have + "/" + targetCount
                + "（背包共 " + absolute + "，目标Y≈" + oreTarget.y() + "）";
        // 没有可用的镐：继续下挖/掘进每个技能都会立刻失败，
        // 直接结束目标，让规划器先去补工具（它会先做木镐/石镐）。
        if (!hasUsablePickaxe()) {
            recordSeenOre();
            finish("挖矿放弃：没有可用的镐，先补工具");
            return;
        }
        // 【第十七轮】有镐但级别不够同样当场收场：钻石/金/红石/绿宝石矿必须
        // 铁镐以上，铁/青金/铜矿必须石镐以上——长跑#6d 实测手里只剩石镐时
        // 旧版照常下发 mine 技能，身体层对着裸露钻石矿 mine-no-tool 刷 159 次、
        // 耗约 30 秒才靠"12 次无进展"放弃，矿也凉了。这里按矿种查镐，
        // 不够格立刻结束目标交回规划器补铁镐，人还在矿区附近，补完能再扫到。
        if (!hasHarvestPickaxe(oreTarget.block())) {
            recordSeenOre();
            finish("挖矿放弃：没有能挖 " + oreTarget.block() + " 的镐（级别不够），先补工具");
            return;
        }
        // 【第十八轮】石镐主力（Fong 工具分级口径）：除"挖石头"本身（那是补圆石
        // 的手段）外，任何挖矿目标开工/续工前必须有可用石镐——掘进段的普通
        // 石头/深板岩全靠它烧。石镐断了若不收场，身体层会自动拿铁镐顶上，
        // 一条矿道就能把铁镐烧掉大半（fix24 只改了选工具顺序，库存断供时
        // 顺序无从谈起）。当场收场交回规划器：它会用背包圆石当场补做石镐。
        if (!"stone".equals(oreTarget.block()) && !hasUsableStonePick()) {
            // 【fix101·Fong 2026-10-28 通则：守卫必须配兜底】旧口径无条件收场
            // 补石镐：合得出时没问题；合不出（缺木棍料/没工作台）时规划层也
            // 变不出石镐，挖矿目标与补镐步互相踢皮球成硬死角（与身体层
            // fix44 守卫同病，浇铸覆灭的同族现场）。改三路：合得出→照旧
            // 收场补造；合不出但有可用铁/钻镐→放行本目标掘进（身体层
            // pick-fallback 逐格兜底选镐，专行日志每目标一次）；合不出且
            // 无高级镐→收场交规划层补工具链（木镐/砍木头）。
            StringBuilder missing = new StringBuilder();
            if (npc.stonePickCraftableNow(missing)) {
                finish("挖矿放弃：石镐耗尽，先补石镐（石镐主力，铁镐只留给必需矿）");
                return;
            }
            if (hasUsablePickaxe()) {
                if (!digPickFallbackLogged) {
                    digPickFallbackLogged = true;
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " pick-fallback | 目标层放行 | reason=石镐耗尽且合不出石镐 missing="
                            + missing.toString().trim()
                            + " | 用铁/钻镐掘进 target=" + oreTarget.block());
                }
            } else {
                finish("挖矿放弃：石镐耗尽且合不出石镐、无高级镐可用，先补工具");
                return;
            }
        }
        // 上一条矿道如果挖空了，下一轮掘进前要先换位置（见下方步骤 3 前的处理）。
        noteStripOutcome();
        // 【fix142】消费 descend 的「无实际下降」结局：坡面幻影步旧版无人
        // 消费，dig 只能靠 stale 计数约 30 分钟收场（farm108 挖铁实证）。
        // 连败 ≥2 即收场交规划层换位（玩家在坡面挖不下去两三次就换地方）。
        noteDigDescendOutcome();
        if (digDescendNoDropFails >= 2) {
            finish("挖矿放弃：descend 两度无实际下降（坡面幻影步坏地形），交规划层换位");
            return;
        }
        // 【fix71·D5】矿道照明：技能空闲间隙顺手插火把（只在真黑、8 格内无火把时）。
        maybePlaceTorch();
        if (have >= targetCount) {
            finish("挖矿完成 " + targetId + " 新采 " + have + "/" + targetCount
                    + "，背包共 " + absolute);
            return;
        }
        // 技能正在跑的时候不计"没进展"：从地表阶梯下挖到 Y=48 要 20-30 秒，
        // 中途本来就捡不到煤。旧逻辑每 0.5 秒计一次，NPC 刚挖到一半就放弃了煤/铁目标。
        // 附近有敌对生物时同样不计：生存层会为自卫反复打断 descend/strip，
        // 目标因此不断重新下发技能，表象是原地不动，实际是在打怪。
        // 用 isEngaged() 而不是固定的 16 格扫描：打不到的怪会被生存层拉黑，
        // 否则这条捷径会让"没进展"永远不计数，目标卡死不放弃。
        SurvivalController survival = plugin.survival(npc.npcName());
        boolean defending = survival != null ? survival.isEngaged() : hostileNearby(5);
        if (defending) {
            staleChecks = 0;
        } else if ("idle".equals(skills.currentSkill())) {
            if (have == lastProgress) {
                // 阈值 12 次（约 6 秒）：descend 每一步都要重新下发技能，
                // 两段技能之间必然有几次 idle，5 次会在正常下矿途中误判成放弃。
                if (++staleChecks >= 12) {
                    finish("挖矿放弃：连续 12 次没有新进展 " + have + "/" + targetCount);
                    return;
                }
            } else {
                lastProgress = have;
                staleChecks = 0;
                stoneStripFails = 0;
                digDescendNoDropFails = 0;
            }
        }

        // 1) 半径 16 格内有裸露的目标矿就先去挖（Fong 02:53 探矿规格）：
        //    探测范围 8 → 16；仍然只认裸露矿（isExposed），全埋矿不入选、不透视。
        //    够不着的矿（洞顶/墙后/低处）由 mine 技能内部的够矿求解接手，
        //    这里只负责"看见并下单"。扫描节流与缓存见 exposedOreTarget()。
        BlockPos exposed = exposedOreTarget();
        if (exposed != null) {
            JsonObject params = new JsonObject();
            params.addProperty("block", oreTarget.block());
            params.addProperty("radius", 16);
            // 【fix302】把扫描选中的目标格直接交给 mine 单点试挖（见 SkillRunner.runMine）：
            // mine 只试这一格，verdict 精确归属；失败时本格进跳过表，下次扫描换下一块。
            params.addProperty("x", exposed.getX());
            params.addProperty("y", exposed.getY());
            params.addProperty("z", exposed.getZ());
            if (skills.run(new Intent("", "mine", params), "目标")) {
                return;
            }
            // mine 没跑起来：只有「候选全部不可达」才记跳过（目标格真试过且全灭）；
            // 「找不到方块」等另案不记（扫描与技能口径不一致时别把目标拉黑）。
            String mineOutcome = skills.lastOutcome();
            if (mineOutcome != null && mineOutcome.startsWith("mine 没有可到达的站立点")) {
                noteOreScanSkipped(exposed);
            }
        }

        // 2a) 泡在水里（或头还埋在水面下）时先上岸：矿道一旦挖进水域就被灌满，
        // 水下水平掘进四面都是流体，strip 只能原地转圈。玩家的做法是先游出去再从干地挖。
        // 但 dryland 如果连续 3 次失败（目标不可达或水太深游不动），就跳过，
        // 让 descend/strip 继续跑——它们会绕过障碍挖新通道，比无限重试 dryland 强。
        // 关键：dryland 正在跑时别 stop+restart 它（skills.run() 会先 stop），
        // 否则每 0.5 秒掐掉正在走的 dryland、重新开始，永远到不了终点。
        BlockPos wetFeet = npc.blockPosition();
        if ((isFluidAt(wetFeet) || isFluidAt(wetFeet.above())) && drylandFailures < 3) {
            if ("dryland".equals(skills.currentSkill())) {
                // 已经在跑了，等它结束再决定下一步
                attempts = 0;
                return;
            }
            // 上一轮 dryland 结束（不管成功失败）才会走到这里，检查结果
            String last = skills.lastOutcome();
            if (last != null && last.startsWith("dryland ") && !last.startsWith("dryland 已到干燥地面")) {
                drylandFailures++;
                if (drylandFailures >= 3) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " dryland 连续失败 " + drylandFailures
                            + " 次，跳过上岸直接下矿 | 最后结果=" + last);
                }
            } else if (last != null && last.startsWith("dryland 已到干燥地面")) {
                drylandFailures = 0;
            }
            if (drylandFailures < 3) {
                JsonObject params = new JsonObject();
                params.addProperty("radius", 16);
                if (skills.run(new Intent("", "dryland", params), "目标")) {
                    attempts = 0;
                    return;
                }
                drylandFailures++;
            }
        } else if (!isFluidAt(wetFeet) && !isFluidAt(wetFeet.above())) {
            // 已经不在流体里了，重置计数
            drylandFailures = 0;
        }

        // 资源层钳制（fix17，超平坦场景实测教训）：oreTarget 的层位是按正常世界
        // 地表（≈Y60）定的绝对值。石头写的是 Y=50——在地表 Y=-30 的超平坦世界里，
        // 挖石头会先 ascend 爬到 Y=51，在空中一路搭柱、最后栖在柱顶，
        // descend/strip 全部挖不动，目标层只能靠看门狗收场；铁层 Y=16 同理
        // 会爬到 Y=17。正常世界里这条也会把洞里挖石头的人拽上三十多层。
        // 修法：①石头到处都是，层位就取当前深度，永不为它赶路；
        // ②其余资源的层位钳到本地地表以下（min(层位, 地表-1)），
        // 层位高于地表时就在当前深度掘进，裸露矿由步骤 1 先行接手。
        int surfaceY = npc.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                npc.blockPosition().getX(), npc.blockPosition().getZ());
        int layerY = "stone".equals(oreTarget.block())
                ? npc.blockPosition().getY()
                : Math.min(oreTarget.y(), surfaceY - 1);

        // 2) 深度不够：阶梯下挖到资源层。挖矿途中会顺手捡到路上暴露的煤/铁。
        if (npc.blockPosition().getY() > layerY + 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", layerY);
            if (skills.run(new Intent("", "descend", params), "目标")) {
                attempts = 0;
                return;
            }
        }

        // 2b) 资源层在头顶（例如刚在 Y=16 挖完铁，现在要去 Y=48 挖煤）：
        // 旧逻辑只会往下挖，会一直在 Y=16 水平掘进找煤，一整个目标周期什么都挖不到。
        if (npc.blockPosition().getY() < layerY - 3) {
            JsonObject params = new JsonObject();
            params.addProperty("y", layerY + 1);
            if (skills.run(new Intent("", "ascend", params), "目标")) {
                attempts = 0;
                return;
            }
        }

        // 【第四十七轮】沼泽式地表无石兜底（沼泽局实证：strip 41 次圆石恒 0）：
        // 目标是石头、strip 已连败 ≥2 次、人还在地表附近时，水平掘进永远穿不过
        // 土层——改原地向下挖 6 格穿土找石（玩家解法）。石头的层位口径取当前
        // 深度，下挖后自动跟随，strip 在石层里就能出圆石；计数靠 noteStripOutcome
        // 记账、出任何进度即清零。下挖途中遇流体 descend 自有防护，失败了下一轮
        // 还在地表附近会再触发、逐次换位下探（relocate 换区也在并行）。
        if ("stone".equals(oreTarget.block()) && stoneStripFails >= 2
                && npc.blockPosition().getY() >= surfaceY - 3) {
            // 【fix114】下挖深度分两档：高度图会把人造柱/台地本身当成自家
            // 列的"地表"（surfaceY 读到柱顶），站在柱顶时 feetY≈surfaceY、
            // 旧口径只下挖 6 格穿"土层"，130 多格的柱子要磨二十多轮。
            // 改用 3×3 列高度图的最小值当真地面：脚比真地面高出 12 格
            // 以上判悬空（柱顶/台地边），一次 descend 到真地面；邻列高差
            // ≤12 的正常地表维持原 -6 穿土口径（山坡/崖边不误触发全程
            // 下潜——崖下也是石头，下去挖同样成立）。
            int feetY = npc.blockPosition().getY();
            int groundY = surfaceY;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    groundY = Math.min(groundY, npc.level().getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            npc.blockPosition().getX() + dx, npc.blockPosition().getZ() + dz));
                }
            }
            int digDownY = feetY > groundY + 12 ? groundY : feetY - 6;
            JsonObject params = new JsonObject();
            params.addProperty("y", digDownY);
            if (skills.run(new Intent("", "descend", params), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " dig-down-for-stone | strip 连败 "
                        + stoneStripFails + " 次，地表无石，改为向下挖穿土层找石"
                        + " 目标Y=" + digDownY + " 真地面=" + groundY);
                stoneStripFails = 0;
                attempts = 0;
                return;
            }
        }

        // 3) 已经在资源层：水平掘进，矿道壁上暴露的矿会顺手挖掉。
        //    上一轮挖空了就先挪 4-9 格再开新矿道：原地再下发 strip 只会把
        //    同一条空矿道再走一遍。goto 发不出去（封闭矿道很常见）就照常掘进。
        if (relocateBeforeStrip) {
            relocateBeforeStrip = false;
            wander();
            if (!"idle".equals(skills.currentSkill())) {
                return;
            }
        }
        JsonObject params = new JsonObject();
        params.addProperty("block", oreTarget.block());
        params.addProperty("length", 24);
        // 只挖还差的数量：石头工具耐久只有 131，多挖的每一块都在烧掉下一把镐子的寿命。
        params.addProperty("limit", Math.max(1, targetCount - have));
        if (skills.run(new Intent("", "strip", params), "目标")) {
            attempts = 0;
            return;
        }
        attempts++;
        if (attempts >= 4) {
            finish("挖矿放弃：无法开始掘进");
            return;
        }
        wander();
    }

    /**
     * 处理上一条矿道的结果：挖空了就标记"下一轮先换位置"。
     *
     * <p>只处理新出现的结果：目标层每 0.5 秒 tick 一次，
     * 同一个结果字符串如果每 tick 都判断，会反复触发 goto 而永远开不了新矿道。
     */
    private void noteStripOutcome() {
        // 【fix104】序号水位：同一结局字符串不要每 tick 重复触发"换点"，
        // 但同文案的新结局（同地点同死因）必须照常计入——字符串水位会把
        // 第二份起全吞掉（浇铸 noteObsidianOutcome 同病，farm66 实证）。
        int seq = skills.outcomeSeq();
        if (seq == handledStripSeq) {
            return;
        }
        handledStripSeq = seq;
        String outcome = skills.lastOutcome();
        // "未发现 + 已挖=0"是正常挖到头没矿；"前方无路可掘"是撞上悬空/流体/基岩。
        // 两种都说明这片区域已经挖不出东西了。
        // 【fix114】"timeout"（技能看门狗掐断）同列：strip 在空中/绝地里
        // 一块都挖不到、磨到超时才收场，同样是"此地挖不出东西"——旧口径
        // 不认 timeout 结局，farm78 实证 NPC 栖在 Y=197 柱顶 strip 40 秒
        // 一轮超时、连败永不计数，四十七轮的下挖取石兜底永远武装不起来。
        if (outcome.startsWith("strip ")
                && ((outcome.contains("未发现") && outcome.contains("已挖=0"))
                || outcome.contains("前方无路可掘")
                || outcome.contains("timeout"))) {
            relocateBeforeStrip = true;
            // 【第四十七轮】挖石头连败单独记账，给 tickDig 的下挖取石兜底当触发量。
            if (oreTarget != null && "stone".equals(oreTarget.block())) {
                stoneStripFails++;
            }
        }
    }

    /**
     * 【fix142】消费 dig 目标内 descend 的结局（序号水位线，照 noteObsidianOutcome
     * 范式）：只认 descend 结局——「到达」清零；含「无实际下降」（fix113 真降
     * 看门狗的幻影步判词）计数并打专行，其余结局不计。收场判定在 tickDig
     * 调用点（连败 ≥2），本方法只记账。
     */
    private void noteDigDescendOutcome() {
        int seq = skills.outcomeSeq();
        if (seq == handledDigDescendSeq) {
            return;
        }
        handledDigDescendSeq = seq;
        String outcome = skills.lastOutcome();
        if (outcome == null || !outcome.startsWith("descend ")) {
            return;
        }
        if (outcome.contains("到达")) {
            digDescendNoDropFails = 0;
            return;
        }
        if (!outcome.contains("无实际下降")) {
            return;
        }
        digDescendNoDropFails++;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " dig descend 无实际下降计数 | fails="
                + digDescendNoDropFails + " outcome=" + outcome + "（fix142）");
    }

    /**
     * 【fix142】消费 revisit 目标内 descend 的结局：与 dig 同范式——竖向段
     * （≤4 格分支）的 descend 旧版无人消费结局，坏地形悬停只能磨满
     * attempts/目标超时（farm105–108 山顶悬停、farm111 地下 480 秒超时）。
     * 收场判定在 tickRevisit 调用点（连败 ≥2），本方法只记账。
     */
    private void noteRevisitDescendOutcome() {
        int seq = skills.outcomeSeq();
        if (seq == handledRevisitDescendSeq) {
            return;
        }
        handledRevisitDescendSeq = seq;
        String outcome = skills.lastOutcome();
        if (outcome == null || !outcome.startsWith("descend ")) {
            return;
        }
        if (outcome.contains("到达")) {
            revisitDescendNoDropFails = 0;
            return;
        }
        if (!outcome.contains("无实际下降")) {
            return;
        }
        revisitDescendNoDropFails++;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " revisit descend 无实际下降计数 | fails="
                + revisitDescendNoDropFails + " outcome=" + outcome + "（fix142）");
    }

    /**
     * 【fix144】过境记矿：travel/revisit 途中节流（约 20 秒一次）扫半径 8 的
     * 裸露钻石/金矿，当前镐挖不了才记进 OreMemory（Fong fix40 口径：只记
     * 真实见到的裸露矿、备好工具回访）。旧版过境零扫描——farm111 回访途中
     * 在 (398,-16) 目击深板岩钻石矿、因无可用镐跳过却无人记账，矿就此丢失。
     * 只记账不停步：行进节奏与行为不变，账本自带 4 格去重/16 条上限/2 小时
     * 过期。铁/青金过境不记（dig 链与 recordSeenOre 已覆盖其主场景）。
     */
    private void maybeRecordTransitOre() {
        if (transitOreScanCooldown > 0) {
            transitOreScanCooldown--;
            return;
        }
        transitOreScanCooldown = 40;
        OreMemory.bindWorld(npc.level());
        for (String block : new String[]{"diamond_ore", "gold_ore"}) {
            if (hasHarvestPickaxe(block)) {
                continue;
            }
            BlockPos seen = scanExposedOre(block, 8);
            if (seen != null) {
                OreMemory.record(npc.npcName(), block, seen);
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 过境记矿 | block=" + block
                        + " pos=" + seen.toShortString()
                        + "（fix144：途中目击、当前镐挖不了）");
            }
        }
        // 【fix195】过境记岩浆：同节流扫一遍露面岩浆源，落 LavaMemory——
        // 黑曜石目标靠这本账直奔旧湖，不再从脚下盲凿竖井（案 A）。
        BlockPos lavaSeen = scanExposedLava(8, 4);
        if (lavaSeen != null) {
            LavaMemory.bindWorld(npc.level());
            LavaMemory.record(npc.npcName(), lavaSeen);
            if (lastLavaLedgerPos == null
                    || lastLavaLedgerPos.distSqr(lavaSeen) > 12.0D * 12.0D) {
                lastLavaLedgerPos = lavaSeen;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 过境记岩浆 | pos="
                        + lavaSeen.toShortString() + "（fix195：途中目击露面岩浆源）");
            }
        }
    }

    /** 扫描附近同名方块（矿石自动带深板岩变种），只返回至少一面暴露的。 */
    private BlockPos nearbyBlockPosAny(String expectedPath, int radius) {
        BlockPos center = npc.blockPosition();
        Set<String> variants = new java.util.HashSet<>();
        variants.add(expectedPath);
        if (expectedPath.endsWith("_ore") && !expectedPath.startsWith("deepslate_")) {
            variants.add("deepslate_" + expectedPath);
        }
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!variants.contains(path)) {
                continue;
            }
            // 被完全包住的方块挖掉后掉落物卡在固体里，伪玩家够不到，先跳过。
            if (!isExposed(pos)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 探矿入口（Fong 02:53 规格）：返回半径 16 内最近的裸露目标矿，没有返回 null。
     *
     * <p>节流：全扫一次要看 33×33×13 ≈ 1.4 万格，只允许在技能空闲的决策点、
     * 且距上次全扫至少 4 次决策时执行；间隔内先用缓存——目标格做单格复核
     * （方块还是目标矿变种、仍然裸露、仍在 17 格内），两次 getBlockState 就能判。
     * 缓存失效又赶上节流期就返回 null，本轮走 descend/strip 老路，几秒后再扫，
     * 不会因为等扫描把挖矿目标拖超时（goal-timeout 口径不变）。
     */
    private BlockPos exposedOreTarget() {
        if (oreScanCachePos != null && oreTarget.block().equals(oreScanCacheBlock)) {
            // 【fix302】缓存格进了跳过表（mine 已判全灭）就作废重扫，不再复用。
            if (isOreScanSkipped(oreScanCachePos)) {
                oreScanCachePos = null;
            } else {
                String path = BuiltInRegistries.BLOCK.getKey(
                        npc.level().getBlockState(oreScanCachePos).getBlock()).getPath();
                boolean variantOk = path.equals(oreTarget.block())
                        || path.equals("deepslate_" + oreTarget.block());
                if (variantOk && isExposed(oreScanCachePos)
                        && npc.blockPosition().distSqr(oreScanCachePos) <= 17.0D * 17.0D) {
                    return oreScanCachePos;
                }
                oreScanCachePos = null;
            }
        }
        if (oreScanCooldown > 0) {
            oreScanCooldown--;
            return null;
        }
        oreScanCooldown = 4;
        BlockPos found = scanExposedOre(oreTarget.block(), 16);
        oreScanCacheBlock = oreTarget.block();
        oreScanCachePos = found;
        if (found != null) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " ore-scan | found=" + lastOreScanFound
                    + " radius=16 best=" + found.toShortString() + " block=" + oreTarget.block());
        }
        return found;
    }

    /** 全扫半径内的裸露目标矿（含深板岩变种）：返回最近的，并把发现总数记进 lastOreScanFound。 */
    private BlockPos scanExposedOre(String expectedPath, int radius) {
        BlockPos center = npc.blockPosition();
        Set<String> variants = new java.util.HashSet<>();
        variants.add(expectedPath);
        if (expectedPath.endsWith("_ore") && !expectedPath.startsWith("deepslate_")) {
            variants.add("deepslate_" + expectedPath);
        }
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            String path = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).getPath();
            if (!variants.contains(path)) {
                continue;
            }
            // 不透视口径不变：全埋矿（六面无空气）不入选，哪怕它就在墙后一格。
            if (!isExposed(pos)) {
                continue;
            }
            // 【fix302】跳过 mine 已判「候选全部不可达」的目标格（60 秒内），不计入 found。
            if (isOreScanSkipped(pos.immutable())) {
                continue;
            }
            found++;
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        lastOreScanFound = found;
        return best;
    }

    /** 【fix302】记一格进探矿跳过表（mine 真试过且全灭），60 秒后自动解禁。 */
    private void noteOreScanSkipped(BlockPos pos) {
        long now = System.currentTimeMillis();
        // 先清过期项，再记新项；超 8 格删最旧。
        oreScanSkipUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (oreScanSkipUntil.size() >= 8) {
            java.util.Iterator<BlockPos> it = oreScanSkipUntil.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        oreScanSkipUntil.put(pos.immutable(), now + 60_000L);
        oreScanCachePos = null;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 探矿跳过（fix302） | pos=" + pos.toShortString()
                + " 原因=mine 候选全部不可达，60 秒内不再入选");
    }

    /** 【fix302】该格是否在探矿跳过表内（未过期）。 */
    private boolean isOreScanSkipped(BlockPos pos) {
        Long until = oreScanSkipUntil.get(pos);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            oreScanSkipUntil.remove(pos);
            return false;
        }
        return true;
    }

    /** 该格是否含流体（水/岩浆）。挖矿前用它判断"是不是还在水里"。 */
    private boolean isFluidAt(BlockPos pos) {
        return !npc.level().getBlockState(pos).getFluidState().isEmpty();
    }

    /** 半径内有没有活着的敌对生物。用来区分"技能卡住"和"正在被怪打断自卫"。 */
    private boolean hostileNearby(int radius) {
        // Enemy 是接口、不继承 Entity，getEntitiesOfClass 的泛型上限要求 Entity，
        // 所以先按 Mob 取，再用 instanceof Enemy 过滤（和生存层 findThreat 的做法一致）。
        return !npc.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                npc.getBoundingBox().inflate(radius),
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy && mob.isAlive()).isEmpty();
    }

    private boolean isExposed(BlockPos pos) {
        return npc.level().getBlockState(pos.above()).isAir()
                || npc.level().getBlockState(pos.below()).isAir()
                || npc.level().getBlockState(pos.north()).isAir()
                || npc.level().getBlockState(pos.south()).isAir()
                || npc.level().getBlockState(pos.east()).isAir()
                || npc.level().getBlockState(pos.west()).isAir();
    }

    /** 找附近已经放下的熔炉（取最近的一台）；没有则返回 null。 */
    private BlockPos nearbyFurnaceBlock() {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-8, -4, -8), center.offset(8, 4, 8))) {
            if (!npc.level().getBlockState(pos).is(Blocks.FURNACE)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 熔炼

    /**
     * 熔炼目标：放下熔炉、塞入原料和燃料、等原版烧炼，再把产物取回背包。
     *
     * <p>为什么直接操作 BlockEntity：伪玩家没有客户端 GUI，但熔炉的烧炼逻辑全在服务端
     * BlockEntity 里，直接写输入/燃料槽等价于玩家打开 GUI 放东西，产物也按原版时间生成。
     */
    private void tickSmelt() {
        int done = Math.max(0, countItem(targetId) - smeltBaseline);
        status = "熔炼 " + targetId + " " + done + "/" + targetCount;
        if (done >= targetCount) {
            // 收炉必须在 finish 之前：finish 之后目标层清空 furnacePos，
            // 剩下没烧的原料和燃料就永远留在方块里了。
            drainFurnace();
            finish("熔炼完成 " + targetId + " " + done + "/" + targetCount);
            return;
        }
        smeltTicks++;
        if (smeltTicks > 2400) {
            drainFurnace();
            finish("熔炼超时：长时间只完成 " + done + "/" + targetCount);
            return;
        }
        if (furnacePos == null) {
            if (!placeFurnace()) {
                return;
            }
        }
        // 每个 tick 都把产物取回背包：伪玩家不会打开 GUI，只能主动搬。
        takeFurnaceOutput();
        int now = Math.max(0, countItem(targetId) - smeltBaseline);
        if (now >= targetCount) {
            // 这是最常见的完成路径（刚取完最后一个产物），收炉同样必须在 finish 之前。
            drainFurnace();
            finish("熔炼完成 " + targetId + " " + now + "/" + targetCount);
            return;
        }
        fillFurnace();
        // 【fix197】停滞探测：炉冷（未点火）+ 产出槽空 +（燃料槽空且包里挑不出
        // 燃料，或原料断供）时，fillFurnace 只会无声 no-op、目标静默挂死——
        // farm167 实证：第 8 个生铁因 5 根原木只够烧 7 个而断火，包里
        // coal/planks/log/stick 全 0，目标白挂 15 分钟靠看门狗超时收场。
        // 玩家没柴会收炉去弄柴：持续 40 拍（≈20 秒，远超一锭 10 秒烧程，
        // 而添柴是同拍动作不会误伤）即收回炉内余料收场，交规划层补燃料/补料。
        if (furnacePos != null
                && npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity stallFurnace) {
            boolean lit = npc.level().getBlockState(furnacePos).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
            boolean fuelBlocked = stallFurnace.getItem(1).isEmpty() && pickFuel() == null;
            String inputId = smeltInputFor(targetId);
            boolean inputBlocked = stallFurnace.getItem(0).isEmpty()
                    && (inputId == null || countItem(inputId) <= 0);
            if (!lit && stallFurnace.getItem(2).isEmpty() && (fuelBlocked || inputBlocked)) {
                smeltStallTicks++;
                if (smeltStallTicks >= 40) {
                    String reason = fuelBlocked
                            ? "熔炼停滞：背包无可用燃料（炉内余料已收回），交规划层补燃料 "
                            : "熔炼停滞：原料不足（炉内余料已收回），交规划层补料 ";
                    smeltStallTicks = 0;
                    drainFurnace();
                    finish(reason + done + "/" + targetCount);
                    return;
                }
            } else {
                smeltStallTicks = 0;
            }
        }
    }

    /** 在脚边找一个空位放下熔炉；失败会直接结束目标并写清原因。 */
    private boolean placeFurnace() {
        String input = smeltInputFor(targetId);
        if (input == null) {
            finish("熔炼失败：没有 " + targetId + " 的熔炼配方");
            return false;
        }
        if (countItem(input) <= 0) {
            finish("熔炼失败：缺少原料 " + input);
            return false;
        }
        // 优先复用自己之前放下的熔炉：熔炉放下后物品栏就空了，
        // 如果只认"背包里有熔炉"，第二次冶炼会永远失败，规划器又会一直重下冶炼目标。
        BlockPos existing = nearbyFurnaceBlock();
        if (existing != null) {
            furnacePos = existing.immutable();
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 复用附近熔炉 | pos=" + furnacePos.toShortString());
            fillFurnace();
            return true;
        }
        if (countItem("furnace") <= 0) {
            finish("熔炼失败：没有熔炉（8 个圆石合成）");
            return false;
        }
        BlockPos base = npc.blockPosition();
        // 由近到远扫三层壳：只认四邻格时，1 格宽的矿道四面全是墙，
        // 实测连续三轮冶炼都直接失败在"脚边没有空位放熔炉"。
        // 玩家在矿道里会把熔炉放在身后两三格的空处，这里同样放宽搜索范围。
        BlockPos spot = null;
        for (int radius = 1; radius <= 3 && spot == null; radius++) {
            for (int dx = -radius; dx <= radius && spot == null; dx++) {
                for (int dz = -radius; dz <= radius && spot == null; dz++) {
                    for (int dy = -1; dy <= 1 && spot == null; dy++) {
                        // 只扫当前半径这一层壳，保证由近到远；重复扫内层没有意义。
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius && Math.abs(dy) != radius) {
                            continue;
                        }
                        BlockPos candidate = base.offset(dx, dy, dz);
                        if (!npc.level().getBlockState(candidate).canBeReplaced()) {
                            continue;
                        }
                        // 熔炉下面必须有支撑，否则放下就掉成掉落物。
                        if (npc.level().getBlockState(candidate.below()).getCollisionShape(
                                npc.level(), candidate.below()).isEmpty()) {
                            continue;
                        }
                        spot = candidate.immutable();
                    }
                }
            }
        }
        if (spot == null) {
            // 四面都是墙（竖井/窄矿道）：像玩家一样凿壁一格，再在那个洞里放熔炉。
            for (BlockPos direction : List.of(base.east(), base.west(), base.north(), base.south())) {
                if (npc.mineWithBestTool(direction)) {
                    spot = direction.immutable();
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 凿壁放熔炉 | pos=" + spot.toShortString());
                    break;
                }
            }
        }
        if (spot == null) {
            finish("熔炼失败：脚边没有空位放熔炉");
            return false;
        }
        if (!consume("furnace", 1)) {
            finish("熔炼失败：熔炉物品扣除失败");
            return false;
        }
        npc.level().setBlockAndUpdate(spot, Blocks.FURNACE.defaultBlockState());
        furnacePos = spot;
        placedFurnacePos = spot;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 放置熔炉 | pos=" + spot.toShortString());
        fillFurnace();
        return true;
    }

    /** 原料槽空了就补一批，燃料槽空了就补燃料。 */
    private void fillFurnace() {
        if (furnacePos == null) {
            return;
        }
        if (!(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            finish("熔炼失败：熔炉方块实体丢失 pos=" + furnacePos.toShortString());
            return;
        }
        String input = smeltInputFor(targetId);
        if (input == null) {
            return;
        }
        int need = targetCount - Math.max(0, countItem(targetId) - smeltBaseline);
        // 输入槽一次最多放 16 个：放太多的话目标提前完成后拿不回来，会浪费材料。
        if (furnace.getItem(0).isEmpty() && need > 0) {
            int available = countItem(input);
            if (available > 0) {
                int batch = Math.min(need, Math.min(16, available));
                if (consume(input, batch)) {
                    furnace.setItem(0, new ItemStack(item(input), batch));
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉放入原料 | " + input + " x" + batch);
                }
            }
        }
        if (furnace.getItem(1).isEmpty()) {
            String fuel = pickFuel();
            if (fuel != null) {
                int put = Math.min(8, countItem(fuel));
                if (put > 0 && consume(fuel, put)) {
                    furnace.setItem(1, new ItemStack(item(fuel), put));
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉放入燃料 | " + fuel + " x" + put);
                }
            }
        }
    }

    /** 取走熔炉产出槽；背包满时留在熔炉里等下一次 tick。 */
    private void takeFurnaceOutput() {
        if (furnacePos == null || !(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            return;
        }
        String path = pathOf(output);
        int count = output.getCount();
        String given = give(path, count);
        if (OK.equals(given)) {
            furnace.setItem(2, ItemStack.EMPTY);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 取出熔炼产物 | " + path + " x" + count
                    + " 背包共=" + countItem(path));
        } else {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炼产物暂存 | " + given);
        }
    }

    /**
     * 目标结束时把熔炉里所有槽位（原料/燃料/产物）收回背包。
     *
     * <p>为什么必须收：fillFurnace 一次塞 8 个燃料，但目标可能只烧 1~2 个就完成了，
     * 剩下的煤还在燃料槽里；NPC 一转身走开，这些煤就等于丢进了虚空，
     * 规划器下一秒又数到"煤不足"重新下矿——实测的「挖煤→熔炼→再挖煤」死循环就是这么来的。
     * 玩家关炉子前一定会把东西掏干净，这里做同样的事。
     */
    private void drainFurnace() {
        if (furnacePos == null) {
            return;
        }
        if (!(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        int moved = 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = furnace.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            int count = stack.getCount();
            // give 内部已处理"背包满→丢杂物→重试"；仍放不下就留在炉子里，
            // 下一次冶炼复用这个熔炉时还能接着烧，不会凭空消失。
            if (OK.equals(give(path, count))) {
                furnace.setItem(slot, ItemStack.EMPTY);
                moved += count;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉收炉 | " + path + " x" + count
                        + " 槽=" + slot);
            } else {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉收炉失败（留在炉内）| " + path + " x" + count);
            }
        }
        if (moved > 0) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 熔炉收炉完成 | 共收回=" + moved
                    + " pos=" + furnacePos.toShortString());
        }
        reclaimFurnaceBlock();
    }

    /**
     * 【第四十一轮·工作站回收】冶炼收尾时，把自己放下的那台熔炉挖回背包
     * （Fong 04:11：工作站随身带、用完挖回收，别再走到哪扔到哪、一局重造
     * 好几台）。只收 placedFurnacePos 登记的那台；复用世界里现成的熔炉
     * 不动。放弃条件（防丢物/防死锁）：炉内还有东西没收干净（背包满）→
     * 不拆、留着下次复用；没有可用镐子 → 不拆、记一行日志。
     */
    private void reclaimFurnaceBlock() {
        if (furnacePos == null || placedFurnacePos == null
                || !furnacePos.equals(placedFurnacePos)) {
            return;
        }
        if (!npc.level().getBlockState(furnacePos).is(Blocks.FURNACE)) {
            // 炉子已经不在了（被怪炸了/被别的流程清了）：登记作废，别再惦记。
            placedFurnacePos = null;
            return;
        }
        if (!(npc.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        for (int slot = 0; slot < 3; slot++) {
            if (!furnace.getItem(slot).isEmpty()) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 工作站回收跳过：炉内还有物品"
                        + " pos=" + furnacePos.toShortString());
                return;
            }
        }
        int before = countItem("furnace");
        // 【fix239】身体层存储守卫默认拒挖一切存储/功能块；回收自家登记
        // 的这台熔炉是定点许可的唯一技能层来源，只在本次挖掘期间有效。
        npc.setStorageDigPermit(furnacePos);
        boolean reclaimed;
        try {
            reclaimed = npc.mineWithBestTool(furnacePos);
        } finally {
            npc.setStorageDigPermit(null);
        }
        if (!reclaimed) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 工作站回收失败：没有可用镐子，熔炉留在原地"
                    + " pos=" + furnacePos.toShortString());
            return;
        }
        // 掉落物同步收回：身体的自动拾取是逐 tick 的，这里直接把脚边的
        // 熔炉掉落物搬进背包（与 takeFurnaceOutput 的主动搬运同一口径）。
        int collected = 0;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(furnacePos).inflate(2.0D);
        for (net.minecraft.world.entity.item.ItemEntity entity : npc.level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, box,
                e -> "furnace".equals(pathOf(e.getItem())))) {
            collected += entity.getItem().getCount();
            entity.discard();
        }
        if (collected > 0) {
            give("furnace", collected);
        }
        placedFurnacePos = null;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 工作站回收：熔炉已挖回背包"
                + " | 背包熔炉 " + before + "→" + countItem("furnace")
                + " pos=" + furnacePos.toShortString());
    }

    /**
     * 回收附近熔炉（含高炉/烟熏炉）里遗留的物品，返回回收的槽数。
     *
     * <p>为什么需要：冶炼中途停服/切目标时，原料和燃料已经进了熔炉方块，
     * 重启后新目标只会去背包里找生铁，看不到熔炉里那 5 个，于是重新下矿。
     * 玩家会先回去把炉子里的东西掏出来，这里做同样的事。
     */
    public int recoverNearbyFurnaces(int radius) {
        int recovered = 0;
        BlockPos center = npc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            if (!(npc.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                continue;
            }
            boolean any = false;
            for (int slot = 0; slot < 3; slot++) {
                ItemStack stack = furnace.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                String given = give(pathOf(stack), stack.getCount());
                if (OK.equals(given)) {
                    furnace.setItem(slot, ItemStack.EMPTY);
                    recovered++;
                    any = true;
                }
            }
            if (any) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 回收遗留熔炉 | pos=" + pos.toShortString());
            }
        }
        return recovered;
    }

    /** 燃料优先级：煤/木炭最省空间，其次木板、原木，最后木棍。 */
    private String pickFuel() {
        for (String candidate : List.of("coal", "charcoal")) {
            if (countItem(candidate) > 0) {
                return candidate;
            }
        }
        if (countPlanks() > 0) {
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                    return pathOf(stack);
                }
            }
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_log")) {
                return pathOf(stack);
            }
        }
        if (countItem("stick") > 0) {
            return "stick";
        }
        return null;
    }

    /** 熔炼产物 → 原料。只列当前科技树会用到的配方，其余交给完整配方表处理。 */
    private static String smeltInputFor(String output) {
        return switch (output) {
            case "iron_ingot" -> "raw_iron";
            case "gold_ingot" -> "raw_gold";
            case "copper_ingot" -> "raw_copper";
            case "cooked_beef" -> "beef";
            case "cooked_porkchop" -> "porkchop";
            case "cooked_chicken" -> "chicken";
            case "cooked_mutton" -> "mutton";
            case "cooked_rabbit" -> "rabbit";
            case "baked_potato" -> "potato";
            case "glass" -> "sand";
            case "stone" -> "cobblestone";
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 种田（fix49·D11）

    /**
     * 种田目标（Fong D11）：开田（establish）与维护（tend）共用一个目标类型，
     * 按 FarmMemory 账本现状分流。玩家口径的闭环：攒种（打草 1/8 掉种子，
     * Wiki）→ 靠水选址（湿润=水水平 4 格内同层或高 1 层）→ 锄地播种 →
     * 等熟 → 收获后立刻补种；维护访 = 收获→走过田面拾取→补种/补锄→
     * （口粮紧且有骨粉时）催熟一轮再复查。锄/种/骨粉走直改方块+代码内
     * 原版规则校验（placeFurnace 先例：伪玩家无客户端不走 useItemOn），
     * 收获走 mineBareHand 真掉落；留种靠"播种不耗总存量（只入土）+
     * 消费端地板（生存层进食守卫/规划层烤土豆只烤富余）"两头守。
     */
    private void tickFarm() {
        FarmMemory.bindWorld(npc.level());
        FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
        boolean tend = farm != null && !farm.cells().isEmpty() && farmHasLiveCells(farm)
                && !"establish".equals(farmMode);
        if (tend) {
            tickFarmTend(farm);
        } else {
            tickFarmEstablish();
        }
    }

    /** 田格里还有没有能救的土（耕地或可重锄的土）：全变成水/石才算田没了、需要重选址。 */
    private boolean farmHasLiveCells(FarmMemory.Farm farm) {
        for (FarmMemory.Cell cell : farm.cells()) {
            BlockState state = npc.level().getBlockState(cell.soil());
            if (state.is(Blocks.FARMLAND) || state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)) {
                return true;
            }
        }
        return false;
    }

    /** 播种材料总量（小麦种子 + 胡萝卜 + 土豆，三者都能下地）。 */
    private int plantStockTotal() {
        return countItem("wheat_seeds") + countItem("carrot") + countItem("potato");
    }

    /** 攒种子轮次耗尽出口（fix168 抽出，回读换位与技能拒启动两路共用）：≥4 粒开小块田、否则放弃。 */
    private boolean farmGatherExhausted() {
        if (attempts < 12) {
            return false;
        }
        if (plantStockTotal() >= 4) {
            LOG.info("[AIV][GOAL] " + npc.npcName()
                    + " [FARM] 种子只攒到 " + plantStockTotal() + "，开小块田");
            farmPhase = 2;
        } else {
            finish("开田放弃：附近攒不到种子（打草） stock=" + plantStockTotal());
        }
        return true;
    }

    private void tickFarmEstablish() {
        switch (farmPhase) {
            case 0 -> {
                status = "种田：开田准备";
                if (findHoeSlot() < 0) {
                    finish("开田放弃：没有可用的锄头");
                    return;
                }
                farmPhase = plantStockTotal() >= FARM_PLOT_CELLS ? 2 : 1;
            }
            case 1 -> {
                // 攒种子：打草 1/8 掉小麦种子（Wiki），攒够一田（8）再开田；
                // 草就长在地表，mine 技能半径内有草就打、没有就扩围换位。
                // 【fix52】放弃口径修正：旧版 attempts 把"附近没草"的轮次累计
                // 6 次就放弃——可种子期望要打 32~64 株草、草是一片片长的，
                // 每换一片草地就白扣一次，实证 72 株草/2 种子时被误杀放弃。
                // 改：技能成功启动（=附近有草）即清零 attempts；只有连续 12 个
                // "没草"轮次（期间扩围换位 12 次仍无草）才判这片地真没草。
                // 半径 8→24：与选址半径同量级，少走冤枉路。
                // 【fix168】结局回读（修订 fix52 的"启动即清零"）：farm135 实证
                // 同一株高坡草站位够不着、身体层 path-abort 瞬时秒退，mine 以
                // 约 2Hz 重启 922 次、attempts 因 run() 恒 true 永不计数，整轮
                // 靠 480 秒看门狗处决+退避滚雪球。改：每次自家 mine 收场按结
                // 局文案判——success=true（真敲碎）才清零 noGain、种子涨了再
                // 清 attempts；失败结局（无法到达/没有可到达的站立点等）连满
                // 3 次按一个"没草轮次" attempts++ 并换位。敲碎没掉种是 1/8
                // 概率的正常干活，不许按存量增量误判。
                status = "种田：打草攒种子 " + plantStockTotal() + "/" + FARM_PLOT_CELLS;
                if (plantStockTotal() >= FARM_PLOT_CELLS) {
                    farmPhase = 2;
                    return;
                }
                if (!"idle".equals(skills.currentSkill())) {
                    return;
                }
                if (farmGatherSeq < 0) {
                    farmGatherSeq = skills.outcomeSeq();
                } else if (farmGatherAwaiting && skills.outcomeSeq() != farmGatherSeq) {
                    farmGatherSeq = skills.outcomeSeq();
                    farmGatherAwaiting = false;
                    String outcome = skills.lastOutcome();
                    if (outcome != null && outcome.startsWith("mine success=true")) {
                        farmGatherNoGain = 0;
                        if (plantStockTotal() > farmGatherStockMark) {
                            attempts = 0;
                        }
                    } else if (++farmGatherNoGain >= 3) {
                        farmGatherNoGain = 0;
                        attempts++;
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " [FARM] 攒种子连续失败换位（fix168） | attempts=" + attempts
                                + " outcome=" + outcome);
                        if (farmGatherExhausted()) {
                            return;
                        }
                        escalateSearch();
                        return;
                    }
                }
                farmGatherStockMark = plantStockTotal();
                JsonObject grass = new JsonObject();
                grass.addProperty("block", "short_grass");
                grass.addProperty("radius", 24);
                if (skills.run(new Intent("", "mine", grass), "目标")) {
                    farmGatherAwaiting = true;
                    return;
                }
                JsonObject tall = new JsonObject();
                tall.addProperty("block", "tall_grass");
                tall.addProperty("radius", 24);
                if (skills.run(new Intent("", "mine", tall), "目标")) {
                    farmGatherAwaiting = true;
                    return;
                }
                attempts++;
                if (farmGatherExhausted()) {
                    return;
                }
                escalateSearch();
            }
            case 2 -> {
                status = "种田：选址（靠水平地）";
                if (farmSiteCells == null) {
                    farmSiteCells = findFarmSite();
                    if (farmSiteCells == null) {
                        farmSiteAttempts++;
                        if (farmSiteAttempts >= 6) {
                            finish("开田放弃：附近找不到靠水的平地");
                            return;
                        }
                        if ("idle".equals(skills.currentSkill())) {
                            relocateForFarmSite();
                        }
                        return;
                    }
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 选址 | cells="
                            + farmSiteCells.size() + " anchor=" + farmSiteCells.get(0).toShortString());
                }
                farmPhase = 3;
            }
            case 3 -> {
                status = "种田：锄地播种";
                if (!farmQueueBuilt) {
                    buildEstablishQueue();
                    farmQueueBuilt = true;
                }
                if (!processFarmActions()) {
                    return;
                }
                farmPhase = 4;
            }
            case 4 -> {
                // 落盘：以活方块为准登记（锄成耕地的格才入账，种上作物的记作物）。
                java.util.List<FarmMemory.Cell> cells = new java.util.ArrayList<>();
                int planted = 0;
                for (BlockPos soil : farmSiteCells) {
                    BlockState soilState = npc.level().getBlockState(soil);
                    if (!soilState.is(Blocks.FARMLAND)) {
                        continue;
                    }
                    String crop = FarmMemory.cropOf(
                            npc.level().getBlockState(soil.above()).getBlock());
                    cells.add(new FarmMemory.Cell(soil.getX(), soil.getY(), soil.getZ(),
                            crop == null ? "" : crop));
                    if (crop != null) {
                        planted++;
                    }
                }
                if (cells.isEmpty()) {
                    finish("开田失败：一格耕地都没锄成");
                    return;
                }
                BlockPos anchor = farmSiteCells.get(0);
                FarmMemory.save(npc.npcName(), new FarmMemory.Farm(FarmMemory.worldKey(),
                        anchor.getX(), anchor.getY(), anchor.getZ(),
                        System.currentTimeMillis(), cells));
                InventorySnapshot.log(npc, "farm", "establish", true);
                finish("开田完成 | 格=" + cells.size() + " 已种=" + planted
                        + " 种子余=" + plantStockTotal()
                        + " @ " + anchor.toShortString());
            }
            default -> finish("开田失败：未知阶段 " + farmPhase);
        }
    }

    private void tickFarmTend(FarmMemory.Farm farm) {
        switch (farmPhase) {
            case 0 -> {
                status = "种田：回田";
                BlockPos anchor = farm.anchor();
                double distSq = npc.blockPosition().distSqr(anchor);
                if (distSq > 100.0D) {
                    if (!"idle".equals(skills.currentSkill())) {
                        return;
                    }
                    // 【fix275】上一趟接近技能已收场但人还没到：结算一
                    // 次尝试（站位轮换/连卡/收场上限都按这个数走）。
                    if (farmPhase0Sortie) {
                        farmPhase0Sortie = false;
                        farmPhase0Attempts++;
                    }
                    // 【fix273】人在深水时不派回田 goto（落点按洼底 Y 解析
                    // 必错），等游泳救援送上岸再派；死 goto 的收场在 tick()
                    // 忙位守卫（本相只在 idle 间隙执行，轮不到收场）。
                    if (farmDeepInFluid()) {
                        return;
                    }
                    // 【fix294b】分段走（乙）：回田接近连败≥3 且分段额度未用完时，
                    // 先 goto 8 格外途经点、再从新位置重走站位接近。A* 从空腔/残柱迷宫
                    // 出发直搜 18 格外目标会节点/时间双爆表（141 棒乙），但 8 格短搜可过；
                    // 途经点每次新分段旋转 60°（防同一坏点重复失败）。到点（水平≤3.5 格）
                    // 后清分段、attempts=0；分段失败 3 次则放弃本段（用掉一次额度）、回落
                    // 站位轮换；15 次预算照常收口（分段到达才重置 attempts，防无限循环）。
                    if (farmPhase0Stage != null) {
                        double sdx = npc.getX() - (farmPhase0Stage.getX() + 0.5D);
                        double sdz = npc.getZ() - (farmPhase0Stage.getZ() + 0.5D);
                        double sDistH = Math.sqrt(sdx * sdx + sdz * sdz);
                        if (sDistH <= 3.5D) {
                            LOG.info("[AIV][GOAL] " + npc.npcName()
                                    + " [FARM] 分段到达（fix294） | stage="
                                    + farmPhase0Stage.toShortString()
                                    + " distSq=" + (int) distSq);
                            farmPhase0Stage = null;
                            farmPhase0StageFails = 0;
                            farmPhase0Attempts = 0;
                        } else {
                            farmPhase0StageFails++;
                            if (farmPhase0StageFails >= 3) {
                                farmPhase0Stagings++;
                                LOG.info("[AIV][GOAL] " + npc.npcName()
                                        + " [FARM] 分段放弃（fix294） | stage="
                                        + farmPhase0Stage.toShortString()
                                        + " fails=" + farmPhase0StageFails
                                        + " stagings=" + farmPhase0Stagings);
                                farmPhase0Stage = null;
                                farmPhase0StageFails = 0;
                            } else {
                                JsonObject go = new JsonObject();
                                go.addProperty("x", farmPhase0Stage.getX() + 0.5D);
                                go.addProperty("z", farmPhase0Stage.getZ() + 0.5D);
                                if (skills.run(new Intent("", "goto", go), "目标")) {
                                    farmPhase0Sortie = true;
                                } else {
                                    farmPhase0Attempts++;
                                }
                                return;
                            }
                        }
                    }
                    if (farmPhase0Stage == null && farmPhase0Attempts >= 3 && farmPhase0Stagings < 2) {
                        double wdx = (anchor.getX() + 0.5D) - npc.getX();
                        double wdz = (anchor.getZ() + 0.5D) - npc.getZ();
                        double wDistH = Math.sqrt(wdx * wdx + wdz * wdz);
                        if (wDistH > 1.0D) {
                            double ang = Math.toRadians(60.0D * farmPhase0Stagings);
                            double cos = Math.cos(ang);
                            double sin = Math.sin(ang);
                            double rdx = (wdx / wDistH) * cos - (wdz / wDistH) * sin;
                            double rdz = (wdx / wDistH) * sin + (wdz / wDistH) * cos;
                            double wLen = Math.min(8.0D, wDistH / 2.0D);
                            BlockPos stage = BlockPos.containing(
                                    npc.getX() + rdx * wLen, anchor.getY(), npc.getZ() + rdz * wLen);
                            JsonObject go = new JsonObject();
                            go.addProperty("x", stage.getX() + 0.5D);
                            go.addProperty("z", stage.getZ() + 0.5D);
                            if (skills.run(new Intent("", "goto", go), "目标")) {
                                farmPhase0Stage = stage;
                                farmPhase0Sortie = true;
                                LOG.info("[AIV][GOAL] " + npc.npcName()
                                        + " [FARM] 分段出发（fix294） | stage="
                                        + stage.toShortString() + " 尝试=" + farmPhase0Attempts);
                            } else {
                                farmPhase0StageFails++;
                                farmPhase0Attempts++;
                                if (farmPhase0StageFails >= 3) {
                                    farmPhase0Stagings++;
                                    farmPhase0StageFails = 0;
                                    LOG.info("[AIV][GOAL] " + npc.npcName()
                                            + " [FARM] 分段派发连拒，放弃（fix294） | stagings="
                                            + farmPhase0Stagings);
                                } else {
                                    return;
                                }
                            }
                        }
                    }
                    // 【fix272】回田目标改站位格＋被拒不穿透（124 棒定谳）：
                    // 旧版派 goto(锚点中心)——锚点列是田心（耕地/水面）恒不
                    // 可站立，goto 落点解析必走回退链（中途点 A* 超预算被拒、
                    // 或 ≤16 格直线蹚水 40 秒超时）；且 run 被拒时旧代码不
                    // return、静默穿透到下方收获队列构建——「回田」从未执行，
                    // 19 格外跨崖接近被甩给逐动作本地接近（farm292 实证：
                    // 点火后 269 秒全耗站位接近、动作执行 0）。改派账本格的
                    // 现成站位（farmStandCandidates 判据的可站格，goto 可直
                    // 解落点）；派发被拒就留在回田相、下 tick 重试，不再穿透。
                    // 【fix275】被拒死重试整段自旋（127 棒定谳 B1）：272a 对
                    // 「被拒」只有重试一种应对——goto 遭 A* 节点上限爆表＋
                    // fix245 熔断连拒时，fresh144 实证 704 行拒绝/355 秒零
                    // 位移零收获、靠入夜才中止。熔断的设计本意（fix245 注
                    // 释）是逼目标层换 ascend/travel 等换法，本相必须真换：
                    // 与 farmGotoStandFor 同带口径走 goto 直达，带外走
                    // moveToStand 共享编排（竖直对齐/死带 ascend/travel 由
                    // 几何决定、锁不死单一方法）；站位按尝试数轮换
                    // （attempts/3，与 271c 纪律同构）；尝试 ≥15 仍未到达
                    // 以「农田维护失败」诚实收场、喂 fix246 退避，不再靠
                    // 入夜/通用超时兜底。被拒日志节流（首次/每 100 次）。
                    java.util.List<BlockPos> stands = farmArrivalStands(farm);
                    int standIdx = Math.min(farmPhase0Attempts / 3, stands.size() - 1);
                    BlockPos stand = stands.get(standIdx);
                    if (farmPhase0Attempts >= 15) {
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " [FARM] 回田接近 15 次未达，诚实收场（fix275） | stand="
                                + stand.toShortString() + " distSq=" + (int) distSq);
                        finish("农田维护失败：回田接近 15 次未达（fix275）");
                        return;
                    }
                    double dxs = npc.getX() - (stand.getX() + 0.5D);
                    double dzs = npc.getZ() - (stand.getZ() + 0.5D);
                    double distH = Math.sqrt(dxs * dxs + dzs * dzs);
                    double feetDiff = Math.abs(npc.getY() - (stand.getY() + 1.0D));
                    // 【fix320】goto 失败记忆（与 farmGotoStandFor 同口径，按站位记死）：
                    // 上一个 goto 以「直线行走放弃」收场时本站位内不再原样重派 goto，
                    // 直落 moveToStand（travel 搭桥／ascend 爬升）换法；站位变或
                    // attempts==0（到达／分段到达清零）时清死，goto 带重开。
                    String lastOutcome320 = skills.lastOutcome();
                    if (lastOutcome320 != null && lastOutcome320.startsWith("goto ")
                            && lastOutcome320.contains("直线行走放弃")) {
                        farmPhase0GotoDeadStand = stand.immutable();
                    }
                    if (farmPhase0Attempts == 0 || (farmPhase0GotoDeadStand != null
                            && !farmPhase0GotoDeadStand.equals(stand))) {
                        farmPhase0GotoDeadStand = null;
                    }
                    boolean phase0GotoDead320 = farmPhase0GotoDeadStand != null
                            && farmPhase0GotoDeadStand.equals(stand);
                    if (!phase0GotoDead320 && ((feetDiff <= 1.0D && distH <= 32.0D)
                            || (feetDiff <= 6.0D && distH <= 6.0D))) {
                        JsonObject go = new JsonObject();
                        go.addProperty("x", stand.getX() + 0.5D);
                        go.addProperty("z", stand.getZ() + 0.5D);
                        if (skills.run(new Intent("", "goto", go), "目标")) {
                            farmPhase0Sortie = true;
                            LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 回田站位（fix272） | stand="
                                    + stand.toShortString() + " distSq=" + (int) distSq);
                        } else {
                            farmPhase0Attempts++;
                            if (farmPhase0Attempts == 1 || farmPhase0Attempts % 100 == 0) {
                                LOG.info("[AIV][GOAL] " + npc.npcName()
                                        + " [FARM] 回田派发被拒，换法接近（fix275） | stand="
                                        + stand.toShortString() + " 尝试=" + farmPhase0Attempts);
                            }
                        }
                        return;
                    }
                    moveToStand(stand, farmPhase0Attempts, "[FARM]");
                    farmPhase0Sortie = true;
                    return;
                }
                if (distSq > 16.0D && !"idle".equals(skills.currentSkill())) {
                    return;
                }
                farmPhase0Attempts = 0;
                farmPhase0Sortie = false;
                // 收获队列：只收成熟的（age=7；未熟收只有 1 粒种子、没小麦，Wiki）。
                farmActions.clear();
                for (FarmMemory.Cell cell : farm.cells()) {
                    BlockState cropState = npc.level().getBlockState(cell.soil().above());
                    String crop = FarmMemory.cropOf(cropState.getBlock());
                    if (crop != null && FarmMemory.cropAge(cropState) >= 7) {
                        farmActions.add(new FarmAction("harvest", cell.soil().above(), crop));
                    }
                }
                farmPhase = farmActions.isEmpty() ? 2 : 1;
            }
            case 1 -> {
                status = "种田：收获 " + farmActions.size() + " 格";
                if (!processFarmActions()) {
                    return;
                }
                farmPickupIndex = 0;
                farmPhase = 2;
            }
            case 2 -> {
                // 拾取：挨个走过刚收获的土格收掉落（平走过田面不踩坏，
                // Wiki 口径踩坏=从 1 格以上落下；田是平地、同层步行安全）。
                status = "种田：拾取掉落 " + farmPickupIndex + "/" + farmHarvestedSoils.size();
                if (farmPickupIndex >= farmHarvestedSoils.size()) {
                    farmPhase = 3;
                    return;
                }
                if (!"idle".equals(skills.currentSkill())) {
                    return;
                }
                BlockPos cell = farmHarvestedSoils.get(farmPickupIndex);
                if (farmInReach(cell)) {
                    farmPickupIndex++;
                    farmActionStuck = 0;
                    farmLastActionKey = "";
                    return;
                }
                String key = "pickup@" + cell.toShortString();
                if (key.equals(farmLastActionKey) && ++farmActionStuck > 4) {
                    farmPickupIndex++;
                    farmActionStuck = 0;
                    farmLastActionKey = "";
                    return;
                }
                farmLastActionKey = key;
                // 【fix320·D】拾取 goto 无竖直守卫：NPC 在田面之下（落进洼/坑）时，
                // goto 只给 x/z、落点以 NPC 当前高度 ±6 解析，田面在 ±6 之外时
                // 落点被解析到洼底、A* 无路改直线蹚水，游泳又把 NPC 带回东岸，
                // goto↔游泳死循环（191 G2 实证：22:02–22:07 约 50 次
                // goto-straight、tend 收获Δ -4）。人低于土格 ≥2 格时先落
                // moveToStand 爬出来（ascend），再水平接近；同层照旧走 goto。
                if (npc.getY() < (double) cell.getY()) {
                    moveToStand(cell, farmActionStuck, "[FARM]");
                    return;
                }
                JsonObject go = new JsonObject();
                go.addProperty("x", cell.getX() + 0.5D);
                go.addProperty("z", cell.getZ() + 0.5D);
                skills.run(new Intent("", "goto", go), "目标");
            }
            case 3 -> {
                status = "种田：补种补锄";
                if (!farmQueueBuilt) {
                    buildTendQueue(farm);
                    farmQueueBuilt = true;
                }
                farmPhase = 4;
            }
            case 4 -> {
                status = "种田：田间作业 " + farmActions.size() + " 项";
                if (!processFarmActions()) {
                    return;
                }
                if (farmBonemealQueued && !farmSecondPass) {
                    // 骨粉刚催过：回收获段复查一次（可能已有新熟的）。
                    farmSecondPass = true;
                    farmQueueBuilt = false;
                    farmPhase = 0;
                    return;
                }
                farmPhase = 5;
            }
            case 5 -> {
                // 更新账本：死格（土变成水/石等非土方块）销账，其余按活方块刷新作物。
                java.util.List<FarmMemory.Cell> cells = new java.util.ArrayList<>();
                for (FarmMemory.Cell cell : farm.cells()) {
                    BlockState soilState = npc.level().getBlockState(cell.soil());
                    if (!soilState.is(Blocks.FARMLAND) && !soilState.is(Blocks.GRASS_BLOCK)
                            && !soilState.is(Blocks.DIRT) && !soilState.is(Blocks.COARSE_DIRT)) {
                        continue;
                    }
                    // 【fix307】盖顶死格出账：土是耕地但上方实心非作物
                    // （石盖/耕地盖顶），永不可播种，占注册数还每趟吃一
                    // 个 plant 丢弃；出账后缺额回归真实，上方清空后扩田
                    // 逻辑可重新收回（未注册耕地＋上方空气即候选）。
                    BlockState aboveState = npc.level().getBlockState(cell.soil().above());
                    String crop = FarmMemory.cropOf(aboveState.getBlock());
                    if (!aboveState.isAir() && crop == null) {
                        continue;
                    }
                    cells.add(new FarmMemory.Cell(cell.x(), cell.y(), cell.z(),
                            crop == null ? "" : crop));
                }
                // 【fix262】补格落账：本访选中的补格里已锄成耕地的，按开田
                // 同标准（活耕地才入账）追加登记；锄没成/被跳过的下轮再补。
                // 锚点与开田时间沿用原账本，不挪田。
                for (BlockPos pos : farmExpansionCells) {
                    boolean duplicate = false;
                    for (FarmMemory.Cell cell : cells) {
                        if (cell.soil().equals(pos)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate
                            || !npc.level().getBlockState(pos).is(Blocks.FARMLAND)) {
                        continue;
                    }
                    String crop = FarmMemory.cropOf(
                            npc.level().getBlockState(pos.above()).getBlock());
                    cells.add(new FarmMemory.Cell(pos.getX(), pos.getY(), pos.getZ(),
                            crop == null ? "" : crop));
                }
                FarmMemory.save(npc.npcName(), new FarmMemory.Farm(farm.world(),
                        farm.anchorX(), farm.anchorY(), farm.anchorZ(),
                        farm.establishedAt(), cells));
                FarmMemory.Scan scan = FarmMemory.scan(npc.level(), npc.npcName());
                InventorySnapshot.log(npc, "farm", "tend", true);
                int harvestDelta = (countItem("wheat") - farmBaseWheat)
                        + (countItem("carrot") - farmBaseCarrot)
                        + (countItem("potato") - farmBasePotato);
                // 【fix60】有动作被跳过且一点没收着 = 这趟白跑：如实报失败让规划退避，
                // 不许再用「完成」把 90 秒连派空转洗成正常（boot-farm17 实锤）。
                // 【fix246】判定口径改为「无净进展」：零收获且生效动作没超过
                // 失败动作（跳过+丢弃）即白跑。旧口径只抓跳过——88 棒新图实
                // 证播种被复核静默丢弃（田格上方被圆石压住）不计数，空转
                // 169 轮全报「完成」、退避永不点火；反过来，实验场格②实证
                // 催熟 2 格+补种 1 格的实干趟只因 1 丢弃被判失败同样失真，
                // 故不搞一票否决、按净进展算（纯丢弃/纯跳过/零动作恒判失败，
                // 锄一格被压一格的反复局也判失败进退避）。
                if (harvestDelta <= 0 && farmEffected <= farmSkipped + farmDropped) {
                    finish("农田维护失败：跳过=" + farmSkipped + " 丢弃=" + farmDropped
                            + " 生效=" + farmEffected
                            + " | 收获Δ 小麦=" + (countItem("wheat") - farmBaseWheat)
                            + " 种子=" + (countItem("wheat_seeds") - farmBaseSeeds)
                            + " 胡萝卜=" + (countItem("carrot") - farmBaseCarrot)
                            + " 土豆=" + (countItem("potato") - farmBasePotato)
                            + " | 在田=" + scan.plantedTotal() + "（熟" + scan.mature()
                            + "/长" + scan.growing() + "） 播种存量=" + plantStockTotal());
                    return;
                }
                finish("农田维护完成 | 收获Δ 小麦=" + (countItem("wheat") - farmBaseWheat)
                        + " 种子=" + (countItem("wheat_seeds") - farmBaseSeeds)
                        + " 胡萝卜=" + (countItem("carrot") - farmBaseCarrot)
                        + " 土豆=" + (countItem("potato") - farmBasePotato)
                        + " | 生效=" + farmEffected + " 丢弃=" + farmDropped
                        + " | 在田=" + scan.plantedTotal() + "（熟" + scan.mature()
                        + "/长" + scan.growing() + "） 播种存量=" + plantStockTotal());
            }
            default -> finish("农田维护失败：未知阶段 " + farmPhase);
        }
    }

    /** 开田动作队列：每格先锄后种；种什么按当时存量模拟分配（见 chooseCropSim）。 */
    private void buildEstablishQueue() {
        farmActions.clear();
        int[] stock = {countItem("wheat_seeds"), countItem("carrot"), countItem("potato")};
        int[] planted = {0, 0, 0};
        for (BlockPos soil : farmSiteCells) {
            farmActions.add(new FarmAction("till", soil, ""));
            String crop = chooseCropSim(stock, planted);
            if (crop != null) {
                farmActions.add(new FarmAction("plant", soil, crop));
            }
        }
    }

    /** 维护动作队列：空地补种（优先原作物）、坏地补锄补种、口粮紧时骨粉催熟一轮。 */
    private void buildTendQueue(FarmMemory.Farm farm) {
        farmActions.clear();
        int[] stock = {countItem("wheat_seeds"), countItem("carrot"), countItem("potato")};
        int[] planted = {0, 0, 0};
        java.util.List<FarmAction> bonemealCandidates = new java.util.ArrayList<>();
        for (FarmMemory.Cell cell : farm.cells()) {
            BlockPos soil = cell.soil();
            BlockState soilState = npc.level().getBlockState(soil);
            BlockState cropState = npc.level().getBlockState(soil.above());
            String liveCrop = FarmMemory.cropOf(cropState.getBlock());
            if (liveCrop != null) {
                int idx = cropIndex(liveCrop);
                planted[idx]++;
                int age = FarmMemory.cropAge(cropState);
                if (age >= 0 && age < 7) {
                    bonemealCandidates.add(new FarmAction("bonemeal", soil.above(), liveCrop));
                }
                continue;
            }
            if (soilState.is(Blocks.FARMLAND)) {
                // 【fix307】盖顶死格不排 plant：上方非空非作物（石盖/
                // 耕地盖顶）者永不可播种，排了必被复核丢弃（实验场三格
                // 每趟必丢）；phase 5 出账，不再占注册数。
                if (!npc.level().getBlockState(soil.above()).isAir()) {
                    continue;
                }
                String crop = chooseCropSim(stock, planted,
                        cell.crop().isEmpty() ? null : cell.crop());
                if (crop != null) {
                    farmActions.add(new FarmAction("plant", soil, crop));
                }
            } else if (soilState.is(Blocks.GRASS_BLOCK) || soilState.is(Blocks.DIRT)
                    || soilState.is(Blocks.COARSE_DIRT)) {
                // 耕地干回泥土/被踩坏：补锄再补种（玩家常识，田不能就这么荒着）。
                // 【fix307】上方实心不可播种者不补锄（锄了也白锄，还会把
                // 死格新登记进账）；草丛类上方由 till 当场清掉，可排。
                BlockState aboveState = npc.level().getBlockState(soil.above());
                if (!aboveState.isAir() && !aboveState.is(Blocks.SHORT_GRASS)
                        && !aboveState.is(Blocks.TALL_GRASS) && !aboveState.is(Blocks.FERN)
                        && !aboveState.is(Blocks.LARGE_FERN)) {
                    continue;
                }
                farmActions.add(new FarmAction("till", soil, ""));
                String crop = chooseCropSim(stock, planted,
                        cell.crop().isEmpty() ? null : cell.crop());
                if (crop != null) {
                    farmActions.add(new FarmAction("plant", soil, crop));
                }
            }
            // 土变成水/石等：死格，不排动作，收尾时账本销账。
        }
        // 【fix262】补格扩田：注册格不足目标格数时，沿田边把相邻合格地锄补
        // 回来（玩家常识：田缺格顺手补，不另开新田）。候选沿 establish 簇
        // 几何（锚点切比雪夫 ≤3、|dy|≤1）+ 同一套湿润/见天/坑底判据；与补种
        // 共用本队列的模拟存量账，chooseCropSim 无种可派就整格跳过、不空锄
        // （种子不够少补、本轮不强求，下轮 tend 再补）。选中格记
        // farmExpansionCells，收尾按活耕地追加登记（phase 5）。
        farmExpansionCells.clear();
        if (farm.cells().size() < FARM_TARGET_CELLS) {
            int deficit = FARM_TARGET_CELLS - farm.cells().size();
            int queued = 0;
            java.util.List<BlockPos> soilCandidates = farmExpansionCandidates(farm);
            for (BlockPos candidate : soilCandidates) {
                if (queued >= deficit) {
                    break;
                }
                String crop = chooseCropSim(stock, planted, null);
                if (crop == null) {
                    break;
                }
                if (!npc.level().getBlockState(candidate).is(Blocks.FARMLAND)) {
                    farmActions.add(new FarmAction("till", candidate, ""));
                }
                farmActions.add(new FarmAction("plant", candidate, crop));
                farmExpansionCells.add(candidate);
                queued++;
            }
            // 【fix264】土格候选补不满缺额时，用填水格补（先垫土再锄种，
            // 泥土按模拟存量逐格扣；没土/没种都不排，降级照常收尾）。
            // 【fix270】就地取土（121 棒定谳）：生产两次 tend 构建时刻包里
            // dirt 恒为 0，填水段被 dirtSim>0 整段否决、补格静默零排队。
            // 玩家在田边垫土没土时先弯腰在岸边挖几块——故填水候选先算表，
            // 泥土缺口先排 digdirt 就地取土（置于填水组之前），dirtSim 按
            // 预计入账增计；取土执行失败/被丢弃时后续 place 由执行前复核
            // 按真实 dirt 拦下留痕，不静默、不假账。
            java.util.List<BlockPos> reclaimCandidates = farmReclaimCandidates(farm);
            // 【fix310】架空垫土格（160 棒定谳）：正式田悬于水洼之上——带内
            // 可耕土为零（土面在水下 y58–59）、仅 3 个带内水格全被坑险门杀，
            // 土/填水两类候选恒双零、田锁死 2 格。玩家在此扩田是贴着现有田
            // 格在同层往水面/空处垫土成格；候选口径见 farmDeckCandidates。
            // 填格池＝填水优先＋架空补缺，dirt 账与取土口径合并计算。
            java.util.List<BlockPos> deckCandidates = farmDeckCandidates(farm);
            java.util.Set<BlockPos> deckSet = new java.util.HashSet<>(deckCandidates);
            java.util.List<BlockPos> fillCandidates = new java.util.ArrayList<>();
            fillCandidates.addAll(reclaimCandidates);
            fillCandidates.addAll(deckCandidates);
            int reclaimed = 0;
            int decked = 0;
            int dirtSim = countItem("dirt");
            int dirtNeed = Math.min(deficit - queued, fillCandidates.size());
            int dugPlanned = 0;
            // 【fix307】干/湿源计数复位：本轮未算源表时评估行不带该段，防陈旧数留痕。
            farmLastSoilDry = -1;
            farmLastSoilWet = -1;
            if (dirtNeed > dirtSim) {
                java.util.Set<BlockPos> digExcluded = new java.util.HashSet<>();
                for (FarmMemory.Cell cell : farm.cells()) {
                    digExcluded.add(cell.soil());
                }
                digExcluded.addAll(farmExpansionCells);
                for (BlockPos waterCell : fillCandidates) {
                    digExcluded.add(waterCell);
                    digExcluded.add(waterCell.below());
                }
                java.util.List<BlockPos> soilSources =
                        farmSoilSourceCandidates(farm, digExcluded);
                int want = Math.min(dirtNeed - dirtSim, soilSources.size());
                for (int i = 0; i < want; i++) {
                    farmActions.add(new FarmAction("digdirt", soilSources.get(i), ""));
                    dugPlanned++;
                }
                dirtSim += dugPlanned;
            }
            if (queued < deficit && dirtSim > 0) {
                for (BlockPos candidate : fillCandidates) {
                    if (queued >= deficit || dirtSim <= 0) {
                        break;
                    }
                    String crop = chooseCropSim(stock, planted, null);
                    if (crop == null) {
                        break;
                    }
                    farmActions.add(new FarmAction("place", candidate, ""));
                    farmActions.add(new FarmAction("till", candidate, ""));
                    farmActions.add(new FarmAction("plant", candidate, crop));
                    farmExpansionCells.add(candidate);
                    dirtSim--;
                    queued++;
                    if (deckSet.contains(candidate)) {
                        decked++;
                    } else {
                        reclaimed++;
                    }
                }
            }
            // 【fix270】补格评估行：注册<FARM_TARGET_CELLS 时无论排队是否为 0
            // 都打一行，杜绝 fix262/264 时代「零排队零日志」的静默失败
            // （121 棒定谳：两次生产 tend 补格零点火，日志里连候选/材料
            // 读数都没有）。【fix290】目标 8→24，评估行随之改口径。
            LOG.info("[AIV][GOAL] " + npc.npcName()
                    + " [FARM] 补格评估（fix270） | 注册=" + farm.cells().size()
                    + " 缺=" + deficit + " 土候选=" + soilCandidates.size()
                    + " 填水候选=" + reclaimCandidates.size()
                    + " 架空候选=" + deckCandidates.size()
                    + " dirt=" + countItem("dirt") + " 取土=" + dugPlanned
                    + " 排队=" + queued
                    + (farmLastSoilDry >= 0
                            ? " 干源=" + farmLastSoilDry + " 湿源=" + farmLastSoilWet : ""));
            if (queued > 0) {
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " [FARM] 补格扩田（fix262） | 注册=" + farm.cells().size()
                        + " 缺=" + deficit + " 排队=" + queued
                        + (reclaimed > 0 ? " 填水格=" + reclaimed : "")
                        + (decked > 0 ? " 架空格=" + decked : ""));
            }
        }
        // 骨粉催熟（Wiki：+2~5 阶段）：口粮低于下矿线、包里有骨粉、田里有
        // 没熟的作物时才舍得用——骨粉也是打骷髅攒的，不许日常浪费。
        if (!farmBonemealQueued && foodNutrition() < 24 && countItem("bone_meal") > 0
                && !bonemealCandidates.isEmpty()) {
            bonemealCandidates.sort((a, b) -> Integer.compare(
                    FarmMemory.cropAge(npc.level().getBlockState(b.pos())),
                    FarmMemory.cropAge(npc.level().getBlockState(a.pos()))));
            int uses = Math.min(countItem("bone_meal"), Math.min(4, bonemealCandidates.size()));
            for (int i = 0; i < uses; i++) {
                farmActions.add(bonemealCandidates.get(i));
            }
            farmBonemealQueued = true;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 骨粉催熟 | 目标格=" + uses
                    + " foodNutrition=" + foodNutrition());
        }
    }

    /**
     * 【fix262】补格候选：锚点簇盒（切比雪夫 ≤3、|dy|≤1，与 findFarmSite 的
     * 簇几何一致）内未注册、土面可耕（草/泥土/粗泥土/未注册耕地）、上方
     * 空气类、见天、湿润、非坑底的格，按距锚点近序。判据与选址完全同口径，
     * 不新造标准；候选全灭时返回空表，tend 照常收尾（安全降级）。
     */
    /** 【fix297】扩田格即时登记：锄成耕地的扩田格当场入账（不只等 phase 5）。
     * 截断的 tend 也能留账；已登记/非耕地直接返回；保存走 FarmMemory.save 全量口径。 */
    private void registerFarmCell(BlockPos pos) {
        if (!farmExpansionCells.contains(pos)) {
            return;
        }
        FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
        if (farm == null) {
            return;
        }
        for (FarmMemory.Cell cell : farm.cells()) {
            if (cell.soil().equals(pos)) {
                return;
            }
        }
        if (!npc.level().getBlockState(pos).is(Blocks.FARMLAND)) {
            return;
        }
        String crop = FarmMemory.cropOf(
                npc.level().getBlockState(pos.above()).getBlock());
        java.util.List<FarmMemory.Cell> cells =
                new java.util.ArrayList<>(farm.cells());
        cells.add(new FarmMemory.Cell(pos.getX(), pos.getY(), pos.getZ(),
                crop == null ? "" : crop));
        FarmMemory.save(npc.npcName(), new FarmMemory.Farm(farm.world(),
                farm.anchorX(), farm.anchorY(), farm.anchorZ(),
                farm.establishedAt(), cells));
        LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 扩田格即时登记（fix297） | "
                + pos.toShortString());
    }

    private java.util.List<BlockPos> farmExpansionCandidates(FarmMemory.Farm farm) {
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        if (!(npc.level() instanceof ServerLevel level)) {
            return candidates;
        }
        BlockPos anchor = farm.anchor();
        java.util.Set<BlockPos> registered = new java.util.HashSet<>();
        for (FarmMemory.Cell cell : farm.cells()) {
            registered.add(cell.soil());
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (registered.contains(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)
                            && !state.is(Blocks.COARSE_DIRT) && !state.is(Blocks.FARMLAND)) {
                        continue;
                    }
                    BlockState above = level.getBlockState(pos.above());
                    if (!above.isAir() && !above.is(Blocks.SHORT_GRASS)
                            && !above.is(Blocks.TALL_GRASS) && !above.is(Blocks.FERN)
                            && !above.is(Blocks.LARGE_FERN) && !above.is(Blocks.SNOW)) {
                        continue;
                    }
                    if (!level.canSeeSky(pos.above())) {
                        continue;
                    }
                    if (!farmHydrated(pos)) {
                        continue;
                    }
                    if (farmPitRisk(pos)) {
                        continue;
                    }
                    candidates.add(pos);
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(anchor::distSqr));
        return candidates;
    }

    /**
     * 【fix264】填水格候选（补格的第二来源）：正式田实测——锚点盒 49 列
     * 地表全是历次自救/搭桥留下的圆石柱台与水面，田本身悬在一个被掏空
     * 的水洼上（两块耕地 y=62、下方是空腔与 y=59 的水），盒内可锄裸土
     * 为零，任何口径的土格扫描都恒空（115 棒剖面实证）。玩家在这种田边
     * 扩田就是往水面垫土：同盒同带内、水面格（本格为水、上方空气、见天、
     * 下方是水或实心＝垫下去不悬空过深）、非坑底、且垫土后仍湿润（湿润
     * 判定排除本格：footprint 内除本格外还有水才算，防把唯一的灌溉源头
     * 格填死）。排队时先 place 垫土再 till/plant，耗包里泥土、没土
     * 不排（模拟存量口径同种子）。土格候选优先，填水格只补缺额。
     */
    private java.util.List<BlockPos> farmReclaimCandidates(FarmMemory.Farm farm) {
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        if (!(npc.level() instanceof ServerLevel level)) {
            return candidates;
        }
        BlockPos anchor = farm.anchor();
        java.util.Set<BlockPos> registered = new java.util.HashSet<>();
        for (FarmMemory.Cell cell : farm.cells()) {
            registered.add(cell.soil());
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (registered.contains(pos)) {
                        continue;
                    }
                    if (!level.getBlockState(pos).is(Blocks.WATER)) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    // 【fix291】删 canSeeSky 门：生产田边填水候选被自家圆石残柱
                    // （搭桥/垫脚/恢复栈，y64–85 散布）遮天挡住，补格评估恒 0。
                    // canSeeSky 是"见天"不是"有光"——散布残柱下仍有日光、作物
                    // 能长；玩家扩田只填水锄地、不会查天。其余门（水/空气/下层/
                    // 邻水/坑险）不动。
                    BlockState below = level.getBlockState(pos.below());
                    if (!below.is(Blocks.WATER)
                            && below.getCollisionShape(level, pos.below()).isEmpty()) {
                        continue;
                    }
                    if (!farmHydratedBeyond(pos)) {
                        continue;
                    }
                    if (farmPitRisk(pos)) {
                        continue;
                    }
                    candidates.add(pos);
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(anchor::distSqr));
        return candidates;
    }

    /**
     * 【fix310】架空垫土格候选（补格的第三来源，160 棒定谳）：正式田悬在
     * 被掏空的水洼上（田面 y62、水面 y60–61、可耕土面在水下 y58–59），
     * 土候选被块型门清零、填水候选被坑险门清零（带内仅 3 水格，±2 内
     * 高一层有水），两类候选恒双零、田锁死 2 格。玩家在此扩田的做法是
     * 贴着现有田格、在田面同层往水面/空处垫土成新格——本候选即此口径：
     * 锚点盒（切比雪夫 ≤3、|dy|≤1）内未注册、本格为空气或水、上方空气、
     * 下方 1–2 格内有水或实心支撑（不向深空架桥）、上方 1 层 ±1 内无水
     * （防高位水灌顶）、水平 ±4 且 dy -2..+1 内临水（湖面在田下 1–2 格
     * 也算临水），且与注册格或已接受架空格同层横向相邻（贴田生长、不
     * 散点）。执行走既有 place→till→plant 链；place 复核对空气格以
     * farmDeckCellOk 复检静态判据（见 farmActionValid）。
     */
    private java.util.List<BlockPos> farmDeckCandidates(FarmMemory.Farm farm) {
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        if (!(npc.level() instanceof ServerLevel level)) {
            return candidates;
        }
        BlockPos anchor = farm.anchor();
        java.util.Set<BlockPos> registered = new java.util.HashSet<>();
        for (FarmMemory.Cell cell : farm.cells()) {
            registered.add(cell.soil());
        }
        java.util.List<BlockPos> pool = new java.util.ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (registered.contains(pos)) {
                        continue;
                    }
                    if (farmDeckStaticOk(level, pos)) {
                        pool.add(pos);
                    }
                }
            }
        }
        pool.sort(java.util.Comparator.comparingDouble(anchor::distSqr));
        java.util.Set<BlockPos> grown = new java.util.HashSet<>(registered);
        boolean progress = true;
        while (progress) {
            progress = false;
            for (BlockPos pos : pool) {
                if (grown.contains(pos)) {
                    continue;
                }
                if (grown.contains(pos.west()) || grown.contains(pos.east())
                        || grown.contains(pos.north()) || grown.contains(pos.south())) {
                    grown.add(pos);
                    candidates.add(pos);
                    progress = true;
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(anchor::distSqr));
        return candidates;
    }

    /** 【fix310】架空垫土格的静态判据（候选生成与 place 执行复核共用）。 */
    private boolean farmDeckStaticOk(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && !state.is(Blocks.WATER)) {
            return false;
        }
        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        // 下方 1–2 格内须有水或实心支撑：不向深空/空腔架桥。
        boolean supported = false;
        for (int d = 1; d <= 2; d++) {
            BlockPos below = pos.below(d);
            BlockState belowState = level.getBlockState(below);
            if (belowState.is(Blocks.WATER)
                    || !belowState.getCollisionShape(level, below).isEmpty()) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            return false;
        }
        // 上方 1 层 ±1 内有水＝高位水会灌顶，不在此垫土。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getBlockState(pos.offset(dx, 1, dz)).is(Blocks.WATER)) {
                    return false;
                }
            }
        }
        // 临水口径：水平 ±4、dy -2..+1 内有水（田下 1–2 格的湖面也算）。
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    if (level.getBlockState(pos.offset(dx, dy, dz)).is(Blocks.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 【fix310】place 执行前复核（空气格）：须仍是合格的架空垫土格。 */
    private boolean farmDeckCellOk(BlockPos pos) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
        if (farm == null) {
            return false;
        }
        BlockPos anchor = farm.anchor();
        if (Math.max(Math.abs(pos.getX() - anchor.getX()),
                Math.abs(pos.getZ() - anchor.getZ())) > 3
                || Math.abs(pos.getY() - anchor.getY()) > 1) {
            return false;
        }
        for (FarmMemory.Cell cell : farm.cells()) {
            if (cell.soil().equals(pos)) {
                return false;
            }
        }
        return farmDeckStaticOk(level, pos);
    }

    /**
     * 【fix270】就地取土源：填水垫土缺土时在田边现挖的土格。锚点
     * ±8/±2（半径宽于补格盒，理由见方法内修订注）内、本格为草方块/泥土
     * （两者挖掘必掉 dirt；粗泥土挖掉是粗泥土本身、与 place 消耗的
     * dirt 账实不符，故不取）、上方无实心碰撞（挖完不塌压）；排除集
     * 由调用方给出（注册格、本轮已选补格、填水候选格及其正下方支撑
     * 格），按距锚点近序。玩家口径：垫土前手边没土，就在岸边挖。
     */
    private java.util.List<BlockPos> farmSoilSourceCandidates(FarmMemory.Farm farm,
            java.util.Set<BlockPos> excluded) {
        java.util.List<BlockPos> sources = new java.util.ArrayList<>();
        if (!(npc.level() instanceof ServerLevel level)) {
            return sources;
        }
        BlockPos anchor = farm.anchor();
        // 【fix296·144 棒生产探针定谳】取土半径 ±8→±16：新图田边锚点 ±8
        // 内草/泥土面已被历次扩田挖空（生产三次补格评估取土=0；只读探针
        // 粗扫 ±8、y61–64 零命中、138 棒三源均已非草方块），最近有效源
        // 在 dx=+10 处（(-2,63,-117) 草，距锚点约 10.2 格、上方空），
        // ±16 环带内另有 6 个已验证有效源。玩家垫土没土会往远处走几步
        // 再挖，不会守着枯掉的土坑干等。纵向 ±2 与 y≥锚点-1、草/泥土
        // 限定一字不动（121 棒洼底源接近失败实证仍有效）。
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (excluded.contains(pos)) {
                        continue;
                    }
                    // 【fix270 源层位·121 棒克隆实证】洼底源（y=锚点-2）站位接近
                    // 反复失败（goto 不通/descend 对齐磨 3 分钟未到位）——玩家取土
                    // 挖岸边地面、不潜洼底，故源层位限锚点同层±1。
                    if (pos.getY() < anchor.getY() - 1) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).getCollisionShape(
                            level, pos.above()).isEmpty()) {
                        continue;
                    }
                    sources.add(pos);
                }
            }
        }
        // 【fix307·157 棒生产定谳】源选择加水路感知：锚点→源直线段穿水
        // 者排后——玩家垫土挖同岸的土，不隔水够对岸的。生产实证：东侧
        // 同岸源 15:19–15:26 三度取土成功后枯竭，此后只剩西侧隔水源，
        // digdirt 落水扰动约 15 次、跳过 4 次，田在 5↔7 格原地踏步。
        // 无同岸源时隔水源仍可排（兜底、不堵死）；干/湿计数供补格评估
        // 行留痕，生产验证用。
        java.util.List<BlockPos> drySources = new java.util.ArrayList<>();
        java.util.List<BlockPos> wetSources = new java.util.ArrayList<>();
        for (BlockPos source : sources) {
            if (farmSourceCrossesWater(anchor, source)) {
                wetSources.add(source);
            } else {
                drySources.add(source);
            }
        }
        farmLastSoilDry = drySources.size();
        farmLastSoilWet = wetSources.size();
        drySources.sort(java.util.Comparator.comparingDouble(anchor::distSqr));
        wetSources.sort(java.util.Comparator.comparingDouble(anchor::distSqr));
        drySources.addAll(wetSources);
        return drySources;
    }

    /** 【fix307】锚点→源直线段是否穿水：沿段每格取样，源层与锚点层
     * 两档各查水；任一取样为水即判隔水源。启发式口径，只定排序、不禁排。
     * 【fix307b·157 棒实验场 F1 实证】取样 y 沿段插值：源比锚点高 1 时
     * 线在水面（y=锚点层）上方爬升，只查 y/y+1 会漏掉正下方的水——
     * 04:03 实验场：锚点 (121,62,54)→(127,63,53) 在 x=126 处 y=63，
     * 只查 63/64 层、漏掉 y=62 的湖面，致隔水源误判干源、digdirt 先
     * 隔水取土、四次落水。故取样加 y-1 档（-1/0/+1），线升线降皆覆
     * 盖锚点层水面；误判方向只会把干源多判成湿（排序次优），不堵死。 */
    private boolean farmSourceCrossesWater(BlockPos anchor, BlockPos source) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        int steps = Math.max(Math.abs(source.getX() - anchor.getX()),
                Math.abs(source.getZ() - anchor.getZ()));
        if (steps <= 0) {
            return false;
        }
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(anchor.getX() + (source.getX() - anchor.getX()) * t);
            int z = (int) Math.round(anchor.getZ() + (source.getZ() - anchor.getZ()) * t);
            int y = (int) Math.round(anchor.getY() + (source.getY() - anchor.getY()) * t);
            for (int dy : new int[]{-1, 0, 1}) {
                if (level.getBlockState(new BlockPos(x, y + dy, z)).is(Blocks.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 湿润判定（排除本格）：垫土后本格不再是水，须靠邻水保持湿润。 */
    private boolean farmHydratedBeyond(BlockPos soil) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = soil.offset(dx, dy, dz);
                    if (!p.equals(soil) && npc.level().getBlockState(p).is(Blocks.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int cropIndex(String crop) {
        return switch (crop) {
            case "carrot" -> 1;
            case "potato" -> 2;
            default -> 0;
        };
    }

    /**
     * 按模拟存量给一格选作物（stock/planted 数组随选随扣，队列建完即弃）：
     * 稀有作物（胡萝卜/土豆，来源靠僵尸掉落/村庄）先各种 2 格繁殖起来，
     * 其余种小麦（主粮）；原作物优先（补种保持田面格局）。
     * 播种不耗总存量（种子只是入土），故这里不设地板门——地板守在
     * 消费端（吃/烤），见 SurvivalController 与规划层烤土豆步。
     */
    private String chooseCropSim(int[] stock, int[] planted) {
        return chooseCropSim(stock, planted, null);
    }

    private String chooseCropSim(int[] stock, int[] planted, String preferred) {
        if (preferred != null && stock[cropIndex(preferred)] > 0) {
            int idx = cropIndex(preferred);
            stock[idx]--;
            planted[idx]++;
            return preferred;
        }
        String[] order = {"carrot", "potato", "wheat"};
        for (String crop : order) {
            int idx = cropIndex(crop);
            if (stock[idx] <= 0) {
                continue;
            }
            if (!"wheat".equals(crop) && planted[idx] >= 2) {
                continue;
            }
            stock[idx]--;
            planted[idx]++;
            return crop;
        }
        // 稀有作物都已种满 2 格、或只剩小麦：有什么种什么。
        for (String crop : order) {
            int idx = cropIndex(crop);
            if (stock[idx] > 0) {
                stock[idx]--;
                planted[idx]++;
                return crop;
            }
        }
        return null;
    }

    /**
     * 逐个执行动作队列：技能在跑就等；到位就当场执行（动作是瞬时直改），
     * 走不到位先 goto 到田边格；同一动作反复走不到位超限就跳过它，
     * 不许一格田卡死整个目标（看门狗之外再加一道单格保险）。
     *
     * @return true = 队列已清空
     */
    private boolean processFarmActions() {
        while (!farmActions.isEmpty()) {
            FarmAction action = farmActions.get(0);
            if (!farmActionValid(action)) {
                // 【fix246】复核失效不再静默：计数 + 专行留痕，收尾计入失败
                // 口径（旧版无声丢弃是 tend 空转冒充完成的藏身处）。
                farmDropped++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 动作丢弃（执行前复核失效） | "
                        + action.kind() + "@" + action.pos().toShortString()
                        + " 本目标累计丢弃=" + farmDropped);
                farmActions.remove(0);
                farmActionStuck = 0;
                farmLastActionKey = "";
                continue;
            }
            String actionKey = action.kind() + "@" + action.pos().toShortString();
            // 【fix273】死 goto 的停掉与落水扰动记账在 tick() 的忙位守卫
            // （tickFarmBusyGuard）里——本方法只在技能 idle 间隙被调用，
            // 死 goto 挂住期间根本轮不到这里（见彼处注释）。
            if (!"idle".equals(skills.currentSkill())) {
                return false;
            }
            if (!farmInReach(actionReachPos(action))) {
                // 【fix273】深水不派发：身体归游泳救援层管辖——此时派
                // goto，落点按当前（洼底）Y 解析必错（格 F2 型漂移），且
                // 新 walkTo 会顶掉救援的 swimGoal。等救援送上岸再恢复。
                // 够格判定在前：在水里已够得着的动作照常执行。
                if (farmDeepInFluid()) {
                    return false;
                }
                String key = actionKey;
                // 【fix271】进度记账（122 棒定谳）：旧版 stuck 只数派发次数，
                // 长途正经接近的编排交接（goto→travel→descend）与原地振荡
                // 同账——一轮编排没走完预算先烧光、动作被跳过。新口径：
                // 水平距较本动作已达最佳改善 ≥1 格 = 真接近，stuck 清零；
                // 净进度 ≈0 的真振荡照旧累计，>6 跳过不变。
                BlockPos reachPos = actionReachPos(action);
                double distDx = npc.getX() - (reachPos.getX() + 0.5D);
                double distDz = npc.getZ() - (reachPos.getZ() + 0.5D);
                double distNow = Math.sqrt(distDx * distDx + distDz * distDz);
                // 【fix272】进度口径补竖直被困惩罚：旧版只看水平距——人被
                // 带上柱台顶时对作物水平距反而变小（11.4 格），被误判「有
                // 进度」清零 stuck、换位/跳过出口永不点火（124 棒实证）。
                // 高出够格带上界（reachPos.y+3）的部分按 2 倍计入有效距
                // 离：水平变近但竖直被困，不再算进度。
                double dyAboveBand = npc.getY() - (reachPos.getY() + 3.0D);
                if (dyAboveBand > 0.0D) {
                    distNow += 2.0D * dyAboveBand;
                }
                if (!key.equals(farmLastActionKey)) {
                    farmLastActionKey = key;
                    farmActionStuck = 0;
                    farmApproachBestDist = distNow;
                    // 【fix273】换动作清落水扰动账（本 tick 刚为本动作记的
                    // 扰动不许被顺手擦掉）。
                    if (!key.equals(farmWaterStrikeKey)) {
                        farmWaterStrikeKey = key;
                        farmWaterStrikes = 0;
                    }
                } else if (distNow < farmApproachBestDist - 1.0D) {
                    farmApproachBestDist = distNow;
                    farmActionStuck = 0;
                } else if (++farmActionStuck > 6) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 动作跳过（反复走不到位） | "
                            + key);
                    farmSkipped++;
                    farmActions.remove(0);
                    farmActionStuck = 0;
                    farmLastActionKey = "";
                    farmApproachBestDist = Double.MAX_VALUE;
                    continue;
                }
                farmGotoStandFor(action);
                return false;
            }
            farmActionStuck = 0;
            farmLastActionKey = "";
            farmApproachBestDist = Double.MAX_VALUE;
            doFarmAction(action);
            farmActions.remove(0);
        }
        return true;
    }

    /** 动作的可达判定点：till/plant 看土格，harvest/bonemeal 看作物格。 */
    private BlockPos actionReachPos(FarmAction action) {
        return action.pos();
    }

    /** 动作执行前复核（方块可能在排队后被水流/怪/自己改掉，失效就丢）。 */
    private boolean farmActionValid(FarmAction action) {
        BlockState state = npc.level().getBlockState(action.pos());
        return switch (action.kind()) {
            case "digdirt" -> (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT))
                    && npc.level().getBlockState(action.pos().above())
                            .getCollisionShape(npc.level(), action.pos().above()).isEmpty();
            // 【fix310】place 复核：水格（填水垫土）照旧；空气格须仍是
            // 合格架空垫土格（田面同层贴田垫土，160 棒），防队列后地形变。
            case "place" -> (state.is(Blocks.WATER)
                    || (state.isAir() && farmDeckCellOk(action.pos())))
                    && countItem("dirt") > 0;
            case "till" -> state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                    || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT);
            case "plant" -> state.is(Blocks.FARMLAND)
                    && npc.level().getBlockState(action.pos().above()).isAir()
                    && countItem(FarmMemory.seedItemOf(action.crop())) > 0;
            case "harvest" -> {
                String crop = FarmMemory.cropOf(state.getBlock());
                yield crop != null && FarmMemory.cropAge(state) >= 7;
            }
            case "bonemeal" -> {
                String crop = FarmMemory.cropOf(state.getBlock());
                yield crop != null && FarmMemory.cropAge(state) < 7
                        && countItem("bone_meal") > 0;
            }
            default -> false;
        };
    }

    /** NPC 是否已在动作格的操作范围内（水平 3.2 格、落差 -1~+3）。 */
    private boolean farmInReach(BlockPos pos) {
        double dx = npc.getX() - (pos.getX() + 0.5D);
        double dz = npc.getZ() - (pos.getZ() + 0.5D);
        double dy = npc.getY() - pos.getY();
        return dx * dx + dz * dz <= 10.24D && dy >= -1.0D && dy <= 3.0D;
    }

    /**
     * 【fix65】建造够格（与种田 farmInReach 分开）：种田口径（水平 3.2 格、
     * 上够 1 格）是按作物在脚边设计的，套到砌筑上结构性够不着——屋内中心
     * 到角柱水平 dist²=12.5＞10.24、屋顶在脚上 3 层 dy=-3＜-1，火柴盒按
     * 原口径永远砌不完（boot-farm22/23 双实证：放置 0、逐格跳过）。
     * 建造口径按原版眼高 1.62＋手臂够格 ~4.5 格核算：水平 ≤3.6 格
     * （角柱 12.5≤12.96）、向上够 4 层（站屋内砌屋顶 dy=-3）、向下 2 层。
     */
    private boolean buildInReach(BlockPos pos) {
        double dx = npc.getX() - (pos.getX() + 0.5D);
        double dz = npc.getZ() - (pos.getZ() + 0.5D);
        double dy = npc.getY() - pos.getY();
        return dx * dx + dz * dz <= 12.96D && dy >= -4.0D && dy <= 2.0D;
    }

    /**
     * 【fix272】回田相（phase 0）的到达站位：优先取首个已熟格的站位
     * （到达即能接收获相），无熟格取首格；站位候选与逐动作接近共用
     * farmStandCandidates（按当前身体位置排序、首位即最近的够格带层
     * 位）。无账本格时回落锚点（tend 前提是 farm 非空，此为兜底）。
     */
    private BlockPos farmArrivalStand(FarmMemory.Farm farm) {
        return farmArrivalStands(farm).get(0);
    }

    /**
     * 【fix275】回田到达站位候选全表（与 farmArrivalStand 同判据同序：
     * 首个已熟格优先、无熟格取首格，候选经干路优先排序）。phase 0 按
     * 尝试数轮换取用——首位站位被地形/熔断锁死时换下一位，不再死守一格。
     */
    private java.util.List<BlockPos> farmArrivalStands(FarmMemory.Farm farm) {
        FarmMemory.Cell chosen = null;
        for (FarmMemory.Cell cell : farm.cells()) {
            BlockState cropState = npc.level().getBlockState(cell.soil().above());
            String crop = FarmMemory.cropOf(cropState.getBlock());
            if (crop != null && FarmMemory.cropAge(cropState) >= 7) {
                chosen = cell;
                break;
            }
        }
        if (chosen == null && !farm.cells().isEmpty()) {
            chosen = farm.cells().get(0);
        }
        if (chosen == null) {
            return java.util.List.of(farm.anchor());
        }
        java.util.List<BlockPos> candidates = farmStandCandidates(chosen.soil());
        if (candidates.isEmpty()) {
            return java.util.List.of(chosen.soil());
        }
        // 【fix273】回田站位同样干路优先：直线段穿深水的站位排后面。
        return farmDryFirstStands(candidates);
    }

    /** 【fix273】深水判定（游泳救援层的管辖面）：游泳脱困进行中，或眼睛泡在流体里。 */
    private boolean farmDeepInFluid() {
        if (npc.isSwimmingEscape()) {
            return true;
        }
        BlockPos eye = BlockPos.containing(npc.getEyePosition());
        return !npc.level().getBlockState(eye).getFluidState().isEmpty();
    }

    /**
     * 【fix273】种田忙位守卫（tick() 在「技能忙」早退之前调用，与 fix251
     * 同置）：farm 的逐动作接近只在技能 idle 间隙被 tickFarm 调用，死
     * goto 挂住期间 tickFarm 轮不到执行——守卫必须站在忙位早退之前，
     * 否则永远饿死（125 棒首验实证）。只管 farm 目标：停掉被游泳顶掉的
     * 死 goto，并给队首动作（队列非空时）记一次落水扰动。
     *
     * @return true = 本 tick 已处置（停了死 goto），调用方直接返回
     */
    private boolean tickFarmBusyGuard() {
        if (!"farm".equals(type)) {
            return false;
        }
        if (farmStopDeadGoto()) {
            if (!farmActions.isEmpty()) {
                FarmAction head = farmActions.get(0);
                noteFarmWaterStrike(head.kind() + "@" + head.pos().toShortString());
            }
            return true;
        }
        return false;
    }

    /**
     * 【fix284】moveToStand descend 慢步守卫（132 棒定谳，tick() 在忙位
     * 早退之前调用）：台阶下行形态下 descend 步数约 30 秒/步慢增、净降
     * 可为负，两条技能自收死线（步数久未变/fix113 无实际下降）都被假
     * 进展推迟，fix282 的结局消费永远等不到——守卫按墙钟直接干预：
     * 自家登记的那一派 descend 运行 ≥45 秒 ∧ 精确净降 <1.0 格 ∧ 非
     * 水中/浆中 → 当场 stop 并自记账一笔 282 的「无实际下降」（stop
     * 不产出结局，noteMtsDescendOutcome 无从消费，必须守卫自记），
     * 下一 idle tick 由 moveToStand 既有 282 分支侧移换列续降，不新
     * 造第二套轮换。45 秒依据：健康台阶下行约 1 级/秒（130 棒克隆
     * 5–7 秒 7–13 步），本病型慢 30–90 倍；误伤代价有界（侧移一列
     * 续降、fails 1..2 封顶、其后回落既有兜底与调用方诚实收场）。
     * 登记五对（技能名/水位/type/站位/时刻）任一不合即不清不拦：
     * 登记过期由结局消费与站位变更清掉（见 noteMtsDescendOutcome 与
     * moveToStand），此处只读不清，避免守卫自己把登记洗掉后误判。
     *
     * @return true = 本 tick 已处置（停了慢步 descend），调用方直接返回
     */
    private boolean tickMtsDescendGuard() {
        if (mtsDescendStartTick < 0L || !"descend".equals(skills.currentSkill())) {
            return false;
        }
        if (!type.equals(mtsDescendGoalType)
                || skills.outcomeSeq() != mtsDescendDispatchSeq
                || mtsStandKey == null) {
            return false;
        }
        long now = npc.level().getGameTime();
        long elapsedTicks = now - mtsDescendStartTick;
        if (elapsedTicks < 900L) {
            return false;
        }
        double drop = mtsDescendStartY - npc.getY();
        if (drop >= 1.0D || npc.isInWater() || npc.isInLava()) {
            return false;
        }
        mtsDescendStartTick = -1L;
        skills.stop();
        mtsNoDropFails++;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " " + mtsDescendTag
                + " 站位接近 descend 慢步无真降，守卫收场（fix284） | 运行秒="
                + (elapsedTicks / 20L) + " 起始Y=" + String.format(java.util.Locale.ROOT, "%.2f", mtsDescendStartY)
                + " 当前Y=" + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                + " 净降=" + String.format(java.util.Locale.ROOT, "%.2f", drop)
                + " 站位=" + mtsStandKey.toShortString() + " fails=" + mtsNoDropFails
                + "（记账并入 fix282 侧移面）");
        return true;
    }

    /**
     * 【fix319】moveToStand 竖直对齐闭环守卫（187 棒定谳，tick() 在忙位
     * 早退之前调用，与 fix284 同置）：旧版竖直对齐是开环派发——|dy|>4
     * 派 ascend/descend（max/steps=160）后等技能自收，收场点由地形决定、
     * 不保证落在站位层 ±4 内。正式田边「东侧高台 Y70–72 → 田下水洼
     * Y53–57 → 田面 Y62」三段地形必然振荡：descend 从高台一路挖穿到
     * 洼底才自收、ascend 在洼内净升 0 或过冲回高台，四趟 tend 合计约
     * 14.5 分钟仅收获 2 棵（185 棒全程实录）。守卫只认自家登记（ascend：
     * 派发水位；descend：派发水位＋起始时刻＋目标 type，与 fix284 登记
     * 口径一致），当前技能就是那一派 ∧ |身体Y − 站位Y| ≤ 4.0（与
     * moveToStand 判据同带）即已入带：清登记后当场 stop，下一 idle
     * tick 由 moveToStand 直接走水平段（goto/travel 沿地形接近），不让
     * 技能跑到自收点。入带是成功不是失败：不记无升/无降连败（stop 不
     * 产出结局，结局消费面靠清登记防误归因，照 fix285 范式）。不设最低
     * 运行时：位置是直读真值、非 fix284 式墙钟推断。fix245 死带 ascend
     * 不在登记内（其派发受调用方连卡节制、目标层由技能自收），不碰。
     *
     * @return true = 本 tick 已处置（停了已入带的对齐技能），调用方直接返回
     */
    private boolean tickMtsAlignGuard() {
        String cur = skills.currentSkill();
        boolean isAscend = "ascend".equals(cur);
        boolean isDescend = "descend".equals(cur);
        if (!isAscend && !isDescend) {
            return false;
        }
        if (mtsStandKey == null) {
            return false;
        }
        String tag;
        if (isAscend) {
            if (mtsAscendDispatchSeq < 0 || skills.outcomeSeq() != mtsAscendDispatchSeq) {
                return false;
            }
            tag = mtsAscendTag;
        } else {
            if (mtsDescendStartTick < 0L || skills.outcomeSeq() != mtsDescendDispatchSeq
                    || !type.equals(mtsDescendGoalType)) {
                return false;
            }
            tag = mtsDescendTag;
        }
        double dy = npc.getY() - mtsStandKey.getY();
        // 【fix354】与 moveToStand 同口径：[PORTAL] 站位竖直带 1.0，
        // 其余站位 4.0（fix319 原带）。
        double alignBand = tag != null && tag.startsWith("[PORTAL]") ? 1.0D : 4.0D;
        if (Math.abs(dy) > alignBand) {
            return false;
        }
        if (isAscend) {
            mtsAscendDispatchSeq = -1;
        } else {
            mtsDescendStartTick = -1L;
            mtsDescendDispatchSeq = -1;
        }
        skills.stop();
        LOG.info("[AIV][GOAL] " + npc.npcName() + " " + (tag == null ? "" : tag)
                + " 站位接近竖直对齐已入带，提前收场转水平接近（fix319） | action=" + cur
                + " 站位=" + mtsStandKey.toShortString()
                + " 当前Y=" + String.format(java.util.Locale.ROOT, "%.2f", npc.getY())
                + " dy=" + String.format(java.util.Locale.ROOT, "%.2f", dy));
        return true;
    }

    /**
     * 【fix320】分级守卫让位接棒守卫（191 棒定谳：fix251 让位结局被三个消费面
     * 一并吞掉——moveToStand 的 travel/descend/ascend 结局消费只认「长途行进
     * 受阻／无实际下降／无实际上升」，让位结局不计数、原样重派；farmGotoStandFor
     * 的派发点根本不消费结局。让位文案的 intended 消费者是「规划层补造石镐」，
     * 但目标存活期间规划层 decide 永不运行，补镐请求无人接棒（187 棒 G1：
     * travel 29 次全 fix251 收场、石镐 20/131＋21/131 耗尽、圆石 x129 躺包里）。
     * 本守卫站在 tick() 忙位早退之前（fix284/fix319 范式）：移动技能刚以 fix251
     * 让位收场时就地补镐，不等目标边界。只认 travel/descend/ascend 结局——strip
     * 让位仍归 fix317（规划层内状态机），mine 技能让位仍归规划层挖矿保障步。
     * 合不出时不强行结束目标、不重插：守卫自身的 pick-fallback（合不出放行铁镐）
     * 是既有兜底，死锁自解。本守卫不拦 tick（补镐后继续派下一步）。
     */
    private void tickTierYieldGuard() {
        int seq = skills.outcomeSeq();
        if (seq == handledTierYieldSeq) {
            return;
        }
        handledTierYieldSeq = seq;
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        String outcome = skills.lastOutcome();
        if (outcome == null || !outcome.contains("礼让收场") || !outcome.contains("分级守卫")) {
            return;
        }
        if (!outcome.startsWith("travel ") && !outcome.startsWith("descend ")
                && !outcome.startsWith("ascend ")) {
            return;
        }
        if (hasUsableLowPick()) {
            return;
        }
        StringBuilder missing = new StringBuilder();
        if (!npc.stonePickCraftableNow(missing)) {
            LOG.info("[AIV][GOAL] " + npc.npcName()
                    + " 分级守卫让位但合不出石镐（fix320） | outcome=" + outcome
                    + " 缺料=" + missing.toString().trim() + "（不重插，守卫 pick-fallback 放行铁镐兜底）");
            return;
        }
        int emptyBefore = countEmptyMainSlots();
        for (int guard = 0; guard < 4 && countEmptyMainSlots() < 3; guard++) {
            if (dropJunk(3 - countEmptyMainSlots()) <= 0) {
                break;
            }
        }
        String result = craftOne("stone_pickaxe");
        LOG.info("[AIV][GOAL] " + npc.npcName()
                + " 分级守卫让位，就地补镐（fix320） | outcome=" + outcome
                + " 合成=" + result + " 空槽=" + emptyBefore + "→" + countEmptyMainSlots()
                + " 可用低阶镐=" + hasUsableLowPick());
    }

    /**
     * 【fix273】死 goto 守望（125 棒定谳）：游泳脱困的 swimTo 会连行走目标
     * 的到达/放弃回调一并清掉（FakePlayerBody.swimTo），被顶掉的 goto 永远
     * 收不到收场信号、只能空等 800 tick 看门狗（125 棒克隆两轮实证整
     * 40 秒，身体早已上岸干等）。当前技能是 goto ∧ 身体未行走 ∧ 未游泳
     * 脱困 ∧ 不在深水，持续 ≥40 tick，即判死当场停掉——此刻 stopWalking
     * 无活可杀（干、静、无游泳），不可能误伤进行中的救援。停掉返回
     * true，调用方按落水扰动记账。
     */
    private boolean farmStopDeadGoto() {
        if (!"goto".equals(skills.currentSkill())
                || npc.isWalking() || npc.isSwimmingEscape() || farmDeepInFluid()) {
            farmDeadGotoSinceTick = -1L;
            return false;
        }
        long now = npc.level().getGameTime();
        if (farmDeadGotoSinceTick < 0L) {
            farmDeadGotoSinceTick = now;
            return false;
        }
        if (now - farmDeadGotoSinceTick < 40L) {
            return false;
        }
        farmDeadGotoSinceTick = -1L;
        skills.stop();
        LOG.info("[AIV][GOAL] " + npc.npcName()
                + " [FARM] goto 被游泳顶掉后空挂，提前收场（fix273） | pos="
                + npc.blockPosition().toShortString());
        return true;
    }

    /** 【fix273】记一次落水扰动：轮换下标立即 +1（见 farmGotoStandFor），换动作自动清零。 */
    private void noteFarmWaterStrike(String key) {
        if (!key.equals(farmWaterStrikeKey)) {
            farmWaterStrikeKey = key;
            farmWaterStrikes = 0;
        }
        farmWaterStrikes++;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 落水扰动（fix273） | " + key
                + " 本动作第 " + farmWaterStrikes + " 次，下轮换站位");
    }

    /**
     * 【fix273】站位候选「干路优先」视图（125 棒格 R 实证）：271a 排序只看
     * 层差与距离——人站高一层时，水对岸的台顶站位（层差 0）会压过同层
     * 岸边站位，goto 直线段必穿深水、落水后与游泳救援互斗。玩家选站位
     * 先看「走过去要不要下水」：把直线段不穿深水的候选整体提到前面
     * （组内保持原序），全穿时原序不变（兜底不缺位）。
     */
    private java.util.List<BlockPos> farmDryFirstStands(java.util.List<BlockPos> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        java.util.List<BlockPos> dry = new java.util.ArrayList<>();
        java.util.List<BlockPos> wet = new java.util.ArrayList<>();
        for (BlockPos cand : candidates) {
            (farmSegmentCrossesDeepWater(cand) ? wet : dry).add(cand);
        }
        if (dry.isEmpty() || wet.isEmpty()) {
            return candidates;
        }
        dry.addAll(wet);
        return dry;
    }

    /**
     * 【fix273】NPC→站位的直线段是否穿深水：沿线每半格采样（起讫列不
     * 算），采样列在脚位层 0..-3 内任一连续两格皆流体即判穿——那是身体
     * 走过去必然入水游泳的水深；只有 1 格流体的蹚水不算（眼睛不出水、
     * 游泳救援不点火，玩家也是直接蹚过去）。
     */
    private boolean farmSegmentCrossesDeepWater(BlockPos stand) {
        double x0 = npc.getX();
        double z0 = npc.getZ();
        double x1 = stand.getX() + 0.5D;
        double z1 = stand.getZ() + 0.5D;
        double dist = Math.hypot(x1 - x0, z1 - z0);
        if (dist < 1.5D) {
            return false;
        }
        int feetY = npc.blockPosition().getY();
        int steps = (int) Math.ceil(dist / 0.5D);
        BlockPos npcCell = npc.blockPosition();
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(x0 + (x1 - x0) * t);
            int bz = (int) Math.floor(z0 + (z1 - z0) * t);
            if ((bx == npcCell.getX() && bz == npcCell.getZ())
                    || (bx == stand.getX() && bz == stand.getZ())) {
                continue;
            }
            for (int dy = 0; dy >= -2; dy--) {
                BlockPos upper = new BlockPos(bx, feetY + dy, bz);
                BlockPos lower = new BlockPos(bx, feetY + dy - 1, bz);
                if (!npc.level().getBlockState(upper).getFluidState().isEmpty()
                        && !npc.level().getBlockState(lower).getFluidState().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 走到动作格旁边的落脚格。
     *
     * <p>【fix264】接近编排改走 moveToStand（fix62/245 既有口径）：旧版
     * fix60 的「goto 连败 ≥2 改派裸 travel」在崖顶/深水位差面必死——
     * travel 的宽带早退（fix156/176）水平 ≤12 格 ∧ |Δy|≥6 即以「水平
     * 到位，需要竖直对齐」收场，而 farm 接近是技能层直调、后面没有
     * 目标层对齐者接手，动作连环秒退烧满 stuck 被跳过（115 棒克隆
     * 实测：崖顶 y=68 对站位 y=62、Δy=6 正中宽带，travel 找到路径仍
     * 同秒收场 ×5、harvest 被跳过；生产 farm279 同型被 fix134 两度
     * 撤退掐死、零田间动作）。moveToStand 先以 descend/ascend 自挖
     * 楼梯对齐高度、死带补 ascend、水平远/连卡才派 travel（y 口径
     * 同 fix245），与建造/长途线同一套已验证编排。
     *
     * <p>【fix271 站位面放宽＋本地 goto 直达（122 棒定谳）】fix264 后残余
     * 形态：站位只认动作格同层四邻——水洼边动作格（place 的水格、洼边
     * digdirt 源）四邻常全灭（全水/耕地），站位落到水格本身或洼底孤格；
     * 其后 travel 的终点 Y 解析被站位列上空的自造残柱污染（高度图读
     * 99、122 棒克隆实证），travel 搭桥爬高宽带早退、descend 对齐又无
     * 水平锚（达标点距目标 10 格），两技能确定性振荡、动作被跳过。
     * 玩家站位是动作格一圈任何能落脚的地面：候选放宽为 8 邻列 × 层位
     * {−1,0,+1} 的可站块（判据同旧：有碰撞 ∧ 上方无碰撞 ∧ 非耕地非
     * 作物；该几何下站位脚位对动作格的够格带恒成立）＋动作格本身兜底，
     * 按脚位层差优先、近序排列；本地层带内（层差 ≤1 ∧ 水平 ≤32，或
     * 层差 ≤6 ∧ 水平 ≤6）由 farm 层直接派 goto（落点 ±12 竖直扫描、
     * 不经 travel 终点 Y 解析），goto 被拒同调用落回 moveToStand；
     * 同一动作 stuck 每满 3 次轮换下一候选站位（与 fix268 候选轮换
     * 同族），远/大落差仍走 moveToStand 既有编排。
     */
    private void farmGotoStandFor(FarmAction action) {
        BlockPos soil = ("harvest".equals(action.kind()) || "bonemeal".equals(action.kind()))
                ? action.pos().below() : action.pos();
        String key = action.kind() + "@" + action.pos().toShortString();
        if (!key.equals(farmStandKey)) {
            farmStandKey = key;
            farmStandList = farmStandCandidates(soil);
        }
        // 【fix323】(B) 守卫受阻连败记账：身体层救援跳过侧挖的累计数递增
        // 即本站位一次守卫受阻（不随微进展清零）；满 2 即换站位。首读
        // 只同步计数、不记账（身体计数自开服累计，目标层初建时已非零）。
        // 【fix327】(C) 按增量记账：旧版每次调用最多 +1，而 processFarmActions
        // 只在技能 idle 间隙调用、travel 腿内几十次受阻只折成 1 次——7 分钟
        // 才攒满 2 次（203 生产实证）；现按 delta（本次调用内新增的跳过数）
        // 一次记足。换动作时同步 seq 并清记死集，不把旧动作余数带入新动作。
        // 轮换时记死上次派发的站位，下轮候选表过滤。
        if (!key.equals(farmGuardBlockKey)) {
            farmGuardBlockKey = key;
            farmGuardBlockStreak = 0;
            farmGuardRotations = 0;
            farmGuardSkipSeq = -1;
            farmGuardDeadStands.clear();
            farmGuardLastStand = null;
            farmGuardAllDeadKey = "";
        }
        int guardSkips323 = npc.farmGuardRescueSkips();
        if (farmGuardSkipSeq < 0) {
            farmGuardSkipSeq = guardSkips323;
        } else if (guardSkips323 != farmGuardSkipSeq) {
            int delta = guardSkips323 - farmGuardSkipSeq;
            farmGuardSkipSeq = guardSkips323;
            if (delta > 0) {
                farmGuardBlockStreak += delta;
                if (farmGuardBlockStreak >= 2) {
                    farmGuardBlockStreak = 0;
                    farmGuardRotations++;
                    if (farmGuardLastStand != null) {
                        farmGuardDeadStands.add(farmGuardLastStand);
                    }
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 守卫受阻换站位（fix327） | "
                            + key + " 轮换=" + farmGuardRotations + " 记死站位=" + farmGuardDeadStands.size());
                }
            }
        }
        // 【fix320】goto 失败记忆：上一个 goto 以「直线行走放弃」收场（A* 无路＋
        // 直线走被地形堵死，如过洼断崖）时记死本动作 key，本动作内不再原样重派 goto，
        // 直落 moveToStand（travel 搭桥／ascend 爬升）换法。187 棒 G1：goto-straight
        // 95 行、每 ~7 秒一轮、同带重派烧光整趟 tend。有进展（farmActionStuck==0，
        // processFarmActions 每 tick 在调用前按 distNow 重算）或换动作时清死。
        int gotoFailSeq320 = skills.outcomeSeq();
        String lastOutcome320 = skills.lastOutcome();
        if (lastOutcome320 != null && lastOutcome320.startsWith("goto ")
                && lastOutcome320.contains("直线行走放弃")
                && gotoFailSeq320 != farmGotoFailSeq) {
            farmGotoFailSeq = gotoFailSeq320;
            farmGotoDeadKey = key;
        }
        if (farmActionStuck == 0 || (farmGotoDeadKey != null && !farmGotoDeadKey.equals(key))) {
            farmGotoDeadKey = null;
        }
        // 【fix273】轮换视图＝干路优先（直线段不穿深水的候选在前，组内
        // 保 271a 原序）；下标＝stuck/3＋落水扰动数——第一次落水后下轮
        // 即换站位，不再同站位同直线重演互斗。
        java.util.List<BlockPos> candidates = farmDryFirstStands(farmStandList.isEmpty()
                ? java.util.List.of(soil) : farmStandList);
        // 【fix327】(C) 过滤已记死的站位：轮换过的站位不再重派（旧版下标顶满时
        // 两腿目标完全相同、换了等于没换——203 生产实证）。全部记死时不再派发，
        // 本次直接返回、由上层 stuck 计数走满后按既有口径诚实跳过该动作
        // （不冒充完成）；只记一次。
        if (!farmGuardDeadStands.isEmpty()) {
            java.util.List<BlockPos> alive = new java.util.ArrayList<>();
            for (BlockPos c : candidates) {
                if (!farmGuardDeadStands.contains(c)) {
                    alive.add(c);
                }
            }
            if (alive.isEmpty()) {
                if (!key.equals(farmGuardAllDeadKey)) {
                    farmGuardAllDeadKey = key;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 站位全记死（fix327） | "
                            + key + " 记死=" + farmGuardDeadStands.size() + "，不再派发、等上层跳过");
                }
                return;
            }
            candidates = alive;
        }
        int strikes = key.equals(farmWaterStrikeKey) ? farmWaterStrikes : 0;
        // 【fix323】(B) 轮换下标叠加守卫受阻轮换数（与 stuck/3、落水扰动数同口径）。
        int idx = Math.min(farmActionStuck / 3 + strikes + farmGuardRotations, candidates.size() - 1);
        BlockPos target = candidates.get(idx);
        farmGuardLastStand = target;
        double dxs = npc.getX() - (target.getX() + 0.5D);
        double dzs = npc.getZ() - (target.getZ() + 0.5D);
        double distH = Math.sqrt(dxs * dxs + dzs * dzs);
        double feetDiff = Math.abs(npc.getY() - (target.getY() + 1.0D));
        // 【fix272】高位被困先竖直对齐：人被早前编排带到土格脚位层上方
        // ≥5 格且水平还远（>6 格）时，旧路落 moveToStand 的 travel 支——
        // travel 传当前高度、段回退可取柱台高点、宽带早退把人留在台顶，
        // 随后 descend 在崖面近冻结（124 棒生产实证：159 秒仅 6 步、
        // onGround=false）。玩家做法是先顺坡下到田面高度再水平走：farm
        // 层直接派 descend 到土格脚位层（steps 上限 40，防崖面长挂），
        // 落地后由下方 goto 直达/候选轮换接手。水里不派（游泳层的事）。
        if (!npc.isInWater() && npc.getY() - (soil.getY() + 1.0D) >= 5.0D && distH > 6.0D) {
            JsonObject dp = new JsonObject();
            dp.addProperty("y", (double) (soil.getY() + 1));
            dp.addProperty("steps", 40);
            if (skills.run(new Intent("", "descend", dp), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 高位先竖直对齐（fix272） | descend 目标Y="
                        + (soil.getY() + 1) + " 当前Y=" + Math.round(npc.getY())
                        + " 站位=" + target.toShortString());
                return;
            }
        }
        // 【fix337】旱岛贴田边爬升：脚在干地上、站位在脚上、本站位已因
        // 农田守卫受阻（streak≥1）或落水扰动（strikes≥1），且脚位四邻
        // （水平 4 向、dy 0／-1）任一格有流体时，goto 直线须蹚水、身体
        // 入水即游泳顶掉回调（fix273），三道既有逃生（goto 直达／fix323(C)
        // 水中爬升／生存层流体脱困）在此「旱岛」几何下全部不适用——
        // 改派 ascend 垫脚爬升（fix321 (A) 羊毛口径复用），爬到站位层
        // 再收；无垫块时 ascend 以「没有垫脚方块」收场，落入 (B) 的
        // 轮换／跳过，不硬失败。只在五条件齐时点火，平时不改 goto 编排。
        BlockPos feet337 = npc.blockPosition();
        boolean dryIslandFluid = isFluidAt(feet337.north()) || isFluidAt(feet337.south())
                || isFluidAt(feet337.east()) || isFluidAt(feet337.west())
                || isFluidAt(feet337.north().below()) || isFluidAt(feet337.south().below())
                || isFluidAt(feet337.east().below()) || isFluidAt(feet337.west().below());
        if (!npc.isInWater() && target.getY() > feet337.getY()
                && key.equals(farmGuardBlockKey) && (farmGuardBlockStreak >= 1 || strikes >= 1)
                && dryIslandFluid) {
            JsonObject ap = new JsonObject();
            ap.addProperty("y", (double) target.getY());
            ap.addProperty("max", 160);
            if (skills.run(new Intent("", "ascend", ap), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 旱岛贴田边改 ascend 爬升（fix337） | 站位="
                        + target.toShortString() + " 当前Y=" + Math.round(npc.getY())
                        + " 守卫受阻=" + farmGuardBlockStreak + " 落水扰动=" + strikes);
                return;
            }
        }
        // 【fix320】goto 带记死时不派 goto（见本方法首部失败记忆），直落 moveToStand 换法。
        boolean gotoDead320 = key.equals(farmGotoDeadKey);
        if (!gotoDead320 && ((feetDiff <= 1.0D && distH <= 32.0D) || (feetDiff <= 6.0D && distH <= 6.0D))) {
            JsonObject go = new JsonObject();
            go.addProperty("x", target.getX() + 0.5D);
            go.addProperty("z", target.getZ() + 0.5D);
            if (skills.run(new Intent("", "goto", go), "目标")) {
                return;
            }
        }
        // 【fix323】(C) 水里贴田边爬升：脚在流体里、站位在脚上、本站位已因
        // 农田守卫受阻（streak≥1）时，goto 直线迈不上被耕地封顶的 1 格
        // 高差、travel 又在低处报假到达——改派 ascend 垫脚爬升（fix321 (A)
        // 羊毛口径复用），爬到站位层再收；无垫块时 ascend 以「没有垫脚方
        // 块」收场，落入 (B) 的轮换／跳过，不硬失败。只在三条件齐时点火，
        // 平时不改 goto／travel／descend 编排；不经 moveToStand（fix319
        // 闭环守卫只认自家登记派发），ascend 自带到层收场与看门狗。
        if (npc.isInWater() && target.getY() > npc.blockPosition().getY()
                && key.equals(farmGuardBlockKey) && farmGuardBlockStreak >= 1) {
            JsonObject ap = new JsonObject();
            ap.addProperty("y", (double) target.getY());
            ap.addProperty("max", 160);
            if (skills.run(new Intent("", "ascend", ap), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 水里贴田边改 ascend 爬升（fix323） | 站位="
                        + target.toShortString() + " 当前Y=" + Math.round(npc.getY())
                        + " 守卫受阻=" + farmGuardBlockStreak);
                return;
            }
        }
        moveToStand(target, farmActionStuck, "[FARM]");
    }

    /** 【fix271】站位候选：动作格 8 邻列 × 层位 {−1,0,+1} 可站块＋动作格本身兜底。 */
    private java.util.List<BlockPos> farmStandCandidates(BlockPos soil) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos candidate = soil.offset(dx, dy, dz);
                    if (isFarmStandable(candidate)) {
                        out.add(candidate);
                    }
                }
            }
        }
        out.sort(java.util.Comparator
                .comparingDouble((BlockPos p) -> Math.abs(npc.getY() - (p.getY() + 1.0D)))
                .thenComparingDouble(p -> npc.blockPosition().distSqr(p)));
        out.add(soil);
        return out;
    }

    /** 站位可站判据（fix294a：与 PathFinder 同口径验真脚位）：
     *  非耕地非作物 ∧ 脚位（candidate 上方）可通行（水面脚位恒不可通行）∧ 脚下有实心支撑（非树叶）。
     *  旧口径只查候选块有碰撞＋上方无碰撞，水没有碰撞形状，脚位是水的候选照样放行（141 棒甲：
     *  站位 (-16,61,-117) 候选块 y=61 石有碰撞、上方 y=62 水无碰撞 → 进表，终点解析「终点不可站立」）。 */
    private boolean isFarmStandable(BlockPos candidate) {
        BlockState state = npc.level().getBlockState(candidate);
        if (state.is(Blocks.FARMLAND) || FarmMemory.cropOf(state.getBlock()) != null) {
            return false;
        }
        return PathFinder.isStandable(npc.level(), candidate.above());
    }

    /** 执行一个田间动作（瞬时；规则校验在 farmActionValid 与本方法内双保险）。 */
    private void doFarmAction(FarmAction action) {
        switch (action.kind()) {
            case "digdirt" -> {
                // 【fix270】就地取土：田边挖一块草/泥土，供同队列随后的
                // place 垫土消耗（与 harvest 同原语）。
                // 【fix270 补环·121 棒克隆实证】取土掉落靠身体逐 tick 自动拾取，
                // 而队列在同一次 run 内紧接着消费 place，place 数包里 dirt——
                // 掉落还没入包 place 就被复核丢弃、till/plant 连环失效
                // （克隆首跑丢弃=3 实证）。照工作站回收熔炉的既有口径：
                // 当场把取土格脚边的 dirt 掉落同步搬进背包。
                if (npc.mineBareHand(action.pos())) {
                    // 入账先行：逐个掉落先 give 进包、按真实入包增量收实体，
                    // 入不了包的掉落原样留在地上（等身体逐 tick 拾取），不蒸发
                    // （121 棒克隆满包实证：先 discard 后 give 失败会把土弄丢）。
                    int collected = 0;
                    net.minecraft.world.phys.AABB box =
                            new net.minecraft.world.phys.AABB(action.pos()).inflate(1.5D);
                    for (ItemEntity entity : npc.level().getEntitiesOfClass(
                            ItemEntity.class, box,
                            e -> e.getOwner() == null && "dirt".equals(pathOf(e.getItem())))) {
                        int n = entity.getItem().getCount();
                        int before = countItem("dirt");
                        give("dirt", n);
                        int delta = countItem("dirt") - before;
                        if (delta >= n) {
                            entity.discard();
                            collected += n;
                        } else if (delta > 0) {
                            entity.getItem().setCount(n - delta);
                            collected += delta;
                        }
                    }
                    farmEffected++;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 就地取土（fix270）| "
                            + action.pos().toShortString() + " 同步收回dirt=+" + collected
                            + " 包内dirt=" + countItem("dirt"));
                }
            }
            case "place" -> {
                // 【fix264】填水垫土：水面格放一块泥土，随后 till 转耕地。
                if (!consume("dirt", 1)) {
                    return;
                }
                npc.level().setBlockAndUpdate(action.pos(), Blocks.DIRT.defaultBlockState());
                farmEffected++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 垫土 | "
                        + action.pos().toShortString());
            }
            case "till" -> {
                // 土上方若有草，先清掉（顺手还能掉种子，1/8，Wiki）。
                BlockPos above = action.pos().above();
                Block aboveBlock = npc.level().getBlockState(above).getBlock();
                if (aboveBlock == Blocks.SHORT_GRASS || aboveBlock == Blocks.TALL_GRASS
                        || aboveBlock == Blocks.FERN || aboveBlock == Blocks.LARGE_FERN) {
                    npc.mineBareHand(above);
                }
                int moisture = farmHydrated(action.pos()) ? 7 : 0;
                npc.level().setBlockAndUpdate(action.pos(),
                        Blocks.FARMLAND.defaultBlockState().setValue(
                                net.minecraft.world.level.block.FarmBlock.MOISTURE, moisture));
                damageHoe();
                farmEffected++;
                // 【fix297】扩田格锄成耕地当场入账：收尾登记只在 phase 5 跑，
                // 趟 tend 被截断（夜截/饥饿撤退）时当趟放置格永不登记、账本滞后
                // 于物理田（145 棒生产实证：(-10,62,-118)）。锄成即登记，截断
                // 也不丢；phase 5 追加有去重，重复入账无害。
                registerFarmCell(action.pos());
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 锄地 | "
                        + action.pos().toShortString() + " moisture=" + moisture);
            }
            case "plant" -> {
                // 亮度 ≤7 原版种不下（Wiki：光照不足植株会掉落），露天白天恒满足。
                boolean lit = npc.level().canSeeSky(action.pos().above())
                        || npc.level().getMaxLocalRawBrightness(action.pos().above()) >= 8;
                if (!lit) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 播种跳过（光照不足） | "
                            + action.pos().toShortString());
                    return;
                }
                String seedItem = FarmMemory.seedItemOf(action.crop());
                if (!consume(seedItem, 1)) {
                    return;
                }
                Block cropBlock = FarmMemory.blockOfCrop(action.crop());
                npc.level().setBlockAndUpdate(action.pos().above(),
                        cropBlock.defaultBlockState());
                farmEffected++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 播种 | crop=" + action.crop()
                        + " @ " + action.pos().toShortString());
            }
            case "harvest" -> {
                if (npc.mineBareHand(action.pos())) {
                    farmEffected++;
                    farmHarvestedSoils.add(action.pos().below());
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 收获 | crop=" + action.crop()
                            + " @ " + action.pos().toShortString());
                }
            }
            case "bonemeal" -> {
                BlockState state = npc.level().getBlockState(action.pos());
                int age = FarmMemory.cropAge(state);
                if (age < 0 || !consume("bone_meal", 1)) {
                    return;
                }
                int newAge = Math.min(7, age + 2 + npc.getRandom().nextInt(4));
                npc.level().setBlockAndUpdate(action.pos(), state.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7,
                        newAge));
                farmEffected++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 骨粉 | age " + age + "→" + newAge
                        + " @ " + action.pos().toShortString());
            }
            default -> {
            }
        }
    }

    /**
     * 选址（玩家口径）：地表草/泥土格、上方见天、水平 4 格内有水（湿润口径，
     * Wiki），成团 ≥5 格才算一块田，目标 8 格。只看已加载区块的地表，
     * 不为找田加载新区块（与找树同口径，非透视）。
     */
    /**
     * 【fix187】选址换位：phase 2 连续找不到田址时沿罗盘轮转方向走 26 格换一片
     * 河岸再扫（真人找田就是沿河走一段换地方）。旧路径借通用 escalateSearch
     * 8 格小跳，farm154 实证四连败净迁移≈0、122 秒原地放弃。方向按
     * farmSiteAttempts 轮转（不重复同向）、目标列未生成（高度图无效）跳过换向；
     * goto 起不来回退 escalateSearch 兜底，不硬失败。
     */
    private void relocateForFarmSite() {
        int invalidY = npc.level().getMinY() + 4;
        for (int tries = 0; tries < 8; tries++) {
            double angle = (farmSiteAttempts + tries) * Math.PI / 4.0D;
            double tx = npc.getX() + Math.cos(angle) * 26;
            double tz = npc.getZ() + Math.sin(angle) * 26;
            int surfaceY = npc.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(tx), (int) Math.floor(tz));
            if (surfaceY <= invalidY) {
                continue;
            }
            JsonObject params = new JsonObject();
            params.addProperty("x", tx);
            params.addProperty("z", tz);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [FARM] 选址换位（fix187） | 第"
                        + farmSiteAttempts + "次 方向=" + Math.round(Math.toDegrees(angle)) + "° 距离=26");
                return;
            }
            break;
        }
        escalateSearch();
    }

    private java.util.List<BlockPos> findFarmSite() {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos me = npc.blockPosition();
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int dx = -26; dx <= 26; dx++) {
            for (int dz = -26; dz <= 26; dz++) {
                int x = me.getX() + dx;
                int z = me.getZ() + dz;
                if (level.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    continue;
                }
                int topY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x, z) - 1;
                BlockPos ground = new BlockPos(x, topY, z);
                BlockState state = level.getBlockState(ground);
                if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)
                        && !state.is(Blocks.FARMLAND)) {
                    continue;
                }
                BlockState above = level.getBlockState(ground.above());
                if (!above.isAir() && !above.is(Blocks.SHORT_GRASS) && !above.is(Blocks.TALL_GRASS)
                        && !above.is(Blocks.FERN) && !above.is(Blocks.LARGE_FERN)
                        && !above.is(Blocks.SNOW)) {
                    continue;
                }
                if (!level.canSeeSky(ground.above())) {
                    continue;
                }
                if (!farmHydrated(ground)) {
                    continue;
                }
                if (farmPitRisk(ground)) {
                    continue;
                }
                candidates.add(ground);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(java.util.Comparator.comparingDouble(me::distSqr));
        BlockPos seed = candidates.get(0);
        java.util.List<BlockPos> cluster = new java.util.ArrayList<>();
        for (BlockPos candidate : candidates) {
            int chebyshev = Math.max(Math.abs(candidate.getX() - seed.getX()),
                    Math.abs(candidate.getZ() - seed.getZ()));
            if (chebyshev <= 3 && Math.abs(candidate.getY() - seed.getY()) <= 1) {
                cluster.add(candidate);
                if (cluster.size() >= FARM_PLOT_CELLS) {
                    break;
                }
            }
        }
        return cluster.size() >= 5 ? cluster : null;
    }

    /** 湿润判定（Wiki）：水平 4 格内、同层或高 1 层有水。 */
    private boolean farmHydrated(BlockPos soil) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (npc.level().getBlockState(soil.offset(dx, dy, dz)).is(Blocks.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 坑底风险（fix167）：近身（切比雪夫 ≤2）有比土面高一层的水 = 这格坐在
     * 高水位池塘边的坑底，水一漫就淹田（旧田 (324,62,-182) 全 lost 的死法）。
     * dy=0 同层水是常规灌溉口径、不拦；只用于选址，不影响已注册田。
     */
    private boolean farmPitRisk(BlockPos soil) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (npc.level().getBlockState(soil.offset(dx, 1, dz)).is(Blocks.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 背包里第一把可用锄头的槽位（剩 1 点耐久的按没有算，口径同镐）。 */
    private int findHoeSlot() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_hoe")) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return slot;
            }
        }
        return -1;
    }

    /** 锄一次地记 1 点耐久（原版锄地消耗），坏了按 [TOOL-BREAK] 记账。 */
    private void damageHoe() {
        int slot = findHoeSlot();
        if (slot < 0) {
            return;
        }
        ItemStack stack = npc.getInventory().getItem(slot);
        if (!stack.isDamageableItem()) {
            return;
        }
        int damage = stack.getDamageValue() + 1;
        if (damage >= stack.getMaxDamage()) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [TOOL-BREAK] " + pathOf(stack)
                    + " 锄地用坏 slot=" + slot);
            npc.getInventory().setItem(slot, ItemStack.EMPTY);
        } else {
            stack.setDamageValue(damage);
        }
    }

    // ---------------------------------------------------------------- 落脚点（fix51·D2）

    /** 小屋平面半径：外 5×5（dx,dz ∈ -2..2），内空 3×3，墙高 3、平顶。 */
    private static final int BASE_HALF = 2;
    /** 砌筑材料总需求上限估算：补地 ≤25 + 墙 46（留门洞 2）+ 顶 25 ≈ 96。 */
    private static final int BASE_MATERIAL_NEED = 80;

    private int basePhase;
    /** 待砌格队列（自下而上：补地→墙三层→屋顶）。 */
    private final java.util.List<BlockPos> baseQueue = new java.util.ArrayList<>();
    /** 选中的中心列（地面顶格）；null=未选址。 */
    private BlockPos baseCenter;
    private int baseActionStuck;
    private String baseLastActionKey = "";
    private int basePlacedCount;
    private int baseTotalCells;
    private boolean baseQueueBuilt;

    /**
     * 建落脚点：选址 → 砌筑队列 → 完工自审 → 落盘 BaseMemory。
     * 放置走 setBlockAndUpdate 直改 + 代码内校验（placeFurnace/种田同先例）：
     * 目标格必须是空气/可替换植被、材料从背包真扣。
     * 全部砌筑从屋内中心一个站位可达（最远角格水平 2.83、屋顶 dy=3，
     * 在 farmInReach 同口径内），不需要脚手架。
     */
    private void tickBase() {
        switch (basePhase) {
            case 0 -> {
                status = "落脚点：选址";
                if (baseCenter == null) {
                    baseCenter = findBaseSite();
                    if (baseCenter == null) {
                        finish("建屋放弃：附近没有合格地块（5×5 平整、无遮挡）");
                        return;
                    }
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 选址 | center="
                            + baseCenter.toShortString() + "（评分：近水/近田/近树）");
                }
                if (!baseQueueBuilt) {
                    buildBaseQueue();
                    baseQueueBuilt = true;
                    baseTotalCells = baseQueue.size();
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 砌筑队列 | 格数="
                            + baseTotalCells + " 建材存量=" + countBuildMaterial());
                }
                basePhase = 1;
            }
            case 1 -> {
                status = "落脚点：砌筑 " + basePlacedCount + "/" + baseTotalCells;
                if (processBaseQueue()) {
                    basePhase = 2;
                }
            }
            case 2 -> {
                // 完工自审：逐格复扫——墙/顶/补地格里是实心方块才算数，
                // 达标才落盘（不许"队列跑完=建成"的假成功）。
                int solid = 0;
                for (BlockPos cell : baseAuditCells()) {
                    BlockState state = npc.level().getBlockState(cell);
                    if (!state.isAir() && state.getFluidState().isEmpty()
                            && !isVegetation(state)) {
                        solid++;
                    }
                }
                int total = baseAuditCells().size();
                if (total > 0 && solid * 100 >= total * 95) {
                    BlockPos anchor = new BlockPos(baseCenter.getX(), baseCenter.getY() + 1,
                            baseCenter.getZ());
                    BaseMemory.bindWorld(npc.level());
                    BaseMemory.save(npc.npcName(), anchor);
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 落脚点建成 | anchor="
                            + anchor.toShortString() + " 自审实心=" + solid + "/" + total
                            + " 用料=" + basePlacedCount);
                    finish("落脚点建成：火柴盒小屋 自审 " + solid + "/" + total
                            + "，锚点已落盘");
                } else {
                    finish("建屋未达标：自审实心 " + solid + "/" + total + "（要求 ≥95%）");
                }
            }
            default -> finish("建屋阶段异常 " + basePhase);
        }
    }

    /** 自审格清单：与砌筑队列同一批格（队列执行后已清空，按中心重建）。 */
    private java.util.List<BlockPos> baseAuditCells() {
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        if (baseCenter == null) {
            return cells;
        }
        int cx = baseCenter.getX();
        int cz = baseCenter.getZ();
        int floor = baseCenter.getY();
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                // 只审墙与顶（结构主体）；补地格在队列里有计数兜底，
                // 高度图会被新墙干扰、不适合复测补地。
                if (Math.abs(dx) == BASE_HALF || Math.abs(dz) == BASE_HALF) {
                    for (int level = 1; level <= 3; level++) {
                        if (dx == 0 && dz == BASE_HALF && level <= 2) {
                            continue; // 门洞（南墙中，2 高）
                        }
                        cells.add(new BlockPos(cx + dx, floor + level, cz + dz));
                    }
                }
                cells.add(new BlockPos(cx + dx, floor + 4, cz + dz)); // 屋顶
            }
        }
        return cells;
    }

    /** 生成砌筑队列：补地（原地面低 1 的列）→ 墙三层（南墙留门洞）→ 屋顶。 */
    private void buildBaseQueue() {
        baseQueue.clear();
        int cx = baseCenter.getX();
        int cz = baseCenter.getZ();
        int floor = baseCenter.getY();
        ServerLevel serverLevel = (ServerLevel) npc.level();
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                int top = serverLevel.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        cx + dx, cz + dz);
                if (top == floor - 1) {
                    baseQueue.add(new BlockPos(cx + dx, floor, cz + dz));
                }
            }
        }
        for (int level = 1; level <= 3; level++) {
            for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
                for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                    if (Math.abs(dx) != BASE_HALF && Math.abs(dz) != BASE_HALF) {
                        continue;
                    }
                    if (dx == 0 && dz == BASE_HALF && level <= 2) {
                        continue; // 门洞
                    }
                    baseQueue.add(new BlockPos(cx + dx, floor + level, cz + dz));
                }
            }
        }
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                baseQueue.add(new BlockPos(cx + dx, floor + 4, cz + dz));
            }
        }
    }

    /**
     * 【fix62】站位接近（目标层编排，照 tickTravel/tickRevisit 既有口径）：
     * 竖直差 >4 先 descend/ascend 对齐——两技能自挖楼梯，屋顶能拆自家避难所下来、
     * 井下能续爬上来；裸 goto 在屋顶/楼梯井/洼地起点必死（standableGoal 垂直窗口
     * 以当前 Y 为锚、A* 开放集 15~97 节点耗尽），建屋三火 from==to 实锤。
     * 竖直对齐后水平远/连败才派 travel，且 y 传当前高度：travel 技能只管水平推进，
     * 其断崖搭桥是「放脚层再站上去」的爬升设计，目标在下方时直接派它会一路搭到
     * 天上（farm20 首火实证：屋顶 88 → 圆石柱 135 以「需要竖直对齐」收场）。
     * 近处平地仍走 goto。travel/descend/ascend 启动后技能层接管，本方法只管发令。
     */
    /** 【fix282】消费 moveToStand 自家派出的 descend 结局（照 fix142 范式，
     * 序号水位＋只认派发水位之后的 descend 结局）：「到达」清零连败；
     * 「无实际下降」计数并打专行，其余结局不计。 */
    private void noteMtsDescendOutcome(String tag) {
        int seq = skills.outcomeSeq();
        if (seq == handledMtsDescendSeq) {
            return;
        }
        handledMtsDescendSeq = seq;
        // 【fix284】派发之后已有任何结局产出 = 那一派 descend 已结束
        // （自收/被顶替/看门狗收场），守卫登记随之作废，防陈账误停。
        if (mtsDescendDispatchSeq >= 0 && seq > mtsDescendDispatchSeq) {
            mtsDescendStartTick = -1L;
        }
        if (mtsDescendDispatchSeq < 0 || seq <= mtsDescendDispatchSeq) {
            return;
        }
        String outcome = skills.lastOutcome();
        if (outcome == null || !outcome.startsWith("descend ")) {
            return;
        }
        if (outcome.contains("到达")) {
            mtsNoDropFails = 0;
            mtsSidestepPending = false;
            return;
        }
        if (outcome.contains("无实际下降")) {
            mtsNoDropFails++;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                    + " 站位接近 descend 无实际下降计数 | fails=" + mtsNoDropFails
                    + "（fix282）outcome=" + outcome);
        }
    }

    /** 【fix285】消费 moveToStand 自家派出的 travel 结局（照 fix282 的
     * descend 消费范式：序号水位＋只认派发水位之后的 travel 结局）：
     * 「长途行进受阻」且自派发点水平净位移 <2 格 → 爆表计数＋专行；
     * 其余 travel 结局（到达/竖直对齐收场等）清零计数。派发之后先有
     * 别的结局产出（被顶替/被中断）时登记作废、不误计。 */
    private void noteMtsTravelOutcome(String tag) {
        int seq = skills.outcomeSeq();
        if (seq == handledMtsTravelSeq) {
            return;
        }
        handledMtsTravelSeq = seq;
        if (mtsTravelDispatchSeq < 0 || seq <= mtsTravelDispatchSeq) {
            return;
        }
        // 派发后已有任何结局：登记只对「紧接着的那一个 travel 结局」
        // 有效，先清登记再判内容（被顶替的 travel 结局已被覆盖时，
        // lastOutcome 不是 travel 开头，自然不计数）。
        mtsTravelDispatchSeq = -1;
        String outcome = skills.lastOutcome();
        if (outcome == null || !outcome.startsWith("travel ")) {
            return;
        }
        if (outcome.contains("长途行进受阻")) {
            double dx = npc.getX() - mtsTravelStartX;
            double dz = npc.getZ() - mtsTravelStartZ;
            if (dx * dx + dz * dz < 4.0D) {
                mtsTravelBlowups++;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                        + " 站位接近 travel 爆表计数（fix285） | blowups=" + mtsTravelBlowups
                        + " outcome=" + outcome);
            }
            return;
        }
        mtsTravelBlowups = 0;
    }

    /** 【fix287】消费 moveToStand 自家派出的 ascend 结局（照 fix285 的
     * travel 消费范式：序号水位＋派发后第一个结局先清登记防顶替误计）：
     * 「已到目标层/已回到地表」清零连败；其余 ascend 结局按自派发点
     * 精确净升结算，净升 <1.0 格记无上升连败并打专行。净升由身体读数
     * 直算、不依赖结局文案——ascend 的失败文案有四五种，descend 的
     * 「无实际下降」式专有文案在 ascend 侧不存在。 */
    private void noteMtsAscendOutcome(String tag) {
        int seq = skills.outcomeSeq();
        if (seq == handledMtsAscendSeq) {
            return;
        }
        handledMtsAscendSeq = seq;
        if (mtsAscendDispatchSeq < 0 || seq <= mtsAscendDispatchSeq) {
            return;
        }
        mtsAscendDispatchSeq = -1;
        String outcome = skills.lastOutcome();
        if (outcome == null || !outcome.startsWith("ascend ")) {
            return;
        }
        if (outcome.contains("已到目标层") || outcome.contains("已回到地表")) {
            mtsNoRiseFails = 0;
            mtsAscendSidestepPending = false;
            return;
        }
        double rise = npc.getY() - mtsAscendStartY;
        if (rise < 1.0D) {
            mtsNoRiseFails++;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                    + " 站位接近 ascend 无实际上升计数（fix287） | fails=" + mtsNoRiseFails
                    + " 净升=" + String.format(java.util.Locale.ROOT, "%.2f", rise)
                    + " outcome=" + outcome);
        } else {
            mtsNoRiseFails = 0;
        }
    }

    /** 【fix282】侧移换列候选：身体当前列 8 邻列里找可站格（同层或低一层），
     * 按连败数轮换起始方向、两次侧移不去同一列。找不到返回 null（照旧派
     * descend，如孤立尖柱顶四邻全悬空——那种几何由 descend 自身的四向
     * 全灭垂直下挖分支处理，本侧移不越界）。 */
    private BlockPos findMtsSidestepCell(int fails) {
        BlockPos feet = npc.blockPosition();
        int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int i = 0; i < offs.length; i++) {
            int[] o = offs[(i + fails) % offs.length];
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos cand = feet.offset(o[0], dy, o[1]);
                if (PathFinder.isStandable(npc.level(), cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    private void moveToStand(BlockPos stand, int stuckTicks, String tag) {
        // 【fix282】站位变了就忘掉旧账：无下降连败只对同一站位累计。
        if (mtsStandKey == null || !mtsStandKey.equals(stand)) {
            mtsStandKey = stand.immutable();
            mtsNoDropFails = 0;
            mtsSidestepPending = false;
            mtsDescendStartTick = -1L;
            handledMtsDescendSeq = skills.outcomeSeq();
            // 【fix285】爆表计数只对同一站位累计，站位变即清零。
            mtsTravelBlowups = 0;
            mtsTravelDispatchSeq = -1;
            handledMtsTravelSeq = skills.outcomeSeq();
            // 【fix287】ascend 无上升连败同样只对同一站位累计。
            mtsNoRiseFails = 0;
            mtsAscendSidestepPending = false;
            mtsAscendDispatchSeq = -1;
            handledMtsAscendSeq = skills.outcomeSeq();
        }
        noteMtsDescendOutcome(tag);
        noteMtsTravelOutcome(tag);
        noteMtsAscendOutcome(tag);
        double dxs = npc.getX() - (stand.getX() + 0.5D);
        double dzs = npc.getZ() - (stand.getZ() + 0.5D);
        double dys = npc.getY() - stand.getY();
        // 【fix338】站位高差死带·下行：站位比身体低 ≥2 格且接近已连卡 ≥3 次，
        // 直接派 descend 挖楼梯降到站位层（fix245 的下行镜像）。判据先于
        // |dy|>4 分支：精确 Y 在 4.0 边界附近浮动时，|dy|>4 分支与 fix319
        // 守卫互锁空转——descend 刚派即因「已入带」被停、重派约 3 秒一轮零
        // 进展（234 棒实验场 G1b/G1c 实证，fix338 一次未点火）；连卡已证明
        // 水平腿走不通，优先下挖。未连卡时仍走原 |dy|>4 口径，fix319 防过
        // 冲、fix282 侧移均不受影响。本派发不登记（照 fix245），fix319
        // 守卫不截停。门框保护：进门接近段的 fix313 分流保证 descend 只
        // 发生在 bottomDist>2.5 的 ② 号分支（离框几格外），不在框顶直挖。
        if (dys >= 2.0D && stuckTicks >= 3) {
            // 【fix339】桥顶短距下落：站位只低 2~3 格（A* 合法下落内）且水平
            // 距站位 ≤4 格时，先走 goto 本地寻路直接落到站位（桥面 1 步落下，
            // 行走层自带下落）；goto 拒派才落回 descend 挖楼梯。
            // 生产床边实证（240 棒）：NPC 在桥顶 (0,66,-111)、站位 1 格外
            // (1,65,-112) 时 fix338 连点 descend，连卡 5→13，descend 却因桥
            // 端列在站位列之外、目标 Y 无承托格而横移漂移（86 秒落点
            // (-2,63,-106)，距站位 8 格），travel 随即再搭桥爬升，垂直拉锯
            // 烧满 15 趟；实验室 G0d 实证：同态桥端 goto 2 秒即到站位睡着。
            // dys>3 不动（进门案 dys=+4、A* 落不下，仍走 descend）。
            if (dys <= 3.0D && dxs * dxs + dzs * dzs <= 16.0D) {
                JsonObject gp = new JsonObject();
                gp.addProperty("x", stand.getX() + 0.5D);
                gp.addProperty("z", stand.getZ() + 0.5D);
                if (skills.run(new Intent("", "goto", gp), "目标")) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                            + " 站位接近桥顶短距下落，先 goto 落站位（fix339） | "
                            + stand.toShortString() + " dys=" + String.format("%.1f", dys));
                    return;
                }
            }
            // 【fix363】fix338 派发的 descend 也登记结局（照 fix284）并
            // 享 fix282 侧移：此前不登记，noteMtsDescendOutcome 消费不
            // 到它的「无实际下降」结局，mtsNoDropFails 永远不涨，fix282
            // 侧移对 fix338 支是盲的——床顶 descend 原样重派 44 次（281
            // 棒生产实证）。连败 1~2 次时先 goto 侧移换列再续降；登记也
            // 让 fix284 墙钟守卫（45 秒无真降）覆盖本支。
            if (mtsNoDropFails >= 1 && mtsNoDropFails <= 2) {
                if (mtsSidestepPending) {
                    // 侧移 goto 已收场（技能空闲才会走到这）：落回 descend。
                    mtsSidestepPending = false;
                } else {
                    BlockPos side = findMtsSidestepCell(mtsNoDropFails);
                    if (side != null) {
                        JsonObject sp = new JsonObject();
                        sp.addProperty("x", side.getX() + 0.5D);
                        sp.addProperty("z", side.getZ() + 0.5D);
                        if (skills.run(new Intent("", "goto", sp), "目标")) {
                            mtsSidestepPending = true;
                            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                                    + " 站位接近 descend 无下降，先侧移换列（fix363） | to="
                                    + side.toShortString() + " fails=" + mtsNoDropFails
                                    + " 当前Y=" + Math.round(npc.getY()));
                            return;
                        }
                    }
                }
            }
            JsonObject dp = new JsonObject();
            dp.addProperty("y", (double) stand.getY());
            dp.addProperty("max", 160);
            dp.addProperty("steps", 160);
            if (skills.run(new Intent("", "descend", dp), "目标")) {
                // 【fix363】登记本派 descend（照 fix284）：水位/起始精确
                // Y/时刻/目标 type/tag，供 noteMtsDescendOutcome 消费结局
                // 与 fix284 墙钟守卫覆盖。
                mtsDescendDispatchSeq = skills.outcomeSeq();
                mtsDescendStartTick = npc.level().getGameTime();
                mtsDescendStartY = npc.getY();
                mtsDescendGoalType = type;
                mtsDescendTag = tag;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag + " 站位接近改 descend 下降（fix338）"
                        + " | 目标Y=" + stand.getY() + " 当前Y=" + Math.round(npc.getY())
                        + " 连卡=" + stuckTicks);
            }
            return;
        }
        // 【fix354】进门站位竖直带收紧：门在沟谷崖边时 ascend 在 |dy|≤4 即
        // 被 fix319 截停、水平段无路可走，振荡跌回沟底（266 棒生产实证）；
        // [PORTAL] 站位须爬满到门洞层（|dy|≤1，与 atStandLevel 同口径）
        // 再转水平。农田/基地/床等站位仍走 4.0 带，不动。
        double alignBand = "[PORTAL]".equals(tag) ? 1.0D : 4.0D;
        if (Math.abs(dys) > alignBand) {
            String action = dys > 0 ? "descend" : "ascend";
            // 【fix282】descend 上一派以「无实际下降」收场时不原样重派：
            // 先 goto 侧移一列换下挖列，goto 收场后下一 tick 再续 descend。
            if ("descend".equals(action) && mtsNoDropFails >= 1 && mtsNoDropFails <= 2) {
                if (mtsSidestepPending) {
                    // 侧移 goto 已收场（技能空闲才会走到这）：落回 descend。
                    mtsSidestepPending = false;
                } else {
                    BlockPos side = findMtsSidestepCell(mtsNoDropFails);
                    if (side != null) {
                        JsonObject sp = new JsonObject();
                        sp.addProperty("x", side.getX() + 0.5D);
                        sp.addProperty("z", side.getZ() + 0.5D);
                        if (skills.run(new Intent("", "goto", sp), "目标")) {
                            mtsSidestepPending = true;
                            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                                    + " 站位接近 descend 无下降，先侧移换列（fix282） | to="
                                    + side.toShortString() + " fails=" + mtsNoDropFails
                                    + " 当前Y=" + Math.round(npc.getY()));
                            return;
                        }
                    }
                }
            }
            // 【fix287】ascend 上一派以无实际上升收场时不原样重派：
            // 先 goto 侧移一列换上挖列（与 fix282 descend 侧移同构），
            // goto 收场后下一 tick 再续 ascend。
            if ("ascend".equals(action) && mtsNoRiseFails >= 1 && mtsNoRiseFails <= 2) {
                if (mtsAscendSidestepPending) {
                    // 侧移 goto 已收场（技能空闲才会走到这）：落回 ascend。
                    mtsAscendSidestepPending = false;
                } else {
                    BlockPos side = findMtsSidestepCell(mtsNoRiseFails);
                    if (side != null) {
                        JsonObject sp = new JsonObject();
                        sp.addProperty("x", side.getX() + 0.5D);
                        sp.addProperty("z", side.getZ() + 0.5D);
                        if (skills.run(new Intent("", "goto", sp), "目标")) {
                            mtsAscendSidestepPending = true;
                            LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                                    + " 站位接近 ascend 无上升，先侧移换列（fix287） | to="
                                    + side.toShortString() + " fails=" + mtsNoRiseFails
                                    + " 当前Y=" + Math.round(npc.getY()));
                            return;
                        }
                    }
                }
            }
            JsonObject p = new JsonObject();
            p.addProperty("y", (double) stand.getY());
            p.addProperty("max", 160);
            if ("descend".equals(action)) {
                // 【fix284】descend 技能读的是 steps（缺省 260、clamp
                // 10–300），旧派发只带 max 自始未生效（生产/克隆日志
                // 「上限步数=260」实证）——160 的防长挂意图从未存在。
                // 补 steps 与 max 同值；ascend 不读 steps，不动它。
                p.addProperty("steps", 160);
            }
            if (skills.run(new Intent("", action, p), "目标")) {
                if ("ascend".equals(action)) {
                    // 【fix287】ascend 派发登记：水位/起始精确 Y，
                    // 结局由 noteMtsAscendOutcome 按净升结算。
                    mtsAscendDispatchSeq = skills.outcomeSeq();
                    mtsAscendStartY = npc.getY();
                    // 【fix319】tag 一并登记，闭环守卫收场行用。
                    mtsAscendTag = tag;
                }
                if ("descend".equals(action)) {
                    mtsDescendDispatchSeq = skills.outcomeSeq();
                    // 【fix284】守卫登记：这一派 descend 的水位/起始
                    // 精确 Y/时刻/目标 type/tag，自收或被消费即清。
                    mtsDescendStartTick = npc.level().getGameTime();
                    mtsDescendStartY = npc.getY();
                    mtsDescendGoalType = type;
                    mtsDescendTag = tag;
                }
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag + " 站位接近先竖直对齐 | action="
                        + action + " 目标Y=" + stand.getY() + " 当前Y=" + Math.round(npc.getY()));
            }
            return;
        }
        // 【fix282】竖直对齐已达成（|dy|≤4）：本段连败清零，下段重新计。
        mtsNoDropFails = 0;
        mtsSidestepPending = false;
        mtsDescendStartTick = -1L;
        // 【fix287】ascend 无上升连败同口径随段清零。
        mtsNoRiseFails = 0;
        mtsAscendSidestepPending = false;
        // 【fix245】站位高差死带：站位比身体高 2~4 格时，goto 的 A* 迈不上
        // +2（PathFinder 单步上限 +1）、travel 又容易在低处邻格报假到达，
        // 两者同秒往返零位移烧满目标超时（86 棒新图建屋实证：站位高 2 格、
        // 约 11 分钟小屋未建成）。旧竖直对齐只管 |dy|>4；现补死带：身体
        // 低于站位 ≥2 格且接近已连卡 ≥3 次，直接派 ascend 挖楼梯爬到站位
        // 层，再回 goto 做水平收尾。向下不收紧：A* 合法下落 ≤3 格、goto
        // 本就能处理站位低 2~4 格，不动原口径。
        if (dys <= -2.0D && stuckTicks >= 3) {
            JsonObject ap = new JsonObject();
            ap.addProperty("y", (double) stand.getY());
            ap.addProperty("max", 160);
            if (skills.run(new Intent("", "ascend", ap), "目标")) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag + " 站位接近改 ascend 爬升（fix245）"
                        + " | 目标Y=" + stand.getY() + " 当前Y=" + Math.round(npc.getY())
                        + " 连卡=" + stuckTicks);
            }
            return;
        }
        if (dxs * dxs + dzs * dzs > 36.0D || stuckTicks >= 6) {
            // 【fix285】本站位 travel 已连爆 ≥2 次（爆表结局由
            // noteMtsTravelOutcome 消费计数）：travel 的深扫在这片地形
            // 是确定性失败，原样重派只是每约 6 秒烧一轮（fresh161 睡觉
            // 接近连爆约 35 次实证）——换 goto 本地寻路（不同引擎、落点
            // 解析口径也不同），换走法不换目标；goto 同样失败时由调用
            // 方的出击结算/站位轮换接手（fix275/285a）。
            if (mtsTravelBlowups >= 2) {
                JsonObject gp = new JsonObject();
                gp.addProperty("x", stand.getX() + 0.5D);
                gp.addProperty("z", stand.getZ() + 0.5D);
                if (skills.run(new Intent("", "goto", gp), "目标")) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag
                            + " 站位接近 travel 连爆，改 goto 接近站位（fix285） | "
                            + stand.toShortString() + " blowups=" + mtsTravelBlowups);
                }
                return;
            }
            JsonObject tv = new JsonObject();
            tv.addProperty("x", stand.getX() + 0.5D);
            // 【fix245】站位更高时 travel 落点传站位高度（同高可站）：旧口径
            // 恒传当前高度，落到低 2 格邻格 3D 距 ≤tolerance 即判"到达"、
            // 与 goto 同秒往返。站位更低时维持当前高度——fix62 的 farm20
            // 防搭天梯口径（目标在下方派 travel 会一路搭到天上）不动。
            tv.addProperty("y", dys < 0 ? (double) stand.getY() : npc.getY());
            tv.addProperty("z", stand.getZ() + 0.5D);
            tv.addProperty("tolerance", 2.0D);
            if (skills.run(new Intent("", "travel", tv), "目标")) {
                // 【fix285】登记自家 travel 派发供爆表结局消费。
                mtsTravelDispatchSeq = skills.outcomeSeq();
                mtsTravelStartX = npc.getX();
                mtsTravelStartZ = npc.getZ();
                LOG.info("[AIV][GOAL] " + npc.npcName() + " " + tag + " goto 不通改 travel 接近站位 | "
                        + stand.toShortString());
            }
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("x", stand.getX() + 0.5D);
        params.addProperty("z", stand.getZ() + 0.5D);
        skills.run(new Intent("", "goto", params), "目标");
    }

    /**
     * 执行砌筑队列。返回 true = 队列处理完（成功/跳过都算）。
     * 站位固定屋内中心：所有格都在够得着口径内（见 tickBase 注释）。
     */
    private boolean processBaseQueue() {
        while (!baseQueue.isEmpty()) {
            BlockPos cell = baseQueue.get(0);
            BlockState state = npc.level().getBlockState(cell);
            if (!state.isAir() && !isVegetation(state) && state.getFluidState().isEmpty()) {
                // 已是实心（地形自带或已砌）：视为满足，不耗料。
                baseQueue.remove(0);
                basePlacedCount++;
                continue;
            }
            break;
        }
        if (baseQueue.isEmpty()) {
            return true;
        }
        BlockPos cell = baseQueue.get(0);
        if (!buildInReach(cell)) {
            // 去屋内中心站位；反复到不了位则换内圈邻格，再不行放弃这一格。
            String key = "base-stand";
            if (key.equals(baseLastActionKey)) {
                baseActionStuck++;
            } else {
                baseLastActionKey = key;
                baseActionStuck = 0;
            }
            if (baseActionStuck > 90) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BASE] 站位到不了，跳过一格 | cell="
                        + cell.toShortString());
                baseQueue.remove(0);
                baseActionStuck = 0;
                return false;
            }
            BlockPos stand = new BlockPos(baseCenter.getX(), baseCenter.getY() + 1, baseCenter.getZ());
            // 【fix64→fix65 收紧】到达判定须精确到站位列：建造够格（buildInReach）
            // 按屋内中心算，偏一列远角格就脱距（boot-farm23 实证：松口径
            // 1.44 在邻列低一格误判到达，墙体逐格够不着被跳）。改方块列
            // 一致 + 脚位差 ≤1.0（站位 Y 是室内脚位、身体低一层属正常）。
            boolean atStand = npc.blockPosition().getX() == stand.getX()
                    && npc.blockPosition().getZ() == stand.getZ()
                    && Math.abs(npc.getY() - stand.getY()) <= 1.0D;
            if (!atStand) {
                moveToStand(stand, baseActionStuck, "[BASE]");
            }
            return false;
        }
        baseLastActionKey = "";
        baseActionStuck = 0;
        String material = pickBuildMaterial();
        if (material == null) {
            finish("建屋放弃：建材不足（已砌 " + basePlacedCount + "/" + baseTotalCells + "）");
            return true;
        }
        Block block = blockOfItem(material);
        if (block == null || !consume(material, 1)) {
            baseQueue.remove(0);
            return false;
        }
        npc.level().setBlockAndUpdate(cell, block.defaultBlockState());
        baseQueue.remove(0);
        basePlacedCount++;
        return false;
    }

    // ===== 【fix56·D1】床：安置与真睡 =====
    private int bedPhase;
    private BlockPos bedFootPos;
    private net.minecraft.core.Direction bedFacing;
    private String bedItemPath;
    private int bedActionStuck;
    /** 【fix185】本目标是否已垫过站位格（防反复垫格烧料，每目标限一次）。 */
    private boolean bedPadAttempted;
    /** 【fix283】选位胜出对的垫基格清单（放床前要在这些格垫一块大宗块当
     * 床基）；scanBedSpot 命中时写入、phase 2 执行时按世界现状重算为准。 */
    private java.util.List<BlockPos> bedBasePadCells = new java.util.ArrayList<>();
    private String bedLastActionKey = "";
    /** 【fix276】安置床 phase 1 接近派发计数与站位候选表（懒建、床位变化即失效重建）。 */
    private int bedSorties;
    private java.util.List<BlockPos> bedStandCandidates;
    private int sleepPhase;
    private long sleepStartMillis;
    private long sleepDayTime0 = -1L;
    private int sleepActionStuck;
    private String sleepLastActionKey = "";
    /** 【fix285】睡觉回床接近的出击结算与站位轮换（照 fix275 农田回田
     * 成例）：旧版站位是 findBedStandCell 单格、接近失败没有任何结算，
     * 唯一出路是 sleepActionStuck 累到 90 放弃（fresh161 实证 1076.9 秒
     * 全程死守一格）。改为候选表按尝试数轮换（attempts/3）、≥15 次
     * 诚实收场；出击＝一趟接近技能从派发到收场（idle 间隙结算）。 */
    private boolean sleepApproachSortie;
    private int sleepApproachAttempts;
    private BlockPos sleepChosenStand;

    /** 包里第一张床的物品路径（任意颜色）；没有返回 null。 */
    private String firstBedItemPath() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_bed")) {
                return pathOf(stack);
            }
        }
        return null;
    }

    /** 床的另一格：账本存脚格时按 FACING 定头格，存头格时反之。非床返回 null。 */
    private BlockPos bedOtherCell(BlockPos bed) {
        BlockState state = npc.level().getBlockState(bed);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
            return null;
        }
        net.minecraft.core.Direction facing =
                state.getValue(net.minecraft.world.level.block.BedBlock.FACING);
        return state.getValue(net.minecraft.world.level.block.BedBlock.PART)
                == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                ? bed.relative(facing) : bed.relative(facing.getOpposite());
    }

    /**
     * 【fix130】床边可站立格（与 SurvivalController fix95 复活站位同规则）：
     * 床占两格，按 PART 定出另一格，两格的四邻逐个验 PathFinder.isStandable，
     * 首个可站的返回；床已不在或四邻全堵返回 null（睡觉 phase 0 回落锚点口径）。
     */
    private BlockPos findBedStandCell(BlockPos bed) {
        java.util.List<BlockPos> cells = bedStandCells(bed);
        return cells.isEmpty() ? null : cells.get(0);
    }

    /**
     * 【fix285】床边可站立格全表（与 findBedStandCell 同判据同序：床两格
     * 四邻 × dy{0,-1,1,-2,2,-3,3,-4,4}，去重保序）。旧版读者只要首格；
     * 睡觉回床接近改用全表轮换（133 棒定谳：单格被地形锁死时无路可换）。
     */
    private java.util.List<BlockPos> bedStandCells(BlockPos bed) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        BlockPos other = bedOtherCell(bed);
        if (other == null) {
            return out;
        }
        // 【fix265】与复活判读同口径竖直放宽（116 棒）：基址下层被挖空时
        // 同层 6 格全悬空，同列 dy=-1/-2 有可站格；dy 序与 SurvivalController
        // findBedRespawnStand 一致，两处读者不许再分叉。
        for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3, -4, 4}) {
            for (BlockPos cell : new BlockPos[]{bed, other}) {
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    BlockPos stand = cell.relative(dir).offset(0, dy, 0);
                    if (stand.equals(bed) || stand.equals(other)) {
                        continue;
                    }
                    if (PathFinder.isStandable(npc.level(), stand) && !out.contains(stand)) {
                        out.add(stand.immutable());
                    }
                }
            }
        }
        return out;
    }

    /** 【fix227】清障进行态：跳过集（挖不动/到不了位的格）、单格挖次、站位卡计数。 */
    private final java.util.Set<BlockPos> clearBedSkipped = new java.util.HashSet<>();
    private int clearBedTries;
    private int clearBedStuck;
    private String clearBedLastKey;

    /**
     * 【fix227】床边清障执行：床在但复活站位 6 格全堵时（66 棒实证为
     * 自家 travel 搭桥逐格铺死 + 北侧坡边），到基址用正常挖矿动作
     * 挖掉床边占格（候选格内实心块、候选格上压顶块），挖完以
     * findBedStandCell 复验为准。只认有碰撞体积的实心块（水/草丛
     * 等可通行格不算占格）；不碰床本体（床格不在候选内）与农田
     * （mineWithBestTool 自带 fix198 守卫）；基岩照 fix199 拒挖。
     * 每格 3 次挖不动即跳过、站位 90 计数到不了即跳过；占格全跳
     * 过或无可挖占格（余格脚下悬空）时诚实失败走床退避，不许
     * 每 decide 重派。成功/已有站位格时 finish 文案不含失败词，
     * 不喂退避。
     */
    private void tickClearBed() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null || base.bedPos() == null) {
            finish("清理床边完成：无床账本，无需清障");
            return;
        }
        BlockPos foot = base.bedPos();
        BlockPos stand = findBedStandCell(foot);
        if (stand != null) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 床边清障（fix227） | 已有可站立格 "
                    + stand.toShortString() + "，无需挖");
            finish("清理床边完成：已有可站立格 " + stand.toShortString());
            return;
        }
        BlockPos other = bedOtherCell(foot);
        if (other == null) {
            finish("清理床边放弃：床本体已不在（fix227）");
            return;
        }
        java.util.List<BlockPos> blockers = new java.util.ArrayList<>();
        for (BlockPos cell : new BlockPos[]{foot, other}) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos cand = cell.relative(dir);
                if (cand.equals(foot) || cand.equals(other)) {
                    continue;
                }
                if (PathFinder.isStandable(npc.level(), cand)) {
                    continue;
                }
                BlockState cellState = npc.level().getBlockState(cand);
                if (!cellState.getCollisionShape(npc.level(), cand).isEmpty()
                        && !cellState.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                        && !clearBedSkipped.contains(cand)) {
                    blockers.add(cand);
                }
                BlockPos above = cand.above();
                BlockState aboveState = npc.level().getBlockState(above);
                if (!aboveState.getCollisionShape(npc.level(), above).isEmpty()
                        && !aboveState.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                        && !clearBedSkipped.contains(above)) {
                    blockers.add(above);
                }
            }
        }
        if (blockers.isEmpty()) {
            if (clearBedSkipped.isEmpty()) {
                finish("清理床边放弃：无可挖占格（余格脚下悬空、挖无可挖）（fix227）");
            } else {
                finish("清理床边放弃：占格均挖不动/到不了位（fix227）");
            }
            return;
        }
        BlockPos target = blockers.get(0);
        String key = "clearbed-" + target.toShortString();
        if (!key.equals(clearBedLastKey)) {
            clearBedLastKey = key;
            clearBedTries = 0;
            clearBedStuck = 0;
        }
        if (!buildInReach(target)) {
            BlockPos standCell = standNear(target);
            if (standCell != null) {
                moveToStand(standCell, clearBedStuck, "[BEDCLEAR]");
            } else {
                JsonObject tv = new JsonObject();
                tv.addProperty("x", target.getX() + 0.5D);
                tv.addProperty("y", (double) npc.blockPosition().getY());
                tv.addProperty("z", target.getZ() + 0.5D);
                tv.addProperty("tolerance", 2.0D);
                skills.run(new Intent("", "travel", tv), "目标");
            }
            clearBedStuck++;
            if (clearBedStuck > 90) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 床边清障（fix227） | 站位到不了，跳过一格 | cell="
                        + target.toShortString());
                clearBedSkipped.add(target);
                clearBedStuck = 0;
            }
            return;
        }
        clearBedStuck = 0;
        npc.mineWithBestTool(target);
        clearBedTries++;
        if (clearBedTries >= 3
                && !npc.level().getBlockState(target).isAir()) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 床边清障（fix227） | 3 次挖不动，跳过一格 | cell="
                    + target.toShortString());
            clearBedSkipped.add(target);
        }
    }

    /** 【fix227】目标格旁的可站格：先同层四邻、再下一层四邻（站低一格挖高格在够格内）。 */
    private BlockPos standNear(BlockPos target) {
        for (BlockPos level : new BlockPos[]{target, target.below()}) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos cand = level.relative(dir);
                if (PathFinder.isStandable(npc.level(), cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    /**
     * 【fix115】原版床阻挡判定复刻（Leaves 1.21.11 ServerPlayer.bedBlocked
     * 字节码实证）：startSleepInBed 只查两格——床格（账本存脚位）上方、
     * 与上方再往床 FACING 反方向一格；任一格方块 isSuffocating 即判
     * OBSTRUCTED。返回堵住的那格（挖掉即可睡），两格都空返回 null。
     * farm79 首睡实证：床贴西墙安置、墙基圆石正落在第二格上，安置校验
     * 从没验过这两格，床一出生就睡不了、拖到首睡才爆。
     */
    private BlockPos bedObstructionCell(BlockPos bed) {
        BlockState state = npc.level().getBlockState(bed);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
            return null;
        }
        net.minecraft.core.Direction facing = state.getValue(
                net.minecraft.world.level.block.BedBlock.FACING);
        BlockPos above = bed.above();
        if (npc.level().getBlockState(above).isSuffocating(npc.level(), above)) {
            return above;
        }
        BlockPos behind = above.relative(facing.getOpposite());
        if (npc.level().getBlockState(behind).isSuffocating(npc.level(), behind)) {
            return behind;
        }
        return null;
    }

    /** 【fix115】拟放床位可睡性：按拟定 foot/facing 查原版判定两格。 */
    private boolean bedSleepableAt(BlockPos foot, net.minecraft.core.Direction facing) {
        BlockPos above = foot.above();
        if (npc.level().getBlockState(above).isSuffocating(npc.level(), above)) {
            return false;
        }
        BlockPos behind = above.relative(facing.getOpposite());
        return !npc.level().getBlockState(behind).isSuffocating(npc.level(), behind);
    }

    /** 床格可放：本格空/植被无流体，且脚下是实心（屋内地面）。 */
    private boolean bedCellFree(BlockPos pos) {
        BlockState state = npc.level().getBlockState(pos);
        if (!state.isAir() && !isVegetation(state)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        BlockState below = npc.level().getBlockState(pos.below());
        return !below.isAir() && below.getFluidState().isEmpty();
    }

    /** 【fix283】床格支撑分档：本格空/植被无流体（bedCellFree 前段）之下，
     * 脚下现成实心（非空∧无流体）=0；脚下是 air/水、可垫 1 块床基且垫格
     * 之下还有实心支撑（坑深恰 1）=1；可垫但垫格之下仍空（悬空垫，搭桥
     * 同型兜底）=2；本格被占/脚下是熔岩等不可垫=-1。131 棒定谳：基址
     * 地板被自家采矿掏空后，旧 bedCellFree 的脚下硬门让 scanBedSpot 全
     * 灭——玩家放床前先垫一块石头，现码宁可不睡也不垫。 */
    private int bedCellSupport(BlockPos pos) {
        BlockState state = npc.level().getBlockState(pos);
        if (!state.isAir() && !isVegetation(state)) {
            return -1;
        }
        if (!state.getFluidState().isEmpty()) {
            return -1;
        }
        BlockState below = npc.level().getBlockState(pos.below());
        if (!below.isAir() && below.getFluidState().isEmpty()) {
            return 0;
        }
        if (below.isAir() || below.is(Blocks.WATER)) {
            BlockState below2 = npc.level().getBlockState(pos.below().below());
            return !below2.isAir() && below2.getFluidState().isEmpty() ? 1 : 2;
        }
        return -1;
    }

    /** 【fix283】床基材料：只许石/土家族大宗块（圆石/泥土/粗泥/杂石），
     * 选料序照 pickBuildMaterial 的「最不心疼」口径（杂石在前、圆石压
     * 尾）；木板与贵重块不入此单（垫床基是本节专用，不借砌筑材料面）。 */
    private static final String[] BED_BASE_MATERIALS = {
            "andesite", "diorite", "granite", "tuff", "cobbled_deepslate",
            "dirt", "coarse_dirt", "stone", "cobblestone"};

    private int bedBaseMaterialTotal() {
        int total = 0;
        for (String material : BED_BASE_MATERIALS) {
            total += countItem(material);
        }
        return total;
    }

    private String pickBedBaseMaterial() {
        for (String material : BED_BASE_MATERIALS) {
            if (countItem(material) > 0) {
                return material;
            }
        }
        return null;
    }

    /** 在床边登记重生点（RespawnConfig 直接组装：维度+床脚格）。 */
    private boolean registerRespawnAtBed(BlockPos bedPos) {
        try {
            npc.setRespawnPosition(new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                    net.minecraft.world.level.storage.LevelData.RespawnData.of(
                            npc.level().dimension(), bedPos, 0.0F, 0.0F), false), false);
            return true;
        } catch (Throwable throwable) {
            LOG.warning("[AIV][GOAL] " + npc.npcName() + " [BED] 重生点登记失败: " + throwable);
            return false;
        }
    }

    /** 【fix82/fix84】床位扫描：内室 = anchor ±1 的 3×3，脚位试两层
     * （anchor.y 与 anchor.y-1——farm43 到场诊断实证屋内地面层在 anchor
     * 下一层、站位 = anchor.y-1，原口径只试同层西列两对、层差一层即全灭），
     * 东西/南北任意相邻两格皆空且脚下实心即命中（foot 取西/北格、facing
     * 指向 head 格，与原版床 FACING 约定及 phase 2 放床一致）。命中写状态
     * 返回 true；扫描面与层位双扩面后不再依赖单一几何假设。
     * 【fix180】搜索面 ±1→±3（按切比雪夫距离近者优先）：farm145 实证
     * 落脚点地面被挖穿/炸穿——±1 双层 18 格仅两格有支撑且互不相邻，
     * 合法对不存在、place_bed 诚实失败（fix179 抢占已把派单送到，败在
     * 选位）。玩家床放不下会往旁边挪几格找平地：床只需在锚点近傍
     * （账本记实际脚格、睡眠/重生都按脚格走），半径放到 3 格；±3 仍
     * 全灭维持诚实失败（垫格修复留后手）。
     * 【fix185】命中选择改「NPC 最近优先」：farm150 实证旧版按扫描序
     * 首命中选在锚点另一侧，phase 1 够不着站位——候选全集里按到 NPC
     * 的距离选最优（同距取 ring 小、dy 0 优先），选位天然落在人够得
     * 着的一侧。
     * 【fix283】选位增「可垫地板」档（131 棒）：候选对两格按 bedCellSupport
     * 分档，现成对（padClass=0）永远优先于需垫对（1=坑深一格、2=悬空
     * 垫），同档沿用原选优序；需垫数超过包内大宗块存量时该候选不入选
     * （全灭维持诚实失败）。胜出对的垫格清单写入 bedBasePadCells，
     * phase 2 放床前按世界现状重算并执行垫基。 */
    private BlockPos scanBestFoot;
    private net.minecraft.core.Direction scanBestFacing;
    private int scanBestRing;
    private int scanBestDy;
    private double scanBestDist;
    private int scanBestPadClass;
    private java.util.List<BlockPos> scanBestPadCells;

    private void considerBedPair(BlockPos cell, net.minecraft.core.Direction facing,
            int ring, int dy) {
        BlockPos head = cell.relative(facing);
        int s1 = bedCellSupport(cell);
        int s2 = bedCellSupport(head);
        if (s1 < 0 || s2 < 0 || !bedSleepableAt(cell, facing)) {
            return;
        }
        java.util.List<BlockPos> padCells = new java.util.ArrayList<>();
        if (s1 > 0) {
            padCells.add(cell.below());
        }
        if (s2 > 0) {
            padCells.add(head.below());
        }
        if (!padCells.isEmpty() && bedBaseMaterialTotal() < padCells.size()) {
            return;
        }
        int padClass = padCells.isEmpty() ? 0 : Math.max(s1, s2);
        double ddx = npc.getX() - (cell.getX() + 0.5D);
        double ddz = npc.getZ() - (cell.getZ() + 0.5D);
        double d = ddx * ddx + ddz * ddz;
        boolean better = scanBestFoot == null
                || padClass < scanBestPadClass
                || (padClass == scanBestPadClass
                    && (d < scanBestDist - 1.0E-9D
                        || (Math.abs(d - scanBestDist) <= 1.0E-9D
                            && (ring < scanBestRing
                                || (ring == scanBestRing && dy > scanBestDy)))));
        if (better) {
            scanBestDist = d;
            scanBestFoot = cell;
            scanBestFacing = facing;
            scanBestRing = ring;
            scanBestDy = dy;
            scanBestPadClass = padClass;
            scanBestPadCells = padCells;
        }
    }

    private boolean scanBedSpot(BlockPos anchor) {
        scanBestFoot = null;
        scanBestFacing = null;
        scanBestRing = 0;
        scanBestDy = 0;
        scanBestDist = Double.MAX_VALUE;
        scanBestPadClass = Integer.MAX_VALUE;
        scanBestPadCells = new java.util.ArrayList<>();
        for (int dy = 0; dy >= -1; dy--) {
            for (int ring = 0; ring <= 3; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        BlockPos cell = anchor.offset(dx, dy, dz);
                        if (dx < 3) {
                            considerBedPair(cell, net.minecraft.core.Direction.EAST, ring, dy);
                        }
                        if (dz < 3) {
                            considerBedPair(cell, net.minecraft.core.Direction.SOUTH, ring, dy);
                        }
                    }
                }
            }
        }
        if (scanBestFoot == null) {
            return false;
        }
        bedFootPos = scanBestFoot;
        bedFacing = scanBestFacing;
        bedBasePadCells = scanBestPadCells;
        if (scanBestRing > 1) {
            LOG.info("[AIV][GOAL] " + npc.npcName()
                    + " [BED] 床位扩面命中（fix180） | foot="
                    + scanBestFoot.toShortString() + " ring=" + scanBestRing);
        }
        if (scanBestPadClass > 0) {
            LOG.info("[AIV][GOAL] " + npc.npcName()
                    + " [BED] 床位命中需垫基（fix283） | foot="
                    + scanBestFoot.toShortString() + " 垫格数=" + scanBestPadCells.size()
                    + " padClass=" + scanBestPadClass + "（面内无现成平地，先垫床基再放床）");
        }
        return true;
    }

    /** 【fix185】站位格可站：脚格空/植被无流体且脚下实心（bedCellFree
     * 口径）+ 头格空/植被无流体。 */
    private boolean bedStandable(BlockPos c) {
        if (!bedCellFree(c)) {
            return false;
        }
        BlockState head = npc.level().getBlockState(c.above());
        return (head.isAir() || isVegetation(head)) && head.getFluidState().isEmpty();
    }

    /** 【fix185】可垫格：脚/头两格空且无流体、脚下是空气——垫一格即成站位。 */
    private boolean bedCellPaddable(BlockPos c) {
        BlockState feet = npc.level().getBlockState(c);
        if ((!feet.isAir() && !isVegetation(feet)) || !feet.getFluidState().isEmpty()) {
            return false;
        }
        BlockState head = npc.level().getBlockState(c.above());
        if ((!head.isAir() && !isVegetation(head)) || !head.getFluidState().isEmpty()) {
            return false;
        }
        return npc.level().getBlockState(c.below()).isAir();
    }

    /** 【fix276】安置床站位候选表：床位已知时取床脚够格几何（相对床脚
     * dx²+dz²≤10、dy∈[-1,3]，与 bedStandCandidate 同面）内可站格按距
     * NPC 近远取前 6，再补锚点及其四邻的可站格（去重保序）；床位未知
     * 只取锚点面。全不可站返回空表，调用方回落 fix185/锚点旧路径。 */
    private java.util.List<BlockPos> buildBedStandCandidates(BlockPos anchor) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (bedFootPos != null && bedFacing != null) {
            BlockPos headCell = bedFootPos.relative(bedFacing);
            java.util.List<BlockPos> cells = new java.util.ArrayList<>();
            for (int dy = -1; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        if (dx * dx + dz * dz > 10) {
                            continue;
                        }
                        BlockPos c = bedFootPos.offset(dx, dy, dz);
                        if (c.equals(bedFootPos) || c.equals(headCell)) {
                            continue;
                        }
                        if (bedStandable(c)) {
                            cells.add(c);
                        }
                    }
                }
            }
            cells.sort((a, b) -> Double.compare(
                    npc.blockPosition().distSqr(a), npc.blockPosition().distSqr(b)));
            for (BlockPos c : cells) {
                if (out.size() >= 6) {
                    break;
                }
                out.add(c);
            }
        }
        BlockPos[] around = {anchor, anchor.north(), anchor.south(), anchor.east(), anchor.west()};
        for (BlockPos c : around) {
            if (bedStandable(c) && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 【fix185】床脚够格站位：farm150 实证 phase 1 旧口径钉死锚点列——
     * 锚点被挖穿时该列不可站立、goto/travel 双灭，47.5 秒空转放弃。
     * 玩家做法是站在床边任何够得着的平地上放床。候选面=farmInReach
     * 反解几何（相对床脚 dx²+dz²≤10、dy∈[-1,3]，站上去必够得着床脚）
     * 内可站立的最近格（排除床脚/床头两格本身）；无可站格且已卡
     * 一阵（stuck&gt;30）且本目标未垫过时，找同几何内的可垫格垫一格
     * 造站位（每目标限一次，防烧料循环）。仍无则返回 null，调用方
     * 落回锚点列原逻辑、90 计数诚实放弃不变。 */
    private BlockPos bedStandCandidate(BlockPos foot) {
        BlockPos headCell = foot.relative(bedFacing);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos padCand = null;
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dz * dz > 10) {
                        continue;
                    }
                    BlockPos c = foot.offset(dx, dy, dz);
                    if (c.equals(foot) || c.equals(headCell)) {
                        continue;
                    }
                    if (bedStandable(c)) {
                        double ddx = npc.getX() - (c.getX() + 0.5D);
                        double ddz = npc.getZ() - (c.getZ() + 0.5D);
                        double d = ddx * ddx + ddz * ddz;
                        if (d < bestDist) {
                            bestDist = d;
                            best = c;
                        }
                    } else if (padCand == null && bedActionStuck > 30 && !bedPadAttempted
                            && bedCellPaddable(c)) {
                        padCand = c;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        if (padCand != null) {
            String material = pickBuildMaterial();
            Block block = material == null ? null : blockOfItem(material);
            if (block != null && consume(material, 1)) {
                npc.level().setBlockAndUpdate(padCand.below(), block.defaultBlockState());
                bedPadAttempted = true;
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 站位垫格（fix185） | cell="
                        + padCand.below().toShortString() + " material=" + material
                        + "（够格几何内无可站格，垫一格造站位）");
                return padCand;
            }
        }
        return null;
    }

    /** 【fix82/fix84】选位失败诊断：双层全内室逐格打 本格/脚下 物品 id，
     * 层位/占用/未加载三类原因靠此一次分辨（日志四问：出事留快照）。
     * 【fix180】诊断面随扫描面扩到 ±3（失败路径才打，行长可控）。 */
    private void logBedScanDiagnostics(BlockPos anchor) {
        StringBuilder sb = new StringBuilder();
        for (int dy = 0; dy >= -1; dy--) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    BlockPos cell = anchor.offset(dx, dy, dz);
                    BlockState state = npc.level().getBlockState(cell);
                    BlockState below = npc.level().getBlockState(cell.below());
                    sb.append(' ').append(cell.toShortString())
                            .append('=').append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())
                            .append('/').append(BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath());
                }
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 选位诊断（格=本格/脚下）:" + sb);
    }

    private void tickPlaceBed() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null) {
            finish("安置床放弃：无落脚点账本");
            return;
        }
        // 【fix265】验真＋现成床重登记收进 BaseMemory.revalidateBed（取代
        // 原 fix99 单次验真清账＋fix174 本地扫描两段）：账本床格在→收场；
        // 账本无床/床格已空但锚点近傍有现成床→重登记后收场（116 棒：
        // 单次坏读数清账后无人重建的断链在此堵死）；确无床才往下走选位
        // 安置（revalidate 已按需失效账本）。收场文案沿用旧口径防退避误判。
        if (BaseMemory.revalidateBed(npc.level(), npc.npcName()) != null) {
            finish("床已安置（账本已有记录）");
            return;
        }
        switch (bedPhase) {
            case 0 -> {
                status = "安置床：选位";
                bedItemPath = firstBedItemPath();
                if (bedItemPath == null) {
                    finish("安置床放弃：包里没床");
                    return;
                }
                BlockPos anchor = base.anchor();
                // 【fix82】远端（>24 格、锚点区块未加载）不预扫：未加载读数会让 bedCellFree
                // 的「脚下实心」判定恒假、四组候选全灭误报无空位（farm41 三度秒败实证）。
                // 改先就位（phase 1 的 moveToStand），到站后在 phase 1 就地扫描。
                double bdx = npc.getX() - (anchor.getX() + 0.5D);
                double bdz = npc.getZ() - (anchor.getZ() + 0.5D);
                double bedDistSqr = bdx * bdx + bdz * bdz;
                if (bedDistSqr > 24.0D * 24.0D) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 远端派单：先就位后选位 | dist="
                            + (int) Math.sqrt(bedDistSqr));
                    bedPhase = 1;
                    return;
                }
                if (!scanBedSpot(anchor)) {
                    logBedScanDiagnostics(anchor);
                    finish("安置床放弃：屋内没有空位（两格连排都被占）");
                    return;
                }
                bedStandCandidates = null;
                bedPhase = 1;
            }
            case 1 -> {
                status = "安置床：就位";
                BlockPos stand = base.anchor();
                // 【fix65】同 processBaseQueue：站位须精确到列 + 脚位差 ≤1.0。
                boolean bedAtStand = npc.blockPosition().getX() == stand.getX()
                        && npc.blockPosition().getZ() == stand.getZ()
                        && Math.abs(npc.getY() - stand.getY()) <= 1.0D;
                // 【fix169】远端重扫触发放宽为近傍口径（距锚点水平 ≤2∧|dy|≤2）：
                // scanBedSpot 读的是锚点周边世界、与人精确站哪格无关，区块
                // 已加载即可；旧版要求精确站锚点列，自低处接近时同存箱病
                // 磨到 90 计数放弃。主路径 farmInReach 近傍口径本就不受影响。
                int bedCheb = Math.max(
                        Math.abs(npc.blockPosition().getX() - stand.getX()),
                        Math.abs(npc.blockPosition().getZ() - stand.getZ()));
                int bedDy = Math.abs(npc.blockPosition().getY() - stand.getY());
                boolean bedNearAnchor = bedCheb <= 2 && bedDy <= 2;
                // 【fix277】选位门再放宽到加载口径（129 棒生产定谳）：基址
                // 柱台地形里 NPC 水平已贴锚点（切比雪夫=1）但高出 4 格，
                // |dy|≤2 的紧门永不开 → bedFootPos 全程为 null → fix185/276
                // 的床边站位机器（全以 bedFootPos 为钥）永不接合、候选表
                // 只剩锚点五格全灭（候选=0），且 fix276 的 15 次预算被选位
                // 前的归航派发烧光、在距锚点 1 格处收场。扫描只读锚点周边
                // 世界，与 fix169 同理：切比雪夫 ≤8∧|dy|≤8 内锚点区块必已
                // 加载（人已步行到场），fix82 的未加载误报前提不成立。
                boolean bedInScanRange = bedCheb <= 8 && bedDy <= 8;
                if (bedFootPos == null && (bedAtStand || bedNearAnchor || bedInScanRange)) {
                    // 【fix82】远端派单到站后就地选位（此时区块已加载、读数真实）。
                    if (!bedAtStand && !bedNearAnchor) {
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " [BED] 近傍就地选位（fix277） | 切比雪夫=" + bedCheb
                                + " dy=" + bedDy + "（选位门加载口径）");
                    }
                    if (!scanBedSpot(stand)) {
                        logBedScanDiagnostics(stand);
                        finish("安置床放弃：到场后确认屋内没有空位（见选位诊断）");
                        return;
                    }
                    bedStandCandidates = null;
                    // 【fix277】计数分界：bedSorties 只计选位已知后的站位
                    // 派发——选位成功即清零，fix276 的 15 次预算回到其设计
                    // 面（选位后振荡）；归航段由 bedActionStuck>90 旧收口与
                    // goal-timeout 兜底（与 fix276 前同口径）。
                    bedSorties = 0;
                }
                if (bedFootPos != null && farmInReach(bedFootPos)) {
                    bedPhase = 2;
                    return;
                }
                // 去屋内中心站位（照 base 站位口径：goto + 同 key 反复计数）。
                String key = "bed-stand";
                if (key.equals(bedLastActionKey)) {
                    bedActionStuck++;
                } else {
                    bedLastActionKey = key;
                    bedActionStuck = 0;
                }
                if (bedActionStuck > 90) {
                    finish("安置床放弃：到不了屋内站位");
                    return;
                }
                if (!bedAtStand) {
                    // 【fix276】站位候选轮换＋派发有界化（128 棒定谳：单候选
                    // 钉死时尖柱顶 descend 一次派发可悬停数分钟、goal-timeout
                    // 又被间歇位移清零，fix179 的修复机会被一次无界接近烧
                    // 光）：候选表懒建（床脚够格面优先＋锚点四邻兜底），每
                    // 5 次派发轮换下一候选，15 次未达诚实收场走既有床退避；
                    // 候选全不可站时回落 fix185/锚点旧路径（含垫格兜底）。
                    // 【fix277】bedSorties 的计数面以选位成功为界（见上方选位
                    // 门）：归航段派发不入此数，选位后清零重计。
                    if (bedStandCandidates == null) {
                        bedStandCandidates = buildBedStandCandidates(stand);
                    }
                    if (bedSorties >= 15) {
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " [BED] 站位接近 15 次未达收场（fix276） | 候选="
                                + bedStandCandidates.size() + " pos="
                                + npc.blockPosition().toShortString());
                        finish("安置床放弃：站位接近 15 次未达（fix276）");
                        return;
                    }
                    // 【fix278】远途先行 travel（129 棒生产二连定谳）：选位
                    // 未知且切比雪夫>8 的远途段，moveToStand 的竖直优先恒
                    // 先派 descend——柱台顶 descend 无路空转（生产实证
                    // Y 79→81→80→79 振荡、701.5 秒净位移切比雪夫 25→20）、
                    // 其 travel 分支永达不到，fix276 的 15 次预算被归航派
                    // 发烧光、两度以候选=0 在距锚点 20+ 格处收场。远途段
                    // 改直接派 travel 横向推进，进 8 格圈后 fix277 选位门
                    // 与既有站位机器接管；travel 派发被技能层拒时回落原路。
                    // 【fix279】travel 的 y 传站位高度（目标高度）：首版传
                    // 当前高度，生产实证柱台顶（dy=15）travel 每 6 秒瞬时
                    // 失败零位移（高空平推无首步可走）；规划层长途 travel
                    // 恒传目标高度、自栈顶 115 一路下行抵达基址（fresh151
                    // 实证）——远途段与之对齐。moveToStand 的当前高度口径
                    // 只用于其竖直对齐后的短途接驳，不适用于远途段。
                    // 【fix280】远途 travel 只走到锚点外 12 格的途经点（沿
                    // 接近线逐段棘轮）：直指屋内锚点列的 travel 在生产基址
                    // 几何下 A* 100ms 预算必爆（fresh154 实证：(-56,73,-106)
                    // 起每 6 秒以「超出时间预算 100ms」瞬时失败、零位移；
                    // 而自 158 格外以目标高度出发的第一段 99 秒推进至 58
                    // 格——终点开阔与否决定搜索成败）。切比雪夫 ≤17 后交
                    // 回 moveToStand 既有编排（fresh151 自 ~20 格走通实证）。
                    // 【fix281】远途 travel 只在不高时派（dy≤4）：travel 是
                    // 纯水平技能，高处派它每轮以「水平到位，需要竖直对齐」
                    // 瞬时结束（fresh155 实证：cheb=20/dy=13 处 ~1 秒一轮、
                    // 91 次派发把 bedActionStuck 在 114 秒内烧满收场）；
                    // dy>4 时回落 moveToStand 的 descend 对齐（竖直优先在
                    // 「已近而高」时是对的工具），dy 落回 ≤4 后 travel 棘
                    // 轮续推——两技能按 dy 交替，正是 moveToStand 的编排
                    // 形状、只换水平引擎。途经点已在脚下（≤3 格）时同样
                    // 回落，避免对自家位置空派 travel 自旋。
                    if (bedFootPos == null && bedCheb > 17 && bedDy <= 4) {
                        double wpScale = 12.0D / bedCheb;
                        int wpX = stand.getX() + (int) Math.round(
                                (npc.blockPosition().getX() - stand.getX()) * wpScale);
                        int wpZ = stand.getZ() + (int) Math.round(
                                (npc.blockPosition().getZ() - stand.getZ()) * wpScale);
                        double wpDx = npc.getX() - (wpX + 0.5D);
                        double wpDz = npc.getZ() - (wpZ + 0.5D);
                        if (wpDx * wpDx + wpDz * wpDz > 9.0D) {
                            JsonObject tv = new JsonObject();
                            tv.addProperty("x", wpX + 0.5D);
                            tv.addProperty("y", (double) stand.getY());
                            tv.addProperty("z", wpZ + 0.5D);
                            tv.addProperty("tolerance", 2.0D);
                            if (skills.run(new Intent("", "travel", tv), "目标")) {
                                LOG.info("[AIV][GOAL] " + npc.npcName()
                                        + " [BED] 远途先行 travel（fix280） | 切比雪夫=" + bedCheb
                                        + " dy=" + bedDy + " 途经点=" + wpX + "," + wpZ);
                                return;
                            }
                        }
                    }
                    BlockPos standTarget;
                    if (bedStandCandidates.isEmpty()) {
                        // 【fix185】床位已知时站位改「床脚够格站位」：够得着
                        // 床脚的最近可站格（见 bedStandCandidate），不再钉
                        // 锚点列——锚点被挖穿时该列不可达、旧口径 47.5 秒
                        // 空转放弃（farm150 实证）。床位未知维持锚点列原逻辑。
                        standTarget = stand;
                        if (bedFootPos != null) {
                            BlockPos cand = bedStandCandidate(bedFootPos);
                            if (cand != null) {
                                standTarget = cand;
                            }
                        }
                    } else {
                        standTarget = bedStandCandidates.get(
                                (bedSorties / 5) % bedStandCandidates.size());
                    }
                    // 【fix278】计数面补完 fix277 分界：bedSorties 只计选
                    // 位已知（bedFootPos!=null）后的站位派发；选位未知的
                    // 锚点兜底腿派发不入此数（其收口归 bedActionStuck>90
                    // 与 goal-timeout）——生产实证选位前 15 次被 descend
                    // 失败回执烧光、床边机器未接合即收场。
                    if (bedFootPos != null) {
                        bedSorties++;
                    }
                    moveToStand(standTarget, bedActionStuck, "[BED]");
                }
            }
            case 2 -> {
                status = "安置床：放床";
                Block block = blockOfItem(bedItemPath);
                if (block == null) {
                    finish("安置床放弃：床物品无对应方块 " + bedItemPath);
                    return;
                }
                // 【fix283】复检走「现成或可垫」双口径并执行垫基：选位后
                // 环境可能变化，垫格按世界现状重算——脚下已实心则免垫、
                // 仍空/水则垫一块大宗块当床基（与 fix185 站位垫格、
                // farm place 同原语），垫后按 bedCellFree 原口径复检才放
                // 床；材料中途不足或复检不过诚实收场，已垫块不回滚（留
                // 作地板，与玩家行为一致）。
                BlockPos bedHeadPos = bedFootPos.relative(bedFacing);
                int footSupport = bedCellSupport(bedFootPos);
                int headSupport = bedCellSupport(bedHeadPos);
                if (footSupport < 0 || headSupport < 0
                        || !bedSleepableAt(bedFootPos, bedFacing)) {
                    finish("安置床放弃：选位被占或不可睡（选位后环境变化）");
                    return;
                }
                java.util.List<BlockPos> padNow = new java.util.ArrayList<>();
                if (footSupport > 0) {
                    padNow.add(bedFootPos.below());
                }
                if (headSupport > 0) {
                    padNow.add(bedHeadPos.below());
                }
                for (BlockPos padCell : padNow) {
                    String material = pickBedBaseMaterial();
                    Block padBlock = material == null ? null : blockOfItem(material);
                    if (padBlock == null || !consume(material, 1)) {
                        finish("安置床放弃：垫基材料不足（选位后存量变化）");
                        return;
                    }
                    npc.level().setBlockAndUpdate(padCell, padBlock.defaultBlockState());
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 垫床基（fix283） | cell="
                            + padCell.toShortString() + " material=" + material
                            + "（床格脚下无支撑，先垫一格当床基）");
                }
                if (!padNow.isEmpty()
                        && (!bedCellFree(bedFootPos) || !bedCellFree(bedHeadPos))) {
                    finish("安置床放弃：垫基后复检不过");
                    return;
                }
                if (!consume(bedItemPath, 1)) {
                    finish("安置床放弃：扣床失败");
                    return;
                }
                npc.level().setBlockAndUpdate(bedFootPos, block.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, bedFacing));
                npc.level().setBlockAndUpdate(bedFootPos.relative(bedFacing), block.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, bedFacing));
                BaseMemory.saveBed(npc.npcName(), bedFootPos);
                boolean respawnSet = registerRespawnAtBed(bedFootPos);
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 安置床 | foot="
                        + bedFootPos.toShortString() + " facing=" + bedFacing
                        + " item=" + bedItemPath + " 重生点登记=" + respawnSet);
                finish("床已安置：" + bedItemPath + " @ " + bedFootPos.toShortString()
                        + (respawnSet ? "，重生点已登记" : "，重生点登记失败（见日志）"));
            }
            default -> finish("安置床阶段异常 " + bedPhase);
        }
    }

    /** 门洞格里若是建材块，就是门塞：拆下回收（无状态幂等，防残留）。 */
    private void clearDoorPlugs(BlockPos anchor) {
        for (int level = 0; level <= 1; level++) {
            BlockPos cell = anchor.offset(0, level, BASE_HALF);
            BlockState state = npc.level().getBlockState(cell);
            if (state.isAir() || isVegetation(state)) {
                continue;
            }
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            boolean isPlug = path.equals("cobblestone") || path.equals("cobbled_deepslate")
                    || path.equals("dirt") || path.equals("andesite") || path.equals("diorite")
                    || path.equals("granite") || path.equals("tuff") || path.equals("stone")
                    || path.endsWith("_planks");
            if (!isPlug) {
                continue;
            }
            npc.level().setBlockAndUpdate(cell,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            give(path, 1);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 门塞拆除 | cell="
                    + cell.toShortString() + " item=" + path);
        }
    }

    /** 睡前堵门洞两格（门暂无门，玩家原味：塞块、晨起拆回）。 */
    private void plugDoor(BlockPos anchor) {
        for (int level = 0; level <= 1; level++) {
            BlockPos cell = anchor.offset(0, level, BASE_HALF);
            BlockState state = npc.level().getBlockState(cell);
            if (!state.isAir() && !isVegetation(state)) {
                continue;
            }
            String material = pickBuildMaterial();
            if (material == null) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 门塞跳过：无建材");
                return;
            }
            Block block = blockOfItem(material);
            if (block == null || !consume(material, 1)) {
                return;
            }
            npc.level().setBlockAndUpdate(cell, block.defaultBlockState());
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 门塞 | cell="
                    + cell.toShortString() + " material=" + material);
        }
    }

    private void tickSleep() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null) {
            finish("睡觉放弃：无床账本");
            return;
        }
        BlockPos anchor = base.anchor();
        // 【fix99】先验床还在：farm59 实证床头被自救挖掘挖掉后，本目标仍对
        // 已成空气的 bedPos 调 startSleepInBed，ServerPlayer 读 BedBlock.PART
        // 抛 IAE（Leaves 抑制为 UpdateSuppressionException），safeTick 每 tick
        // 吞下刷屏、目标卡死等看门狗处决。床没了→失效账本 + 优雅收场，
        // 不许再碰 startSleepInBed。
        // 【fix265】验真走 BaseMemory.revalidateBed 统一入口：账本床格单次
        // 读空不再直接清账（116 棒 farm276 实证误杀）——先扫锚点近傍现成
        // 床重登记，确无床才失效；账本已清但床还在时睡觉当场自愈，床变
        // 量以重验返回值为准（可能与账本旧值不同格）。
        BlockPos bed = BaseMemory.revalidateBed(npc.level(), npc.npcName());
        if (bed == null) {
            finish("睡觉放弃：床已不在（账本已失效，待重新安置）");
            return;
        }
        switch (sleepPhase) {
            case 0 -> {
                status = "睡觉：回床边";
                clearDoorPlugs(anchor);
                // 【fix130】到达判定改床边口径：旧版只认 distSqr(anchor)≤1，
                // 而 moveToStand 的 goto 只带 x/z、Y 由寻路解析——NPC 站到锚点
                // 正下方 3 格（farm94：站 (733,62,-129)、anchor Y=65）时水平
                // 贴死却永不达标，每秒原地 goto 磨 46 秒判败（连败 6 次）。
                // 玩家判到达看离床近不近：按 fix95 同规则找床边可站立格作
                // 站位目标，到达=「水平距床 ≤2 格且 |dy|≤2」与旧锚点判定取或；
                // 找不到床边格时回落旧锚点口径，兜底不缺位。
                boolean atAnchor = npc.blockPosition().distSqr(anchor) <= 1.0D;
                // 【fix131】按床两格邻域判：fix130 首试量到床脚中心——床占
                // 两格、站位在床头外侧时中心距恰 2.0 格、dist²=4.026 卡在
                // ≤4.0 门槛外 0.026（farm95 实证：人站在床边格上仍判不到）。
                // 改为方块位与床脚/床头任一格水平偏差 ≤1 且 |dy|≤2 即到达。
                boolean atBedside = false;
                {
                    BlockPos other = bedOtherCell(bed);
                    BlockPos me = npc.blockPosition();
                    for (BlockPos cell : new BlockPos[]{bed, other}) {
                        if (cell != null
                                && Math.abs(me.getX() - cell.getX()) <= 1
                                && Math.abs(me.getZ() - cell.getZ()) <= 1
                                && Math.abs(me.getY() - cell.getY()) <= 2) {
                            atBedside = true;
                            break;
                        }
                    }
                }
                if (atAnchor || atBedside) {
                    plugDoor(anchor);
                    sleepPhase = 1;
                    sleepApproachSortie = false;
                    sleepApproachAttempts = 0;
                    sleepChosenStand = null;
                    return;
                }
                // 【fix285】出击结算（照 fix275 农田回田成例）：本相只在
                // idle 间隙执行——上一趟接近技能既已收场而人还没到床边，
                // 记一次尝试。此前没有任何结算：站位单格死守、travel 爆
                // 表/ascend 自收都无人记账，唯一出路是下方 stuck 累到 90
                // （fresh161 实证 1076.9 秒、同一站位耗尽整夜）。
                if (sleepApproachSortie) {
                    sleepApproachSortie = false;
                    sleepApproachAttempts++;
                }
                // 【fix285】站位候选只取到达判定认账的层带（|standY−bedY|
                // ≤2，与下方 atBedside 的 |dy|≤2 同口径）：轮换进更低的
                // 悬空站位会出现「人站到了、到达却判不到」的死局（旧单
                // 格口径选到低站位时同有此陷）。候选按与身体水平距离
                // 排序、attempts/3 轮换；全表为空（层带内无可站格）时
                // 回落旧口径首格/锚点，兜底不缺位。
                java.util.List<BlockPos> stands = new java.util.ArrayList<>();
                for (BlockPos cand : bedStandCells(bed)) {
                    if (Math.abs(cand.getY() - bed.getY()) <= 2) {
                        stands.add(cand);
                    }
                }
                BlockPos meNow = npc.blockPosition();
                stands.sort(java.util.Comparator.comparingDouble(c -> {
                    double dx = c.getX() - meNow.getX();
                    double dz = c.getZ() - meNow.getZ();
                    return dx * dx + dz * dz;
                }));
                if (!stands.isEmpty() && sleepApproachAttempts >= 15) {
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " [BED] 回床接近 15 次未达，诚实收场（fix285） | 候选="
                            + stands.size() + " pos=" + meNow.toShortString());
                    finish("睡觉放弃：床边接近 15 次未达（fix285）");
                    return;
                }
                BlockPos standTarget;
                if (stands.isEmpty()) {
                    BlockPos bedStand = findBedStandCell(bed);
                    standTarget = bedStand != null ? bedStand : anchor;
                } else {
                    int idx = Math.min(sleepApproachAttempts / 3, stands.size() - 1);
                    standTarget = stands.get(idx);
                }
                if (sleepChosenStand == null || !sleepChosenStand.equals(standTarget)) {
                    if (sleepChosenStand != null) {
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " [BED] 回床站位轮换（fix285） | stand="
                                + standTarget.toShortString() + " 尝试=" + sleepApproachAttempts
                                + " 候选=" + stands.size());
                    }
                    sleepChosenStand = standTarget.immutable();
                }
                String key = "sleep-stand";
                if (key.equals(sleepLastActionKey)) {
                    sleepActionStuck++;
                } else {
                    sleepLastActionKey = key;
                    sleepActionStuck = 0;
                }
                if (sleepActionStuck > 90) {
                    finish("睡觉放弃：到不了床边");
                    return;
                }
                moveToStand(standTarget, sleepActionStuck, "[BED]");
                sleepApproachSortie = true;
            }
            case 1 -> {
                status = "睡觉：上床";
                // 【fix115】睡前清障：床被上方方块堵住时先挖掉再睡（玩家
                // 做法）。判定格照 bedObstructionCell 的原版复刻口径（只
                // 两格），挖掉后不收场、下 tick 复查清净了才真正上床——
                // 旧行为是秒败 finish + 规划层约 1 秒一轮重派热循环
                // （farm79 首睡实证：墙基圆石压住判定格）。
                // 【fix132】清障改定点拆格：fix115 旧调用走 mine 技能只带
                // x/y/z，而 SkillRunner.runMine 只认 block 名参——出生即
                // 「mine 参数非法」恒失败、清障从未执行过（farm97 实证：
                // 门塞后紧跟参数非法行，随即撞 OBSTRUCTED 秒败）。玩家是
                // 哪格挡着敲哪格：照 clearDoorPlugs 同口径定点拆掉该格
                // （setBlockAndUpdate AIR + 方块回包），不按名满屋找同类块。
                BlockPos plugCell = bedObstructionCell(bed);
                if (plugCell != null) {
                    String plugPath = BuiltInRegistries.BLOCK.getKey(
                            npc.level().getBlockState(plugCell).getBlock()).getPath();
                    npc.level().setBlockAndUpdate(plugCell,
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    give(plugPath, 1);
                    status = "睡觉：清床上方块";
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 床被上方方块堵住，定点拆掉 | cell="
                            + plugCell.toShortString() + " block=" + plugPath);
                    return;
                }
                var result = npc.startSleepInBed(bed, false);
                if (result.left().isPresent()) {
                    clearDoorPlugs(anchor);
                    finish("睡不成：" + result.left().get() + "（原版判定）");
                    return;
                }
                sleepStartMillis = System.currentTimeMillis();
                sleepDayTime0 = npc.level().getDayTime();
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 入睡 | bed="
                        + bed.toShortString() + " dayTime=" + sleepDayTime0);
                sleepPhase = 2;
            }
            case 2 -> {
                status = "睡觉中";
                if (!npc.isSleeping()) {
                    clearDoorPlugs(anchor);
                    long dayTime = npc.level().getDayTime();
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [BED] 睡醒 | dayTime "
                            + sleepDayTime0 + " → " + dayTime);
                    finish("睡醒了（dayTime " + sleepDayTime0 + " → " + dayTime + "）");
                    return;
                }
                if (System.currentTimeMillis() - sleepStartMillis > 20_000L) {
                    npc.stopSleepInBed(false, true);
                    clearDoorPlugs(anchor);
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " [BED] 睡眠超时 20 秒未跳夜，强制起床（跳夜未生效，如实记账）");
                    finish("睡觉超时：跳夜未生效，强制起床");
                }
            }
            default -> finish("睡觉阶段异常 " + sleepPhase);
        }
    }

    /**
     * 选址：半径 26 内找 5×5 地块——25 列顶高差 ≤1（只许低 1 待补，不许高）、
     * 每列上方 1..4 格无实心遮挡、顶格非液体/非落沙类松散顶（沙/沙砾会塌）。
     * 评分：近水（8 格内有水 -100）、近田锚点（距离）、近树（16 格内原木列 -2/棵）、
     * 离 NPC 近（0.1/格）。洞口评分 v1 未含（无廉价可靠的洞口判定，宁缺）。
     */
    private BlockPos findBaseSite() {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos me = npc.blockPosition();
        FarmMemory.bindWorld(npc.level());
        FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int cx = me.getX() - 26; cx <= me.getX() + 26; cx += 1) {
            for (int cz = me.getZ() - 26; cz <= me.getZ() + 26; cz += 1) {
                if (level.getChunkIfLoaded(cx >> 4, cz >> 4) == null) {
                    continue;
                }
                int floor = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                if (floor <= level.getMinY() + 4) {
                    continue;
                }
                if (!baseFootprintOk(level, cx, cz, floor)) {
                    continue;
                }
                double score = me.distSqr(new BlockPos(cx, floor, cz)) * 0.01D;
                if (farm != null) {
                    score += farm.anchor().distSqr(new BlockPos(cx, floor, cz)) * 0.05D;
                }
                if (baseNearWater(level, cx, floor, cz, 8)) {
                    score -= 100.0D;
                }
                score -= Math.min(10, baseTreeColumns(level, cx, cz, 16)) * 2.0D;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(cx, floor, cz);
                }
            }
        }
        return best;
    }

    private boolean baseFootprintOk(ServerLevel level, int cx, int cz, int floor) {
        for (int dx = -BASE_HALF; dx <= BASE_HALF; dx++) {
            for (int dz = -BASE_HALF; dz <= BASE_HALF; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (level.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    return false;
                }
                int top = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (top != floor && top != floor - 1) {
                    return false;
                }
                BlockState topState = level.getBlockState(new BlockPos(x, top, z));
                if (!topState.getFluidState().isEmpty()
                        || topState.is(Blocks.SAND) || topState.is(Blocks.RED_SAND)
                        || topState.is(Blocks.GRAVEL)) {
                    return false;
                }
                // 【fix64】高度表返回值比地表块高一层（boot-farm22 实证：水道列
                // top=63 而水在 62，上面的 topState 查到的是空气，流体排除从未
                // 生效，水边址被判合格、站位正落在水道上）。改按标称地表
                // floor 上下直接扫块：floor-2..floor 任一格有流体即否决；
                // 农田/作物在足迹内同样否决（房子不许压田，boot-farm22 选址
                // 足迹压了农田南侧一排）。
                for (int y = floor - 2; y <= floor; y++) {
                    BlockState s = level.getBlockState(new BlockPos(x, y, z));
                    if (!s.getFluidState().isEmpty()) {
                        return false;
                    }
                    if (s.is(Blocks.FARMLAND) || s.is(Blocks.WHEAT)
                            || s.is(Blocks.CARROTS) || s.is(Blocks.POTATOES)
                            || s.is(Blocks.BEETROOTS)) {
                        return false;
                    }
                }
                for (int up = 1; up <= 4; up++) {
                    BlockState above = level.getBlockState(new BlockPos(x, top + up, z));
                    if (!above.isAir() && !isVegetation(above)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean baseNearWater(ServerLevel level, int cx, int floor, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = floor - 1; y <= floor + 1; y++) {
                    // 用方块判定不用 FluidState.is(Fluids.WATER)：后者在
                    // 编译/运行时映射下签名对不齐、类加载 VerifyError
                    // （fix52 首跑实证，auto-npc 生成直接失败）。
                    if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 数半径内"地表有树"的列（口径同 findVisibleTreeColumn 的简化版）。 */
    private int baseTreeColumns(ServerLevel level, int cx, int cz, int radius) {
        int count = 0;
        for (int x = cx - radius; x <= cx + radius; x += 2) {
            for (int z = cz - radius; z <= cz + radius; z += 2) {
                if (level.getChunkIfLoaded(x >> 4, z >> 4) == null) {
                    continue;
                }
                int top = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState state = level.getBlockState(new BlockPos(x, top - 1, z));
                if (state.is(net.minecraft.tags.BlockTags.LOGS)
                        || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 砌筑材料：按"最不心疼"排序挑背包里真有的方块物品。圆石是工具料，
     * 只在存量 &gt;16 时用；花岗岩/闪长岩/安山岩是 Fong 定的第一档可弃杂石，
     * 拿来砌墙正好废物利用；木板是棍料，存量 &gt;8 才用。
     */
    private String pickBuildMaterial() {
        if (countItem("cobblestone") > 16) {
            return "cobblestone";
        }
        for (String trash : new String[]{"andesite", "diorite", "granite", "cobbled_deepslate",
                "dirt", "tuff", "stone"}) {
            if (countItem(trash) > 0) {
                return trash;
            }
        }
        if (countItem("cobblestone") > 0) {
            return "cobblestone";
        }
        String planks = firstPlanksPath();
        if (planks != null && countItemsEndingWith("_planks") > 8) {
            return planks;
        }
        return null;
    }

    /** 建材总存量（与规划层的触发口径一致）。 */
    private int countBuildMaterial() {
        return countItem("cobblestone") + countItem("cobbled_deepslate") + countItem("dirt")
                + countItem("andesite") + countItem("diorite") + countItem("granite")
                + countItem("tuff") + countItem("stone") + countItemsEndingWith("_planks");
    }

    private String firstPlanksPath() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                return pathOf(stack);
            }
        }
        return null;
    }

    /** 物品路径 → 对应方块（只认 BlockItem）。 */
    private Block blockOfItem(String itemPath) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:" + itemPath));
        if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
            return blockItem.getBlock();
        }
        return null;
    }

    /** 可替换的植被/薄层（与种田选址同口径的近似集合）。 */
    private boolean isVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SNOW) || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.DANDELION) || state.is(Blocks.POPPY)
                || state.is(Blocks.AZURE_BLUET) || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.CORNFLOWER) || state.is(Blocks.LILY_OF_THE_VALLEY)
                || state.is(Blocks.RED_TULIP) || state.is(Blocks.ORANGE_TULIP)
                || state.is(Blocks.WHITE_TULIP) || state.is(Blocks.PINK_TULIP)
                || state.is(Blocks.ALLIUM) || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.SUNFLOWER) || state.is(Blocks.LILAC)
                || state.is(Blocks.ROSE_BUSH) || state.is(Blocks.PEONY);
    }

    // ---------------------------------------------------------------- 狩猎

    /**
     * 【fix106】追杀无战利品计时：进度量与目标完成判定同源（存量驱动看存量、
     * 计数狩猎看肉类总数、定点猎杀看对应掉落）。增量上涨即重置计时；
     * 满 {@link #CHASE_NO_GAIN_LIMIT_MILLIS} 无新增返回 true。
     * 只由 tick() 在猎杀类目标（hunt/slay）上、看门狗之后调用。
     */
    private boolean chaseNoGainExpired() {
        int metric;
        if ("hunt".equals(type)) {
            metric = !huntNeed.isEmpty() ? huntNeedStock() : countHuntLoot();
        } else {
            metric = countItem(slayDrop);
        }
        int gain = Math.max(0, metric - chaseBaseline);
        if (gain > chaseGainSeen) {
            chaseGainSeen = gain;
            chaseLastGainMillis = System.currentTimeMillis();
            return false;
        }
        return System.currentTimeMillis() - chaseLastGainMillis >= CHASE_NO_GAIN_LIMIT_MILLIS;
    }

    /** 【fix247】存量驱动 hunt 的存量口径：皮革看 leather、羊毛看床料等价量、
     * 其余（food）看食物营养合计。追杀时限与目标基线同源共用此口径。 */
    private int huntNeedStock() {
        if ("leather".equals(huntNeed)) {
            return countItem("leather");
        }
        if ("wool".equals(huntNeed)) {
            return bedWoolStock();
        }
        return foodNutrition();
    }

    /** 【fix247】床料等价量：与规划层做床分支同口径——任一单色羊毛最大值与
     * 「白羊毛＋线/4 折算」取大者；≥3 即够合一张床（Wiki：床=3 同色羊毛+3 木板，
     * 白羊毛=4 线合成）。 */
    private int bedWoolStock() {
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                "red", "black"};
        int best = 0;
        for (String color : colors) {
            best = Math.max(best, countItem(color + "_wool"));
        }
        int whitePath = countItem("white_wool") + countItem("string") / 4;
        return Math.max(best, whitePath);
    }

    /** 【fix346】羊毛总量（全部颜色合计）：杀羊信号用——bedWoolStock 只取单色最大值。 */
    private int woolTotal() {
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                "red", "black"};
        int total = 0;
        for (String color : colors) {
            total += countItem(color + "_wool");
        }
        return total;
    }

    /** 【fix267】零获收场后的航向轮转：+72° 确定性扫掠（5 向覆盖一圈），
     * 取代旧版随机重掷与回退角改写——基址周边存量耗尽时（118 棒普查：64 格
     * 内可猎为 0、最近约 118 格），方向不收敛是搜索包络恒在原地打转的主因。
     * 有获时不调本方法：达标照旧清零航向、中性收工保留航向（该方向有猎物）。 */
    private void rotateHuntHeading() {
        if (!Double.isNaN(huntHeading)) {
            huntHeading += Math.toRadians(72.0D);
        }
    }

    /** 【fix267】扫描记账：本次 hunt 派发点距已计扫描锚 ≥16 格（水平）、或
     * 自锚定以来战利品有增量，才计入 huntScansCounted 并移锚；坑洼短跳
     * （净位移 <16）不计——8 次收场额只烧在真正换过地方的无获扫描上，
     * 总时长仍由 fix106 的 120 秒无增量硬顶兜底，不会无限 hunt。 */
    private void noteHuntScan() {
        double x = npc.getX();
        double z = npc.getZ();
        if (Double.isNaN(huntScanAnchorX) || chaseGainSeen > huntGainAtAnchor) {
            huntScansCounted++;
            huntScanAnchorX = x;
            huntScanAnchorZ = z;
            huntGainAtAnchor = chaseGainSeen;
            return;
        }
        double dx = x - huntScanAnchorX;
        double dz = z - huntScanAnchorZ;
        if (dx * dx + dz * dz >= 256.0D) {
            huntScansCounted++;
            huntScanAnchorX = x;
            huntScanAnchorZ = z;
            huntGainAtAnchor = chaseGainSeen;
        }
    }

    private void tickHunt() {
        // 【第二十二轮】存量驱动（Fong 18:49 拍板）：规划器给了 need/stock 就不数
        // "打死几只"——掉落是随机的（牛皮革 0–2、1/3 空手），数击杀会出现在打完
        // 工单皮革还是 0 的假完成。每轮复查背包存量：皮革看 leather 计数、食物看
        // 全部食物的营养值合计；存量一变就打复查行，够了立刻收工，不够继续打；
        // 连续 8 次找不到目标照旧连败退避交回规划器（防原地空转）。
        if (!huntNeed.isEmpty()) {
            boolean leatherNeed = "leather".equals(huntNeed);
            boolean woolNeed = "wool".equals(huntNeed);
            int stock = huntNeedStock();
            status = "狩猎（存量驱动 " + huntNeed + "） " + stock + "/" + huntStockTarget;
            if (stock != lastHuntStock) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " hunt 存量复查 | " + huntNeed + "="
                        + stock + "/" + huntStockTarget
                        + (stock >= huntStockTarget ? " 已达标" : " 不够，继续"));
                lastHuntStock = stock;
            }
            // 【fix346】羊群锚点登记：羊毛总量上升＝本轮杀过羊，记杀羊位为锚点。
            if (woolNeed && lastWoolTotal >= 0) {
                int total = woolTotal();
                if (total > lastWoolTotal) {
                    flockAnchor = npc.blockPosition();
                    flockAnchorReached = true;
                    flockAnchorSince = 0;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 羊群锚点登记（fix346） | 杀羊得毛 wool="
                            + lastWoolTotal + "→" + total + " 锚点=" + flockAnchor.toShortString());
                }
                lastWoolTotal = total;
            }
            if (stock >= huntStockTarget) {
                huntFailStreak = 0;
                huntHeading = Double.NaN;
                finish("狩猎完成（存量达标）" + huntNeed + "=" + stock + "/" + huntStockTarget);
                return;
            }
            // 【fix346】羊毛 hunt 羊群锚点路由：本 boot 内曾在某处杀过羊时，先赴锚点再本地扫描。
            // 生产 628373 boot 实证：羊群在基址西南约 70 格，盲搜 4/5 次 120 秒 0 获，唯一成功
            // 是 wander 漂移偶然撞见；下一轮 hunt 对羊群位置一无所知、从头盲搜。赴锚点用 goto
            //（travel 系，会游泳/搭桥）；60 秒不到作废落回盲搜，不堵 hunt 的 120 秒预算。
            // 【fix346 v2】水中/岩浆中不派 goto：A* 起点须可站立，水中必瞬时失败并触发 fix245 熔断
            //（G1 实证：tp 落河里后 60 秒连败、距锚点 18→20）；等生存层游泳脱困上岸再派，60 秒钟
            // 不走字。陆上连续 3 次派不上即判锚点不可达、清锚点落回盲搜。
            if (woolNeed && flockAnchor != null && !flockAnchorReached) {
                double fdx = flockAnchor.getX() + 0.5D - npc.getX();
                double fdz = flockAnchor.getZ() + 0.5D - npc.getZ();
                double fdist = Math.sqrt(fdx * fdx + fdz * fdz);
                if (fdist <= 10.0D) {
                    flockAnchorReached = true;
                } else if (fdist > 128.0D) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 羊群锚点过远作废（fix346） | 锚点="
                            + flockAnchor.toShortString() + " 距=" + (int) fdist);
                    flockAnchor = null;
                    flockAnchorSince = 0;
                    flockAnchorGotoFails = 0;
                } else if (npc.isInWater() || npc.isInLava()) {
                    status = "赴羊群锚点（待上岸） " + (int) fdist + " 格";
                    return;
                } else {
                    long now = System.currentTimeMillis();
                    if (flockAnchorSince == 0) {
                        flockAnchorSince = now;
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " 赴羊群锚点（fix346） | 锚点="
                                + flockAnchor.toShortString() + " 距=" + (int) fdist);
                    }
                    if (now - flockAnchorSince > 60_000L) {
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " 赴羊群锚点超时作废（fix346） | 锚点="
                                + flockAnchor.toShortString() + " 距=" + (int) fdist);
                        flockAnchor = null;
                        flockAnchorSince = 0;
                        flockAnchorGotoFails = 0;
                    } else {
                        JsonObject anchorParams = new JsonObject();
                        anchorParams.addProperty("x", flockAnchor.getX() + 0.5D);
                        anchorParams.addProperty("z", flockAnchor.getZ() + 0.5D);
                        status = "赴羊群锚点 " + (int) fdist + " 格";
                        if (skills.run(new Intent("", "goto", anchorParams), "羊群锚点")) {
                            flockAnchorGotoFails = 0;
                        } else if (++flockAnchorGotoFails >= 3) {
                            LOG.info("[AIV][GOAL] " + npc.npcName()
                                    + " 赴羊群锚点寻路连败作废（fix346） | 锚点="
                                    + flockAnchor.toShortString() + " 距=" + (int) fdist);
                            flockAnchor = null;
                            flockAnchorSince = 0;
                            flockAnchorGotoFails = 0;
                        }
                        return;
                    }
                }
            }
            if (huntScansCounted >= 8) {
                // 【fix189】按存量增量判成败（Fong 口径「判定成败按存量增量」）：
                // 起始存量 chaseBaseline（fix106 同源、目标起始捕获）在任，
                // 本轮净增 >0 说明本地 hunt 喂得动它——未达标只是靶高，
                // 不算失败：中性收工、不自增 huntFailStreak，规划层两侧
                // 连败（步 0 hungerHuntFailStreak、步 13 reserveFailStreak
                // 均按 goalFailed 消费本结局）一并不喂。零增/净减（打猎
                // 期间吃掉存量等）照旧判放弃，兜底不缺。旧版只认
                // stock>=target 一刀切：存量 3→21（靶 24）亦判放弃，
                // 连败虚高放大饥饿接缝循环（第五十二棒实证 7/12、21/24）。
                int gained = stock - chaseBaseline;
                if (gained > 0) {
                    finish("狩猎收工（有获 +" + gained + "，存量 " + stock + "/"
                            + huntStockTarget + " 未达标）");
                    return;
                }
                // 【fix346】锚点处 8 次扫描零增＝羊群已不在：清除锚点，下一轮落回盲搜。
                // （超时/未抵达不清除——未证明锚点已空。）
                if (woolNeed && flockAnchor != null && flockAnchorReached) {
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 羊群锚点处空扫清除（fix346） | 锚点="
                            + flockAnchor.toShortString());
                    flockAnchor = null;
                }
                huntFailStreak++;
                rotateHuntHeading();
                finish("狩猎放弃（存量未达标 " + stock + "/" + huntStockTarget
                        + "）：连续 8 次没找到目标");
                return;
            }
            JsonObject stockParams = new JsonObject();
            // 【第四十三轮】搜猎半径 12/24 → 32/48：五局群系合证半径 12 在动物
            // 稀疏/斑块分布地形从未猎成（且技能层旧钳制把 24 也压回 16、从未生效）。
            // 按“玩家目视”口径：常态 32、久猎无获 48，配 wanderForHunt 步幅放大。
            stockParams.addProperty("radius", huntAttempts >= 4 || huntFailStreak >= 1 ? 48 : 32);
            stockParams.addProperty("hits", 10);
            if (leatherNeed) {
                stockParams.addProperty("filter", "leather");
            } else if (woolNeed) {
                stockParams.addProperty("filter", "wool");
            } else if (countItem("leather") < 4) {
                stockParams.addProperty("prefer", "cow");
            }
            noteHuntScan();
            huntAttempts++;
            if (skills.run(new Intent("", "hunt", stockParams), "目标")) {
                return;
            }
            wanderForHunt();
            return;
        }
        int absolute = countHuntLoot();
        int have = Math.max(0, absolute - huntBaseline);
        status = "狩猎 " + have + "/" + targetCount + "（肉类共 " + absolute + "）";
        if (have >= targetCount) {
            huntFailStreak = 0;
            huntHeading = Double.NaN;
            finish("狩猎完成 新得 " + have + "/" + targetCount + "，肉类共 " + absolute);
            return;
        }
        if (huntScansCounted >= 8) {
            // 【fix189】计数分支同口径：本轮新得 have>0 按增量判有获
            // 收工（不喂连败）；have=0 才是真打不着，照旧判放弃。
            if (have > 0) {
                finish("狩猎收工（有获 " + have + "/" + targetCount + "，未达标）");
                return;
            }
            huntFailStreak++;
            rotateHuntHeading();
            finish("狩猎放弃：连续 8 次附近没有可猎动物");
            return;
        }
        JsonObject params = new JsonObject();
        // 【第十五轮】久猎无获时放大搜猎半径（旧版 12 格外一步之遥永远看不见）。
        // 【第四十三轮】口径再放大：常态 32、久猎无获 48（见存量分支同注）。
        params.addProperty("radius", huntAttempts >= 4 || huntFailStreak >= 1 ? 48 : 32);
        params.addProperty("hits", 10);
        // 【第十九轮】皮革不足 4 张时打猎优先牛：牛肉是顶级食物、皮革是皮靴
        // 材料，一举两得（Fong 口径）。攒够 4 张后恢复"最近优先"。
        if (countItem("leather") < 4) {
            params.addProperty("prefer", "cow");
        }
        noteHuntScan();
        huntAttempts++;
        if (skills.run(new Intent("", "hunt", params), "目标")) {
            return;
        }
        wanderForHunt();
    }

    /**
     * 【第十五轮】找猎专用搬迁：旧版所有"附近没有"都用通用 wander 小步随机
     * 4-9 格换位，在无动物区就是原地打转——长跑#6 实测 food=0 后连续 4 轮
     * 狩猎目标全失败、8 分钟只挪出 50 格，铁装/钻石主线被饥饿顶住饿死循环。
     * 改为逐级放大：本轮前两次仍小步；之后沿一条固定航向逐级远走
     * （航向在整轮失败期间保持，跨目标 streak 叠加升温），真正离开贫瘠区；
     * 打到猎物即清零复位。航向走不通时依次试 ±45° 与反向，都不通退回通用小步。
     * 【第四十三轮】步幅随搜猎半径（32/48）同步放大：24→48→64 格——步幅小于
     * 扫描半径时新扫区大半与旧区重叠，等于在贫瘠区里换个姿势盲走。
     */
    private void wanderForHunt() {
        int heat = huntAttempts + huntFailStreak * 2;
        if (heat <= 2) {
            wander();
            return;
        }
        double distance = heat <= 4 ? 24.0D : heat <= 6 ? 48.0D : 64.0D;
        if (Double.isNaN(huntHeading)) {
            huntHeading = java.util.concurrent.ThreadLocalRandom.current()
                    .nextDouble() * Math.PI * 2.0D;
        }
        double[] angles = {huntHeading, huntHeading + Math.PI / 4, huntHeading - Math.PI / 4,
                huntHeading + Math.PI};
        for (double angle : angles) {
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * distance);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * distance);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                // 【fix267】回退角（±45°/反向）只许借道起步，不许改写
                // huntHeading——旧版任一回退角起步成功即覆盖航向，整轮失败
                // 期间航向被反复改写（118 棒实证一轮内 78→33→213→168），
                // 与第十五轮「整轮保持航向」的设计自述相悖、方向不收敛。
                LOG.info("[AIV][GOAL] " + npc.npcName() + " hunt-escalate | streak="
                        + huntFailStreak + " dist=" + (int) distance
                        + " heading=" + (int) Math.toDegrees(angle));
                return;
            }
        }
        wander();
    }

    /** 【第二十二轮】背包食物总营养值：按 FOOD 组件 nutrition×数量合计（下矿口粮线的口径）。
     * 【第二十四轮】腐肉每块按 1 点计（Wiki：恢复 4/饱和 0.8/80% 概率饥饿效果，
     * 满值高估），与规划层、盘点同一口径。 */
    private int foodNutrition() {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                total += ("rotten_flesh".equals(pathOf(stack)) ? 1 : food.nutrition())
                        * stack.getCount();
            }
        }
        return total;
    }

    /** 背包里的肉类总数（生熟都算）：狩猎进度按"本轮新打到多少"统计。 */
    private int countHuntLoot() {
        int total = 0;
        for (String meat : List.of("beef", "porkchop", "chicken", "mutton",
                "rabbit", "cooked_beef", "cooked_porkchop", "cooked_chicken",
                "cooked_mutton", "cooked_rabbit")) {
            total += countItem(meat);
        }
        return total;
    }

    // ---------------------------------------------------------------- 下界传送门（通关路线）

    /** 10 格传送门框架局部坐标（四角省略，同 SkillRunner）。 */
    private static final int[][] PORTAL_FRAME = {
            {0, 1}, {0, 2}, {0, 3},
            {3, 1}, {3, 2}, {3, 3},
            {1, 0}, {2, 0},
            {1, 4}, {2, 4}
    };

    /** 传送门选址：origin 是框架左下角，alongX 决定门面横向沿 X 还是 Z。 */
    private record PortalSite(BlockPos origin, boolean alongX) {
    }

    /**
     * 找燧石。
     *
     * <p>原版沙砾只有 10% 掉燧石，满世界找沙砾滩很慢；玩家最常用的办法是
     * "把已有的沙砾放下再挖掉"，反复刷到燧石为止。这里优先用这个办法，
     * 背包里没有沙砾时才去附近找天然沙砾。
     */
    // ===== 【fix69·箱子】回落脚点存贵重物（v1 只存不取，草案见 status-round14） =====
    private int storePhase;
    private BlockPos storeChestPos;
    private int storeActionStuck;
    private String storeLastActionKey = "";
    // 【fix251】phase 1 接近预算与礼让消费：storePhase1StartTick 记进入
    // phase 1 的 NPC tick（-1=未进）；技能运行期目标 tick 整体早退、phase
    // 计数推不动，长技能钉死时唯一能兜底的是技能结局消费（handledStoreSeq
    // 序号水位）＋复位后首 tick 的墙钟预算复核，两者都在下方 phase 1 入口。
    private long storePhase1StartTick = -1L;
    private int handledStoreSeq;

    // ===== 【fix80·D12】搜刮世界箱子（村庄/结构，只拿用得上的） =====
    private int lootX;
    private int lootY;
    private int lootZ;
    private int lootStuck;
    private BlockPos lootStandPos;

    /** 存入名单（草案定版）：死了怕丢、平时不用的贵重物 + 第二档杂物（存箱优先于扔）。
     * 【fix92·Fong 2026-10-28】红石加入：留存的一组（配额裁剪已封顶 64）
     * 回家存进箱子，不在包里占格。 */
    private static final java.util.Set<String> STORE_LOOT = java.util.Set.of(
            "diamond", "emerald", "netherite_ingot", "netherite_scrap", "ancient_debris",
            "blaze_rod", "ender_pearl", "gold_ingot", "redstone",
            "bone", "string", "spider_eye");

    /** 落脚点箱子是否已安置且在 range 格内（第二档"存箱优先"的判定口）。
     * 【fix249】加实地复核：账本箱子已被挖毁时（正式世界 2026-11-05 贵重箱
     * 挖毁后账本残留），旧版只看账本会恒判"箱子在近傍"，第二档杂物（骨头
     * 等）被"先存箱"口径永久护住腾不出格，连带满包做箱子都腾不出第一格、
     * 存箱自解链断裂（94 棒定谳，与规划层 recordedChestExists 同根同修）。 */
    private boolean hasChestWithin(double range) {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        return base != null && base.chestPos() != null
                && npc.level().getBlockState(base.chestPos())
                        .is(net.minecraft.world.level.block.Blocks.CHEST)
                && npc.blockPosition().distSqr(base.chestPos()) <= range * range;
    }

    /**
     * 【fix169】存箱站位格：锚点可站立仍站锚点（屋内中心、与旧口径一致）；
     * 否则在箱位邻格（切比雪夫 1、层位 dy∈{0,1,−1,−2}——均在 fix169 到达
     * 包络 |dy|≤2 内）中取可站立且离 NPC 最近的一格；再无则回落锚点
     * （moveToStand 自带竖直对齐/travel 兜底，90 计数放弃仍在）。
     */
    private BlockPos storeStandCell(BlockPos anchor) {
        if (storeChestPos == null || PathFinder.isStandable(npc.level(), anchor)) {
            return anchor;
        }
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dy : new int[]{0, 1, -1, -2}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    BlockPos cell = storeChestPos.offset(dx, dy, dz);
                    if (!PathFinder.isStandable(npc.level(), cell)) {
                        continue;
                    }
                    double distSq = npc.blockPosition().distSqr(cell);
                    if (distSq < bestSq) {
                        bestSq = distSq;
                        best = cell;
                    }
                }
            }
        }
        return best != null ? best : anchor;
    }

    /**
     * 【fix251】存箱接近忙位守卫（tick() 在技能忙位早退之前调用，仅
     * store_loot 且 phase 1 时有效）：① 消费子技能礼让结局——含「礼让
     * 收场」∧「分级守卫」→ 停技能、按守卫堵死收场交规划层补镐；
     * ② 接近墙钟预算：phase 1 起 3000 tick（150 秒）未达箱位近傍 →
     * 停技能、按接近超时收场。返回 true = 已收场，tick 本轮到此为止。
     * phase 1 入口（tickStoreLoot）保留同口径消费作双保险（技能恰在
     * 空闲间隙收场时由那边先接）。
     */
    private boolean tickStoreBusyGuard() {
        if (!"store_loot".equals(type) || storePhase != 1 || storeChestPos == null) {
            return false;
        }
        BlockPos meNow = npc.blockPosition();
        boolean nearChestNow = Math.max(Math.abs(meNow.getX() - storeChestPos.getX()),
                Math.abs(meNow.getZ() - storeChestPos.getZ())) <= 2
                && Math.abs(meNow.getY() - storeChestPos.getY()) <= 2;
        if (nearChestNow) {
            return false;
        }
        int seqNow = skills.outcomeSeq();
        if (seqNow != handledStoreSeq) {
            handledStoreSeq = seqNow;
            String outcomeNow = skills.lastOutcome();
            if (outcomeNow.contains("礼让收场") && outcomeNow.contains("分级守卫")) {
                skills.stop();
                finish("存箱放弃：接近被镐子分级守卫堵死（石镐可合），交规划层补造石镐");
                return true;
            }
        }
        if (storePhase1StartTick < 0L) {
            storePhase1StartTick = npc.tickCount;
        } else if (npc.tickCount - storePhase1StartTick > 3000L) {
            skills.stop();
            finish("存箱放弃：接近超时未达箱位近傍（fix251 预算）");
            return true;
        }
        return false;
    }

    private void tickStoreLoot() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        if (base == null) {
            finish("存箱放弃：无落脚点账本");
            return;
        }
        switch (storePhase) {
            case 0 -> {
                status = "存箱：定箱位";
                BlockPos recorded = base.chestPos();
                if (recorded != null && npc.level().getBlockState(recorded)
                        .is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    storeChestPos = recorded;
                } else {
                    if (recorded != null) {
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 账本箱位块已不在（"
                                + recorded.toShortString() + "），重新选位");
                    }
                    BlockPos anchor = base.anchor();
                    // 【fix366】当场放箱（284／288 定谳：箱子做出来、存箱接近失败、
                    // 箱子滞留袋内、下一次背包变动即被溢出兜底丢掉）：
                    // 账本箱位实地已无箱、包里有刚做好的箱子时，先试在 NPC 当场放下：
                    // 只当 |NPC.y − 锚点.y| ≤ 8（288 dy=2、284 dy=1 皆在门内；深矿落回
                    // 旧路、不放矿洞箱），扫描 NPC 邻格（dy ∈ {0,+1,−1}，脚／头立柱与
                    // 锚点格除外），首个 bedCellFree 格（空／植被＋脚下实心＋无流体——
                    // 床／传送门／田皆非空气，天然排除）即当场放置并登记账本；随后
                    // phase 1 近傍判定立即通过、phase 2 随即存入。两条派单路（fix217
                    // 到达支与 6.77 顺路支）皆只派 store_loot，修在目标层一处即全
                    // 覆盖；两处派单皆要求 NPC 距锚点 ≤48 格，故当场放箱恒在锚点
                    // 50 格内。邻格全无效／|dy|>8／包里无箱时落回既有环选位＋接近
                    // 流程（行为不变）；consume 失败亦落回旧路，绝不重派做箱子。
                    storeChestPos = null;
                    if (countItem("chest") > 0
                            && Math.abs(npc.blockPosition().getY() - anchor.getY()) <= 8) {
                        BlockPos me = npc.blockPosition();
                        BlockPos placed = null;
                        int[] placeDys = {0, 1, -1};
                        for (int placeDy : placeDys) {
                            if (placed != null) {
                                break;
                            }
                            for (int placeDx = -1; placeDx <= 1 && placed == null; placeDx++) {
                                for (int placeDz = -1; placeDz <= 1; placeDz++) {
                                    if (placeDx == 0 && placeDz == 0 && placeDy >= 0) {
                                        continue;
                                    }
                                    BlockPos cell = me.offset(placeDx, placeDy, placeDz);
                                    if (cell.equals(anchor) || !bedCellFree(cell)) {
                                        continue;
                                    }
                                    placed = cell;
                                    break;
                                }
                            }
                        }
                        if (placed != null && consume("chest", 1)) {
                            npc.level().setBlockAndUpdate(placed,
                                    net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                            BaseMemory.saveChest(npc.npcName(), placed);
                            LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 当场放箱（fix366） | pos="
                                    + placed.toShortString());
                            storeChestPos = placed;
                        }
                    }
                    if (storeChestPos == null) {
                        // 【fix155】候选补齐 ring-1 八邻格 + ring-2 十六格兜底：
                        // 旧版只有 6 格（还漏了 (-1,0,-1)/(0,0,-1)），火柴盒里
                        // 床/熔炉/墙占满即「屋内没有放箱子的空位」放弃——fix136
                        // 专程腿到了门口死在这一步（round40 账）。玩家做法是屋里
                        // 放不下就放门口/外墙边；仍走 bedCellFree 同口径（空/植被
                        // +无流体+脚下实心），锚点本身永不入选（留作站位）。
                        BlockPos[] candidates = {
                                anchor.offset(1, 0, 1), anchor.offset(1, 0, -1),
                                anchor.offset(-1, 0, 1), anchor.offset(-1, 0, -1),
                                anchor.offset(1, 0, 0), anchor.offset(-1, 0, 0),
                                anchor.offset(0, 0, 1), anchor.offset(0, 0, -1),
                                anchor.offset(2, 0, 0), anchor.offset(-2, 0, 0),
                                anchor.offset(0, 0, 2), anchor.offset(0, 0, -2),
                                anchor.offset(2, 0, 1), anchor.offset(2, 0, -1),
                                anchor.offset(-2, 0, 1), anchor.offset(-2, 0, -1),
                                anchor.offset(1, 0, 2), anchor.offset(1, 0, -2),
                                anchor.offset(-1, 0, 2), anchor.offset(-1, 0, -2),
                                anchor.offset(2, 0, 2), anchor.offset(2, 0, -2),
                                anchor.offset(-2, 0, 2), anchor.offset(-2, 0, -2)};
                        for (BlockPos cell : candidates) {
                            // bedCellFree 要求空/植被+脚下实心：床格是实体块天然被排除，
                            // 锚点本身不在候选内（留作站位）。
                            if (bedCellFree(cell)) {
                                storeChestPos = cell;
                                break;
                            }
                        }
                        if (storeChestPos == null) {
                            finish("存箱放弃：屋内没有放箱子的空位");
                            return;
                        }
                        if (Math.max(Math.abs(storeChestPos.getX() - anchor.getX()),
                                Math.abs(storeChestPos.getZ() - anchor.getZ())) > 1) {
                            LOG.info("[AIV][GOAL] " + npc.npcName()
                                    + " [STORE] 屋内无空位，箱位选到外圈（fix155） | pos="
                                    + storeChestPos.toShortString());
                        }
                        if (countItem("chest") == 0) {
                            finish("存箱放弃：包里没箱子");
                            return;
                        }
                    }
                }
                storePhase = 1;
            }
            case 1 -> {
                status = "存箱：就位";
                // 【fix251】接近段两道闸（本 case 只在技能空闲时被 tick 到；
                // 长技能钉死期间的同名检查在 tick() 忙位早退之前另有一份
                // （tickStoreBusyGuard）——本目标的接近技能恰恰全程占忙位，
                // 只放在这里会被饿死，同第四轮修复 A/fix106 的教训）：
                // ① 礼让消费：子技能以「礼让收场…分级守卫…」收场 = 接近被
                // 镐子分级守卫堵死——立即放弃交规划层补造石镐，不许再派
                // 同一条接近腿原地磨（fresh100 形态乙 480 秒实证）。
                int storeSeq = skills.outcomeSeq();
                if (storeSeq != handledStoreSeq) {
                    handledStoreSeq = storeSeq;
                    String storeOutcome = skills.lastOutcome();
                    if (storeOutcome.contains("礼让收场") && storeOutcome.contains("分级守卫")) {
                        finish("存箱放弃：接近被镐子分级守卫堵死（石镐可合），交规划层补造石镐");
                        return;
                    }
                }
                // ② 接近墙钟预算 3000 tick（150 秒）：触发半径 ≤48 格的合法
                // 接近正常 <60 秒；垫柱攀崖烧料型（93 棒圆石 106→0、505 秒）
                // 与一切未预见的接近病理在此止损，交规划层退避换活。
                if (storePhase1StartTick < 0L) {
                    storePhase1StartTick = npc.tickCount;
                } else if (npc.tickCount - storePhase1StartTick > 3000L) {
                    finish("存箱放弃：接近超时未达箱位近傍（fix251 预算）");
                    return;
                }
                // 【fix169】到达改近傍口径：距箱位水平切比雪夫 ≤2∧|dy|≤2 即进
                // phase 2（phase 2 本是直改方块+容器操作、无距离校验）。旧版
                // 要求精确站在锚点列∧|dy|≤1：人自低处/屋外接近时 moveToStand
                // 的 ±4 带不派竖直对齐、goto 爬不上火柴盒地板，68 秒放弃
                // （farm137 实证：终点与锚点同列低 3 格）；且与 fix155 外圈
                // 箱位自相矛盾（箱能放屋外、人却必须站屋内中心）。移动目标
                // 改站箱位近傍格（storeStandCell，所选格本身即在到达包络内）。
                BlockPos me = npc.blockPosition();
                boolean nearChest = Math.max(Math.abs(me.getX() - storeChestPos.getX()),
                        Math.abs(me.getZ() - storeChestPos.getZ())) <= 2
                        && Math.abs(me.getY() - storeChestPos.getY()) <= 2;
                if (nearChest) {
                    storePhase = 2;
                    return;
                }
                String key = "store-stand";
                if (key.equals(storeLastActionKey)) {
                    storeActionStuck++;
                } else {
                    storeLastActionKey = key;
                    storeActionStuck = 0;
                }
                if (storeActionStuck > 90) {
                    finish("存箱放弃：到不了箱位近傍");
                    return;
                }
                moveToStand(storeStandCell(base.anchor()), storeActionStuck, "[STORE]");
            }
            case 2 -> {
                status = "存箱：存入";
                if (!npc.level().getBlockState(storeChestPos)
                        .is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    if (!consume("chest", 1)) {
                        finish("存箱放弃：扣箱子失败");
                        return;
                    }
                    npc.level().setBlockAndUpdate(storeChestPos,
                            net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    BaseMemory.saveChest(npc.npcName(), storeChestPos);
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 放箱 | pos="
                            + storeChestPos.toShortString());
                }
                net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                        npc.level().getBlockEntity(storeChestPos);
                if (!(blockEntity instanceof net.minecraft.world.Container container)) {
                    finish("存箱放弃：箱子容器不可用");
                    return;
                }
                int ironExcess = Math.max(0, countItem("iron_ingot") - 32);
                int kinds = 0;
                int total = 0;
                for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                    ItemStack stack = npc.getInventory().getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String path = pathOf(stack);
                    // 【fix346】床链挂起且床料未满时，线是床料（线×4 合白羊毛）：不许存箱。
                    // fix68 已护线不被丢弃，但 6.77 存箱会把线锁进箱子（253 实证 string x10
                    // 存入锚点箱，规划层线折算分支只数包里线、无取箱路径）；床成后自动恢复。
                    if (path.equals("string") && bedChainPending() && bedWoolStock() < 3) {
                        continue;
                    }
                    int want;
                    if (STORE_LOOT.contains(path)) {
                        want = stack.getCount();
                    } else if (path.equals("iron_ingot")) {
                        // 铁锭只存超出常备 32 的部分（Fong 常备口径，草案定版）。
                        want = Math.min(stack.getCount(), ironExcess);
                    } else {
                        continue;
                    }
                    if (want <= 0) {
                        continue;
                    }
                    ItemStack moving = stack.copy();
                    moving.setCount(want);
                    addToContainer(container, moving);
                    int moved = want - moving.getCount();
                    if (moved <= 0) {
                        continue;
                    }
                    stack.shrink(moved);
                    if (stack.isEmpty()) {
                        npc.getInventory().setItem(slot, ItemStack.EMPTY);
                    }
                    if (path.equals("iron_ingot")) {
                        ironExcess -= moved;
                    }
                    kinds++;
                    total += moved;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " [STORE] 存入 | item="
                            + path + " x" + moved + " 箱位=" + storeChestPos.toShortString());
                }
                blockEntity.setChanged();
                if (total == 0) {
                    finish("存箱完成：无可存物品（或箱已满）");
                } else {
                    finish("存箱完成：存入 " + kinds + " 种共 " + total + " 个 @ "
                            + storeChestPos.toShortString());
                }
            }
            default -> finish("存箱阶段异常 " + storePhase);
        }
    }

    /** 把一堆物品放进容器：先合并同物品堆、再填空槽（原版箱子放入口径）。 */
    private void addToContainer(net.minecraft.world.Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack cur = container.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, stack)) {
                int room = Math.min(cur.getMaxStackSize(), container.getMaxStackSize())
                        - cur.getCount();
                if (room > 0) {
                    int move = Math.min(room, stack.getCount());
                    cur.grow(move);
                    stack.shrink(move);
                    container.setItem(i, cur);
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) {
                int move = Math.min(stack.getCount(),
                        Math.min(stack.getMaxStackSize(), container.getMaxStackSize()));
                ItemStack put = stack.copy();
                put.setCount(move);
                container.setItem(i, put);
                stack.shrink(move);
            }
        }
    }

    /**
     * 【fix80·D12】搜刮世界箱子：走到箱边 → 按清单拿 → 记账销账。
     * 只拿用得上的（口粮/床料/贵重物/原料），不动箱子本身、不拆任何东西、
     * 不碰床和铁傀儡（Fong 拍板边界）。搜完与到不了都进 LootMemory 账本，
     * 同一箱子不重复派单（到不了多半是永久几何，记下别再试）。
     */
    private void tickLootChest() {
        BlockPos chestPos = new BlockPos(lootX, lootY, lootZ);
        status = "搜刮箱子 " + chestPos.toShortString();
        LootMemory.bindWorld(npc.level());
        net.minecraft.world.level.block.state.BlockState chestState =
                npc.level().getBlockState(chestPos);
        if (!chestState.is(net.minecraft.world.level.block.Blocks.CHEST)
                && !chestState.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
            LootMemory.markLooted(npc.npcName(), chestPos);
            finish("搜箱放弃：箱子已不在 " + chestPos.toShortString());
            return;
        }
        double dx = chestPos.getX() + 0.5D - npc.getX();
        double dz = chestPos.getZ() + 0.5D - npc.getZ();
        boolean atChest = Math.sqrt(dx * dx + dz * dz) <= 2.2D
                && Math.abs(npc.getY() - chestPos.getY()) <= 2.0D;
        if (!atChest) {
            if (lootStandPos == null) {
                lootStandPos = pickLootStand(chestPos);
                if (lootStandPos == null) {
                    LootMemory.markLooted(npc.npcName(), chestPos);
                    finish("搜箱放弃：箱子四周没有可站的格 " + chestPos.toShortString());
                    return;
                }
            }
            lootStuck++;
            if (lootStuck > 240) {
                LootMemory.markLooted(npc.npcName(), chestPos);
                finish("搜箱放弃：多次推进仍未到达箱子边 " + chestPos.toShortString());
                return;
            }
            moveToStand(lootStandPos, lootStuck, "[LOOT]");
            return;
        }
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        net.minecraft.world.level.block.entity.BlockEntity blockEntity =
                npc.level().getBlockEntity(chestPos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            LootMemory.markLooted(npc.npcName(), chestPos);
            finish("搜箱放弃：箱子容器不可用 " + chestPos.toShortString());
            return;
        }
        int kinds = 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            if (!lootWorthTaking(path)) {
                continue;
            }
            ItemStack moving = stack.copy();
            int before = moving.getCount();
            npc.getInventory().add(moving);
            int taken = before - moving.getCount();
            if (taken <= 0) {
                continue;
            }
            if (taken >= stack.getCount()) {
                container.setItem(slot, ItemStack.EMPTY);
            } else {
                stack.shrink(taken);
                container.setItem(slot, stack);
            }
            kinds++;
            total += taken;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " [LOOT] " + path + " x" + taken
                    + " | 箱=" + chestPos.toShortString());
        }
        container.setChanged();
        LootMemory.markLooted(npc.npcName(), chestPos);
        finish(total > 0
                ? "搜箱完成：拿到 " + kinds + " 种共 " + total + " 件 @ " + chestPos.toShortString()
                : "搜箱完成：箱里没有用得上的东西 @ " + chestPos.toShortString());
    }

    /** 箱边站位：四个水平邻格里挑能站的（脚格可替换、脚下有实心），离 NPC 最近优先。 */
    private BlockPos pickLootStand(BlockPos chestPos) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos cell = chestPos.offset(d[0], 0, d[1]);
            net.minecraft.world.level.block.state.BlockState cellState =
                    npc.level().getBlockState(cell);
            net.minecraft.world.level.block.state.BlockState belowState =
                    npc.level().getBlockState(cell.below());
            if (!(cellState.isAir() || cellState.canBeReplaced())
                    || belowState.getCollisionShape(npc.level(), cell.below()).isEmpty()) {
                continue;
            }
            double sq = cell.distSqr(npc.blockPosition());
            if (sq < bestSq) {
                bestSq = sq;
                best = cell;
            }
        }
        return best;
    }

    /** 搜箱拿取清单（D12 定版）：食物（含生肉，回去烤）+ 床料 + 功能料 + 贵重物。 */
    private static final java.util.Set<String> LOOT_TAKE = java.util.Set.of(
            "bread", "apple", "golden_apple", "enchanted_golden_apple",
            "carrot", "golden_carrot", "potato", "baked_potato", "beetroot",
            "melon_slice", "sweet_berries", "glow_berries", "pumpkin_pie",
            "dried_kelp", "cookie", "honey_bottle", "wheat",
            "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
            "cooked_cod", "cooked_salmon", "cooked_rabbit",
            "beef", "porkchop", "chicken", "mutton", "cod", "salmon", "rabbit",
            "rabbit_stew", "mushroom_stew", "beetroot_soup",
            "string", "bone", "arrow", "bucket", "torch", "coal",
            "iron_ingot", "gold_ingot", "diamond", "emerald", "lapis_lazuli",
            "ender_pearl", "blaze_rod", "gold_nugget", "iron_nugget");

    /** 这件东西值不值得从箱子里拿（干草块另有小麦当量上限口径）。 */
    private boolean lootWorthTaking(String path) {
        if (path.endsWith("_wool") || LOOT_TAKE.contains(path)) {
            return true;
        }
        if (path.equals("hay_block")) {
            // 干草块=面包原料：一块顶 9 小麦，小麦当量满一组（64）就停手。
            return countItem("wheat") + countItem("hay_block") * 9 < 64;
        }
        return false;
    }

    private void tickFlint() {
        int have = countItem("flint");
        status = "找燧石 " + have + "/" + targetCount;
        if (have >= targetCount) {
            finish("燧石够了 " + have + "/" + targetCount);
            return;
        }
        if (countItem("gravel") > 0 && placeGravelForFlint()) {
            attempts = 0;
            return;
        }
        BlockPos gravel = nearbyBlockPos("gravel");
        if (gravel != null) {
            JsonObject params = params("block", "gravel", "radius", GATHER_RADIUS);
            if (skills.run(new Intent("", "mine", params), "目标")) {
                attempts = 0;
                return;
            }
        }
        attempts++;
        if (attempts >= 12) {
            finish("找燧石放弃：附近没有沙砾");
            return;
        }
        wander();
    }

    /** 把背包里的 1 个沙砾放在脚边再挖掉，反复触发 10% 的燧石掉落。 */
    private boolean placeGravelForFlint() {
        BlockPos base = npc.blockPosition();
        for (BlockPos candidate : List.of(base.east(), base.west(), base.north(), base.south(),
                base.east().above(), base.west().above())) {
            BlockState state = npc.level().getBlockState(candidate);
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }
            BlockPos below = candidate.below();
            if (npc.level().getBlockState(below).getCollisionShape(npc.level(), below).isEmpty()) {
                continue;
            }
            if (!consume("gravel", 1)) {
                return false;
            }
            npc.level().setBlockAndUpdate(candidate, Blocks.GRAVEL.defaultBlockState());
            // 【第十八轮】走 mineWithBestTool：沙砾不需要工具，它会先换空手再挖；
            // 旧版 npc.mine() 拿主手当前的镐硬挖，白磨铁镐耐久（选工具审计逮到的小漏口）。
            boolean mined = npc.mineWithBestTool(candidate);
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 刷燧石 | 放挖沙砾 success=" + mined
                    + " 燧石=" + countItem("flint"));
            return true;
        }
        return false;
    }

    /**
     * 【fix70·D4】装满水桶（常备保命水）：转派技能层 fillbucket
     * （64 格内找水源→走过去→舀水，照原版取水口径）。技能在跑时目标层
     * tick 被全局守卫挡住，这里只在技能空闲时派单/收手。
     */
    private void tickFillBucket() {
        status = "装满水桶";
        if (countItem("water_bucket") > 0) {
            finish("水桶已装满");
            return;
        }
        if (countItem("bucket") <= 0) {
            finish("没有空铁桶");
            return;
        }
        // 【fix74】技能一轮跑完（fillbucket→idle）仍没装到水：计一次失败运行，
        // 6 次诚实收手。旧版只有 attempts（派发被拒才计数、派发成功即清零），
        // 技能每 20 秒正常收场就无限重派——farm33 实证靠 300 秒看门狗才收场。
        if ("fillbucket".equals(skills.currentSkill())) {
            fillSkillActive = true;
        } else if (fillSkillActive) {
            fillSkillActive = false;
            if (++fillFailedRuns >= 6) {
                finish("装水多次接近失败（地形到不了水源）");
                return;
            }
        }
        if (skills.run(new Intent("", "fillbucket", new JsonObject()), "目标")) {
            attempts = 0;
            // 【fix269】同步收场补计：runFillBucket 在「附近没有水源」等结局下
            // 于 run() 调用栈内同步 finish（current 已回 idle），fix74 的跨 tick
            // 采样闩永远采不到「在跑」态、fillFailedRuns 恒 0——fresh139 实证
            // 同点同文 600 次/300 秒、唯一出口是规划层 goal-timeout。派发后
            // 同 tick 直读：已 idle 且 lastOutcome 为 fillbucket 非成功结局
            // → 计一次失败运行（与 fix74 共用计数与 6 次门槛），满 6 次
            // 诚实收手喂规划层装水退避。异步运行（挖路/跟随）收场仍走上方
            // 采样闩：同步收场时闩从未置位，两路互斥不双计。
            if ("idle".equals(skills.currentSkill())) {
                String outcome = skills.lastOutcome();
                if (outcome != null && outcome.startsWith("fillbucket ")
                        && !outcome.contains("装满水桶") && !outcome.contains("已经有水桶")) {
                    if (++fillFailedRuns >= 6) {
                        finish("装水多次失败（附近无水源/接近失败）");
                    }
                }
            }
            return;
        }
        if (++attempts >= 12) {
            finish("装水连续没有进展（附近无水源）");
        }
    }

    /** 【fix100】消费浇铸目标的 descend 结果：只认新出现的结果（水位线），
     * 到达清零、其余 descend 结局计一次失败。
     * 【fix104】水位线改序号制（skills.outcomeSeq）：字符串水位被同文案
     * 结局击穿——farm66 实证 descend 在滴石洞底同地点同死因 1.5 秒一轮，
     * 结局逐字相同、计数永远停在 1，436 次重派热循环复发。 */
    private void noteObsidianOutcome() {
        int seq = skills.outcomeSeq();
        if (seq == handledObsidianSeq) {
            return;
        }
        handledObsidianSeq = seq;
        String outcome = skills.lastOutcome();
        // 【fix105】挖矿/浇铸两条黑曜石路线结局同样消费：有进账清零、无进账
        // 计失败；挖矿主路连败 3 次降级切浇铸兜底，浇铸兜底再连败 3 次
        // 交回规划层（两路都走不通时不许在目标层无限重派）。
        if (outcome.startsWith("mineobsidian ") || outcome.startsWith("castobsidian ")) {
            boolean got = !outcome.contains("未挖到")
                    && (outcome.contains("success=true") || outcome.contains("挖到")
                    || (outcome.contains("mined=") && !outcome.contains("mined=0")));
            if (got) {
                obsidianRouteFails = 0;
                badLavaHints.clear(); // 【fix112】有进账说明地形已变，坏点清账
                return;
            }
            // 【fix112】提示点质量失败（没露面/没落脚点）：拉黑本次派单点，
            // 目标扫描换下一个源——同一坏点不许连烧连败计数（farm74 实证
            // 同点 6 次 3 秒烧光两路）。
            if ((outcome.contains("未挖到") || outcome.contains("没有落脚点"))
                    && lastObsidianHint != null && badLavaHints.add(lastObsidianHint)) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 坏岩浆提示点拉黑 | pos="
                        + lastObsidianHint.toShortString() + " outcome=" + outcome
                        + "（fix112）");
            }
            obsidianRouteFails++;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 黑曜石路线失败计数 | fails="
                    + obsidianRouteFails + " route="
                    + (obsidianRouteCasting ? "casting" : "mining")
                    + " outcome=" + outcome);
            if (!obsidianRouteCasting && obsidianRouteFails >= 3) {
                obsidianRouteCasting = true;
                obsidianRouteFails = 0;
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " obsidian-route | route=mining→casting reason=挖矿路线连败3次，降级浇铸兜底");
            } else if (obsidianRouteCasting && obsidianRouteFails >= 3) {
                // 【fix212】两路连败收场前补销账：本目标真抵达过账本点且
                // 零产出，即该点已找遍无可用岩浆（旧版只有 attempts≥10
                // 一处调用本函数，此收场路径漏记，坏点永不销账）。
                strikeLavaLedgerIfArrivedEmpty();
                // 【fix241】抵达账本点两路连败：先换下一点，账本点
                // 试完才按原口径收场。
                if (!(obsidianLavaArrived
                        && retargetObsidianLavaPoint("到点两路连败"))) {
                    finish("黑曜石失败：挖矿与浇铸两路连败，交回规划层 outcome=" + outcome);
                }
            }
            return;
        }
        if (!outcome.startsWith("descend ")) {
            return;
        }
        if (outcome.contains("到达")) {
            obsidianDescendFails = 0;
            return;
        }
        // 【fix196】礼让收场不计数：分级守卫拒挖是偏好事件、非挖掘失败，
        // 规划层下一 decide 即补造石镐续挖；计入连败会污染路线降级判定。
        if (outcome.contains("礼让收场")) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 浇铸下挖礼让不计数 | outcome=" + outcome
                    + "（fix196）");
            // 【fix215】礼让不计数≠原地重派：守卫状态不变，重派 descend
            // 必然同果（farm189 实证 ~1 秒节律空转 290+ 次、白烧目标
            // 看门狗 300 秒）。置待交接标，本 tick 随即 finish 交规划层
            // 补镐（fix109 对「缺少镐」的同款处置）；已挖深度不丢——
            // 补镐后新目标在当前位置续挖。
            obsidianPickYieldPending = true;
            return;
        }
        obsidianDescendFails++;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 浇铸下挖失败计数 | fails="
                + obsidianDescendFails + " outcome=" + outcome);
        // 【fix109】缺镐类失败不许原地重派凑数：分级守卫拒挖的状态不变，
        // descend 必然 1 秒速败（farm70/71 两度实证 fails 相隔 1 秒、同点
        // 同因），重派几次都不会变，只会白烧连败计数。计数直接顶满，
        // 由 tickObsidian 本 tick 收场交规划器补镐；补完再派时 descend
        // 从当前深度续挖，已挖深度不丢。其他死因维持 3 次计数。
        if (outcome.contains("缺少镐")) {
            obsidianDescendFails = 3;
            LOG.info("[AIV][GOAL] " + npc.npcName()
                    + " 浇铸下挖缺镐速断 | outcome=" + outcome
                    + "（fix109：不原地重派，直接交规划器补镐）");
        }
    }

    /**
     * 【fix213】旅途下地的地面高度：本列 + 8 向 12 格列的高度图
     * （MOTION_BLOCKING_NO_LEAVES）采样取最小。自家恢复栈是单列高柱，
     * 本列高度图就是柱顶（223），看本列等于没下地；邻列才是真地面。
     * 采样全高（真高原）时取最小也无妨——下到邻列地面再赶路同样成立。
     */
    private int tripGroundLevel() {
        net.minecraft.world.level.levelgen.Heightmap.Types type =
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        BlockPos center = npc.blockPosition();
        int min = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int y = npc.level().getHeight(type,
                        center.getX() + dx * 12, center.getZ() + dz * 12);
                if (y < min) {
                    min = y;
                }
            }
        }
        return min == Integer.MAX_VALUE ? center.getY() : min;
    }

    /**
     * 【fix195】账本点落空销账：本目标消费了账本点（抵近过）且整轮找遍
     * 仍无岩浆时，给该点记一次失败（2 次销账，同 OreMemory 纪律）；
     * 只记一次（obsidianLavaStruck 闩），去过点附近才有资格判它落空。
     * 【fix212】抵达凭据改认 obsidianLavaArrived：旧口径在调用时复核
     * 「结束位置距点 ≤32 格」，但抵达后本地搜湖必然漂移（farm182 第一轮
     * 收场漂到 47 格外），且两路连败收场根本不调本函数——坏点 attempts
     * 永远 0、永远最近、永远被选（六轮实证）。真抵达过则免除结束位置
     * 复核；未抵达维持原复核（旅途放弃不销账，fix195d 不变）。
     */
    private void strikeLavaLedgerIfArrivedEmpty() {
        if (obsidianLavaTarget == null || obsidianLavaStruck || !obsidianLavaTripDone) {
            return;
        }
        if (!obsidianLavaArrived) {
            double dx = obsidianLavaTarget.getX() + 0.5D - npc.getX();
            double dz = obsidianLavaTarget.getZ() + 0.5D - npc.getZ();
            if (dx * dx + dz * dz > 32.0D * 32.0D) {
                return;
            }
        }
        obsidianLavaStruck = true;
        LavaMemory.bindWorld(npc.level());
        boolean removed = LavaMemory.noteFailed(npc.npcName(), obsidianLavaTarget);
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 黑曜石岩浆账本点落空 | lava="
                + obsidianLavaTarget.toShortString() + " removed=" + removed
                + "（fix195：到点找遍无岩浆，记一次失败）");
    }

    /**
     * 【fix241】账本选点（本目标口径）：跳过本目标已试过的点，其余同
     * LavaMemory.nearest——优先没被判过落空的条目（attempts==0）取 3D
     * 最近，全有失败记录才回落全体最近。无未试点返回 null。
     * 首次选点时已试集为空，与旧口径结果一字不差。
     */
    private LavaMemory.Entry nearestUntriedLava() {
        LavaMemory.bindWorld(npc.level());
        BlockPos from = npc.blockPosition();
        LavaMemory.Entry best = null;
        double bestSq = Double.MAX_VALUE;
        for (LavaMemory.Entry entry : LavaMemory.entries(npc.npcName())) {
            if (entry.attempts() > 0 || obsidianLavaTried.contains(entry.pos())) {
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
        for (LavaMemory.Entry entry : LavaMemory.entries(npc.npcName())) {
            if (obsidianLavaTried.contains(entry.pos())) {
                continue;
            }
            double sq = entry.pos().distSqr(from);
            if (sq < bestSq) {
                bestSq = sq;
                best = entry;
            }
        }
        return best;
    }

    /**
     * 【fix241】到点落空换点：本目标抵达的账本点找遍无岩浆（调用方已
     * 销账）或两路连败时，若账本里还有本目标没试过的点，复位旅途段
     * 立刻换过去再试；账本点试完才返回 false 让调用方按原口径收场
     * （78 棒正式世界实证：首点落空拉黑后 139.5 秒即收工、深点簇从未
     * 被访——玩家到了假点位是换下一个已知点，不是一趟收工回家）。
     * 换点同时复位路线连败（新点位重新走挖矿主路）与无进展计数。
     */
    private boolean retargetObsidianLavaPoint(String reason) {
        if (nearestUntriedLava() == null) {
            return false;
        }
        BlockPos from = obsidianLavaTarget;
        obsidianLavaTarget = null;
        obsidianLavaTripDone = false;
        obsidianLavaArrived = false;
        obsidianLavaStruck = false;
        obsidianLavaGroundDone = false;
        obsidianLavaDetour = 0;
        obsidianLavaGotoTries = 0;
        obsidianLavaHopInFlight = false;
        obsidianRelocPhase = 0;
        obsidianRelocated = false;
        attempts = 0;
        staleChecks = 0;
        obsidianRouteCasting = false;
        obsidianRouteFails = 0;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 黑曜石账本点换点 | from="
                + (from == null ? "-" : from.toShortString())
                + " reason=" + reason
                + "（fix241：落空销账后立刻换下一点，账本点试完才收场）");
        return true;
    }

    /**
     * 黑曜石（fix105 路线制）：主路=挖矿路线——下潜到岩浆层、水桶浇湖面
     * 成壳、钻石镐挖走（mineobsidian）；浇铸（castobsidian）降为兜底，
     * 挖矿连败 3 次才切换。附近没有岩浆时先阶梯下挖到 Y≈-52 的岩浆层。
     * 无钻镐时两路都产不出黑曜石（浇铸产物同样要钻镐才掉落，硬走浇铸
     * 只会把岩浆源白白转成挖不走的黑曜石块），直接交规划层补钻镐链。
     */
    private void tickObsidian() {
        int have = countItem("obsidian");
        status = "黑曜石 " + have + "/" + targetCount
                + (obsidianRouteCasting ? "（浇铸兜底）" : "（挖矿路线）")
                + (countItem("water_bucket") > 0 ? "（水桶就绪）" : "（需要装水）");
        if (have >= targetCount) {
            finish("黑曜石完成 " + have + "/" + targetCount);
            return;
        }
        if (!hasUsableDiamondPickaxe()) {
            finish("采集黑曜石需要钻石镐");
            return;
        }
        // 【fix200】耐久预演：准入旧口径只查「没断」，第五十六棒 live 钻镐
        // 仅剩 16/1561——挖满 20 块必然中途断镐（断后 finish→补钻链虽能
        // 兜底，但整趟岩浆之旅+已挖存量全押在半路）。真人出发前先看镐
        // 够不够挖完：全部钻镐剩余耐久合计 < 还需块数+4 损耗余量时，
        // 出发前就收场交规划层补钻镐链，不等断在岩浆边。
        int obsidianNeed = targetCount - have;
        int pickLeft = diamondPickRemainingTotal();
        if (pickLeft < obsidianNeed + 4) {
            finish("钻石镐耐久不足（合计剩 " + pickLeft + "，需挖 "
                    + obsidianNeed + " 块黑曜石），先补钻镐");
            return;
        }
        if (!obsidianRouteLogged) {
            obsidianRouteLogged = true;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " obsidian-route | route="
                    + (obsidianRouteCasting ? "casting" : "mining")
                    + " reason=" + (obsidianRouteCasting
                    ? "挖矿路线连败降级" : "有可用钻石镐，挖矿主路"));
        }
        if (countItem("bucket") <= 0 && countItem("water_bucket") <= 0) {
            finish("缺少铁桶（3 个铁锭合成）");
            return;
        }
        // 【fix100】先消费 descend 的技能结果（水位线模式，同 noteStripOutcome）：
        // farm61 三趟浇铸实证——石镐在下楼途中烧到退役后，fix44 分级守卫按
        // 设计拒用高级镐挖石头，descend 1 秒速败；旧版 tickObsidian 不看结果、
        // 每 tick 无条件重派 descend，热循环 900 次直至 300 秒看门狗处决，
        // 规划层的补镐保障步永远等不到目标边界。改：descend 失败计数，
        // 连续 3 次即收场交回规划器（补镐/换区自有既有步处置）；descend
        // 到达或黑曜石进账清零。
        noteObsidianOutcome();
        // 【fix215】礼让待交接：优先于连败收场判定，直接交规划层补镐。
        if (obsidianPickYieldPending) {
            finish("浇铸黑曜石：下挖礼让（镐子分级守卫），交回规划器补镐");
            return;
        }
        if (obsidianDescendFails >= 3) {
            finish("浇铸黑曜石：下挖连续失败（多为石镐烧光待补镐），交回规划器");
            return;
        }
        // 【fix195】岩浆账本旅途段（PORTAL 案 A）：账本里有已知岩浆点且人
        // 不在点位附近时，先分段 goto 到点位再走原流程（在旧湖上空 descend、
        // nearbyLavaSource 命中即派 mineobsidian）；不再从当前位置盲凿竖井
        // 撞全淹洞群（第五十三棒五轮实证）。账本为空/本目标已消费账本时
        // 整段跳过，行为与 fix195 之前完全一致（首探语义不丢兜底）。
        // 【fix195b】旅途段独占口径（staging 定因）：裸 goto 远跳会被截断到
        // 48 格、截断点不可站立时在启动口径直接被拒（run 返回 false）——
        // v1 在派发失败时 fall-through，盲 descend 抢走技能槽整段垄断、
        // 案 A 当场作废。改：旅途未完一律 return 独占本 tick；沿射线 36 格
        // 一跳、整跳被拒改 12 格短跳（trek-to-tree 既有口径）；每跳落地按
        // 实际位移结算（≥8 格清零连败），6 跳无推进或旅途超 240 秒放弃
        // 账本点（记一次失败）走原流程兜底。
        if (!obsidianLavaTripDone) {
            if (obsidianLavaTarget == null) {
                // 【fix195e】启程前先看脚边：24 格内已有岩浆源（哪怕还没入账）
                // 就交原流程就地开挖，绝不舍近求远（staging 实证：NPC 站在
                // 新池边上却被旅途段拉去 500 格外的旧账点）。
                if (nearbyLavaSource(24, 8) != null) {
                    obsidianLavaTripDone = true;
                } else {
                    LavaMemory.Entry known = nearestUntriedLava();
                    if (known == null) {
                        obsidianLavaTripDone = true;
                    } else {
                        double dx = known.x() + 0.5D - npc.getX();
                        double dz = known.z() + 0.5D - npc.getZ();
                        obsidianLavaTarget = known.pos();
                        obsidianLavaTried.add(known.pos()); // 【fix241】本目标已试点，换点时排除
                        if (dx * dx + dz * dz <= 20.0D * 20.0D) {
                            obsidianLavaTripDone = true;
                            obsidianLavaArrived = true; // 【fix212】选点即在点位附近，同抵达
                        } else {
                            obsidianLavaGotoTries = 0;
                            obsidianLavaTripStartMillis = System.currentTimeMillis();
                            obsidianLavaHopX = npc.getX();
                            obsidianLavaHopZ = npc.getZ();
                            obsidianLavaHopInFlight = false;
                            LOG.info("[AIV][GOAL] " + npc.npcName()
                                    + " 黑曜石直奔已知岩浆点 | lava="
                                    + obsidianLavaTarget.toShortString()
                                    + " dist=" + (int) Math.sqrt(dx * dx + dz * dz)
                                    + "（fix195：案 A 账本优先，免盲凿竖井）");
                        }
                    }
                }
            }
            if (obsidianLavaTarget != null && !obsidianLavaTripDone) {
                double dx = obsidianLavaTarget.getX() + 0.5D - npc.getX();
                double dz = obsidianLavaTarget.getZ() + 0.5D - npc.getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq <= 16.0D * 16.0D) {
                    obsidianLavaTripDone = true;
                    obsidianLavaArrived = true; // 【fix212】真抵达，销账资格成立
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " 黑曜石抵达已知岩浆点附近 | lava="
                            + obsidianLavaTarget.toShortString()
                            + "（fix195：交回原流程下潜找湖面）");
                } else if (!"idle".equals(skills.currentSkill())) {
                    return;
                } else if (obsidianLavaHopInFlight) {
                    double mx = npc.getX() - obsidianLavaHopX;
                    double mz = npc.getZ() - obsidianLavaHopZ;
                    obsidianLavaHopInFlight = false;
                    boolean progressed = mx * mx + mz * mz >= 64.0D;
                    obsidianLavaGotoTries = progressed
                            ? 0 : obsidianLavaGotoTries + 1;
                    // 【fix214】无推进一跳即升一级绕行偏角（派跳时生效），
                    // 有推进清零回正射线——沿障带滑动的位移也算真推进，
                    // 绕行方向随级数走、净位移逐轮累积。
                    obsidianLavaDetour = progressed
                            ? 0 : Math.min(obsidianLavaDetour + 1, 4);
                    return;
                } else if (obsidianLavaGotoTries >= 6
                        || System.currentTimeMillis() - obsidianLavaTripStartMillis
                        > 480_000L) {
                    // 【fix195d】旅途放弃不销账：远点走不到只说明本趟放弃，
                    // 湖还在那儿（玩家同理）；销账只发生在到点找遍
                    // （strikeLavaLedgerIfArrivedEmpty），别把真湖冤杀。
                    obsidianLavaTripDone = true;
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " 黑曜石岩浆账本点旅途放弃 | lava="
                            + obsidianLavaTarget.toShortString()
                            + " tries=" + obsidianLavaGotoTries
                            + "（fix195d：多跳无推进/超时，不记失败，走原流程兜底）");
                } else if (!obsidianLavaGroundDone
                        && npc.blockPosition().getY() - obsidianLavaTarget.getY() > 40
                        && npc.blockPosition().getY() > tripGroundLevel() + 10) {
                    // 【fix213】高处先下地：旅途跳点是水平射线，站在恢复
                    // 栈顶（Y=223 一柱之巅）/高台上时射线全是空气——goto
                    // 退到同高 12 格仍是半空，walk-stuck+超时一跳记一次
                    // tries，6 跳全程离不开柱顶（farm187 实证；五/六轮
                    // 栈顶派单同死）。玩家站在高处要去远方，先顺柱下到
                    // 地面再走。一次性子段：descend 到地面高度（采样口径
                    // 见 tripGroundLevel），派发成败都置标交回原派跳逻辑，
                    // 6 跳放弃/480 秒兜底原样保留；descend 结局走既有
                    // noteObsidianOutcome 消费（到达清零 descend 连败账）。
                    obsidianLavaGroundDone = true;
                    int groundY = tripGroundLevel();
                    boolean started = skills.run(
                            new Intent("", "descend", params("y", groundY)), "目标");
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " 黑曜石旅途先下地 | from="
                            + npc.blockPosition().toShortString()
                            + " groundY=" + groundY + " descend=" + started
                            + "（fix213：高处水平射线全是空气，先下到地面再赶路）");
                    return;
                } else {
                    double dist = Math.sqrt(distSq);
                    double hopLen = Math.min(36.0D, dist - 8.0D);
                    // 【fix214】绕行偏角：正射线连撞障带时（(350,-232) 河沟
                    // 带 farm183/188 两度实证：A* 长路径身体走 0~2 格即
                    // 路径中断、同点连环），按级数把跳向旋出正射线，沿带
                    // 找渡口——fix111 找岩浆走廊的同款手法接到旅途跳上。
                    double ux = dx / dist;
                    double uz = dz / dist;
                    if (obsidianLavaDetour > 0) {
                        double[] detourAngles = {Math.PI / 4.0D, -Math.PI / 4.0D,
                                Math.PI / 2.0D, -Math.PI / 2.0D};
                        double ang = detourAngles[
                                Math.min(obsidianLavaDetour - 1, 3)];
                        double cos = Math.cos(ang);
                        double sin = Math.sin(ang);
                        double rx = ux * cos - uz * sin;
                        uz = ux * sin + uz * cos;
                        ux = rx;
                        LOG.info("[AIV][GOAL] " + npc.npcName()
                                + " 黑曜石旅途绕行跳 | detour=" + obsidianLavaDetour
                                + "（fix214：正射线无推进，偏角沿障带找渡口）");
                    }
                    boolean started = false;
                    if (hopLen > 4.0D) {
                        JsonObject params = new JsonObject();
                        params.addProperty("x", npc.getX() + ux * hopLen);
                        params.addProperty("z", npc.getZ() + uz * hopLen);
                        started = skills.run(new Intent("", "goto", params), "目标");
                    }
                    if (!started) {
                        JsonObject hop = new JsonObject();
                        hop.addProperty("x", npc.getX() + ux * 12.0D);
                        hop.addProperty("z", npc.getZ() + uz * 12.0D);
                        started = skills.run(new Intent("", "goto", hop), "目标");
                    }
                    if (started) {
                        obsidianLavaHopX = npc.getX();
                        obsidianLavaHopZ = npc.getZ();
                        obsidianLavaHopInFlight = true;
                    } else {
                        obsidianLavaGotoTries++;
                    }
                    return;
                }
            }
        }
        // 【fix124】换区编排（回地表→地面换位→原流程 descend 自然接管）：
        // phase 1 等 ascend 把人带出地下（Y>-10 视为已回地表层）；phase 2
        // 沿走廊方向地面 goto 40 格、实走 ≥30 格算换位完成。两段都只在
        // 技能空闲时推进（tick 入口已挡技能忙），失败有次数上限、超限回
        // 落原流程就地续搜，绝不把目标钉死在换区里。
        if (obsidianRelocPhase == 1) {
            if (npc.blockPosition().getY() > -10) {
                obsidianRelocPhase = 2;
                obsidianRelocFromX = npc.getX();
                obsidianRelocFromZ = npc.getZ();
                obsidianRelocGotoTries = 0;
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " 黑曜石换区 | 已回地表，沿走廊方向地面换位 40 格（fix124）");
            } else {
                if (obsidianRelocAscendTries >= 5) {
                    obsidianRelocPhase = 0;
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " 黑曜石换区 | ascend 五试未回地表，放弃换区就地续搜（fix124）");
                } else {
                    obsidianRelocAscendTries++;
                    skills.run(new Intent("", "ascend", new JsonObject()), "目标");
                    return;
                }
            }
        }
        if (obsidianRelocPhase == 2) {
            double rdx = npc.getX() - obsidianRelocFromX;
            double rdz = npc.getZ() - obsidianRelocFromZ;
            if (rdx * rdx + rdz * rdz >= 30.0D * 30.0D) {
                obsidianRelocPhase = 0;
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " 黑曜石换区 | 地面换位完成，重新下井续搜（fix124）");
            } else if (obsidianRelocGotoTries >= 3) {
                obsidianRelocPhase = 0;
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " 黑曜石换区 | 地面换位三试不动，就地重新下井（fix124）");
            } else {
                obsidianRelocGotoTries++;
                if (Double.isNaN(lavaSearchDir)) {
                    lavaSearchDir = java.util.concurrent.ThreadLocalRandom.current()
                            .nextDouble() * Math.PI * 2.0D;
                }
                JsonObject params = new JsonObject();
                params.addProperty("x", obsidianRelocFromX + Math.cos(lavaSearchDir) * 40.0D);
                params.addProperty("z", obsidianRelocFromZ + Math.sin(lavaSearchDir) * 40.0D);
                skills.run(new Intent("", "goto", params), "目标");
                return;
            }
        }
        // 先到地下层：地表附近常常找不到水（草原/沙漠/平原），
        // 但 Y=-50 附近既有岩浆湖（浇铸材料）也有地下水（装水），
        // 一次 descend 解决两个前置条件。
        // 【fix195c】脚边已有岩浆源就不下潜：地表岩浆池（天然的或账本
        // 直奔抵达的）就在身边时玩家直接开挖，不会先挖口井下去再爬回来；
        // 深湖场景 nearby 恒空、下潜口径不变。
        // 【fix223】已抵达账本点（fix212 obsidianLavaArrived）时潜到点位
        // 自身的 Y：浅点 (313,18,-215) 的湖在 Y=18，旧口径无条件潜 -52
        // 整整潜过头 70 格、井底扫描恒空（farm197 实证 27 分钟零进展）。
        // 门槛 Y > diveY+7 在默认支与旧值 -45 恒等，未抵达/旅途放弃/
        // 就地开挖诸路径行为一字不改。
        int diveY = (obsidianLavaArrived && obsidianLavaTarget != null)
                ? obsidianLavaTarget.getY() : -52;
        if (npc.blockPosition().getY() > diveY + 7
                && nearbyLavaSource(24, 8) == null) {
            JsonObject params = params("y", diveY);
            if (skills.run(new Intent("", "descend", params), "目标")) {
                return;
            }
        }
        if (countItem("water_bucket") <= 0) {
            skills.run(new Intent("", "fillbucket", new JsonObject()), "目标");
            return;
        }
        // 技能空闲时的无进展判定：浇铸可能因为岩浆被方块挡住而反复失败。
        if (have == lastProgress) {
            if (++staleChecks >= 12) {
                // 【fix241】抵达账本点后连续无进展：先换下一点，
                // 账本点试完才按原口径收场。
                if (obsidianLavaArrived
                        && retargetObsidianLavaPoint("到点连续无进展")) {
                    return;
                }
                finish("浇铸黑曜石连续没有进展 " + have + "/" + targetCount);
                return;
            }
        } else {
            lastProgress = have;
            staleChecks = 0;
            obsidianDescendFails = 0;
            obsidianRouteFails = 0;
        }

        BlockPos lava = nearbyLavaSource(24, 8);
        if (lava == null && !obsidianRouteCasting) {
            // 【fix243】全池浇水成壳后，扫描只认岩浆源会判「附近无岩浆」走
            // fix111 换位/换区，刚成形的黑曜石被弃在原地（83 棒实验场 83o
            // 实证：浇水 2 秒后目标当即换位走人、4 块黑曜石成孤儿）。成形
            // 黑曜石在场时改派 mineobsidian 回收（技能层无岩浆时转挖成形
            // 黑曜石，见 SkillRunner fix243）。
            BlockPos formed = scanNearbyObsidian(24, 8);
            if (formed != null) {
                JsonObject params = params("x", formed.getX(), "y", formed.getY(), "z", formed.getZ());
                params.addProperty("count", targetCount - have);
                if (skills.run(new Intent("", "mineobsidian", params), "目标")) {
                    attempts = 0;
                    return;
                }
            }
        }
        if (lava != null) {
            // 【fix212】派单提示点优先露面源：nearbyLavaSource 取的是任意
            // 源方块最近者——湖底覆盖块能以 0.2 格险胜湖面块（farm182
            // 第一轮实证：提示点 (472,-60,-245)、湖面在其上方 6 格），而
            // mineobsidian 绕提示点的露面扫描只有 ±6/±2，湖面结构性看
            // 不见、当场判无露面。派单要对着湖面派：露面源（未被拉黑）
            // 优先，nearbyLavaSource 的记账副作用已在上行发生、不受影响。
            BlockPos exposed = scanExposedLava(24, 8);
            if (exposed != null && !badLavaHints.contains(exposed)) {
                lava = exposed;
            }
            lastObsidianHint = lava; // 【fix112】记下派单点，失败时拉黑用
            JsonObject params = params("x", lava.getX(), "y", lava.getY(), "z", lava.getZ());
            boolean started;
            if (obsidianRouteCasting) {
                started = skills.run(new Intent("", "castobsidian", params), "目标");
            } else {
                params.addProperty("count", targetCount - have);
                started = skills.run(new Intent("", "mineobsidian", params), "目标");
            }
            if (started) {
                attempts = 0;
                return;
            }
        }
        attempts++;
        if (attempts >= 10) {
            strikeLavaLedgerIfArrivedEmpty();
            // 【fix241】抵达账本点且找遍无岩浆：先换下一点，账本点
            // 试完才走下面的换区/收场口径。
            if (obsidianLavaArrived
                    && retargetObsidianLavaPoint("到点找遍无岩浆")) {
                return;
            }
            // 【fix124】第一次找遍无岩浆不直接判败：井底若是封闭口袋，
            // 走廊 goto 目标全不可站立、原地磨到判败（farm87 实证）。先
            // 回地表换位再下井（玩家口径），换过一次仍找不到才诚实收场。
            if (!obsidianRelocated && npc.blockPosition().getY() <= -45) {
                obsidianRelocated = true;
                obsidianRelocPhase = 1;
                obsidianRelocAscendTries = 0;
                attempts = 0;
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " 黑曜石换区 | 井底找不到岩浆源，回地表换位再下井（fix124）"
                        + " pos=" + npc.blockPosition().toShortString());
                return;
            }
            finish("找不到岩浆源，黑曜石失败 " + have + "/" + targetCount);
            return;
        }
        wanderForLava();
    }

    /**
     * 【fix110】找岩浆源专用换位：扫描半径 24 格时，通用 wander 的 4–9 格
     * 小跳与上次扫描重叠约九成，干岩区里挪不动（farm72 实证：Y=-52 随机
     * 挪窝 5 分钟、attempts 没攒满先被目标看门狗处决）。
     * 【fix111】随机方向仍不行：farm73 实证大跳虽获接受、方向随机互相
     * 抵消，整轮 344 秒净位移约 2 格。改走廊式：本目标定一个方向
     * （start 时重置、新目标换方向等效换区），每跳沿该方向 18 格，
     * 正前方走不通依次试 ±45°、±90°，只有反向跳成功才把走廊翻转。
     */
    private void wanderForLava() {
        if (Double.isNaN(lavaSearchDir)) {
            lavaSearchDir = java.util.concurrent.ThreadLocalRandom.current()
                    .nextDouble() * Math.PI * 2.0D;
        }
        double[] offsets = {0.0D, Math.PI / 4.0D, -Math.PI / 4.0D,
                Math.PI / 2.0D, -Math.PI / 2.0D, Math.PI};
        for (double offset : offsets) {
            double angle = lavaSearchDir + offset;
            JsonObject params = new JsonObject();
            params.addProperty("x", npc.getX() + Math.cos(angle) * 18.0D);
            params.addProperty("z", npc.getZ() + Math.sin(angle) * 18.0D);
            if (skills.run(new Intent("", "goto", params), "目标")) {
                if (Math.abs(offset) >= Math.PI / 2.0D) {
                    lavaSearchDir = angle;
                }
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " 附近没有岩浆源，沿走廊换位 accepted=true 距离=18.0"
                        + " 方向=" + String.format(Locale.ROOT, "%.2f", lavaSearchDir)
                        + "（fix111）");
                return;
            }
        }
        wander();
    }

    /**
     * 【fix313】进门前站位接近：先把身体送到门前站位，再派 enterportal。
     *
     * <p>为什么单独立这一段：enterportal 技能是平地走位——两段 walk 直指
     * staging/门洞中心，A* 单步 +1、无竖直手段。门建在台面上、接近侧是
     * 低 5 格的水洼时（163 实验室 C1＋164 生产 E3 双实证），身体恒在水面层
     * 磨满 600 tick×多轮，目标以「多次走进传送门仍未传送」收场：振荡的是
     * 走位目标，身体从未到过门所在层。玩家的做法是先爬/垫到门前平台再
     * 平走进门；现成的 moveToStand 编排（ascend/descend 竖直对齐＋travel
     * 搭桥＋goto 收尾）各接近链都在用，唯独进门前没用——本方法把它接上。
     *
     * <p>站位选择：staging（fix261 同高侧格）优先；staging 为 null（门框
     * 悬空、四周同层无可站格，165 实验室实证）时用门洞底行格本身——底框
     * 就是门内的地板，PathFinder 口径该格可站。就位判据：脚格已在门内，
     * 或与站位同层（|dy|≤1）且水平距站位中心 ≤1.2，或已在门洞底格同层
     * ≤2.2 内（平地异侧直入，技能两段走本就胜任，不绕路）。
     *
     * <p>接近动作按距离/同层分流护门框（v1 教训：无差别 moveToStand 时
     * travel 爬上门框顶、descend 挖掉框柱 3 块黑曜石把门拆了）：同层只
     * goto；异层远距（>2.5）才 moveToStand 竖直对齐；异层贴门先 goto
     * 集结格拉开距离。详见方法内注释。
     *
     * @return true＝已就位（或无站位可接近、回落旧直指行为不堵死），
     *         调用方继续派 enterportal；false＝已派接近动作，本 tick 返回。
     */
    private boolean approachPortal(BlockPos staging, BlockPos bottomCell) {
        if (npc.level().getBlockState(npc.blockPosition()).is(Blocks.NETHER_PORTAL)) {
            portalApproachStuck = 0;
            portalApproachStand = null;
            portalApproachBestDist = Double.MAX_VALUE;
            return true;
        }
        if (staging != null && atStandLevel(staging, 1.2D)) {
            portalApproachStuck = 0;
            portalApproachStand = null;
            portalApproachBestDist = Double.MAX_VALUE;
            return true;
        }
        if (bottomCell != null && atStandLevel(bottomCell, 2.2D)) {
            portalApproachStuck = 0;
            portalApproachStand = null;
            portalApproachBestDist = Double.MAX_VALUE;
            return true;
        }
        BlockPos stand = staging != null ? staging : bottomCell;
        // 【fix354】staging 全灭时（沟谷崖边门：同高侧格皆不 standable，
        // 266 棒生产探针证实四格全灭），改取门洞侧格垫脚站位供 moveToStand
        // 垫脚爬到门洞层；无此种格回落底格（旧行为，不堵死）。
        BlockPos pillarStand = staging == null ? portalPillarStand(bottomCell) : null;
        if (pillarStand != null) {
            stand = pillarStand;
        }
        if (stand == null) {
            return true;
        }
        if (portalApproachStand == null || !portalApproachStand.equals(stand)) {
            portalApproachStand = stand.immutable();
            portalApproachStuck = 0;
            portalApproachBestDist = Double.MAX_VALUE;
            portalPillarBaseGotoActive = false;
            portalPillarClimbActive = false;
            if (pillarStand != null) {
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " [PORTAL] staging 全灭，改取垫脚站位（fix354） | "
                        + pillarStand.toShortString());
            }
        }
        double sdx = npc.getX() - (stand.getX() + 0.5D);
        double sdz = npc.getZ() - (stand.getZ() + 0.5D);
        double sdy = npc.getY() - stand.getY();
        double standDist = Math.sqrt(sdx * sdx + sdz * sdz + sdy * sdy);
        if (standDist < portalApproachBestDist - 0.5D) {
            portalApproachBestDist = standDist;
            portalApproachStuck = 0;
        } else {
            portalApproachStuck++;
        }
        // 【fix313】按与门洞的距离/同层分流，核心是护住门框：moveToStand 的
        // ascend/descend 会挖、travel 会搭且会爬高——165 实验场 v1 实证：
        // travel 把身体送上门框顶、descend 顺框柱挖掉 3 块黑曜石、门消失。
        // ①同层：只许 goto 平走（不挖不搭），远近都一样；
        // ②异层且水平距门洞底格 >2.5：才许 moveToStand 竖直对齐（挖/搭都
        // 发生在离框几格外，如岸边土柱）；
        // ③异层且贴门（≤2.5，如门正下方的水洼）：先 goto 到同层集结格拉开
        // 距离，下一 tick 走 ②；找不到集结格才回落 moveToStand（不堵死）。
        // goto 分支久攻不下（连卡 ≥12）回落 enterportal 技能直指（旧行为），
        // 目标仍由 attempts 上限收场，不无限耗。
        boolean atLevel = Math.abs(npc.getY() - stand.getY()) <= 1.0D;
        double bdx = bottomCell == null ? 99.0D
                : npc.getX() - (bottomCell.getX() + 0.5D);
        double bdz = bottomCell == null ? 0.0D
                : npc.getZ() - (bottomCell.getZ() + 0.5D);
        double bottomDist = Math.sqrt(bdx * bdx + bdz * bdz);
        // 【fix355】垫脚站位先平移到柱底再爬：staging 全灭时站位为 portalPillarStand，
        // 其下方立柱通空到身体层，设计意图是沿柱垫脚直上站位；但 moveToStand 的
        // ascend 原地爬升（267 棒生产实证：NPC 在 13 格外爬到门洞层 dy=-0.82 后
        // 水平腿跨沟坠落入水、goto 超时、约 45 秒一循环，(A) 11 次），故先 goto
        // 柱底（与身体同层）再爬。柱底水平距 ≤1.0 即视为已就位，走原 moveToStand
        // 编排；久攻不下（连卡 ≥12）回落 enterportal 技能直指（旧行为，不堵死）。
        if (pillarStand != null && !atLevel) {
            double pdx = npc.getX() - (pillarStand.getX() + 0.5D);
            double pdz = npc.getZ() - (pillarStand.getZ() + 0.5D);
            if (pdx * pdx + pdz * pdz > 1.0D) {
                if (portalApproachStuck >= 12) {
                    return true;
                }
                if (!portalPillarBaseGotoActive) {
                    LOG.info("[AIV][GOAL] " + npc.npcName()
                            + " [PORTAL] 垫脚站位先平移到柱底（fix355） | "
                            + pillarStand.toShortString());
                    portalPillarBaseGotoActive = true;
                }
                JsonObject gp = new JsonObject();
                gp.addProperty("x", pillarStand.getX() + 0.5D);
                gp.addProperty("z", pillarStand.getZ() + 0.5D);
                skills.run(new Intent("", "goto", gp), "目标");
                status = "进门前站位：先平移到垫脚柱底（fix355） | stand=" + pillarStand.toShortString();
                return false;
            }
            // 【fix359】已在柱底水平位置（pdx²+pdz² ≤ 1.0）时走沿柱直上：
            // goto 柱底是 no-op（273 棒生产定谳：NPC 首 tick 即在垫脚柱正
            // 下方沟底，水平距柱心仅约 0.7），moveToStand 的 ascend 又只爬
            // 就近东坡（267 失败复现）。柱心 x/z 为竖直轴，pillarclimb 复用
            // ore-reach pillar 的跳搭节奏逐格垫脚上行至站位高度；远处仍先
            // goto 柱底、下一 tick 走本分支，不再走通用 ascend。连卡 ≥12
            // 回落 enterportal 技能直指（旧行为，不堵死）。
            if (portalApproachStuck >= 12) {
                return true;
            }
            if (!portalPillarClimbActive) {
                LOG.info("[AIV][GOAL] " + npc.npcName()
                        + " [PORTAL] 沿柱直上（fix359） | stand=" + pillarStand.toShortString()
                        + " 当前Y=" + Math.round(npc.getY()));
                portalPillarClimbActive = true;
            }
            JsonObject cp = new JsonObject();
            cp.addProperty("x", pillarStand.getX() + 0.5D);
            cp.addProperty("z", pillarStand.getZ() + 0.5D);
            cp.addProperty("y", pillarStand.getY());
            skills.run(new Intent("", "pillarclimb", cp), "目标");
            status = "进门前站位：沿柱直上（fix359） | stand=" + pillarStand.toShortString();
            portalPillarBaseGotoActive = false;
            return false;
        }
        // 【fix370】ascend 连续 12 轮无上升（mtsNoRiseFails≥12）回落 enterportal 技能直指：
        // 旧版只在 goto 分支查连卡，moveToStand 分支（bottomDist>2.5）永不回落——
        // 313 棒生产实证：NPC 站在床上 7 格低于站位时 ascend 6 连败×624 轮、46 分钟
        // 进不了门，enterportal 技能一次都没开始。mtsNoRiseFails 只在 ascend 整轮
        // 净升<1 时累计、上升即清零，正常爬升恒为 0，不会误触（portalApproachStuck
        // 按 tick 计，正常挖矿停顿 0.6 秒即达 12，不可用作此口径）。回落即走旧直指
        // 行为，技能自带 staging 停滞回落（200 tick 无位移→直指门洞）＋600 tick
        // 超时＋目标层 4 次尝试上限（多次走进传送门仍未传送即收场），不堵死。
        if (mtsNoRiseFails >= 12) {
            return true;
        }
        if (!atLevel && bottomDist > 2.5D) {
            status = "先进门前站位 | stand=" + stand.toShortString()
                    + " 连卡=" + portalApproachStuck;
            moveToStand(stand, portalApproachStuck, "[PORTAL]");
            return false;
        }
        BlockPos gotoTarget = stand;
        String how = "近距 goto";
        if (!atLevel && bottomCell != null) {
            BlockPos rally = findPortalRallyCell(bottomCell);
            if (rally != null) {
                gotoTarget = rally;
                how = "先到集结格拉开与门距离";
            } else {
                status = "先进门前站位（无集结格回落编排） | stand="
                        + stand.toShortString() + " 连卡=" + portalApproachStuck;
                moveToStand(stand, portalApproachStuck, "[PORTAL]");
                return false;
            }
        }
        JsonObject gp = new JsonObject();
        gp.addProperty("x", gotoTarget.getX() + 0.5D);
        gp.addProperty("z", gotoTarget.getZ() + 0.5D);
        skills.run(new Intent("", "goto", gp), "目标");
        status = "先进门前站位（" + how + "） | stand=" + stand.toShortString()
                + " goto=" + gotoTarget.toShortString()
                + " 连卡=" + portalApproachStuck;
        return false;
    }

    /**
     * 【fix313】集结格：身体贴在门洞正下方/侧下方（异层近距）时，先退到的
     * 同层可站格——与门洞底格水平距 2.5–7、与身体同层 ±1、离身体最近。
     * 退到这里之后竖直对齐的挖/搭都发生在离门框几格外，不伤门。
     */
    private BlockPos findPortalRallyCell(BlockPos bottomCell) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-8, -1, -8), center.offset(8, 1, 8))) {
            if (Math.abs(pos.getY() - center.getY()) > 1) {
                continue;
            }
            double dx = pos.getX() - (double) bottomCell.getX();
            double dz = pos.getZ() - (double) bottomCell.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 2.5D || d > 7.0D) {
                continue;
            }
            if (!PathFinder.isStandable(npc.level(), pos)) {
                continue;
            }
            double score = center.distSqr(pos);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 与站位格同层（|feetY−standY|≤1）且水平距其中心在 radius 内。 */
    private boolean atStandLevel(BlockPos stand, double radius) {
        if (Math.abs(npc.getY() - stand.getY()) > 1.0D) {
            return false;
        }
        double dx = npc.getX() - (stand.getX() + 0.5D);
        double dz = npc.getZ() - (stand.getZ() + 0.5D);
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * 建造并点燃下界传送门，然后走进去。
     *
     * <p>分阶段而不是一口气做完：建门是 10 格方块 + 点火的动画过程，
     * 中间要重新读世界确认门真的亮了；进门后由基类的维度切换检查结束目标。
     */
    private void tickPortal() {
        switch (portalPhase) {
            case 0 -> {
                // 【fix361·277 棒】重燃旧框：附近有已灭的完整黑曜石框时先重燃，
                // 不走新建流程（0 黑曜石消耗，只需打火石）。框体以
                // findUnlitPortalFrame() 实测为准；buildportal 带 relight=1
                // 只点火、不补框。无框时落下方新建流程。
                PortalSite relight = findUnlitPortalFrame();
                if (relight != null && countItem("flint_and_steel") > 0) {
                    portalOrigin = relight.origin();
                    portalAlongX = relight.alongX();
                    JsonObject relightParams = params("x", portalOrigin.getX(), "y", portalOrigin.getY(),
                            "z", portalOrigin.getZ(), "axis", portalAlongX ? "x" : "z", "relight", 1);
                    if (skills.run(new Intent("", "buildportal", relightParams), "目标")) {
                        portalPhase = 1;
                        status = "重燃旧传送门（fix361） origin=" + portalOrigin.toShortString()
                                + " axis=" + (portalAlongX ? "x" : "z");
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " 重燃旧传送门（fix361） | origin="
                                + portalOrigin.toShortString() + " axis=" + (portalAlongX ? "x" : "z"));
                    }
                    return;
                }
                // 【fix362·280 棒】单格缺损补框重燃：框体仅缺 1 格、内腔全空气
                // 且包里有黑曜石时，补 1 格后打火石重燃（玩家最简路线，省
                // 约 9 块黑曜石浇铸）；无黑曜石时落下方新建流程（浇铸得料后
                // 下次再判）。缺损 ≥2 格不补，落浇铸链。
                PortalSite repair = findRepairablePortalFrame();
                if (repair != null && countItem("flint_and_steel") > 0 && countItem("obsidian") >= 1) {
                    portalOrigin = repair.origin();
                    portalAlongX = repair.alongX();
                    JsonObject repairParams = params("x", portalOrigin.getX(), "y", portalOrigin.getY(),
                            "z", portalOrigin.getZ(), "axis", portalAlongX ? "x" : "z", "relight", 1);
                    if (skills.run(new Intent("", "buildportal", repairParams), "目标")) {
                        portalPhase = 1;
                        status = "补框重燃（fix362） origin=" + portalOrigin.toShortString()
                                + " axis=" + (portalAlongX ? "x" : "z");
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " 补框重燃（fix362） | origin="
                                + portalOrigin.toShortString() + " axis=" + (portalAlongX ? "x" : "z"));
                    }
                    return;
                }
                if (countItem("obsidian") < 10) {
                    finish("黑曜石不足 10，无法建门");
                    return;
                }
                if (countItem("flint_and_steel") <= 0) {
                    finish("缺少打火石，无法点燃传送门");
                    return;
                }
                // 【fix96·审计B】有落脚点账本时门建在锚点附近：浇铸收工点在
                // Y≈-52 岩浆湖边，就地选址门必然建在地下湖边（回程落点深、
                // 下界侧贴岩浆海）；玩家是背黑曜石回家门口建门。照 fix82
                // 先就位后选位：距锚点水平 >16 格先 moveToStand 回锚点
                // （竖直对齐/travel 编排由它管），到位后选址中心自然落在锚点一带。
                BaseMemory.bindWorld(npc.level());
                BaseMemory.Base portalBase = BaseMemory.base(npc.npcName());
                if (portalBase != null) {
                    BlockPos anchor = portalBase.anchor();
                    double dxa = npc.getX() - (anchor.getX() + 0.5D);
                    double dza = npc.getZ() - (anchor.getZ() + 0.5D);
                    if (dxa * dxa + dza * dza > 16.0D * 16.0D) {
                        status = "先回落脚点附近建门 | 锚点=" + anchor.toShortString();
                        moveToStand(anchor, 99, "[PORTAL]");
                        return;
                    }
                }
                PortalSite site = findPortalSite();
                if (site == null) {
                    attempts++;
                    if (attempts >= 8) {
                        finish("附近找不到 4x5 的空地建门");
                        return;
                    }
                    wander();
                    return;
                }
                portalOrigin = site.origin();
                portalAlongX = site.alongX();
                JsonObject params = params("x", portalOrigin.getX(), "y", portalOrigin.getY(),
                        "z", portalOrigin.getZ(), "axis", portalAlongX ? "x" : "z");
                if (skills.run(new Intent("", "buildportal", params), "目标")) {
                    portalPhase = 1;
                    status = "建造传送门 origin=" + portalOrigin.toShortString()
                            + " axis=" + (portalAlongX ? "x" : "z");
                }
            }
            case 1 -> {
                // 能进到这里说明建造技能已经结束；用世界状态确认门真的点亮了。
                if (portalOrigin == null) {
                    finish("传送门选址丢失");
                    return;
                }
                BlockPos inside = interiorPosition(portalOrigin, portalAlongX, 1, 1);
                if (npc.level().getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
                    portalPhase = 2;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 传送门已点亮，准备进入");
                } else {
                    finish("建门失败：" + skills.lastOutcome());
                }
            }
            case 2 -> {
                if (portalOrigin == null) {
                    finish("传送门选址丢失");
                    return;
                }
                // 【fix96·审计C】进门执行层口粮兜底（规划层同口径软闸在前）。
                if (!inNetherDim() && !netherFoodGateOk()) {
                    finish("进门中止：口粮不足（入境软闸 food>=14 且 nutrition>=20）");
                    return;
                }
                // 【fix93·D8】自建门走进去之前同样过金装闸（主世界侧）。
                if (!inNetherDim() && !ensureGoldBootsWorn()) {
                    finish("进门中止：包里没有金靴（D8 下界须穿一件金装）");
                    return;
                }
                double x = portalAlongX ? portalOrigin.getX() + 2.0D : portalOrigin.getX() + 0.5D;
                double z = portalAlongX ? portalOrigin.getZ() + 0.5D : portalOrigin.getZ() + 2.0D;
                double y = portalOrigin.getY() + 1.0D;
                JsonObject params = params("x", x, "y", y, "z", z);
                // 【fix261】带上同高 staging 格，技能先绕到可平走入的那一侧。
                BlockPos bottomA = interiorPosition(portalOrigin, portalAlongX, 1, 1);
                BlockPos bottomB = interiorPosition(portalOrigin, portalAlongX, 2, 1);
                BlockPos staging = portalStagingCell(java.util.List.of(bottomA, bottomB),
                        portalAlongX, false);
                // 【fix313】先进门前站位：身体没到门所在层时先 moveToStand
                // 接近（ascend/travel/goto 编排），就位后才派平地走位技能。
                BlockPos bottomNear = npc.blockPosition().distSqr(bottomA)
                        <= npc.blockPosition().distSqr(bottomB) ? bottomA : bottomB;
                if (!approachPortal(staging, bottomNear)) {
                    return;
                }
                if (staging != null) {
                    params.addProperty("sx", staging.getX() + 0.5D);
                    params.addProperty("sy", (double) staging.getY());
                    params.addProperty("sz", staging.getZ() + 0.5D);
                }
                if (skills.run(new Intent("", "enterportal", params), "目标")) {
                    portalPhase = 3;
                    status = "走进传送门";
                }
            }
            case 3 -> {
                // 穿过门后基类会先因维度变化结束目标；能走到这里说明这次没传送成功。
                enterAttempts++;
                if (enterAttempts >= 4) {
                    finish("多次走进传送门仍未传送");
                    return;
                }
                portalPhase = 2;
            }
            default -> finish("portal 阶段异常 " + portalPhase);
        }
    }

    /**
     * 走进附近已经点亮的传送门。
     *
     * <p>为什么单独做一个目标：门可能是上一轮建好的、也可能是走路时偶然发现的；
     * 玩家看到门的第一反应就是走进去，而不是再造一座。真正搬运身体的工作交给
     * {@code enterportal} 技能（持续贴门 + 维度变化检测），这里只负责重试和判定。
     */
    /** 当前是否身处下界维度（与规划层 inNether 同口径）。 */
    private boolean inNetherDim() {
        return npc.level().dimension().identifier().toString().contains("the_nether");
    }

    /** 【fix96·审计C】入境口粮软闸的执行层口径（与规划层同值）：饱食条与随身营养双过线。 */
    private boolean netherFoodGateOk() {
        return npc.getFoodData().getFoodLevel() >= 14 && foodNutrition() >= 20;
    }

    /**
     * 【fix93·D8】进门前把金靴换到脚上：主世界侧 AutoArmor 按防御分穿
     * 最好的靴子，金靴（防御 1）永远轮不到——进下界前必须强制换装，
     * 换不出来（包里没有金靴）就中止进门，宁可不进也不许裸装进下界
     * 被猪灵围殴。下界内回程进门不拦（方向相反，且金靴本就该在脚上）。
     */
    private boolean ensureGoldBootsWorn() {
        net.minecraft.world.entity.EquipmentSlot feet =
                net.minecraft.world.entity.EquipmentSlot.FEET;
        if (npc.getItemBySlot(feet).is(net.minecraft.world.item.Items.GOLDEN_BOOTS)) {
            // 【fix261】已穿金靴也刷新保护窗：进门尝试可能连续多轮，
            // 窗要覆盖到传送发生的瞬间。
            npc.protectGoldBoots();
            return true;
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(net.minecraft.world.item.Items.GOLDEN_BOOTS)) {
                ItemStack old = npc.getItemBySlot(feet);
                npc.setItemSlot(feet, stack);
                npc.getInventory().setItem(slot, old);
                // 【fix261】换穿即开保护窗：生存层 AutoArmor 每约 1.5 秒按
                // 防御分选靴，无窗时金靴约 1 秒就被换回（111/112 棒实证）。
                npc.protectGoldBoots();
                LOG.info("[AIV][GOAL] " + npc.npcName() + " [NETHER] 进门前换上金靴 | 替换="
                        + (old.isEmpty() ? "空" : old.getItem().toString()));
                return true;
            }
        }
        return false;
    }

    /**
     * 【fix261】进门 staging 格：门洞底行各列、门面两侧、与门洞地板同高
     * （feet＝门洞底行格的 y，即站在底框行顶面）的可站格，按 NPC 当前
     * 所在侧优先、同侧近者优先。进门技能先走到 staging 格再平走入门洞：
     * 直指门洞中心点时，接近侧地面比门槛低 2 格就无合法路径（身体单步
     * +1、+1 中间格是框体），身体冻在框脚把技能期耗光（fresh123 生产
     * 实证）；有同高侧格时玩家也是绕到那一侧平走进去。无可站格返回
     * null，调用方不传 staging、技能回落旧直指行为（不堵死）。
     *
     * @param bottoms   门洞底行格（1..4 个）
     * @param faceAlongZ 门面法向沿 Z（门面沿 X 时 true）；法向沿 X 时 false
     * @param allFaces  底行只有一格、无从判断门面方向时两侧都试
     */
    private BlockPos portalStagingCell(java.util.List<BlockPos> bottoms,
                                        boolean faceAlongZ, boolean allFaces) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos b : bottoms) {
            int[][] offs = allFaces
                    ? new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}
                    : (faceAlongZ ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}});
            for (int[] o : offs) {
                BlockPos cell = b.offset(o[0], 0, o[1]);
                if (!PathFinder.isStandable(npc.level(), cell)) {
                    continue;
                }
                // NPC 所在侧优先：staging 格相对门面的法向符号与 NPC 相对
                // 门面位置同号者不加罚；跨侧者重罚（先绕侧本身也要成本）。
                double penalty = 0.0D;
                if (!allFaces) {
                    double npcSide = faceAlongZ ? npc.getZ() - (b.getZ() + 0.5D)
                            : npc.getX() - (b.getX() + 0.5D);
                    double cellSide = faceAlongZ ? o[1] : o[0];
                    if (npcSide * cellSide < 0) {
                        penalty = 10000.0D;
                    }
                }
                double score = penalty + npc.blockPosition().distSqr(cell);
                if (score < bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
        }
        return best;
    }

    /**
     * 【fix354】门洞侧格垫脚站位：staging 全灭（沟谷崖边门：门洞底行同高
     * 侧格皆不 standable，266 棒生产探针证实四格全灭）时，取门洞底行格
     * 水平四邻中为空气、且下方立柱通空到身体所在层的格，供 moveToStand
     * 垫脚爬到门洞层后再平走入门洞。沿门长向的邻格必为门洞/框柱（非空
     * 气），空气检查天然排除，无需传门轴向。无此种格返回 null，调用方
     * 回落底格（旧行为，不堵死）。
     */
    private BlockPos portalPillarStand(BlockPos bottomCell) {
        if (bottomCell == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offs) {
            BlockPos cell = bottomCell.offset(o[0], 0, o[1]);
            if (!npc.level().getBlockState(cell).isAir()) {
                continue;
            }
            boolean columnClear = true;
            for (int y = cell.getY() - 1; y >= npc.blockPosition().getY(); y--) {
                if (!npc.level().getBlockState(
                        new BlockPos(cell.getX(), y, cell.getZ())).isAir()) {
                    columnClear = false;
                    break;
                }
            }
            if (!columnClear) {
                continue;
            }
            double score = npc.blockPosition().distSqr(cell);
            if (score < bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return best;
    }

    private void tickEnterPortal() {
        BlockPos portal = nearbyPortal();
        if (portal == null) {
            finish("附近找不到已点亮的传送门");
            return;
        }
        // 【fix96·审计C】进门执行层口粮兜底（规划层同口径软闸在前）。
        if (!inNetherDim() && !netherFoodGateOk()) {
            finish("进门中止：口粮不足（入境软闸 food>=14 且 nutrition>=20）");
            return;
        }
        // 【fix93·D8】主世界侧进门先过金装闸（下界内回程不拦）。
        if (!inNetherDim() && !ensureGoldBootsWorn()) {
            finish("进门中止：包里没有金靴（D8 下界须穿一件金装）");
            return;
        }
        JsonObject params = params("x", portal.getX() + 0.5D, "y", portal.getY(),
                "z", portal.getZ() + 0.5D);
        // 【fix313】staging/门洞底格提出作用域：进门前站位接近要用。
        BlockPos stagingCell = null;
        BlockPos bottomNear = null;
        // 【fix261】由找到的门格推门洞底行，再算同高 staging 格随技能带走。
        {
            int bottomY = portal.getY();
            while (bottomY > npc.level().getMinY()
                    && npc.level().getBlockState(new BlockPos(portal.getX(), bottomY - 1, portal.getZ()))
                            .is(Blocks.NETHER_PORTAL)) {
                bottomY--;
            }
            java.util.List<BlockPos> bottoms = new java.util.ArrayList<>();
            java.util.Deque<BlockPos> queue = new java.util.ArrayDeque<>();
            BlockPos seed = new BlockPos(portal.getX(), bottomY, portal.getZ());
            queue.add(seed);
            while (!queue.isEmpty() && bottoms.size() < 4) {
                BlockPos cur = queue.poll();
                if (bottoms.contains(cur)
                        || !npc.level().getBlockState(cur).is(Blocks.NETHER_PORTAL)) {
                    continue;
                }
                bottoms.add(cur);
                queue.add(cur.offset(1, 0, 0));
                queue.add(cur.offset(-1, 0, 0));
                queue.add(cur.offset(0, 0, 1));
                queue.add(cur.offset(0, 0, -1));
            }
            boolean extendsX = bottoms.stream().anyMatch(b -> b.getX() != seed.getX());
            boolean extendsZ = bottoms.stream().anyMatch(b -> b.getZ() != seed.getZ());
            BlockPos staging = portalStagingCell(bottoms, extendsX || !extendsZ,
                    bottoms.size() == 1);
            stagingCell = staging;
            for (BlockPos b : bottoms) {
                if (bottomNear == null
                        || npc.blockPosition().distSqr(b) < npc.blockPosition().distSqr(bottomNear)) {
                    bottomNear = b;
                }
            }
            if (staging != null) {
                params.addProperty("sx", staging.getX() + 0.5D);
                params.addProperty("sy", (double) staging.getY());
                params.addProperty("sz", staging.getZ() + 0.5D);
            }
        }
        // 【fix313】先进门前站位，就位后才派技能并计数（接近 tick 不烧
        // attempts：旧口径每次 tick 调用先计数，高差地形下接近编排还没
        // 开始就会被「多次未传送」误收场）。
        if (!approachPortal(stagingCell, bottomNear)) {
            return;
        }
        attempts++;
        if (attempts > 3) {
            finish("多次走进传送门仍未传送");
            return;
        }
        if (!skills.run(new Intent("", "enterportal", params), "目标")) {
            finish("传送技能启动失败");
            return;
        }
        status = "走进传送门 " + portal.toShortString();
    }

    // ---------------------------------------------------------------- 通关路线

    /**
     * 定点猎杀：按"对应掉落物"统计进度。
     *
     * <p>为什么不按击杀数：烈焰人/末影人掉落 0~1 个，击杀数达标但掉落不足时
     * 会误判完成，规划器随后合成眼睛就会因为缺材料反复失败。
     */
    private void tickSlay() {
        int gained = countItem(slayDrop) - slayBaseline;
        status = "猎杀 " + slayEntity + " | " + slayDrop + " " + gained + "/" + targetCount;
        if (gained >= targetCount) {
            finish("已取得 " + slayDrop + " x" + gained);
            return;
        }
        slayAttempts++;
        if (slayAttempts > 30) {
            finish("猎杀放弃：附近始终找不到 " + slayEntity);
            return;
        }
        // 【fix331】末影人猎杀半径 16→48（219 棒 G0 探针实证：诡异森林内末影人在 33–64 格，
        // 16 格检测盒＋4–9 格 wander 永远够不着；48 与主世界打猎「久猎无获」口径同值。
        // runSlay 按 entity 放宽上限，烈焰人仍 16——刷怪笼锚点已定位，生成在笼旁 4 格内）。
        double slayRadius = "enderman".equals(slayEntity) ? 48.0D : 16.0D;
        JsonObject params = params("entity", slayEntity, "radius", slayRadius, "attempts", 240);
        if (!skills.run(new Intent("", "slay", params), "目标")) {
            // 【fix324】连续无目标即换层（205 棒定谳环④⑤）：wander 只在同一高度
            // 平移，怪在刷怪笼层时永远够不着；8 次空转（约 30 秒）即收场交规划器
            // 改赴刷怪笼。文案含“找不到”，进 isFailureText 失败口径。
            slayNoTargetStreak++;
            if (slayNoTargetStreak >= 8) {
                finish("猎杀换层：附近找不到 " + slayEntity + "，改赴刷怪笼");
                return;
            }
            // 附近没有目标：先四处走走扩大搜索范围。玩家找怪也是这么做的。
            wander();
        } else {
            slayNoTargetStreak = 0;
        }
    }

    /** 长途行进：水平先靠近，再竖向对齐（要塞/传送门房间都在地下或高台）。 */
    /**
     * 【fix40】记矿：级别门/没镐收场前，16 格内真扫到裸露目标矿才记账
     *（扫不到不记、不编造坐标——Fong 口径"只记真实见到的"）。
     */
    private void recordSeenOre() {
        if (oreTarget == null) {
            return;
        }
        OreMemory.bindWorld(npc.level());
        BlockPos seen = scanExposedOre(oreTarget.block(), 16);
        if (seen != null) {
            OreMemory.record(npc.npcName(), oreTarget.block(), seen);
        }
    }

    /**
     * 【fix40】记矿回访：赶到记账点、把当时挖不了的矿挖掉。
     * 长途段镜像 tickTravel（travel 技能+竖直对齐）；进 12 格圈后先验记账点
     * 本体、再扫裸露同矿下 mine 技能。收场口径：挖到=成功销账；矿已不在=
     * 销账（不算失败）；工具又不够=中止（不计次）；其余失败由 finish() 计次，
     * 满 2 次自动销账防死锁。
     */
    private void tickRevisit() {
        OreMemory.bindWorld(npc.level());
        status = "回访记账矿 " + oreTarget.block() + " @ " + revisitX + "," + revisitY + "," + revisitZ;
        // 【fix144】过境记矿：回访本身就是长途过境，途中目击的珍贵矿顺手记账。
        maybeRecordTransitOre();
        // 【fix142】消费竖向 descend 的「无实际下降」结局，连败 ≥2 收场计次。
        noteRevisitDescendOutcome();
        if (revisitDescendNoDropFails >= 2) {
            finish("回访放弃：竖向 descend 两度无实际下降（坏地形悬停），交规划层");
            return;
        }
        if (!hasHarvestPickaxe(oreTarget.block())) {
            finish("回访中止：又没有能挖 " + oreTarget.block() + " 的镐了");
            return;
        }
        if (countItem(oreTarget.drop()) > baselineCount) {
            OreMemory.remove(npc.npcName(), oreTarget.block(), revisitX, revisitY, revisitZ);
            finish("回访成功：挖到 " + oreTarget.drop() + "，销账");
            return;
        }
        double dx = revisitX + 0.5D - npc.getX();
        double dz = revisitZ + 0.5D - npc.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq > 144.0D || Math.abs(revisitY - npc.getY()) > 8.0D) {
            attempts++;
            if (attempts > 240) {
                finish("回访放弃：多次推进仍未到达记账点");
                return;
            }
            // 【fix156】竖直对齐带同步放宽（与 tickTravel 同口径）：水平 ≤8 格
            // ∧ 高差 ≥6 时也在自家列先对齐，免回访终点在崖上/洞下时 travel 空磨。
            // 【fix176】带宽同步 8→12 格（同 tickTravel 口径，farm141 唇口实证）。
            if (horizontalSq <= 16.0D
                    || (horizontalSq <= 144.0D && Math.abs(revisitY - npc.getY()) >= 6.0D)) {
                String action = npc.getY() > revisitY ? "descend" : "ascend";
                JsonObject p = params("y", revisitY, "max", 160);
                if (!skills.run(new Intent("", action, p), "目标")) {
                    finish("回访竖直对齐失败 action=" + action);
                }
                return;
            }
            JsonObject p = params("x", revisitX, "y", npc.getY(), "z", revisitZ,
                    "tolerance", 4.0D);
            if (!skills.run(new Intent("", "travel", p), "目标")) {
                finish("回访行进技能启动失败");
            }
            return;
        }
        BlockPos exact = new BlockPos(revisitX, revisitY, revisitZ);
        String exactPath = BuiltInRegistries.BLOCK.getKey(
                npc.level().getBlockState(exact).getBlock()).getPath();
        boolean exactOk = (exactPath.equals(oreTarget.block())
                || exactPath.equals("deepslate_" + oreTarget.block())) && isExposed(exact);
        BlockPos found = exactOk ? exact : scanExposedOre(oreTarget.block(), 12);
        if (found == null) {
            OreMemory.remove(npc.npcName(), oreTarget.block(), revisitX, revisitY, revisitZ);
            finish("记账矿已不在（挖走/采完），销账");
            return;
        }
        if (!"idle".equals(skills.currentSkill())) {
            return;
        }
        JsonObject p = new JsonObject();
        p.addProperty("block", oreTarget.block());
        p.addProperty("radius", 16);
        if (!skills.run(new Intent("", "mine", p), "目标")) {
            if (++staleChecks >= 12) {
                finish("回访放弃：矿在附近但 mine 技能反复启动失败");
            }
        } else {
            staleChecks = 0;
        }
    }

    /**
     * 【fix107】竖向对齐死线：travel/revisit 水平已近（≤6 格）但高差 >3 时
     * 进入对齐带计时；高差改善 ≥4 格重新锚定（稳步升降永不误杀），连续
     * 300 秒（6000 tick）无改善 → 停技能、finish 交规划层，抢在 LONG
     * 看门狗 480 秒处决之前诚实收场（farm67 山顶悬停 7 分钟实证）。
     * 带宽取 6 格而非 tickTravel 派对齐技能的 4 格：对齐僵局里身体会在
     * 落脚面内横移数格（staging 基岩台实证：角上 dist²=16.25 短暂滑出
     * 4 格带、计时被反复清零），6 格内仍是同一处对齐僵局、不是新行程。
     *
     * @return 是否已收场（调用方直接 return）
     */
    /**
     * 【fix121】入夜收工：规划层夜闸只是派单时点闸——白天派出的
     * hunt/slay/travel/revisit 黄昏后一路跑进夜里，规划层在目标运行期
     * 间不 decide、插不进睡觉/小屋步（#7/#8/#11 长途跨入夜 + 夜猎续跑）。
     * 目标层自查：入夜（dayTime%24000≥13000，与规划层 isNight 同口径）
     * ∧ 主世界 ∧ 头顶见天（地下/室内本就安全、不扰）→ 停技能收场，
     * 交回规划器走过夜链。收场文案不带失败关键词，不喂各步连败退避。
     *
     * <p>豁免两条：①stage=「饥饿求生」的 hunt——步 0 故意夜猎，派单已由
     * fix122 的 hp 闸把关；这里断了规划层会立即重派、成热循环。
     * ②travel 距目标水平 ≤24 格——快到安全点了，赶完比半路停下过夜
     * 更安全（玩家口径）。
     *
     * @return true 表示已收场，tick 应立即返回
     */
    private boolean checkNightAbort() {
        // 【fix172】farm 补入类型面（与 fix153 A 环补 farm 同理）：开田是
        // 长时地表户外作业、非救援腿，步 6.7 本就不夜派——跨夜不收则整夜
        // 在野外磨 vitals（#37 实证：establish 跨夜跑 1554 秒、A 环磨到底
        // 才中止、间隙被失眠幻翼收尾）。
        if (!"hunt".equals(type) && !"slay".equals(type)
                && !"travel".equals(type) && !"revisit".equals(type)
                && !"farm".equals(type)) {
            return false;
        }
        if ("hunt".equals(type) && "饥饿求生".equals(goalStage)) {
            return false;
        }
        if (!net.minecraft.world.level.Level.OVERWORLD.equals(npc.level().dimension())) {
            return false;
        }
        long dayTime = npc.level().getDayTime() % 24000L;
        if (dayTime < 13000L) {
            return false;
        }
        if (!npc.level().canSeeSky(npc.blockPosition())) {
            return false;
        }
        if ("travel".equals(type)) {
            double dx = travelX - npc.getX();
            double dz = travelZ - npc.getZ();
            if (dx * dx + dz * dz <= 24.0D * 24.0D) {
                return false;
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 入夜收工 | type=" + type
                + " stage=" + (goalStage.isEmpty() ? "-" : goalStage)
                + " pos=" + npc.blockPosition().toShortString()
                + " dayTime=" + dayTime + "（fix121：地表野外目标入夜中止，交规划器过夜）");
        skills.stop();
        finish("入夜收工：地表野外目标入夜中止（fix121）type=" + type);
        return true;
    }

    /**
     * 【fix134】中途撤退线（与 fix122 入场闸成对的「中途」一面）：fix121 管
     * 入夜、fix122 只管入场那一刻——目标进行中自身血/饭被磨穿没有任何自查
     * （#15 入场 hp16.6/food8 合法、缠斗磨穿被收尾；#20/#21 地下磨穿；
     * #22 饥饿地表 hunt 磨到 hp1.2）。作业类目标（hunt/slay/gather/dig）
     * 执行中 hp ≤ 7.0（35% 惜命线，与 fix122/isLowHealth 同值）∨
     * foodLevel ≤ 3（回血余地归零）→ 停技能收场，交规划层撤退步（步 -1）
     * 送回安全点。只收目标、不碰生存层：贴身自卫/逃跑照旧，脱离战斗后由
     * 规划层接手回家，而不是原样续跑原目标。
     *
     * <p>口径边界：只查主世界（下界链有 fix96/93 入境闸把关）；地下同查
     * （血线与昼夜无关，夜闸的 canSeeSky 口径不适用）；travel 自 fix151
     * 起分口径纳入（救援腿阶段名豁免，见方法内注释），revisit 仍不查
     * （记账面刚收口、留观）；目标开始 10 秒内不查（入场合法性归各步闸）；
     * 触发后本地冷却 120 秒。收场文案避开 isFailureText 关键词、不喂连败
     * 退避（与 fix121 同口径），规划层以文案含「中途撤退」专认记账。
     *
     * @return true 表示已收场，tick 应立即返回
     */
    private boolean checkVitalRetreat() {
        if (!"hunt".equals(type) && !"slay".equals(type)
                && !"gather".equals(type) && !"dig".equals(type)
                && !"travel".equals(type) && !"farm".equals(type)) {
            return false;
        }
        // 【fix153】farm 补入类型面：#30 实证——开田目标白天派出、跨夜运行
        // 449 秒、选址南漂 163 格，入夜开阔地被僵尸+骷髅轮番缠斗约 2 分钟
        // 磨穿（hp 1.0 逃跑未脱身被收），farm 不在类型面、目标层无人中止。
        // farm 是与 gather 同风险的长时户外作业目标、不是救援腿，不适用
        // travel 的豁免逻辑，中止后同交步 -1 接管。
        // 【fix151】travel 分口径扩面：救援腿本身豁免——步 -1「中途撤退：
        // 回安全点」与步 -0.5「满包存箱：回落脚点」就是把人送回安全点的
        // 动作，中止它等于自锯；其余 travel（回程补给/换区换位/杂派长途）
        // 在 vitals 磨穿时同样收场交步 -1 改派豁免腿。#29 实证：回程补给
        // travel 崖面 descend 悬空磨 5.9 分钟、food 7→3 入夜被收，旧版
        // travel 整体豁免无人管。
        // 【fix230】「饥饿回农场」从 fix151 的收场面中撤回、改豁免：
        // 2026-11-05 farm209 实证死锁——food 0 时 fix134（回安全点）
        // 与饥饿回程（回农场找吃的）互斥恒真：农场腿 541 格约 72 秒必被
        // 打断、基址腿 185 格约 52 秒总能跑完，循环空转、粮永不恢复。
        // 撤退假设“安全点能休整补给”，基址无粮时该假设不成立；农场腿
        // 本身就是去补给的救援腿，半路打断等于否决救援。豁免后农场腿全
        // 程仍受生存层（逃跑/自卫）与夜闸 fix121 保护，不靠 fix134 保命。
        if ("travel".equals(type)
                && ("中途撤退：回安全点".equals(goalStage)
                    || "满包存箱：回落脚点".equals(goalStage)
                    || "饥饿求生：回农场".equals(goalStage))) {
            return false;
        }
        // 【fix304·155 棒】farm 救援 stage 豁免：饥饿求生 tend 本身就是去农场
        // 找吃的救援腿（生产 01:08–01:13 三次中止实证 + 实验场 N1 三次中止
        // 复现：tend 从未走到 phase 5、营养恒 0）。豁免仅限 stage=饥饿求生：
        // 回农场 ∧ hp>7 ∧ 距田锚点水平≤48 且 |dy|≤12（fix293 同口径）；hp≤7
        // 时照旧中止（fix153 夜战磨穿案仍受保护）；非救援 stage 的 farm 照旧。
        if ("farm".equals(type) && "饥饿求生：回农场".equals(goalStage)
                && npc.getHealth() > 7.0D) {
            FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
            if (farm != null) {
                BlockPos anchor = farm.anchor();
                double dx = npc.getX() - (anchor.getX() + 0.5D);
                double dz = npc.getZ() - (anchor.getZ() + 0.5D);
                double dy = npc.getY() - anchor.getY();
                if (dx * dx + dz * dz <= 48.0D * 48.0D && Math.abs(dy) <= 12.0D) {
                    return false;
                }
            }
        }
        if (!net.minecraft.world.level.Level.OVERWORLD.equals(npc.level().dimension())) {
            return false;
        }
        if (npc.tickCount - goalStartTick < 200L) {
            return false;
        }
        if (npc.tickCount < vitalRetreatCooldownUntilTick) {
            return false;
        }
        double hp = npc.getHealth();
        int foodLevel = npc.getFoodData().getFoodLevel();
        if (hp > 7.0D && foodLevel > 3) {
            return false;
        }
        vitalRetreatCooldownUntilTick = npc.tickCount + 2400L;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 中途撤退 | type=" + type
                + " stage=" + (goalStage.isEmpty() ? "-" : goalStage)
                + " hp=" + String.format(java.util.Locale.ROOT, "%.1f", hp)
                + " foodLevel=" + foodLevel
                + " pos=" + npc.blockPosition().toShortString()
                + "（fix134：血量/口粮见底，中止当前目标回安全点）");
        skills.stop();
        finish("中途撤退：血量/口粮见底，先回安全点休整（fix134）type=" + type);
        return true;
    }

    private boolean checkVerticalAlignDeadline() {
        double tx;
        double ty;
        double tz;
        if ("travel".equals(type)) {
            tx = travelX;
            ty = travelY;
            tz = travelZ;
        } else if ("revisit".equals(type)) {
            tx = revisitX + 0.5D;
            ty = revisitY;
            tz = revisitZ + 0.5D;
        } else {
            alignBandSinceTick = 0;
            return false;
        }
        double dx = tx - npc.getX();
        double dz = tz - npc.getZ();
        double absDelta = Math.abs(ty - npc.getY());
        if (dx * dx + dz * dz > 36.0D || absDelta <= 3.0D) {
            alignBandSinceTick = 0;
            alignBestAbsDelta = Double.MAX_VALUE;
            return false;
        }
        if (alignBandSinceTick == 0 || absDelta < alignBestAbsDelta - 4.0D) {
            alignBandSinceTick = npc.tickCount;
            alignBestAbsDelta = absDelta;
            return false;
        }
        if (npc.tickCount - alignBandSinceTick > 6000L) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 竖向对齐死线 | type=" + type
                    + " 高度差=" + Math.round(absDelta)
                    + " 已带内秒=" + ((npc.tickCount - alignBandSinceTick) / 20)
                    + "（fix107：300 秒无改善，诚实收场）");
            skills.stop();
            finish("竖向对齐 300 秒无进展（高度差 " + Math.round(absDelta)
                    + "），放弃交规划器（fix107）");
            return true;
        }
        return false;
    }

    private void tickTravel() {
        double dx = travelX - npc.getX();
        double dz = travelZ - npc.getZ();
        double horizontalSq = dx * dx + dz * dz;
        status = "长途行进 剩余 " + Math.round(Math.sqrt(horizontalSq))
                + " 格 高度差 " + Math.round(travelY - npc.getY());
        // 【fix144】过境记矿：长途途中目击挖不了的珍贵矿记坐标待回访。
        maybeRecordTransitOre();
        // 【fix170】带 arriveYCap 的 travel（回小屋）额外要求 npcY ≤ cap：
        // 屋顶（基座+3）在 |dy|≤3 边界内、无 cap 时会被判到达（#36 定谳）；
        // 被 cap 拦下后落入下方竖直对齐带 descend 至目标层。
        if (horizontalSq <= 16.0D && Math.abs(travelY - npc.getY()) <= 3.0D
                && (Double.isNaN(travelArriveYCap)
                    || npc.getY() <= travelArriveYCap)) {
            finish("到达目的地");
            return;
        }
        attempts++;
        // 要塞/下界要塞可能在一两千格之外：每次技能只推进几十格，
        // 上限太小会在半路放弃，然后在"到达目的地"和重新下单之间来回刷。
        if (attempts > 240) {
            finish("长途行进放弃：多次推进仍未到达");
            return;
        }
        // 【fix156】近终点竖向收尾宽带：水平 ≤8 格 ∧ 高差 ≥6 时也派竖直对齐，
        // 【fix176】带宽放宽为 12 格（见 wideVerticalBand 处注释）。
        // 不再只限 4 格带。farm124 实证：目标列 Y=57、NPC 在上方山头水平 5 格/
        // 高差 29，落在 4 格收尾带外、fix107 死线带（6 格）内的缝里，travel
        // 技能 A* 耗尽即时重启 45 次、白烧 775 秒靠死线收场。玩家到山头会
        // 自己挖下去/垫上去，不原地转圈。对齐后下一轮回本函数重新判定水平，
        // 与原 4 格带同一回路；fix107 死线继续在外层兜底。
        boolean wideVerticalBand = horizontalSq <= 144.0D
                && Math.abs(travelY - npc.getY()) >= 6.0D;
        if (horizontalSq <= 16.0D || wideVerticalBand) {
            // 水平到位但高度不对：先竖直挖楼梯下去/爬上来，再由下一轮继续水平推进。
            if (wideVerticalBand && horizontalSq > 16.0D) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 近终点竖向收尾（fix156） | 水平="
                        + Math.round(Math.sqrt(horizontalSq)) + " 高度差="
                        + Math.round(travelY - npc.getY()));
                // 【fix208】宽带无下降记账：同站位（水平移 <4 格）连续派单间
                // 高度差改善 <2 格累计，满 3 次诚实收场交规划层退避/换路——
                // 玩家在山头下不去会换位或绕行，不在同点每 90 秒撞一轮看门
                // 狗。真下降 ≥2 格或换了站位即清账，不误伤正常收尾；窄带
                // （水平 ≤4 格）由 fix107 死线管辖、不走本账。
                double absDelta = Math.abs(travelY - npc.getY());
                int feetX = npc.blockPosition().getX();
                int feetZ = npc.blockPosition().getZ();
                boolean sameSite = wideAlignLastAbsDelta >= 0.0D
                        && Math.abs(feetX - wideAlignLastX) < 4
                        && Math.abs(feetZ - wideAlignLastZ) < 4;
                if (sameSite && absDelta > wideAlignLastAbsDelta - 2.0D) {
                    wideAlignFailCount++;
                } else {
                    wideAlignFailCount = 0;
                }
                wideAlignLastAbsDelta = absDelta;
                wideAlignLastX = feetX;
                wideAlignLastZ = feetZ;
                if (wideAlignFailCount >= 3) {
                    wideAlignFailCount = 0;
                    wideAlignLastAbsDelta = -1.0D;
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 竖向收尾宽带收场（fix208） | 水平="
                            + Math.round(Math.sqrt(horizontalSq)) + " 高度差=" + Math.round(absDelta)
                            + "（同站位连续 3 次派单高度差改善 <2 格，交规划层退避/换路）");
                    finish("长途行进受阻：近终点竖向收尾多次无下降");
                    return;
                }
            }
            String action = npc.getY() > travelY ? "descend" : "ascend";
            JsonObject params = params("y", travelY, "max", 160);
            if (!skills.run(new Intent("", action, params), "目标")) {
                finish("竖直对齐失败 action=" + action);
            }
            return;
        }
        // 【fix156·起点侧】高空起点死线：travel 技能在柱顶/崖头起点会 A* 开放集
        // 耗尽即时重启、位置纹丝不动（farm126 实证：桥棘轮带上 Y=221 后冻在
        // (311,221,-195) 每 6 秒受阻一次）。连续 3 次派单水平推进 <2 格、且人
        // 高出目标层 >12 格时，不再重派 travel，先派 descend 在自家列下到目标
        // 层再续行——玩家被困高柱也是先挖下去。descend 派单后计数清零，收场
        // 回本函数重新判定；attempts 总帽与 fix107 死线仍在外层兜底。
        double horizontalNow = Math.sqrt(horizontalSq);
        if (!Double.isNaN(travelStallTargetX)
                && (Math.abs(travelX - travelStallTargetX) > 4.0D
                    || Math.abs(travelZ - travelStallTargetZ) > 4.0D)) {
            travelStallCount = 0;
            travelStallAnyCount = 0;
            travelStallLastHorizontal = -1.0D;
            travelStallLastY = -1.0D;
        }
        travelStallTargetX = travelX;
        travelStallTargetZ = travelZ;
        if (travelStallLastHorizontal >= 0.0D) {
            boolean highAbove = npc.getY() - travelY > 12.0D;
            // 【fix192】高度不收敛口径：高出目标层 >12 且两派单间高度没降
            // ≥2 格时，水平推进不算真推进——farm161 实证：自恢复栈顶
            // Y=199 出发回农场（目标层 62），travel 水平腿以当前高度为
            // 目标 Y（本段下方注释的既有口径，前提是人站在地形上），在
            // 空中搭桥棘轮反爬到 Y=293，水平距却在缩，fix156 的「无推进」
            // 死线永远不触发。玩家从高柱去低处是先下到地面再走，不会
            // 在 130 格高空搭桥横移。高度不收敛时不清高空账、照样累加，
            // 满 3 次走下方 descend 先下行；正常下山高度逐段收敛不误伤。
            boolean altitudeStuck = highAbove && travelStallLastY >= 0.0D
                    && npc.getY() > travelStallLastY - 2.0D;
            if (horizontalNow <= travelStallLastHorizontal - 2.0D) {
                travelStallAnyCount = 0;
                if (altitudeStuck) {
                    travelStallCount++;
                } else {
                    travelStallCount = 0;
                }
            } else {
                // 【fix186】无推进（<2 格）时平地计数同步累加（fix156 的
                // travelStallCount 仍只在高出 >12 格时累加、口径不变）。
                travelStallAnyCount++;
                if (highAbove) {
                    travelStallCount++;
                }
            }
        }
        travelStallLastHorizontal = horizontalNow;
        travelStallLastY = npc.getY();
        if (travelStallAnyCount >= 6) {
            // 【fix186】平地停滞收场：宽河岸实证旧版每 6 秒同参重派、
            // 烧 attempts 240 帽≈24 分钟（farm152 (486,65,-208) 74+ 轮）。
            // 连续 6 次派单推进 <2 格即诚实收场，交规划层退避/换路。
            travelStallAnyCount = 0;
            travelStallLastHorizontal = -1.0D;
            travelStallLastY = -1.0D;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 长途停滞收场（fix186） | 水平="
                    + Math.round(horizontalNow) + " 目标=" + Math.round(travelX) + ","
                    + Math.round(travelZ) + "（连续 6 次派单推进 <2 格，交规划层退避/换路）");
            finish("长途行进受阻：多次推进无进展");
            return;
        }
        if (travelStallCount >= 3) {
            travelStallCount = 0;
            travelStallLastHorizontal = -1.0D;
            travelStallLastY = -1.0D;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 高空起点先下行（fix156） | 水平="
                    + Math.round(horizontalNow) + " 高出目标="
                    + Math.round(npc.getY() - travelY) + "，改派 descend 至目标层");
            JsonObject dp = params("y", travelY, "max", 220);
            if (!skills.run(new Intent("", "descend", dp), "目标")) {
                finish("高空起点下行技能启动失败");
            }
            return;
        }
        // 水平推进时 Y 用当前高度：A* 只能在地形里找路，追一个"空中的目标 Y"必然失败。
        JsonObject params = params("x", travelX, "y", npc.getY(), "z", travelZ,
                "tolerance", 4.0D);
        if (!skills.run(new Intent("", "travel", params), "目标")) {
            finish("行进技能启动失败");
        }
    }

    /** 末地传送门：到达房间 -> 镶满 12 只眼睛 -> 走进传送门。 */
    private void tickEndPortal() {
        if (!inEndDimension()) {
            if (countItem("ender_eye") < 12) {
                finish("末影之眼不足 12 个（现有 " + countItem("ender_eye") + "）");
                return;
            }
        }
        switch (endPhase) {
            case 0 -> {
                double dx = travelX - npc.getX();
                double dz = travelZ - npc.getZ();
                double horizontalSq = dx * dx + dz * dz;
                if (horizontalSq > 16.0D || Math.abs(travelY - npc.getY()) > 3.0D) {
                    attempts++;
                    if (attempts > 100) {
                        finish("无法抵达末地传送门房间");
                        return;
                    }
                    // 复用长途行进的推进逻辑：水平到位后它会自动竖直对齐。
                    JsonObject params = params("x", travelX, "y", travelY, "z", travelZ,
                            "tolerance", 4.0D);
                    if (!skills.run(new Intent("", "travel", params), "目标")) {
                        finish("前往传送门房间失败");
                    }
                    return;
                }
                endPhase = 1;
            }
            case 1 -> {
                status = "寻找末地传送门框架";
                List<BlockPos> frames = findEndPortalFrames(16, 10);
                if (frames.isEmpty()) {
                    frames = findEndPortalFrames(32, 16);
                }
                if (frames.isEmpty()) {
                    attempts++;
                    if (attempts > 120) {
                        finish("附近找不到末地传送门框架");
                        return;
                    }
                    wander();
                    return;
                }
                fillEndPortal(frames);
            }
            case 2 -> {
                status = "走进末地传送门";
                attempts++;
                if (attempts > 40) {
                    finish("多次尝试仍未进入末地");
                    return;
                }
                JsonObject params = params("x", travelX, "y", travelY, "z", travelZ,
                        "tolerance", 1.5D);
                if (!skills.run(new Intent("", "travel", params), "目标")) {
                    finish("走入传送门失败");
                }
            }
            default -> finish("末地传送门阶段异常 " + endPhase);
        }
    }

    /**
     * 把找齐的末地传送门框架全部镶上眼睛，并直接写 3x3 的 END_PORTAL。
     *
     * <p>为什么手动写传送门方块：原版在放入最后一只眼睛时才会触发激活，
     * 而伪玩家是通过 setBlockAndUpdate 改状态，不一定经过那套激活判定。
     * 直接把 3x3 内部填成 END_PORTAL，效果与激活完全一致，还能避免"眼睛放完但门没开"。
     */
    private void fillEndPortal(List<BlockPos> frames) {
        int eyeCount = countItem("ender_eye");
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos frame : frames) {
            minX = Math.min(minX, frame.getX());
            maxX = Math.max(maxX, frame.getX());
            minY = Math.min(minY, frame.getY());
            minZ = Math.min(minZ, frame.getZ());
            maxZ = Math.max(maxZ, frame.getZ());
        }
        int filled = 0;
        for (BlockPos frame : frames) {
            BlockState state = npc.level().getBlockState(frame);
            if (!state.is(Blocks.END_PORTAL_FRAME)) {
                continue;
            }
            if (state.getValue(EndPortalFrameBlock.HAS_EYE)) {
                filled++;
                continue;
            }
            if (eyeCount <= 0) {
                finish("末影之眼不足，无法镶满 12 个（已镶 " + filled + "）");
                return;
            }
            consume("ender_eye", 1);
            eyeCount--;
            npc.level().setBlockAndUpdate(frame, state.setValue(EndPortalFrameBlock.HAS_EYE, true));
            filled++;
        }
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        // 框架是 5x5 的环，内部一定是 3x3。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                npc.level().setBlockAndUpdate(new BlockPos(centerX + dx, minY, centerZ + dz),
                        Blocks.END_PORTAL.defaultBlockState());
            }
        }
        travelX = centerX + 0.5D;
        travelY = minY;
        travelZ = centerZ + 0.5D;
        endPhase = 2;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 末地传送门已激活 | 框架=" + frames.size()
                + " 带眼=" + filled + " 内部中心=" + centerX + "," + minY + "," + centerZ);
        status = "末地传送门已激活";
    }

    /**
     * 末地屠龙：先拆水晶，再打龙。
     *
     * <p>和玩家一致的打法：末影水晶会持续治疗龙，必须先清掉；
     * 龙大部分时间在空中，用弓箭消耗；停靠时贴上去用剑。
     */
    private void tickDragon() {
        if (!inEndDimension()) {
            finish("不在末地，屠龙目标中止");
            return;
        }
        List<Entity> dragons = npc.level().getEntities(npc, npc.getBoundingBox().inflate(256.0D),
                entity -> entity instanceof EnderDragon && entity.isAlive());
        if (dragons.isEmpty()) {
            finish("末影龙已被击败，通关");
            return;
        }
        LivingEntity dragon = (LivingEntity) dragons.get(0);
        List<Entity> crystals = npc.level().getEntities(npc, npc.getBoundingBox().inflate(160.0D),
                entity -> entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal
                        && entity.isAlive());
        if (!crystals.isEmpty()) {
            dragonPhase = 0;
            status = "拆除末影水晶 剩余 " + crystals.size() + " | 龙 HP="
                    + String.format(Locale.ROOT, "%.0f", dragon.getHealth());
            dragonAttempts++;
            if (dragonAttempts > 120) {
                finish("拆水晶失败或超时");
                return;
            }
            if (countItem("arrow") > 0) {
                // 一支箭就能打碎水晶；每次只射最近的水晶，打完再找下一颗。
                if (skills.run(new Intent("", "shoot",
                        params("entity", "end_crystal", "shots", 4)), "目标")) {
                    return;
                }
            }
            // 没有箭：靠近最近的水晶近战（会被爆炸波及，但总比站桩好）。
            Entity nearest = crystals.stream()
                    .min((left, right) -> Double.compare(npc.distanceToSqr(left),
                            npc.distanceToSqr(right))).orElseThrow();
            if (skills.run(new Intent("", "travel", params("x", nearest.getX(),
                    "y", nearest.getY(), "z", nearest.getZ(), "tolerance", 3.0D)), "目标")) {
                return;
            }
            wander();
            return;
        }
        if (dragonPhase == 0) {
            dragonPhase = 1;
            dragonAttempts = 0;
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 末影水晶已清空，开始攻击末影龙");
        }
        status = "攻击末影龙 HP=" + String.format(Locale.ROOT, "%.0f", dragon.getHealth());
        dragonAttempts++;
        if (dragonAttempts > 900) {
            finish("攻击末影龙超时");
            return;
        }
        double distanceSq = npc.distanceToSqr(dragon);
        // 龙在高空时先射箭；降到停靠高度后交给近战追击。
        if (distanceSq > 36.0D && countItem("arrow") > 0) {
            if (skills.run(new Intent("", "shoot",
                    params("entity", "ender_dragon", "shots", 6)), "目标")) {
                return;
            }
        }
        if (skills.run(new Intent("", "slay",
                params("entity", "ender_dragon", "radius", 64.0D, "attempts", 300)), "目标")) {
            return;
        }
        wander();
    }

    /** 是否在末地维度。 */
    private boolean inEndDimension() {
        return npc.level().dimension().identifier().toString().contains("the_end");
    }

    /** 扫描附近的末地传送门框架。 */
    private List<BlockPos> findEndPortalFrames(int horizontal, int vertical) {
        List<BlockPos> frames = new java.util.ArrayList<>();
        BlockPos center = npc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-horizontal, -vertical, -horizontal),
                center.offset(horizontal, vertical, horizontal))) {
            if (npc.level().getBlockState(pos).is(Blocks.END_PORTAL_FRAME)) {
                frames.add(pos.immutable());
            }
        }
        return frames;
    }

    /**
     * 通过结构数据找到要塞的末地传送门房间中心。
     *
     * <p>为什么不用大范围方块扫描：要塞有上百格长，逐格扫描会强制加载几十个区块，
     * 在主线程上能卡住服务器好几秒。结构 piece 自带包围盒，直接给出房间中心，
     * 代价是一次区块加载。
     */
    public BlockPos strongholdPortalRoom(BlockPos anchor) {
        try {
            // 要塞只存在于主世界：在下界调用这个方法时必须切回主世界读结构数据，
            // 否则拿下界的 Level 去查 stronghold 永远是 null。
            ServerLevel level = npc.level().getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) {
                return null;
            }
            // 【fix263】同 ProgressionPlanner.locateStructure（114 棒定谳）：
            // lookupOrThrow 与 Registry.getValue 的引用会被 Paper 插件重映射
            // 器改指 IdMap、运行时恒抛 NoSuchMethodError；改走 lookup(...)
            // 取注册表、引用收窄到 HolderLookup.RegistryLookup 型别后
            // get(ResourceKey) 取 Holder 再 .value()。
            HolderLookup.RegistryLookup<Structure> registry = level.registryAccess()
                    .lookup(Registries.STRUCTURE).orElse(null);
            if (registry == null) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 结构注册表不可用（strongholdPortalRoom）");
                return null;
            }
            var strongholdHolder = registry.get(ResourceKey.create(Registries.STRUCTURE,
                    Identifier.withDefaultNamespace("stronghold")));
            if (strongholdHolder.isEmpty()) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 找不到 stronghold 结构注册项");
                return null;
            }
            Structure stronghold = strongholdHolder.get().value();
            // 结构 start 只存在于已生成的区块里；锚点区块通常已由 /locate 加载，
            // 这里再显式取一次，避免偶发的 null。
            level.getChunk(anchor.getX() >> 4, anchor.getZ() >> 4);
            StructureStart start = level.structureManager().getStructureAt(anchor, stronghold);
            if (start == null) {
                LOG.info("[AIV][GOAL] " + npc.npcName() + " 要塞结构未生成于锚点 "
                        + anchor.toShortString());
                return null;
            }
            for (StructurePiece piece : start.getPieces()) {
                if (piece.getClass().getSimpleName().contains("PortalRoom")) {
                    BoundingBox box = piece.getBoundingBox();
                    BlockPos center = new BlockPos((box.minX() + box.maxX()) / 2,
                            box.minY() + 1, (box.minZ() + box.maxZ()) / 2);
                    LOG.info("[AIV][GOAL] " + npc.npcName() + " 找到末地传送门房间 "
                            + center.toShortString());
                    return center;
                }
            }
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 要塞里没有 PortalRoom piece");
        } catch (Throwable throwable) {
            LOG.log(java.util.logging.Level.WARNING,
                    "[AIV][GOAL] " + npc.npcName() + " 解析要塞结构失败", throwable);
        }
        return null;
    }

    /** 生物 ID → 对应掉落物（slay 的进度统计用）。 */
    private static String dropOfEntity(String entity) {
        return switch (normalizeEntity(entity)) {
            case "spider", "cave_spider" -> "string";
            case "chicken" -> "feather";
            case "blaze" -> "blaze_rod";
            case "enderman" -> "ender_pearl";
            default -> "unknown_drop_" + normalizeEntity(entity);
        };
    }

    private static String normalizeEntity(String raw) {
        String path = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (path.startsWith("minecraft:")) {
            path = path.substring("minecraft:".length());
        }
        return path;
    }

    /** 找附近已经点亮的传送门方块（取最低、最近的一格作为走入目标）。 */
    public BlockPos nearbyPortal() {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        // 【fix318】竖直搜索 ±8 → ±16：181 生产实证——NPC 掉到门洞竖直
        // 8–9 格下即「附近找不到已点亮的传送门」收场（门洞底 Y66/67，
        // NPC Y58）；approachPortal 的回升编排只在找得到门时才有机会
        // 运行。放宽后差 ≤16 的门下站位都能找到门、再由 approachPortal
        // 的 moveToStand 回升就位。唯一调用点是 tickEnterPortal（规划层
        // 派单前也调本方法复核），主世界侧各服只有一扇门，误找他门风险
        // 可忽略；差 >16 仍找不到门→finish，目标收场后规划层可重派，
        // 不硬失败（兜底通则）。
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-24, -16, -24), center.offset(24, 16, 24))) {
            if (!npc.level().getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                continue;
            }
            // 越低越接近玩家站的那一层；同层用距离决胜。
            double score = center.distSqr(pos) + (pos.getY() - center.getY()) * 4.0D;
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 【fix361·277 棒】找附近已灭的完整黑曜石门框（内腔 6 格全空气、框体 10 格
     * 全黑曜石、无传送门方块）：重燃旧框的选址。扫描盒与 nearbyPortal() 同口径
     * （±24/±16），取最近的一座。框体几何复用 PORTAL_FRAME（10 格极简框、无角）
     * 与 interiorPosition()；每块黑曜石格反推 10 种 origin × 2 轴向验框。 */
    private PortalSite findUnlitPortalFrame() {
        BlockPos center = npc.blockPosition();
        PortalSite best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-24, -16, -24), center.offset(24, 16, 24))) {
            if (!npc.level().getBlockState(pos).is(Blocks.OBSIDIAN)) {
                continue;
            }
            for (boolean alongX : new boolean[]{true, false}) {
                for (int[] local : PORTAL_FRAME) {
                    BlockPos origin = alongX
                            ? pos.offset(-local[0], -local[1], 0)
                            : pos.offset(0, -local[1], -local[0]);
                    if (isUnlitFrame(origin, alongX)) {
                        double score = center.distSqr(origin) + (origin.getY() - center.getY()) * 4.0D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new PortalSite(origin, alongX);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 框体 10 格全黑曜石、内腔 6 格全空气（有传送门方块即已点亮，不算旧框）。 */
    private boolean isUnlitFrame(BlockPos origin, boolean alongX) {
        return frameMissingCount(origin, alongX) == 0;
    }

    /** 【fix362·280 棒】框体缺格数：内腔不全空气时返回 -1（不是可补的旧框）。 */
    private int frameMissingCount(BlockPos origin, boolean alongX) {
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                if (!npc.level().getBlockState(interiorPosition(origin, alongX, dx, dy)).isAir()) {
                    return -1;
                }
            }
        }
        int missing = 0;
        for (int[] local : PORTAL_FRAME) {
            BlockPos pos = alongX ? origin.offset(local[0], local[1], 0)
                    : origin.offset(0, local[1], local[0]);
            if (!npc.level().getBlockState(pos).is(Blocks.OBSIDIAN)) {
                missing++;
            }
        }
        return missing;
    }

    /** 【fix362·280 棒】找附近单格缺损的旧框（框体仅缺 1 格、内腔全空气）：
     * 补框重燃的选址；缺损 ≥2 格或内腔不净时不算，落浇铸链。扫描盒与
     * findUnlitPortalFrame() 同口径（±24/±16），取最近的一座。 */
    private PortalSite findRepairablePortalFrame() {
        BlockPos center = npc.blockPosition();
        PortalSite best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-24, -16, -24), center.offset(24, 16, 24))) {
            if (!npc.level().getBlockState(pos).is(Blocks.OBSIDIAN)) {
                continue;
            }
            for (boolean alongX : new boolean[]{true, false}) {
                for (int[] local : PORTAL_FRAME) {
                    BlockPos origin = alongX
                            ? pos.offset(-local[0], -local[1], 0)
                            : pos.offset(0, -local[1], -local[0]);
                    if (frameMissingCount(origin, alongX) == 1) {
                        double score = center.distSqr(origin) + (origin.getY() - center.getY()) * 4.0D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new PortalSite(origin, alongX);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 附近有无单格缺损可补框重燃的旧框（规划层重燃支派单用）。 */
    public boolean hasRepairablePortalFrame() {
        return findRepairablePortalFrame() != null;
    }

    /** 附近有无可重燃的旧框（规划层重燃支派单用）。 */
    public boolean hasUnlitPortalFrame() {
        return findUnlitPortalFrame() != null;
    }

    /** 扫描半径内最近的岩浆源方块。 */
    private BlockPos nearbyLavaSource(int radius, int vertical) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -vertical, -radius),
                center.offset(radius, vertical, radius))) {
            BlockState state = npc.level().getBlockState(pos);
            if (!state.is(Blocks.LAVA) || !state.getFluidState().isSource()) {
                continue;
            }
            if (badLavaHints.contains(pos)) {
                continue; // 【fix112】技能已判此源不可用的，不再重复派单
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                best = pos.immutable();
            }
        }
        if (best != null) {
            // 【fix195】真实目击即落岩浆账本（同点 12 格内不重复写盘）。
            if (lastLavaLedgerPos == null
                    || lastLavaLedgerPos.distSqr(best) > 12.0D * 12.0D) {
                lastLavaLedgerPos = best;
                LavaMemory.bindWorld(npc.level());
                LavaMemory.record(npc.npcName(), best);
            }
        }
        return best;
    }

    /** 【fix243】扫半径内最近的已成形黑曜石（浇水成壳产物），供目标层回收派单。 */
    private BlockPos scanNearbyObsidian(int radius, int vertical) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -vertical, -radius),
                center.offset(radius, vertical, radius))) {
            if (!npc.level().getBlockState(pos).is(Blocks.OBSIDIAN)) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 【fix195】扫半径内最近的露面岩浆源（源方块∧上方空气，同 mineobsidian 可用口径）。 */
    private BlockPos scanExposedLava(int radius, int vertical) {
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -vertical, -radius),
                center.offset(radius, vertical, radius))) {
            BlockState state = npc.level().getBlockState(pos);
            if (!state.is(Blocks.LAVA) || !state.getFluidState().isSource()) {
                continue;
            }
            if (!npc.level().getBlockState(pos.above()).isAir()) {
                continue;
            }
            double distanceSq = center.distSqr(pos);
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 【fix200】全部钻石/下界合金镐的剩余耐久合计（黑曜石出发前预演用）。 */
    private int diamondPickRemainingTotal() {
        int total = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            String path = pathOf(stack);
            if ("diamond_pickaxe".equals(path) || "netherite_pickaxe".equals(path)) {
                total += stack.getMaxDamage() - stack.getDamageValue();
            }
        }
        return total;
    }

    private boolean hasUsableDiamondPickaxe() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            if (!"diamond_pickaxe".equals(path) && !"netherite_pickaxe".equals(path)) {
                continue;
            }
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 在脚边由近到远找一个能放 4x5 传送门框架的空地。 */
    private PortalSite findPortalSite() {
        BlockPos base = npc.blockPosition();
        for (int radius = 2; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos origin = base.offset(dx, dy, dz);
                        if (validPortalSite(origin, true)) {
                            return new PortalSite(origin, true);
                        }
                        if (validPortalSite(origin, false)) {
                            return new PortalSite(origin, false);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 框架 10 格 + 门洞 6 格全空、底部有支撑、门外能站人。 */
    private boolean validPortalSite(BlockPos origin, boolean alongX) {
        for (int[] local : PORTAL_FRAME) {
            BlockPos pos = alongX ? origin.offset(local[0], local[1], 0)
                    : origin.offset(0, local[1], local[0]);
            BlockState state = npc.level().getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
        }
        for (int dx = 1; dx <= 2; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos pos = interiorPosition(origin, alongX, dx, dy);
                BlockState state = npc.level().getBlockState(pos);
                if (!state.isAir() && !state.canBeReplaced()) {
                    return false;
                }
            }
        }
        BlockPos bottomA = alongX ? origin.offset(1, 0, 0) : origin.offset(0, 0, 1);
        BlockPos bottomB = alongX ? origin.offset(2, 0, 0) : origin.offset(0, 0, 2);
        if (!hasSupport(bottomA) || !hasSupport(bottomB)) {
            return false;
        }
        BlockPos outside = origin.offset(1, 1, 1);
        return PathFinder.isStandable(npc.level(), outside);
    }

    private boolean hasSupport(BlockPos pos) {
        BlockPos below = pos.below();
        return !npc.level().getBlockState(below).getCollisionShape(npc.level(), below).isEmpty();
    }

    private static BlockPos interiorPosition(BlockPos origin, boolean alongX, int dx, int dy) {
        return alongX ? origin.offset(dx, dy, 0) : origin.offset(0, dy, dx);
    }

    /** JsonObject 构造助手（目标层自己用，避免每处都手写 addProperty）。 */
    private static JsonObject params(Object... pairs) {
        JsonObject json = new JsonObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value instanceof Number number) {
                json.addProperty(String.valueOf(pairs[index]), number);
            } else {
                json.addProperty(String.valueOf(pairs[index]), String.valueOf(value));
            }
        }
        return json;
    }

    // ---------------------------------------------------------------- 建造

    /** 小屋建材候选表（顺序即优先级）：规划层派单门与执行层选料共用同一口径。 */
    public static final String[] SHELTER_MATERIALS = {"cobblestone", "cobbled_deepslate",
            "blackstone", "stone", "dirt",
            "oak_planks", "spruce_planks",
            "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks",
            "mangrove_planks", "cherry_planks", "bamboo_planks", "pale_oak_planks",
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
            "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"};

    private void tickBuild() {
        status = "建造 " + targetId;
        // 选一种背包里够 25 个的方块；优先圆石（抗爆），其次泥土，再木板/原木。
        String material = pickBuildingMaterial(SHELTER_BLOCKS);
        if (material == null) {
            // 【fix159】单料不够 25 不等于盖不了：圆石+泥土+木板混着盖是玩家
            // 常识。farm127 #32 实证：回程腿把圆石耗到 ~20、泥土 17，单一口径
            // 处处 <25，保持门落原地等待、幻翼磨死。候选总量够就走混料逐格取材。
            int total = 0;
            for (String candidate : SHELTER_MATERIALS) {
                total += countItem(candidate);
            }
            if (total < SHELTER_BLOCKS) {
                finish("建造失败：建筑方块不足 " + SHELTER_BLOCKS + " 个（需要先采集）");
                return;
            }
            int placedMixed = buildShelter(null);
            finishShelterBuild(placedMixed, "混合");
            return;
        }
        int placed = buildShelter(material);
        finishShelterBuild(placed, material);
    }

    private void finishShelterBuild(int placed, String material) {
        if (placed <= 0) {
            // 【fix108】放置=0 = 站位周围根本没法下块（已建满/被占）：旧版按
            // 「部分完成」成功口径收场，规划层连败被清零、每个 decide 都重派，
            // farm70 实证整夜约 1 Hz 热循环（96+ 次）。0 块必须算失败，
            // 交规划层小屋退避（同位置冷却、挪窝失效）；1 块起仍按部分完成。
            finish("建造失败：站位周围无可放格，放置=0/" + SHELTER_BLOCKS);
        } else if (placed < SHELTER_BLOCKS) {
            finish("建造部分完成：材料=" + material + " 放置=" + placed + "/" + SHELTER_BLOCKS);
        } else {
            finish("建造完成：材料=" + material + " 放置=" + placed);
        }
    }

    private String pickBuildingMaterial(int needed) {
        // 顺序即优先级：圆石最结实，木板其次，泥土最后。
        // 【fix58】补深板岩圆石/黑石：深挖时代包里最多的就是深板岩圆石，选料表不认它
        // 会在泥土耗尽后整夜瞬间失败「建筑方块不足」（boot-farm13 实锤），与 fix57 同病。
        for (String candidate : SHELTER_MATERIALS) {
            if (countItem(candidate) >= needed) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 在脚下盖一个 3x3、两格高、带屋顶的小屋，南面留一个门洞。
     * 直接 setBlock 而不是模拟放置：伪玩家没有客户端，走 useItemOn 还要处理朝向和碰撞，
     * 服务端直接改方块结果一致，且能保证不会把 NPC 自己封死（内部两格始终跳过）。
     */
    private int buildShelter(String materialPath) {
        // 【fix159】materialPath=null 为混料模式：逐格按 SHELTER_MATERIALS
        // 优先序取当前有货的材料（圆石用完自动接深板岩圆石/泥土/木板/原木）。
        boolean mixed = materialPath == null;
        Block block = null;
        if (!mixed) {
            Item item = item(materialPath);
            if (item == Items.AIR) {
                LOG.warning("[AIV][GOAL] " + npc.npcName() + " 建筑方块映射失败 item=" + materialPath);
                return 0;
            }
            block = Block.byItem(item);
        }
        BlockPos base = npc.blockPosition();
        int placed = 0;
        int farmSkipped = 0;
        FarmMemory.bindWorld(npc.level());
        outer:
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // 内部 1x2 空间必须留空，否则会把 NPC 埋进方块里。
                    if (dy < 2 && dx == 0 && dz == 0) {
                        continue;
                    }
                    // 门洞：南侧底层留空，NPC 才能进出。
                    if (dy == 0 && dx == 0 && dz == 1) {
                        continue;
                    }
                    BlockPos pos = base.offset(dx, dy, dz);
                    // 【fix72】自封门：与身体包围盒相交的格子永不放块。恢复点
                    // 醒来时身体常横跨两列（z=-219.89 横跨 -220/-221），墙格
                    // 放进包围盒眼高层会 inWall 窒息（farm30 实证：建屋自封
                    // 13 秒致死）。缺这一两块按「部分完成」既有口径接受。
                    if (npc.getBoundingBox().intersects(
                            new net.minecraft.world.phys.AABB(pos))) {
                        continue;
                    }
                    // 【fix219】建屋永不覆盖自家田格（土与作物位）：放置条件只认
                    // air/replaceable，而作物属 replaceable、会被墙块直接盖掉
                    // （farm192 田注销前夜：小屋 base 距田锚点仅 2 格、3×3 壳压田
                    // 缘作物层；fix198 只守 mine、放置面零校验）。跳过该格按
                    // fix72 的「部分完成」既有口径接受——只堵插件自有覆盖向量，
                    // 不动小屋选址与任何闸值；土变回普通土的踩踏/水源面不在此列。
                    if (FarmMemory.isFarmCell(npc.npcName(), pos)) {
                        farmSkipped++;
                        continue;
                    }
                    BlockState current = npc.level().getBlockState(pos);
                    if (!current.isAir() && !current.canBeReplaced()) {
                        continue;
                    }
                    String use = materialPath;
                    if (mixed) {
                        use = null;
                        for (String candidate : SHELTER_MATERIALS) {
                            if (countItem(candidate) > 0) {
                                use = candidate;
                                break;
                            }
                        }
                        if (use == null) {
                            LOG.info("[AIV][GOAL] " + npc.npcName() + " 建筑材料用完，已放置 " + placed + " 个");
                            break outer;
                        }
                    }
                    if (countItem(use) <= 0) {
                        LOG.info("[AIV][GOAL] " + npc.npcName() + " 建筑材料用完，已放置 " + placed + " 个");
                        break outer;
                    }
                    if (!consume(use, 1)) {
                        break outer;
                    }
                    Block useBlock = mixed ? Block.byItem(item(use)) : block;
                    npc.level().setBlockAndUpdate(pos, useBlock.defaultBlockState());
                    placed++;
                }
            }
        }
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 建造小屋 | base=" + base.toShortString()
                + " 材料=" + (mixed ? "混合" : materialPath) + " 放置=" + placed);
        if (farmSkipped > 0) {
            LOG.info("[AIV][GOAL] " + npc.npcName() + " 建屋避让田格（fix219） | 跳过=" + farmSkipped
                    + " base=" + base.toShortString());
        }
        return placed;
    }

    // ---------------------------------------------------------------- 探索

    private void tickExplore() {
        if (!exploreStarted) {
            // 随机方向可能正好撞山壁/水面：多试几个方向，别第一个走不通就放弃整个探索目标。
            for (int attempt = 0; attempt < 5; attempt++) {
                double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D;
                JsonObject params = new JsonObject();
                params.addProperty("x", npc.getX() + Math.cos(angle) * targetCount);
                params.addProperty("z", npc.getZ() + Math.sin(angle) * targetCount);
                if (skills.run(new Intent("", "goto", params), "目标")) {
                    exploreStarted = true;
                    return;
                }
            }
            finish("探索失败：连续 5 个方向都走不过去");
            return;
        }
        // tick 能走到这里说明技能已经结束（上面的 idle 检查），直接结算。
        finish("探索结束：" + skills.lastOutcome());
    }

    // ---------------------------------------------------------------- 背包工具

    // ---------------------------------------------------------------- 目标总超时看门狗（第四轮修复 A）

    /**
     * 观察一次目标进展，有进展就刷新 lastProgressTick。
     *
     * <p>进展信号（任一成立）：①目标相关产出计数变化（见 {@link #goalMetric()}，
     * 只认与目标直接相关的物品——在水洼里蹭挖到的圆石不算挖铁的进展）；
     * ②位置离开锚点 6 格以上（长距离移动、下矿/上浮途中物品还没产出时靠它续命）；
     * ③熔炼目标且熔炉正点着（等烧炼是合法等待，产物每约 10 秒一变本也会触发①，
     * 这里把"火还着"也算上，免得大批量慢烧被误判）。
     */
    private void observeGoalProgress() {
        int metric = goalMetric();
        BlockPos pos = npc.blockPosition();
        boolean moved = progressAnchor == null || pos.distSqr(progressAnchor) >= 36.0D;
        boolean smeltBurning = false;
        if ("smelt".equals(type) && furnacePos != null) {
            net.minecraft.world.level.block.state.BlockState furnaceState =
                    npc.level().getBlockState(furnacePos);
            smeltBurning = furnaceState.hasProperty(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                    && furnaceState.getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
        }
        if (metric != progressMetric || moved || smeltBurning) {
            progressMetric = metric;
            progressAnchor = pos;
            lastProgressTick = npc.tickCount;
        }
    }

    /** 目标相关的产出计数：与目标无关的物品进出背包不算进展。 */
    private int goalMetric() {
        return switch (type) {
            case "gather" -> gatherCount();
            case "dig", "revisit" -> oreTarget != null ? countItem(oreTarget.drop()) : 0;
            case "smelt", "craft" -> countItem(targetId);
            case "hunt" -> countHuntLoot();
            case "farm" -> countItem("wheat") + countItem("wheat_seeds")
                    + countItem("carrot") + countItem("potato") + countItem("bread");
            case "base" -> basePlacedCount;
            case "slay" -> countItem(slayDrop);
            case "flint" -> countItem("flint");
            case "fillbucket" -> countItem("water_bucket");
            case "obsidian" -> countItem("obsidian");
            default -> 0;
        };
    }

    /** 无进展超时阈值（tick）：按目标类型放宽，但都有上限。 */
    private int noProgressLimitTicks() {
        return switch (type) {
            case "dig" -> GOAL_NO_PROGRESS_TICKS_DIG;
            // 【fix85】place_bed/sleep 与 travel/store_loot 同为「长接近+
            // 短动作」的回家型目标：接近段（下栈/越岭/崖面 descend）动辄
            // 数分钟困在小范围内刨挖，位移 <6 格不触发进展信号，按 DEFAULT
            // 300 秒必被误杀（farm44 实证：stall 满 300 秒被杀、目标死后
            // 10 秒 descend 自行打通）。归 LONG 档（480 秒）与同形目标同口径；
            // 总时长仍有 1800 秒硬顶回收，不会无限期挂死。
            case "smelt", "travel", "revisit", "endportal", "dragon", "farm", "base", "store_loot", "loot_chest", "place_bed", "sleep" -> GOAL_NO_PROGRESS_TICKS_LONG;
            default -> GOAL_NO_PROGRESS_TICKS_DEFAULT;
        };
    }

    /** 总时长硬顶（tick）：进展再零星也不许超过。 */
    private int hardCapTicks() {
        return ("dig".equals(type) || "revisit".equals(type))
                ? GOAL_HARD_CAP_TICKS_DIG : GOAL_HARD_CAP_TICKS_DEFAULT;
    }

    private void finish(String reason) {
        // 【fix40】回访失败计次统一入口：成功/销账/中止不计，其余（超时、
        // 行进失败等）计一次，满 2 次 OreMemory 自动销账防死锁。
        if ("revisit".equals(type) && oreTarget != null
                && !reason.contains("回访成功") && !reason.contains("销账")
                && !reason.contains("中止")) {
            OreMemory.noteAttempt(npc.npcName(), oreTarget.block(), revisitX, revisitY, revisitZ);
        }
        active = false;
        lastOutcome = reason;
        outcomeCount++;
        String doneType = type;
        type = "-";
        // 【第二十三轮】动作有头有尾（日志标准第 2 条）：结束行带耗时与起止位置，
        // 出问题时不用再靠两条日志的时间戳和坐标反推这一段干了多久、走了多远。
        long elapsedMs = goalStartedMillis > 0 ? System.currentTimeMillis() - goalStartedMillis : -1;
        LOG.info("[AIV][GOAL] " + npc.npcName() + " 目标结束 | type=" + doneType + " " + reason
                + " | elapsed=" + (elapsedMs < 0 ? "-"
                        : String.format(java.util.Locale.ROOT, "%.1f", elapsedMs / 1000.0) + "s")
                + " from=" + goalStartX + "," + goalStartY + "," + goalStartZ
                + " to=" + npc.getBlockX() + "," + npc.getBlockY() + "," + npc.getBlockZ());
        // 【第二十三轮】合成/冶炼一批完成后盘点一次（家底触发点之一）。
        if (("craft".equals(doneType) || "smelt".equals(doneType)) && reason.contains("完成")) {
            InventorySnapshot.log(npc, doneType, doneType + ":" + targetId, false);
        }
    }

    /**
     * 目标结束次数。规划器用它判断"又完成了一轮"，
     * 光比较 lastOutcome 字符串的话，同一个失败原因连续出现时会被当成"没有新结果"，
     * 冷却时间永远不刷新，于是每秒重下同一个做不到的合成目标。
     */
    public int outcomeCount() {
        return outcomeCount;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeBlock(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        return value;
    }

    /** 模型可能说“木镐”“pickaxe”，统一映射到可合成物品 ID。 */
    private static String normalizeItem(String raw) {
        String value = normalizeBlock(raw);
        return switch (value) {
            case "planks", "wood_planks", "wooden_planks", "木板", "木材" -> "planks";
            case "pickaxe", "wood_pickaxe", "木镐" -> "wooden_pickaxe";
            case "sword", "wood_sword", "木剑" -> "wooden_sword";
            case "axe", "wood_axe", "木斧" -> "wooden_axe";
            case "stone_pick", "石镐" -> "stone_pickaxe";
            case "stone_sword", "石剑" -> "stone_sword";
            case "sticks", "木棍" -> "stick";
            case "竹板", "竹木板" -> "bamboo_planks";
            case "工作台" -> "crafting_table";
            case "熔炉" -> "furnace";
            case "火把" -> "torch";
            default -> value;
        };
    }

    private static Item item(String path) {
        Identifier id = Identifier.tryParse(path);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
    }

    private static String pathOf(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }

    private int countItem(String itemPath) {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && itemPath.equals(pathOf(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countPlanks() {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith("_planks")) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countItemsEndingWith(String suffix) {
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (!stack.isEmpty() && pathOf(stack).endsWith(suffix)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 背包里是否有还能用的镐。
     *
     * <p>把"最后 1 点耐久"的镐也排除掉：身体层不会再用它挖（避免整把碎掉消失），
     * 所以对目标层来说它已经等于没有工具，继续下挖只会空转。
     */
    private boolean hasUsablePickaxe() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_pickaxe")) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return true;
            }
        }
        return false;
    }

    /** 是否有可用石镐（第十八轮，石镐主力检查；见底的按没有算，口径同 hasUsablePickaxe）。 */
    private boolean hasUsableStonePick() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !"stone_pickaxe".equals(pathOf(stack))) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return true;
            }
        }
        return false;
    }

    /** 【fix320】是否有可用低阶镐（木/石/金）：分级守卫「无可用石镐/木镐」口径的
     * 目标层镜像（FakePlayerBody.hasUsableCheapPick 认木/金；石镐由 stoneSlot<0 另判；
     * 耐久口径同 hasUsableStonePick，留最后 1 点不用）。低阶镐全无时守卫才会让位。 */
    private boolean hasUsableLowPick() {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String p = pathOf(stack);
            if (!"wooden_pickaxe".equals(p) && !"stone_pickaxe".equals(p)
                    && !"golden_pickaxe".equals(p)) {
                continue;
            }
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) {
                return true;
            }
        }
        return false;
    }

    /** 目标矿的收获等级检查（第十七轮）：原版掉落规则——低级镐挖高级矿只碎不掉。 */
    private boolean hasHarvestPickaxe(String blockId) {
        String[] tiers = switch (blockId) {
            case "diamond_ore", "gold_ore", "redstone_ore", "emerald_ore" ->
                    new String[]{"iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"};
            case "iron_ore", "lapis_ore", "copper_ore" ->
                    new String[]{"stone_pickaxe", "iron_pickaxe", "diamond_pickaxe",
                            "netherite_pickaxe"};
            default -> null;
        };
        if (tiers == null) {
            return true;
        }
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            for (String tier : tiers) {
                if (tier.equals(path) && (!stack.isDamageableItem()
                        || stack.getDamageValue() < stack.getMaxDamage() - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String countPath(String itemPath) {
        // 目标写成具体木板（oak_planks）时按“任意木板”计数，避免只砍了丛林木就永远做不成。
        return itemPath.endsWith("_planks") ? "planks" : itemPath;
    }

    private int countItemFor(String counted) {
        if ("planks".equals(counted)) {
            return countPlanks();
        }
        int total = 0;
        for (int slot = 0; slot < COUNT_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !counted.equals(pathOf(stack))) {
                continue;
            }
            // 工具类目标只算还能用的：耐久见底的镐留在背包里会让"补一把备用石镐"
            // 在目标开始的瞬间就判定 2/2 完成，实际一把新的都没做出来，规划器每秒重下一次。
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }

    private boolean consume(String itemPath, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !itemPath.equals(pathOf(stack))) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) {
                npc.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        return remaining == 0;
    }

    /**
     * 把物品塞进主背包。返回 "ok" 或失败原因。
     * 不用 Inventory.add：它可能把方块放进护甲槽，这里只认 0-35 号槽。
     */
    private String give(String itemPath, int amount) {
        if (item(itemPath) == Items.AIR) {
            return "未知物品 " + itemPath;
        }
        int remaining = storeInMain(itemPath, amount);
        // 背包满时先丢杂物再重试：实测 NPC 打到铁器阶段后背包被圆石/泥土塞满，
        // 合成产物放不下 → "背包已满" 失败 → 规划器每秒重下同一个合成目标，科技树死锁。
        // 玩家这时候会扔掉泥土/沙砾，这里做等价处理。
        if (remaining > 0 && dropJunk(2) > 0) {
            remaining = storeInMain(itemPath, remaining);
        }
        return remaining == 0 ? OK : "背包已满，另有 " + remaining + " 个 " + itemPath + " 放不下";
    }

    /** 只做"合并堆叠 + 找空槽"两步，返回没放下的数量。 */
    private int storeInMain(String itemPath, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !itemPath.equals(pathOf(stack))) {
                continue;
            }
            int room = Math.min(stack.getMaxStackSize() - stack.getCount(), remaining);
            if (room > 0) {
                stack.grow(room);
                remaining -= room;
            }
        }
        for (int slot = 0; slot < MAIN_SLOTS && remaining > 0; slot++) {
            if (npc.getInventory().getItem(slot).isEmpty()) {
                ItemStack part = new ItemStack(item(itemPath), remaining);
                npc.getInventory().setItem(slot, part);
                remaining = 0;
            }
        }
        return remaining;
    }

    /**
     * 丢弃背包里的低价值杂物，最多腾出 needed 个槽位，返回实际腾出的槽数。
     *
     * <p>保留上限而不是"见到就丢"：#1 泥土在夜晚建屋还有用；沙砾要留着筛燧石；
     * 竹子是木板替代品。只丢掉超出上限的部分，避免把后续科技树要用的材料扔了。
     */
    private int dropJunk(int needed) {
        // 【fix38·背包处置，Fong 04:15/04:17 定】丢弃分两档：
        // 第一档（建筑杂石+地形杂料）必须排在配额裁剪与兜底之前——旧兜底按
        // "最小堆"挑，可能先扔掉小堆正经物资而留着整组杂石；
        // 第二档（蜘蛛眼/线，怪掉落当前用不上；骨头已由 fix315 移出，见
        // TIER2_JUNK 注）在配额裁剪之后、兜底之前：
        // 有落脚点箱子时应优先存箱（线日后做弓），存箱逻辑随 P2 箱子落地接上，
        // 在此之前没箱子且背包满就照扔。腐肉是应急口粮，不入档、口径照旧。
        int freed = dropTier1Junk(needed);
        if (freed >= needed) {
            return freed;
        }
        // 再按"每种物品的总保留量"裁剪一遍：这一步与背包是否满无关，
        // 顺便把只剩 1 点耐久的废工具清出去（它们占格但已经不能挖/打）。
        freed += trimOverflow();
        if (freed < needed) {
            freed += dropTier2Junk(needed - freed);
        }
        // 名单式清理没腾够（比如全是工具/关键材料挤在一起）：按堆大小兜底再丢。
        if (freed < needed) {
            freed += dropLeastValuable(needed - freed);
        }
        return freed;
    }

    /** 第一档·建筑杂石（Fong 04:15 名单）：花岗岩/闪长岩/安山岩及其磨制变种。 */
    private static final java.util.Set<String> BUILDING_RUBBLE = java.util.Set.of(
            "granite", "polished_granite",
            "diorite", "polished_diorite",
            "andesite", "polished_andesite");

    /** 第一档·地形杂料（Fong 04:17 扩充）：沙子、沙砾，挖矿/地形途中大量混入。 */
    private static final java.util.Set<String> TERRAIN_JUNK = java.util.Set.of(
            "sand", "gravel");

    /** 第二档（Fong 04:17）：蜘蛛眼/线——怪掉落、假人当前用不上；满包压力下才扔。
     * 例外（fix68）：线在床链挂起期间是床料，dropTier2Junk 与兜底都护住。
     * 【fix315·169 棒】骨头移出第二档：fix38 列档时前提是「骨头用不上」，但磨
     * 骨粉支（规划步 6.7）落地后骨头是骨粉的唯一原料、骨粉本身又在 fix54 保
     * 护名单里——原料可扔、成品受护的口径自相矛盾：正式服全史拾骨 29 次、第
     * 二档丢弃 37 根、磨骨粉派单 0 次（拾后 46 秒–18 分钟必被满包腾格清掉，
     * 2026-11-15 田边实证行 211437），骨粉支断粮定谳在此。且与 fix102 白名
     * 单（名单外一律不碰）冲突时以后出规矩为准。骨头自此不入任何丢弃档。 */
    private static final java.util.Set<String> TIER2_JUNK = java.util.Set.of(
            "spider_eye", "string");

    /**
     * 第一档优先丢弃：建筑杂石 + 地形杂料，整堆丢、丢够为止，返回腾出的槽数。
     * 每堆走 dropItems 统一入口（逐笔流水日志：物品 x数量），末尾再打一行小计
     * （腾格数），验收按这两类行对账：满包时应先见第一档清仓、圆石与正经物资不动。
     * 圆石是功能材料（垫脚/小屋/脱困），不在此列——它的超量由配额裁剪按 128 上限管。
     */
    private int dropTier1Junk(int needed) {
        int freed = 0;
        for (int slot = 0; slot < MAIN_SLOTS && freed < needed; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = pathOf(stack);
            String label = BUILDING_RUBBLE.contains(path) ? "建筑杂石优先丢弃 "
                    : TERRAIN_JUNK.contains(path) ? "地形杂料优先丢弃 " : null;
            if (label == null) {
                continue;
            }
            dropItems(slot, stack.getCount(), label + path);
            freed++;
        }
        if (freed > 0) {
            LOG.info("[AIV][INV] " + npc.npcName() + " 第一档优先丢弃小计 | 腾格=" + freed
                    + " 需求=" + needed);
        }
        return freed;
    }

    /**
     * 第二档丢弃：蜘蛛眼/线整堆丢（骨头 fix315 移出）。只在 dropJunk 满包压力路径里调用
     * （不进周期配额裁剪——平时不扔，线还要留着日后做弓）；
     * P2 落脚点箱子落地后，本档改为"先存箱、箱满/无箱才扔"，在此挂钩。
     */
    private int dropTier2Junk(int needed) {
        // 【fix69】落脚点箱子在 48 格内时，第二档整档先存箱不扔——靠规划层
        // 存箱步（6.77）回落脚点时批量收尾；离箱子远/没箱子时照旧扔（草案
        // v1 简化口径，如实记：不是逐趟折返存，是"在家附近不扔、存箱步收"）。
        if (hasChestWithin(48.0D)) {
            return 0;
        }
        int freed = 0;
        for (int slot = 0; slot < MAIN_SLOTS && freed < needed; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !TIER2_JUNK.contains(pathOf(stack))) {
                continue;
            }
            // 【fix68】床链挂起期间线是床料，第二档也不许扔（床成后恢复）。
            if (pathOf(stack).equals("string") && bedChainPending()) {
                continue;
            }
            dropItems(slot, stack.getCount(), "第二档丢弃 " + pathOf(stack));
            freed++;
        }
        if (freed > 0) {
            LOG.info("[AIV][INV] " + npc.npcName() + " 第二档丢弃小计 | 腾格=" + freed
                    + " 需求=" + needed);
        }
        return freed;
    }

    /** 镐的等级：数值越大越高级。木/金同为 0（金镐挖得快但挖不动铁以上，不比木镐强）。 */
    private static int pickaxeTier(String path) {
        return switch (path) {
            case "netherite_pickaxe" -> 4;
            case "diamond_pickaxe" -> 3;
            case "iron_pickaxe" -> 2;
            case "stone_pickaxe" -> 1;
            default -> 0;
        };
    }

    /** 这把工具还算不算"能用"：按全系统约定，耐久只剩最后 1 点的不算能用。 */
    private static boolean isUsableStack(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1;
    }

    /**
     * 受保护的镐是哪一槽（第三轮修复 D 的保底，全丢弃路径共用）：
     *
     * <ul>
     *   <li>有能用的镐时：最高等级里只有一把能用的 → 就是它（扔了就掉档/断档）；</li>
     *   <li>最高等级有多把能用的 → 不用特别保护（丢一把还剩同级可用的）；</li>
     *   <li>一把能用的都没有 → 保护最高等级的那把残镐，身体层兜底还会用它挖最后一格。</li>
     * </ul>
     *
     * @return 受保护的槽位；背包里根本没有镐时返回 -1
     */
    private int protectedPickaxeSlot() {
        int bestTier = -1;
        int bestSlot = -1;
        int usableAtBest = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_pickaxe") || !isUsableStack(stack)) {
                continue;
            }
            int tier = pickaxeTier(pathOf(stack));
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = slot;
                usableAtBest = 1;
            } else if (tier == bestTier) {
                usableAtBest++;
            }
        }
        if (bestSlot >= 0) {
            return usableAtBest == 1 ? bestSlot : -1;
        }
        // 没有能用的镐：保护最高等级的残镐（最后稻草）。
        bestTier = -1;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !pathOf(stack).endsWith("_pickaxe")) {
                continue;
            }
            int tier = pickaxeTier(pathOf(stack));
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * 按"每种物品总量"裁剪背包，返回真正腾空的槽数。
     *
     * <p>为什么不能只按每格上限判断：旧实现是逐格比较 junkKeepLimit（圆石上限 128），
     * 而单堆最多 64，于是 14 个满堆每一堆都"没超上限"，836 个圆石永远裁不掉，白占 13 格。
     * 这里把上限当成"全局配额"，从背包尾部往前保留，超出的部分丢掉。
     */
    private int trimOverflow() {
        java.util.Map<String, Integer> quota = new java.util.HashMap<>();
        int freed = 0;
        // 镐保底（第三轮修复 D）：先算出"受保护的镐"是哪一槽，裁剪时整槽跳过。
        // 长跑实测背包满时石镐被裁掉 8 把以上、全程重造 16 把——裁剪规则互相叠加时
        // 没有一条全局底线。这里钉死：当前能用的最好的一把镐永远不扔；
        // 一把能用的都没有时，最高等级的那把（哪怕只剩 1 耐久）也留着当最后稻草。
        int protectedPickaxe = protectedPickaxeSlot();
        // 【fix184】弓配额：junkKeepLimit 对 bow 落 default=-1 永不裁剪，骷髅
        // 掉落弓只进不出（实况 ×6 占 6 格、其中两把近废），是满包腾格「一二
        // 线皆无受害者」的深层驱动之一。仍走配额裁剪路径：先按剩余耐久
        // 排出最差的超额弓（保留最优 2 把），主循环命中即裁；只裁同型
        // 冗余、≤2 把不动，不碰 fix102 白名单兜底面。
        java.util.Set<Integer> excessBows = new java.util.HashSet<>();
        {
            java.util.List<Integer> bowSlots = new java.util.ArrayList<>();
            for (int s = 0; s < MAIN_SLOTS; s++) {
                ItemStack st = npc.getInventory().getItem(s);
                if (!st.isEmpty() && pathOf(st).equals("bow")) {
                    bowSlots.add(s);
                }
            }
            if (bowSlots.size() > 2) {
                bowSlots.sort((a, b) -> {
                    ItemStack sa = npc.getInventory().getItem(a);
                    ItemStack sb = npc.getInventory().getItem(b);
                    int ra = sa.isDamageableItem() ? sa.getMaxDamage() - sa.getDamageValue() : 0;
                    int rb = sb.isDamageableItem() ? sb.getMaxDamage() - sb.getDamageValue() : 0;
                    return Integer.compare(ra, rb);
                });
                for (int i = 0; i < bowSlots.size() - 2; i++) {
                    excessBows.add(bowSlots.get(i));
                }
            }
        }
        // 先统计每个"工具家族"里还有多少把真能用的镐/剑/护甲，
        // 防止 trimOverflow 把所有剩 1 耐久的都丢光，最后变成真没工具。
        // 但注意：只能把"同等级或更好的"算进可用池——石镐能挖石头但挖不动深板岩，
        // 一把石镐的存在不能让铁镐的 1 耐久版本被放心丢弃。
        java.util.Map<String, Integer> usableByFamily = new java.util.HashMap<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            // 只数"还有 2+ 耐久"的——剩 1 耐久的算废品，但要知道还有几把真能用的
            if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            usableByFamily.merge(toolFamily(pathOf(stack)), 1, Integer::sum);
        }
        for (int slot = MAIN_SLOTS - 1; slot >= 0; slot--) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (slot == protectedPickaxe) {
                continue;
            }
            if (excessBows.contains(slot)) {
                dropItems(slot, 1, "超量裁剪 bow 保留最优=2");
                freed++;
                continue;
            }
            String path = pathOf(stack);
            int keep = quota.computeIfAbsent(path, this::junkKeepLimit);
            // 【fix271】farm 队列材料保全（122 棒定谳）：tend 队列挂有未执行
            // place 时，它要用的 dirt 是工料不是囤货——dirt 配额在基址建成
            // 后为 0、本裁剪约 5 秒一轮，place 接近只要超过一个裁剪节拍，
            // digdirt 刚挖的土就被扔光、整组 place/till/plant 连环复核失效
            // （克隆实证：取土 +1 后 18 秒被裁 3 块）。仅队列存续期间、仅
            // 未执行 place 的数量内保留；配额本体与丢弃规格不动。
            if ("dirt".equals(path)) {
                int need = pendingFarmPlaceDirt();
                if (need > keep) {
                    keep = need;
                    quota.put(path, keep);
                }
            }
            // 【fix233】1 耐久壳丢弃不得污染整条路径的配额。
            // 68 棒实测：铁镐 A 烧至剩 1 耐久（同路径有健康版 B）→ 壳分支 keep=0 丢 A，
            // 循环尾 quota.put(path, 0) 把铁镐路径配额写死为 0，随后扫到健康版 B 时
            // keep 直接取缓存 0（不再问 junkKeepLimit，其对 iron_pickaxe 本应返回 -1
            // 永不裁）→ B 也被丢掉，NPC 只剩钻镐被 fix225 排除、基址边沿钉死。
            // 壳处置是"按格"的事（这格是废壳），不是"按路径"的事（该路径配额归零）；
            // 配额仍由 junkKeepLimit 说了算，壳分支丢弃后不写 quota。
            boolean shellDrop = false;
            // 只剩最后 1 点耐久的护甲等同废品：身体层约定不再用它。
            // 但工具（镐/剑/斧等）即便 1 耐久也不能随便丢——它们是生存层的最后手段。
            // 只有当"同路径"（同等级，比如 iron_pickaxe）存在真能用的版本时才丢，
            // 这样石镐剩 1 耐久不会因为"有铁镐可用"就被一起丢了（反过来也一样）。
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                // 护甲类：只要家族里有可用的就丢（1 耐久护甲没用）
                if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                        || path.endsWith("_leggings") || path.endsWith("_boots")) {
                    String family = toolFamily(path);
                    int usable = usableByFamily.getOrDefault(family, 0);
                    if (usable == 0) {
                        continue;  // 1 耐久护甲是家族唯一库存，留着
                    }
                    keep = 0;
                    shellDrop = true;
                } else {
                    // 工具类：只在"同物品路径"有可用版本时才丢，跨等级不算
                    // 例：iron_pickaxe 剩 1 耐久 + stone_pickaxe 有 100 耐久 => 不丢铁镐
                    // 例：iron_pickaxe 剩 1 耐久 + 另一把 iron_pickaxe 有 100 耐久 => 丢 1 耐久那把
                    int samePathUsable = 0;
                    for (int s = 0; s < MAIN_SLOTS; s++) {
                        ItemStack other = npc.getInventory().getItem(s);
                        if (other.isEmpty() || !pathOf(other).equals(path)) continue;
                        if (other.getDamageValue() < other.getMaxDamage() - 1) samePathUsable++;
                    }
                    if (samePathUsable == 0) {
                        continue;  // 该路径下唯一一把，留着当救命稻草
                    }
                    keep = 0;
                    shellDrop = true;
                }
            }
            if (keep < 0) {
                continue;
            }
            if (stack.getCount() <= keep) {
                quota.put(path, keep - stack.getCount());
                continue;
            }
            int amount = stack.getCount() - keep;
            dropItems(slot, amount, "超量裁剪 " + path + " 保留=" + keep);
            if (keep == 0) {
                freed++;
            }
            // 【fix233】壳分支丢弃不写 quota：见上方注释。配额型丢弃（keep 来自
            // junkKeepLimit）才把路径配额清零，壳丢弃只处理这一格。
            if (!shellDrop) {
                quota.put(path, 0);
            }
        }
        return freed;
    }

    /** 【fix271】当前 farm 队列里未执行的 place 动作数（每个 place 耗 1 块 dirt）。 */
    private int pendingFarmPlaceDirt() {
        int need = 0;
        for (FarmAction a : farmActions) {
            if ("place".equals(a.kind())) {
                need++;
            }
        }
        return need;
    }

    /** 把具体工具映射到"家族"，用于判断工具多样性。 */
    private static String toolFamily(String path) {
        if (path.endsWith("_pickaxe")) return "pickaxe";
        if (path.endsWith("_sword")) return "sword";
        if (path.endsWith("_axe")) return "axe";
        if (path.endsWith("_shovel")) return "shovel";
        if (path.endsWith("_hoe")) return "hoe";
        if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")) return "armor";
        return path;
    }

    /** 从指定槽位丢出若干物品，丢弃后永久不可再拾取。 */
    private void dropItems(int slot, int amount, String reason) {
        ItemStack stack = npc.getInventory().getItem(slot);
        if (stack.isEmpty() || amount <= 0) {
            return;
        }
        int actual = Math.min(amount, stack.getCount());
        ItemEntity drop = new ItemEntity(npc.level(), npc.getX(), npc.getY() + 0.5D, npc.getZ(),
                new ItemStack(stack.getItem(), actual));
        drop.setThrower(npc);
        // 关键：必须设成"永不拾取"（32767 是原版约定的永久值）。
        // 只设 40 tick 的延迟没用：伪玩家站在丢包点上，两秒后原版自动拾取会把
        // 刚丢掉的 700 个圆石全捡回来，于是"裁剪→捡回→再裁剪"每 5 秒循环一次，
        // 背包永远腾不出来，日志还被刷屏。
        drop.setPickUpDelay(32767);
        npc.level().addFreshEntity(drop);
        stack.shrink(actual);
        if (stack.isEmpty()) {
            npc.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        LOG.info("[AIV][INV] " + npc.npcName() + " 丢弃 | " + reason + " x" + actual);
    }

    /** 这类物品的保留数量；-1 表示有意义、永远不丢。 */
    /** 本世界是否已建成落脚点（fix55 建材预留的开关；账本口径同规划层）。 */
    private boolean baseBuiltHere() {
        BaseMemory.bindWorld(npc.level());
        return BaseMemory.hasBase(npc.npcName());
    }

    /**
     * 床链是否挂起（fix68）：本世界有落脚点、但床位还没安置落盘。
     * 这段时间线是床料（线×4 合 1 白羊毛，规划步 6.76 折算），丢弃路径要
     * 把线护住；床安置落盘后自动恢复第二档原口径（存箱优先、没箱才扔）。
     */
    private boolean bedChainPending() {
        BaseMemory.bindWorld(npc.level());
        BaseMemory.Base base = BaseMemory.base(npc.npcName());
        return base != null && base.bedPos() == null;
    }

    private int junkKeepLimit(String path) {
        switch (path) {
            case "sand", "clay_ball",
                 "polished_andesite", "polished_diorite", "polished_granite",
                 "calcite", "deepslate",
                 // 【fix54】落叶层（leaf_litter）：森林地表装饰，打草/路过自动
                 // 捡满包（实证囤到 215 个、3 格半），纯地形杂料不保留。
                 "leaf_litter",
                 // 【fix38·Fong 04:17】沙砾列第一档地形杂料、不再保留：
                 // 旧版留 16 个筛燧石，但燧石有专门的"找燧石"目标按需重挖，
                 // 满包时先保格子；筛出的燧石本身在永不扔清单里，不受影响。
                 "gravel" -> {
                return 0;
            }
            // 【fix55】建屋建材预留：圆石深板岩/凝灰岩/泥土/三杂石是 fix51
            // 小屋的砌墙料（pickBuildMaterial「拿来砌墙正好废物利用」、
            // 规划层建屋触发也按它们计数），旧口径一律保留=0 等于挖一块
            // 扔一块——farm8~10 三局实证建屋触发 0 次、地表建材永远攒
            // 不够 80。只在「本世界还没落脚点」时给预留额度，建成后恢复
            // 0 照旧不囤；这只改周期配额裁剪，满包时的第一档优先丢弃
            // 顺序（Fong 04:15 规格）不动，压力兜底仍在。
            case "cobbled_deepslate" -> {
                return baseBuiltHere() ? 0 : 96;
            }
            case "tuff", "andesite", "diorite", "granite" -> {
                return baseBuiltHere() ? 0 : 64;
            }
            case "dirt" -> {
                // 【fix304·155 棒】田缺格时留土：dirt 配额基址建成后为 0，
                // trim 约 5 秒一轮——NPC 顺路挖的土/给的土在 tend 补格评估
                // 前就被裁光，填水段 dirtSim 恒 0、扩田永不排队（实验场 N2b
                // 实证：给土 x16 后 69 秒内被裁至 0，补格评估 dirt=0 排队=0，
                // 田 19→17→13 格持续缩水）。田缺格（注册<24）时保留至缺额
                // （上限 24），让土活到补格评估、place 得以排队；fix271 的
                // 队列内保全仍覆盖 place 执行期。田满格后恢复配额 0。
                if (baseBuiltHere()) {
                    FarmMemory.Farm farm = FarmMemory.farm(npc.npcName());
                    if (farm != null) {
                        int deficit = FARM_TARGET_CELLS - farm.cells().size();
                        if (deficit > 0) {
                            return Math.min(deficit, 24);
                        }
                    }
                    return 0;
                }
                return 48;
            }
            // 圆石只留 1 格（128 个）：它是世界上最好再挖的东西，
            // 实测囤到 700+ 个会占掉 12 格，合成产物直接没地方放（空槽=0 导致全科技树卡死）。
            case "cobblestone" -> {
                return 128;
            }
            // 【fix92·Fong 2026-10-28 拍板】「红石最多就留一组，其他都扔了。
            // 回家就把这些放箱子」：保留=64，超出部分由本裁剪清掉；留存的
            // 一组回家存箱（STORE_LOOT 已同步加入 redstone）。此口径只点名
            // 红石，滴石块等其他大宗囤积不套用。
            case "redstone" -> {
                return 64;
            }
            case "stone" -> {
                return 64;
            }
            // 沙砾保留量已按 Fong 04:17 改为 0（见上方第一档分组）。
            case "bamboo" -> {
                return 32;
            }
            // 【fix54】小麦种子是田的命根子（D11），不再走下面 *_seeds 的
            // 杂物口径：整组保留。fix49 短验实证旧口径保留=0，NPC 一边打草
            // 一边把种子全扔了、攒种阶段永远攒不够（17 次拾取全被裁掉）。
            case "wheat_seeds" -> {
                return 64;
            }
            case "stick" -> {
                return 16;
            }
            // 【fix91】腐肉移出本组：Fong 定腐肉是应急口粮（平时不吃、
            // 危险线才吃，见 SurvivalController.foodScore 的 fix91 分支），
            // 盘点/规划/hunt 存量也都按食物记账——旧口径把它和蜘蛛眼等
            // 真毒物同组保留=0，farm50 实证 NPC food=0/hp=8.8 时拾到的
            // 唯一食物腐肉×2 在 9 秒后被配额裁剪整堆扔掉。移出后落
            // default -1，与其他食物同格不裁剪。
            case "spider_eye", "poisonous_potato", "pufferfish" -> {
                return 0;
            }
            // 【fix93·D8】金靴是下界金装指定件（进下界的钥匙），不是低阶
            // 废品：单列保留 1——旧口径把它混在下方低阶护甲组里（有石镐就
            // 保留=0），做出来也会被配额裁剪扔掉，金装闸永远齐不了料。
            case "golden_boots" -> {
                return 1;
            }
            // 低阶工具/护甲：有更好的就丢，留着只会占槽位。
            case "wooden_pickaxe", "wooden_sword", "wooden_axe", "wooden_shovel", "wooden_hoe",
                 "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
                 "golden_helmet", "golden_chestplate", "golden_leggings",
                 "chainmail_helmet", "chainmail_chestplate", "chainmail_leggings", "chainmail_boots" -> {
                // 用"可用口径"（countItemFor，滤掉只剩 1 耐久的）判断有没有更好的镐：
                // 以前用 countItem，一把只剩 1 耐久的石镐也会让木镐保留量变 0，
                // 等于把唯一还能用的木镐扔了（第一轮遗留问题）。
                return countItemFor("stone_pickaxe") > 0 || countItemFor("iron_pickaxe") > 0
                        || countItemFor("diamond_pickaxe") > 0 ? 0 : 1;
            }
            default -> {
            }
        }
        if (path.endsWith("_seeds") || path.endsWith("_sapling") || path.endsWith("_leaves")
                || path.equals("vine") || path.equals("lily_pad")) {
            return 0;
        }
        // 木料给一个"够用就好"的上限：24 原木 / 32 木板已经够整条科技树的木棍消耗，
        // 再多就是占格子（规划器只在 wood<16 时补料，正常不会囤到这个量）。
        if (path.endsWith("_log") || path.endsWith("_stem")) {
            return 24;
        }
        if (path.endsWith("_planks")) {
            return 32;
        }
        return -1;
    }

    /**
     * 判断"只剩 1 耐久的工具"是不是背包里唯一一把该类可用工具。
     * 如果是，就算只剩 1 耐久也不能丢。
     *
     * <p>"可用"的定义：耐久 > 1（身体层的约定，避免刚到 1 耐久就被算进去）。
     * "同 kind"的定义：同后缀（_pickaxe、_sword、_axe、_helmet 等）。
     */
    private boolean isLastUsableOfKind(String path, ItemStack damaged) {
        // 先从 path 里抽 kind 后缀，比如 "iron_pickaxe" → "_pickaxe"
        String kind = null;
        for (String suff : new String[]{"_pickaxe", "_sword", "_axe", "_shovel", "_hoe",
                "_helmet", "_chestplate", "_leggings", "_boots"}) {
            if (path.endsWith(suff)) {
                kind = suff;
                break;
            }
        }
        if (kind == null) {
            return false; // 不是工具/护甲类，不算
        }
        int usableCount = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack s = npc.getInventory().getItem(slot);
            if (s.isEmpty()) continue;
            String p = pathOf(s);
            if (!p.endsWith(kind)) continue;
            if (!s.isDamageableItem()) continue;
            if (s.getDamageValue() < s.getMaxDamage() - 1) {
                usableCount++;
            }
        }
        return usableCount == 0;
    }

    /** 是否属于"无论多满都不该丢"的关键物品（工具/护甲/食物/通关材料）。 */
    private boolean isEssential(ItemStack stack) {
        String path = pathOf(stack);
        // 只剩最后 1 点耐久的工具通常是废品（身体层约定不再用它），
        // 但如果这是背包里唯一一把可用的该类工具，就绝不能丢——
        // 否则"背包满 + 唯一一把铁镐只剩 1 耐久"这种情况会把铁镐直接丢掉，
        // NPC 紧接着就得重造，科技树被打断好几分钟（2026-10-25 14:26 实测）。
        if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
            if (isLastUsableOfKind(path, stack)) {
                return true;
            }
            return false;
        }
        if (path.endsWith("_pickaxe") || path.endsWith("_sword") || path.endsWith("_axe")
                || path.endsWith("_shovel") || path.endsWith("_hoe")
                || path.endsWith("_helmet") || path.endsWith("_chestplate")
                || path.endsWith("_leggings") || path.endsWith("_boots")
                || path.equals("shield") || path.equals("bow") || path.equals("arrow")
                || path.equals("bucket") || path.equals("water_bucket")
                // 【fix75】火把是规划步 13.06 按常备 ≥8 维护的消耗品，不保护
                // 就会被满包兜底当最小堆扔掉（farm33 实证：合成 8/8 后 23 秒
                // 全数丢弃、规划又重派合成，烧煤烧棍空转）。
                || path.equals("torch")
                || path.equals("flint_and_steel") || path.equals("obsidian")
                || path.equals("ender_eye") || path.equals("ender_pearl")
                || path.equals("blaze_rod") || path.equals("blaze_powder")
                || path.equals("iron_ingot") || path.equals("diamond")
                || path.equals("raw_iron") || path.equals("raw_gold") || path.equals("gold_ingot")
                || path.equals("redstone") || path.equals("lapis_lazuli")
                || path.equals("coal") || path.equals("charcoal") || path.equals("flint")
                || path.equals("crafting_table") || path.equals("furnace")
                // 【fix54】种田物资兜底保护：小麦种子（田的命根子）、骨粉
                // （催熟料，堆小、最容易被"最小堆兜底"误杀）。
                // 【fix315·169 棒】原料骨头同护：只护成品不护原料时，兜底最小
                // 堆照样挑 bone x1（正式 1 次/新图 10 次实证），与第二档移出配套。
                || path.equals("wheat_seeds") || path.equals("bone_meal")
                || path.equals("bone")
                // 【fix66】主粮与床料兜底保护：wheat 在原版没有 FOOD 组件
                // （不能直接吃）、不在名单就会被兜底当最小堆扔掉（farm22
                // 收获后 wheat×3 被扔实证）——它是面包原料、种田产出的主粮；
                // 羊毛是 D1 床料（同色 3 张靠猎羊零散攒），farm24 实证拾到
                // 即被兜底扔掉（x1/x2 两笔），不护住床链永远攒不齐。
                || path.equals("wheat") || path.endsWith("_wool")
                // 【fix83】床本体兜底保护：farm41 实证——20:57 合成的白床在
                // 当晚满包兜底里被当最小堆扔掉（21:28:52），NPC 隔天重攒羊毛
                // 再合一张。床是 D1 链的交付物本身，与床料同格不受兜底清理。
                || path.endsWith("_bed")
                // 【fix68】床链挂起期间线是床料（线×4 合白羊毛）：兜底不许扔，
                // 否则自卫杀蜘蛛攒的线被满包实时清零、床链永远齐不了（与
                // fix66 羊毛同病）。床安置后 bedChainPending 转假、自动恢复。
                || (path.equals("string") && bedChainPending())
                // 木料类是整条科技树的根基（木棍→工具/箭、木板→工作台/木棍），
                // 兜底清理时把它当关键材料保住，否则"背包满"会先把木棍丢掉。
                || path.equals("stick") || path.equals("bamboo") || path.equals("bamboo_block")
                || path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_stem")
                // 【fix89】圆石族兜底保护（圆石/深板岩圆石/黑石）：Fong 明规
                // 「圆石是功能材料（垫脚/小屋/脱困）、永不扔」，超量由
                // trimOverflow 的配额（128）管，不归兜底。兜底按最小堆挑，
                // 刚挖到的小堆圆石永远是第一受害者——farm48 实证满包兜底
                // 把刚挖的圆石 ×11 整堆扔掉（04:00:43），同一秒派发的石镐
                // 合成随即因缺圆石失败，挖石→扔石→合成败循环 ×3。
                || path.equals("cobblestone") || path.equals("cobbled_deepslate")
                || path.equals("blackstone")) {
            return true;
        }
        // 能吃的都不丢：饿死比背包乱更糟。
        net.minecraft.world.item.Item item = item(path);
        return item != Items.AIR
                && new ItemStack(item).get(net.minecraft.core.component.DataComponents.FOOD) != null;
    }

    /**
     * 兜底腾槽：按"数量最少且不关键"丢一整个堆。
     *
     * <p>上一版只处理固定名单里的杂物，背包塞满 12 把半耐久石镐 + 12 格圆石后
     * 一格都腾不出来，合成产物放不下 → "空槽=0" 反复失败。
     * 这里把非关键物品按堆大小排序，先丢最没价值的那一堆，保证合成永远有地方落物。
     */
    private int dropLeastValuable(int needed) {
        int freed = 0;
        // 兜底路径同样受镐保底约束（第三轮修复 D）：再满也不能把最好的一把可用镐扔了。
        int protectedPickaxe = protectedPickaxeSlot();
        for (int round = 0; round < needed; round++) {
            int worstSlot = -1;
            int worstCount = Integer.MAX_VALUE;
            for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (slot == protectedPickaxe) {
                    continue;
                }
                // 【fix173】当前目标正在收集的物品不做受害者（见 isActiveGatherTarget）。
                if (isActiveGatherTarget(stack)) {
                    continue;
                }
                // 【fix119】耐久物一律不做一线受害者（与二线硬保对齐）：
                // farm83 实证——钻剑磨到 1 耐久时 isEssential 首行判「废品」
                //（同后缀石剑/铁剑可用 → 不算唯一），count=1 又是全包最小堆，
                // 被兜底扔掉给腐肉腾位。武器再破也是武器（钻剑 1 耐久照样
                // 8 点伤害），玩家满包先扔大宗垃圾、绝不扔神装。腾不出就
                // 如实返回不足、调用方失败退避（兜底通则的降级路径）。
                if (stack.isDamageableItem()) {
                    continue;
                }
                if (isEssential(stack)) {
                    continue;
                }
                if (stack.getCount() < worstCount) {
                    worstCount = stack.getCount();
                    worstSlot = slot;
                }
            }
            boolean lastResortUsed = false;
            if (worstSlot < 0) {
                // 【fix90】二线兜底：一线（非关键物）挑不出受害者时——farm49
                // 实证 36 格全是关键物（工具/护甲/矿石/口粮全在保护名单）、
                // 兜底返回 0、合成产物无处落物「放不下」连败死循环。二线只对
                // 大宗消耗料下手（煤/红石/燧石/种子/箭/青金石等，取最小堆），
                // 硬保：一切耐久物（工具/武器/护甲）、一切能吃的、贵重锭料与
                // 通关物（钻石/铁锭/金/珍珠/烈焰棒/黑曜石/桶/工作台/熔炉）、
                // 火把与圆石族（fix89）。玩家满包做镐时也会先扔一把煤腾格。
                for (int slot = 0; slot < MAIN_SLOTS; slot++) {
                    ItemStack stack = npc.getInventory().getItem(slot);
                    if (stack.isEmpty() || slot == protectedPickaxe) {
                        continue;
                    }
                    if (isActiveGatherTarget(stack)) {
                        continue;
                    }
                    if (!isLastResortDroppable(stack)) {
                        continue;
                    }
                    if (stack.getCount() < worstCount) {
                        worstCount = stack.getCount();
                        worstSlot = slot;
                    }
                }
                lastResortUsed = worstSlot >= 0;
            }
            if (worstSlot < 0) {
                return freed;
            }
            ItemStack stack = npc.getInventory().getItem(worstSlot);
            int amount = stack.getCount();
            // 【fix181】种子丢弃量封顶：二线整堆丢弃会穿留种下限（单堆
            // 常态是全存量，总量 17 也能一丢归零、下限形同虚设）——受害者
            // 为小麦种子时只丢超出 16 的部分。守卫口径已保证总量 >16 才
            // 会选到种子，此处 ≤0 仅作双保险、如实返回已腾格数走退避。
            if ("wheat_seeds".equals(pathOf(stack))) {
                amount = Math.min(amount, countItem("wheat_seeds") - 16);
                if (amount <= 0) {
                    return freed;
                }
            }
            // 走统一的丢弃入口：那里会把拾取延迟设成永久，避免伪玩家两秒后又捡回来。
            dropItems(worstSlot, amount, "背包塞满，兜底 " + pathOf(stack)
                    + (lastResortUsed ? "（二线）" : ""));
            freed++;
        }
        return freed;
    }

    /**
     * 【fix173】当前目标正在收集的物品（farm establish 攒种子期的
     * wheat_seeds）：满包丢弃不许拿它做受害者——种子在二线白名单内是
     * 常态口径，但正攒种子时把刚攒的种子反复扔掉是自毁目标（#37 实证：
     * 拾取 ×1 与兜底丢弃 ×3/×1/×2 交替、存量永达不到开田门槛、phase 1
     * 磨 1554 秒跨夜被收）。玩家攒种子时满包先扔别的。今后其他收集型
     * 目标同病须各自实证再扩此面。
     * 【fix292】tend 补格执行中的 dirt：139 棒生产实证——满包拾取触发
     * dropJunk → 兜底按最小堆丢掉刚取的 dirt×8 → 8 个 place 复核
     * dirt=0 全灭、连带 till/plant 24 动作全弃、田仍 8 格。fix271e 只护
     * trimOverflow，不管 dropLeastValuable 的拾取腾格——两条丢土路径
     * 口径不一。place 未执行完时 dirt 是待用材料，兜底不许拿它做受害者
     * （待 place 数用既有 pendingFarmPlaceDirt()；tend/establish 均覆盖，
     * 队列清空后保护自动失效、其后 trimOverflow 照常裁）。
     */
    private boolean isActiveGatherTarget(ItemStack stack) {
        if ("farm".equals(type) && "establish".equals(farmMode)
                && farmPhase <= 1 && "wheat_seeds".equals(pathOf(stack))) {
            return true;
        }
        return "farm".equals(type) && "dirt".equals(pathOf(stack))
                && pendingFarmPlaceDirt() > 0;
    }

    /** 【fix90】二线兜底可丢的大宗消耗料。
     * 【fix102·兜底审计 #20】旧实现是「默认放行 + 黑名单」：名单外的东西
     * 一律可丢，而黑名单没跟上一线 isEssential 的历次补漏（fix83 床/
     * fix66 羊毛主粮等）——farm61 实证合成好的白床被二线当最小堆扔掉
     * （同族第 4 例）。改白名单 fail-closed：只有点名的大宗消耗料可丢，
     * 名单外一律不动；今后新增关键物默认安全，不再靠「记得补黑名单」。 */
    private boolean isLastResortDroppable(ItemStack stack) {
        if (stack.isDamageableItem()) {
            return false;
        }
        String path = pathOf(stack);
        net.minecraft.world.item.Item item = item(path);
        if (item != Items.AIR
                && new ItemStack(item).get(net.minecraft.core.component.DataComponents.FOOD) != null) {
            return false;
        }
        return switch (path) {
            // 真大宗消耗料：烧的（煤/木炭）、攒的（燧石/青金石）、打剩的
            // （箭）——玩家满包时先扔的也是这几样。
            case "coal", "charcoal", "flint", "lapis_lazuli",
                    "arrow" -> true;
            // 【fix181】小麦种子加留种下限（与下方红石同型）：种子是田的
            // 命根子（D11 留种），但旧口径在二线白名单里无条件可丢、又按
            // 最小堆挑受害者——种子堆通常最小，于是成了全技能通用的常态
            // 受害者：跨 farm101–145 数十笔二线丢种（合成腾格 ×11、hunt
            // 拾取腾格 ×5 等，时点均不在 farm 技能内，fix173 的攒种期豁免
            // 口径只认当前技能、罩不住日常沥干），establish 派单时存量
            // 已归零、只能现攒（farm146 407 秒败）。总量 >16 才可丢（16=
            // 开田跳攒种门槛 8 的两倍，够种田+一次补种），且 dropLeastValuable
            // 对种子另有丢弃量封顶防整堆穿底。还要种田的人不扔最后一份种。
            case "wheat_seeds" -> countItem("wheat_seeds") > 16;
            // 【fix92·Fong 2026-10-28】红石最多留一组：总量 ≤64 时二线
            // 也不许动；超出时才可丢——二线按最小堆挑受害者，丢掉最小的
            // 红石堆后总量仍 ≥64 或收敛到一组内，不会把留存组扔穿。
            case "redstone" -> countItem("redstone") > 64;
            default -> false;
        };
    }

    /** 主背包 0-35 里是否还有空槽。 */
    private boolean hasEmptyMainSlot() {
        return countEmptyMainSlots() > 0;
    }

    private int countEmptyMainSlots() {
        int empty = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            if (npc.getInventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }
}
