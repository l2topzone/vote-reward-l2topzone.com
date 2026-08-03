package com.faketest.gameserver;
/** Stands in for a real pack's GameServer: the universal build reads the pack
 *  root package off this class's frame in the call stack. */
public class GameServer {
    public static void boot() {
        l2topzone.universal.VoteRewardManager.getInstance();
    }
}
