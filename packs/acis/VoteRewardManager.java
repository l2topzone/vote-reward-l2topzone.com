package l2topzone.acis;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import net.sf.l2j.commons.pool.ThreadPool;
import net.sf.l2j.gameserver.handler.IVoicedCommandHandler;
import net.sf.l2j.gameserver.handler.VoicedCommandHandler;
import net.sf.l2j.gameserver.model.World;
import net.sf.l2j.gameserver.model.actor.Player;

import l2topzone.L2TopzoneRewardBase;

/**
 * L2Topzone vote-reward integration for aCis (Interlude, Java 17).
 * Hook in GameServer.java:
 *   l2topzone.acis.VoteRewardManager.getInstance();
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
    { ThreadPool.scheduleAtFixedRate(task, initialSec, periodSec, TimeUnit.SECONDS); }

    @Override
    protected Collection<?> getOnlinePlayers()
    { return World.getInstance().getPlayers(); }
}
