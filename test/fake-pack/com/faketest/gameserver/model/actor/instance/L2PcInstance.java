package com.faketest.gameserver.model.actor.instance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
public class L2PcInstance {
    public static class GameClient {
        private final String ip;
        public GameClient(String ip) { this.ip = ip; }
        public String getIp() { return ip; }
    }
    private final int id; private final GameClient client;
    public final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger addItemCalls = new AtomicInteger();
    public final AtomicLong received = new AtomicLong();
    public L2PcInstance(int id, String ip) { this.id = id; this.client = new GameClient(ip); }
    public int getObjectId() { return id; }
    public String getName() { return "Fake" + id; }
    public int getLevel() { return 80; }
    public boolean isOnline() { return true; }
    public boolean isGM() { return false; }
    public GameClient getClient() { return client; }
    public void sendMessage(String s) { messages.add(s); }
    public Object addItem(String process, int itemId, int count, Object ref, boolean sm) {
        addItemCalls.incrementAndGet(); received.addAndGet(count); return new Object();
    }
    public String last() { synchronized (messages) { return messages.isEmpty() ? "" : messages.get(messages.size()-1); } }
}
