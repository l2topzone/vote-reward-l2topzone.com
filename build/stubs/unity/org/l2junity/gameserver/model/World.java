package org.l2junity.gameserver.model;
import java.util.Collection;
import org.l2junity.gameserver.model.actor.instance.PlayerInstance;
public class World { private static final World I = new World(); public static World getInstance() { return I; } public Collection<PlayerInstance> getPlayers() { return java.util.Collections.emptyList(); } }
