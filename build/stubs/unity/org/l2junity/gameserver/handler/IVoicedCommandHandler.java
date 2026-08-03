package org.l2junity.gameserver.handler;
import org.l2junity.gameserver.model.actor.instance.PlayerInstance;
public interface IVoicedCommandHandler { boolean useVoicedCommand(String command, PlayerInstance player, String params); String[] getVoicedCommandList(); }
