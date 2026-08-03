package com.faketest.gameserver.handler;
import com.faketest.gameserver.model.actor.instance.L2PcInstance;
public interface IVoicedCommandHandler {
    boolean useVoicedCommand(String command, L2PcInstance activeChar, String params);
    String[] getVoicedCommandList();
}
