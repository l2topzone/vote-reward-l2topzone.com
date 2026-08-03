package com.l2jserver.gameserver.model;
import java.util.Collection;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance;
public class L2World { private static final L2World I = new L2World(); public static L2World getInstance() { return I; } public Collection<L2PcInstance> getPlayers() { return java.util.Collections.emptyList(); } }
