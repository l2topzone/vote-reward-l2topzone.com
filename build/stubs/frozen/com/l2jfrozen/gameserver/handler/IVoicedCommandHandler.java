package com.l2jfrozen.gameserver.handler;
import com.l2jfrozen.gameserver.model.actor.instance.L2PcInstance;
public interface IVoicedCommandHandler { boolean useVoicedCommand(String command, L2PcInstance player, String params); String[] getVoicedCommandList(); }
