package com.example.aivillager.npc;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * 伪玩家专用的"空连接"。
 *
 * <p>为什么需要它：
 * <ul>
 *   <li>{@code PlayerList#placeNewPlayer} 会调用 {@code setupInboundProtocol} 建立下行/上行管道，
 *       而伪玩家没有真实客户端，因此这里全部空实现。</li>
 *   <li>{@code Connection#isConnected()} 依赖 {@code channel != null}。物品拾取、末影珍珠传送等
 *       原版逻辑只对"在线玩家"生效，所以必须挂一个 EmbeddedChannel 让连接看起来是打开的。</li>
 *   <li>所有出站包直接丢弃，避免 EmbeddedChannel 的出站队列无限增长导致内存泄漏。</li>
 * </ul>
 */
public final class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // 只为让 isConnected() 返回 true；不注册任何 netty handler，也就不触发真正的网络流程。
        this.channel = new EmbeddedChannel();
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        // 伪玩家没有接收方，直接丢弃。
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
        // 伪玩家不会发送任何上行包，不需要协议解码器。
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
        // 防止 Paper 在连接被判定关闭时执行真正的玩家断开/存档流程。
    }
}
