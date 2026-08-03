package l2topzone.reunion;

import java.util.Collection;

import l2r.gameserver.ThreadPoolManager;
import l2r.gameserver.handler.IVoicedCommandHandler;
import l2r.gameserver.handler.VoicedCommandHandler;
import l2r.gameserver.model.L2World;
import l2r.gameserver.model.actor.instance.L2PcInstance;

import l2topzone.L2TopzoneRewardBase;

/**
 * L2Topzone vote-reward integration for L2jReunion / Sunrise (l2r.* namespace, Java 8).
 * Hook in GameServer.java:
 *   l2topzone.reunion.VoteRewardManager.getInstance();
 */
public final class VoteRewardManager extends L2TopzoneRewardBase implements IVoicedCommandHandler
{
    private static VoteRewardManager INSTANCE;

    public static VoteRewardManager getInstance()
    {
        if (INSTANCE == null) synchronized (VoteRewardManager.class)
        {
            if (INSTANCE == null)
            {
                INSTANCE = new VoteRewardManager();
                INSTANCE.init("./config/L2TopzoneVoteReward.properties", "./config/L2TopzoneVoteReward.store");
            }
        }
        return INSTANCE;
    }

    @Override
    public boolean useVoicedCommand(String command, L2PcInstance player, String params)
    { return handle(command, params, player); }

    @Override
    public String[] getVoicedCommandList()
    { return voicedCommands(); }

    @Override
    protected void registerHandler()
    { VoicedCommandHandler.getInstance().registerHandler(this); }

    @Override
    protected void schedulePeriodic(Runnable task, long initialSec, long periodSec)
    { ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(task, initialSec * 1000L, periodSec * 1000L); }

    @Override
    protected Collection<?> getOnlinePlayers()
    { return L2World.getInstance().getPlayers(); }
}
