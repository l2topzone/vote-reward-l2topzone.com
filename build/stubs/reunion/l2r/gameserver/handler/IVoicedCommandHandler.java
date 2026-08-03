package l2r.gameserver.handler;
import l2r.gameserver.model.actor.instance.L2PcInstance;
public interface IVoicedCommandHandler { boolean useVoicedCommand(String command, L2PcInstance player, String params); String[] getVoicedCommandList(); }
