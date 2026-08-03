package com.faketest.gameserver.handler;
import com.faketest.gameserver.model.actor.instance.L2PcInstance;
import java.util.HashMap;
import java.util.Map;
public class VoicedCommandHandler {
    private static final VoicedCommandHandler I = new VoicedCommandHandler();
    public static VoicedCommandHandler getInstance() { return I; }
    private final Map<String, IVoicedCommandHandler> handlers = new HashMap<>();
    public void registerHandler(IVoicedCommandHandler h) {
        for (String c : h.getVoicedCommandList()) handlers.put(c, h);
    }
    public int size() { return handlers.size(); }
    public boolean dispatch(String cmd, L2PcInstance p, String params) {
        IVoicedCommandHandler h = handlers.get(cmd);
        return h != null && h.useVoicedCommand(cmd, p, params);
    }
}
