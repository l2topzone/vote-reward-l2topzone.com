package l2topzone.frozen;

import java.util.Collection;

import com.l2jfrozen.gameserver.handler.IVoicedCommandHandler;
import com.l2jfrozen.gameserver.handler.VoicedCommandHandler;
import com.l2jfrozen.gameserver.model.L2World;
import com.l2jfrozen.gameserver.model.actor.instance.L2PcInstance;
import com.l2jfrozen.gameserver.thread.ThreadPoolManager;

import l2topzone.L2TopzoneRewardBase;

/**
 * L2Topzone vote-reward integration for L2jFrozen (Interlude, Java 8).
 * Hook in GameServer.java:
 *   l2topzone.frozen.VoteRewardManager.getInstance();
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
    { VoicedCommandHandler.getInstance().registerVoicedCommandHandler(this); }

    @Override
    protected void schedulePeriodic(final Runnable task, long initialSec, long periodSec)
    { ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(task, initialSec * 1000L, periodSec * 1000L); }

    @Override
    protected Collection<?> getOnlinePlayers()
    { return L2World.getInstance().getAllPlayers().values(); }
}
