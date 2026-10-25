package com.example.aivillager.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 基于方块格的 A* 寻路，专为"伪玩家"这种必须走原版物理的身体设计。
 *
 * <p>和原版 {@code PathNavigation} 的取舍差异：
 * <ul>
 *   <li>节点是"脚所在的那一格"，落脚判定 = 脚/头可通行 + 脚下有碰撞支撑。</li>
 *   <li>只允许上 1 格、下最多 3 格（3 格以内不摔伤），不做 4 格以上的跳跃或坠落。</li>
 *   <li>斜向移动必须两个正交邻格同时可站，避免从墙角"切"过去卡住。</li>
 *   <li>水、岩浆、火、仙人掌等一律视为不可通行：小模型给的坐标经常在水里，NPC 掉进去就上不来。</li>
 * </ul>
 *
 * <p>所有查询都在主线程执行。为了控制开销，单次搜索有节点上限，并缓存落脚判定结果
 * （同一个格子在一次搜索里会被邻居枚举重复检查很多次）。
 */
public final class PathFinder {

    /** 四正方向 + 四斜方向；斜向用于让路径贴近平滑，避免 4 向的锯齿。 */
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** 允许的最高下落格数；MC 里 3 格落地不掉血，超过就开始受伤。 */
    private static final int MAX_DROP = 3;
    /** 斜向移动的代价，约等于 sqrt(2)。 */
    private static final double DIAGONAL_COST = 1.4142135623730951D;
    /**
     * 启发式权重（加权 A*）：h 乘 1.35 会牺牲一点路径最短性，换来大幅减少展开节点。
     * 丛林/树冠这种开阔集很大的地形里，等权 A* 常常把 6000 个节点全展开还找不到路，
     * 加权后搜索会明显朝目标方向收敛，短距离移动才能稳定成功。
     */
    private static final double HEURISTIC_WEIGHT = 1.35D;
    /**
     * 默认节点预算。48 格内的绕障路径通常远低于这个值；
     * 丛林里偶尔需要更多，代价约 2-3 微秒/节点，12000 约 30ms，单次可接受。
     */
    public static final int DEFAULT_MAX_NODES = 12000;

    private PathFinder() {
    }

    /**
     * 寻路结果。
     *
     * @param path    从起点到终点的格子序列（含起点）；空表示没找到
     * @param visited 展开的节点数，用于日志和性能观察
     * @param millis  搜索耗时（毫秒）
     * @param detail  失败原因或"到达"
     */
    public record Result(List<BlockPos> path, int visited, long millis, String detail) {
        public boolean found() {
            return !path.isEmpty();
        }
    }

    public static Result find(BlockGetter level, BlockPos start, BlockPos goal) {
        return find(level, start, goal, DEFAULT_MAX_NODES);
    }

