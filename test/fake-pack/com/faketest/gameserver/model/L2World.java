package com.faketest.gameserver.model;
import com.faketest.gameserver.model.actor.instance.L2PcInstance;
import java.util.LinkedHashMap;
import java.util.Map;
public class L2World {
    private static final L2World I = new L2World();
    public static L2World getInstance() { return I; }
    private final Map<Integer, L2PcInstance> players = new LinkedHashMap<>();
    public void add(L2PcInstance p) { players.put(p.getObjectId(), p); }
    // Map-returning variant, as used by Frozen/Orion-style packs.
    public Map<Integer, L2PcInstance> getAllPlayers() { return players; }
}
