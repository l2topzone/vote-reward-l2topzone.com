package l2topzone.mobius;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;

import l2topzone.L2TopzoneRewardBase;

/**
 * L2Topzone vote-reward integration for L2jMobius (Java 17).
 * Hook in GameServer.java:
 *   l2topzone.mobius.VoteRewardManager.getInstance();
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
    public boolean useVoicedCommand(String command, Player player, String params)
    { return handle(command, params, player); }

    @Override
    public String[] getVoicedCommandList()
    { return voicedCommands(); }

    @Override
    protected void registerHandler()
    { VoicedCommandHandler.getInstance().registerHandler(this); }

    @Override
    protected void schedulePeriodic(Runnable task, long initialSec, long periodSec)
    { ThreadPool.scheduleAtFixedRate(task, TimeUnit.SECONDS.toMillis(initialSec), TimeUnit.SECONDS.toMillis(periodSec)); }

    @Override
    protected Collection<?> getOnlinePlayers()
    { return World.getInstance().getPlayers(); }
}
