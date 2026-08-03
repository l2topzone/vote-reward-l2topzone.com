package org.l2jmobius.gameserver.model;
import java.util.Collection;
import org.l2jmobius.gameserver.model.actor.Player;
public class World { private static final World I = new World(); public static World getInstance() { return I; } public Collection<Player> getPlayers() { return java.util.Collections.emptyList(); } }
