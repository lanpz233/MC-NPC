package com.example.aivillager.listener;

import com.example.aivillager.AiVillagerPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 玩家聊天 -> 附近 AI 村民回应。
 *
 * <p>AsyncChatEvent 在异步线程触发，因此这里只做纯字符串处理，
 * 找 NPC、读实体状态、发消息全部切回主线程（{@code runTask}）。
 * 用 MONITOR 优先级是为了不改动其他插件的聊天处理结果。
 */
public final class NpcChatListener implements Listener {

    private final AiVillagerPlugin plugin;

    public NpcChatListener(AiVillagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.isEmpty()) {
            return;
        }
        // 异步线程不能碰世界/实体，统一交给主线程处理。
        Bukkit.getScheduler().runTask(plugin, () -> plugin.handlePlayerChat(player, text));
    }
}
