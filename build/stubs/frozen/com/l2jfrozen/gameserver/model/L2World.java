package com.l2jfrozen.gameserver.model;
import java.util.Map;
import com.l2jfrozen.gameserver.model.actor.instance.L2PcInstance;
public class L2World { private static final L2World I = new L2World(); public static L2World getInstance() { return I; } public Map<Integer, L2PcInstance> getAllPlayers() { return java.util.Collections.emptyMap(); } }
