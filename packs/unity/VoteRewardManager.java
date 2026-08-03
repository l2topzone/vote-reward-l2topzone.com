package l2topzone.unity;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.l2junity.commons.util.concurrent.ThreadPool;
import org.l2junity.gameserver.handler.IVoicedCommandHandler;
import org.l2junity.gameserver.handler.VoicedCommandHandler;
import org.l2junity.gameserver.model.World;
import org.l2junity.gameserver.model.actor.instance.PlayerInstance;

import l2topzone.L2TopzoneRewardBase;

/**
 * L2Topzone vote-reward integration for L2jUnity (Java 11).
 * Hook in GameServer.java:
 *   l2topzone.unity.VoteRewardManager.getInstance();
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
    public boolean useVoicedCommand(String command, PlayerInstance player, String params)
    { return handle(command, params, player); }

    @Override
    public String[] getVoicedCommandList()
    { return voicedCommands(); }

    @Override
    protected void registerHandler()
    { VoicedCommandHandler.getInstance().registerHandler(this); }

    @Override
    protected void schedulePeriodic(Runnable task, long initialSec, long periodSec)
    { ThreadPool.scheduleAtFixedRate(task, initialSec, periodSec, TimeUnit.SECONDS); }

    @Override
    protected Collection<?> getOnlinePlayers()
    { return World.getInstance().getPlayers(); }
}
