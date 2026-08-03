package org.l2jmobius.gameserver.handler;
import org.l2jmobius.gameserver.model.actor.Player;
public interface IVoicedCommandHandler { boolean useVoicedCommand(String command, Player player, String params); String[] getVoicedCommandList(); }