    /**
     * 从 start 搜到 goal。start/goal 会先被修正到最近的合法落脚点，
     * 因为模型给的坐标、以及实体自身位置，都可能落在半空或方块内部。
     */
    public static Result find(BlockGetter level, BlockPos start, BlockPos goal, int maxNodes) {
        long begin = System.nanoTime();

        BlockPos safeStart = nearestStandable(level, start, 2);
        if (safeStart == null) {
            return new Result(List.of(), 0, elapsedMillis(begin), "起点不可站立 " + start.toShortString());
        }
        BlockPos safeGoal = nearestStandable(level, goal, 2);
        if (safeGoal == null) {
            return new Result(List.of(), 0, elapsedMillis(begin), "终点不可站立 " + goal.toShortString());
        }

        long startKey = safeStart.asLong();
        long goalKey = safeGoal.asLong();
        if (startKey == goalKey) {
            return new Result(List.of(safeStart), 1, elapsedMillis(begin), "到达");
        }

        // 一次搜索内共享的落脚缓存：邻居枚举会反复问到同一批格子。
        Map<Long, Boolean> standCache = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));

        gScore.put(startKey, 0.0D);
        open.add(new Node(safeStart, 0.0D, heuristic(safeStart, safeGoal)));

        int visited = 0;
        while (!open.isEmpty()) {
            Node current = open.poll();
            long currentKey = current.pos().asLong();
            if (currentKey == goalKey) {
                return new Result(reconstruct(parent, goalKey), visited, elapsedMillis(begin), "到达");
            }
            if (!closed.add(currentKey)) {
                continue;
            }
            if (++visited > maxNodes) {
                return new Result(List.of(), visited, elapsedMillis(begin), "超出节点上限 " + maxNodes);
            }

            for (int[] direction : DIRECTIONS) {
                boolean diagonal = direction[0] != 0 && direction[1] != 0;
                BlockPos raw = current.pos().offset(direction[0], 0, direction[1]);
                BlockPos next = resolveStep(level, current.pos(), raw, diagonal, standCache);
                if (next == null) {
                    continue;
                }
                long nextKey = next.asLong();
                if (closed.contains(nextKey)) {
                    continue;
                }

                double stepCost = (diagonal ? DIAGONAL_COST : 1.0D) + verticalCost(next.getY() - current.pos().getY());
                double tentative = current.g() + stepCost;
                Double known = gScore.get(nextKey);
                if (known != null && known <= tentative) {
                    continue;
                }
                gScore.put(nextKey, tentative);
                parent.put(nextKey, currentKey);
                open.add(new Node(next, tentative, tentative + heuristic(next, safeGoal)));
            }
        }

        return new Result(List.of(), visited, elapsedMillis(begin), "开放集耗尽，没有可达路径");
    }

    /** 终点坐标不可站立时，向上下找最近的合法落脚点；找不到返回 null。 */
    public static BlockPos standableGoal(BlockGetter level, BlockPos desired, int verticalSearch) {
        if (isStandable(level, desired)) {
            return desired;
        }
        for (int dy = 1; dy <= verticalSearch; dy++) {
            BlockPos up = desired.above(dy);
            if (isStandable(level, up)) {
                return up;
            }
            BlockPos down = desired.below(dy);
            if (isStandable(level, down)) {
                return down;
            }
        }
        return null;
    }

    /** 脚/头可通行且脚下有支撑，才算一个能站人的格子。 */
    public static boolean isStandable(BlockGetter level, BlockPos feet) {
        return isPassable(level, feet)
                && isPassable(level, feet.above())
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    /**
     * 判断一格能不能让身体通过。
     *
     * <p>不能只看 isAir：草丛、火把等没有碰撞体积的方块应该能穿过，
     * 而水/岩浆虽然没有碰撞体积却会让 NPC 失去移动能力，必须排除。
     */
    private static boolean isPassable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (isHazard(state)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** 踩上去会持续掉血或减速的方块。 */
    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW);
    }

    /**
     * 把一个水平邻格修正到"能站的那一层"：先试同层，再逐层向下，最后试上 1 格。
     * 返回 null 表示这个方向过不去。
     */
    private static BlockPos resolveStep(BlockGetter level, BlockPos from, BlockPos raw,
                                        boolean diagonal, Map<Long, Boolean> standCache) {
        for (int dy = 0; dy >= -MAX_DROP; dy--) {
            BlockPos candidate = raw.offset(0, dy, 0);
            if (!isStandableCached(level, candidate, standCache)) {
                continue;
            }
            if (diagonal) {
                // 斜向不做上下台阶：既避免切角穿墙，也避免"斜着掉两层"穿过实心方块。
                if (candidate.getY() != from.getY()) {
                    return null;
                }
                BlockPos sideA = new BlockPos(raw.getX(), candidate.getY(), from.getZ());
                BlockPos sideB = new BlockPos(from.getX(), candidate.getY(), raw.getZ());
                if (!isStandableCached(level, sideA, standCache) || !isStandableCached(level, sideB, standCache)) {
                    return null;
                }
            }
            // 往下掉之前，身体得先水平挤进 raw 这一列：当前层的脚/头两格必须能过。
            // 少了这项检查，A* 会给出"脚下有洞、但头被方块挡住"的假路径——
            // 伪玩家走到边上既掉不下去也退不回来，只能顶着墙站桩。
            if (candidate.getY() < from.getY()
                    && (!isPassable(level, raw) || !isPassable(level, raw.above()))) {
                return null;
            }
            return candidate;
        }

        // 上 1 格：需要头顶再往上留一格空间，否则起跳会撞头。
        BlockPos up = raw.above();
        if (isStandableCached(level, up, standCache) && isPassable(level, from.above(2))) {
            return up;
        }
        return null;
    }

    private static boolean isStandableCached(BlockGetter level, BlockPos pos, Map<Long, Boolean> cache) {
        long key = pos.asLong();
        Boolean known = cache.get(key);
        if (known != null) {
            return known;
        }
        boolean standable = isStandable(level, pos);
        cache.put(key, standable);
        return standable;
    }

    /** 上下台阶的额外代价：鼓励模型走平地，减少不必要的攀爬和掉落。 */
    private static double verticalCost(int dy) {
        if (dy > 0) {
            return 1.0D;
        }
        return dy < 0 ? 0.5D * -dy : 0.0D;
    }

    /** 八方向 octile 距离，加上垂直分量；再乘权重让搜索朝目标收敛（不保证最短路径）。 */
    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = Math.abs(from.getX() - to.getX());
        double dz = Math.abs(from.getZ() - to.getZ());
        double dy = Math.abs(from.getY() - to.getY());
        double horizontal = Math.max(dx, dz) + Math.min(dx, dz) * (DIAGONAL_COST - 1.0D);
        return (horizontal + dy * 1.5D) * HEURISTIC_WEIGHT;
    }

    private static BlockPos nearestStandable(BlockGetter level, BlockPos pos, int range) {
        if (isStandable(level, pos)) {
            return pos;
        }
        for (int dy = 1; dy <= range; dy++) {
            BlockPos up = pos.above(dy);
            if (isStandable(level, up)) {
                return up;
            }
            BlockPos down = pos.below(dy);
            if (isStandable(level, down)) {
                return down;
            }
        }
        return null;
    }

    private static List<BlockPos> reconstruct(Map<Long, Long> parent, long goalKey) {
        List<BlockPos> reversed = new ArrayList<>();
        long key = goalKey;
        while (true) {
            reversed.add(BlockPos.of(key));
            Long previous = parent.get(key);
            if (previous == null) {
                break;
            }
            key = previous;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static long elapsedMillis(long beginNanos) {
        return (System.nanoTime() - beginNanos) / 1_000_000L;
    }

    /** 优先队列节点：g 是已走代价，f = g + 启发式。 */
    private record Node(BlockPos pos, double g, double f) {
    }
}
