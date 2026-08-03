import com.faketest.gameserver.GameServer;
import com.faketest.gameserver.handler.VoicedCommandHandler;
import com.faketest.gameserver.model.L2World;
import com.faketest.gameserver.model.actor.instance.L2PcInstance;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Proves the universal build works on a fork it has never seen.
 * "com.faketest" is deliberately absent from its KNOWN_ROOTS list, so the only
 * way it can attach is by reading the caller package off the stack.
 */
public class UniversalIntegrationTest
{
    static int passed = 0, failed = 0;
    static volatile int totalVotes = 100;

    public static void main(String[] args) throws Exception
    {
        java.util.logging.Logger.getLogger("L2Topzone").setLevel(java.util.logging.Level.INFO);

        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            String body = p.endsWith("getServerData")
                ? "{\"ok\":true,\"totalVotes\":" + totalVotes + ",\"serverRank\":1}"
                : p.endsWith("getUserData")
                    ? "{\"ok\":true,\"isVoted\":true,\"voteTime\":0,\"serverTime\":" + (System.currentTimeMillis() / 1000) + "}"
                    : "{\"ok\":true}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        api.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        api.start();

        // The universal manager reads ./config/... relative to the working dir.
        new File("config").mkdirs();
        Files.write(Paths.get("config/L2TopzoneVoteReward.properties"),
            ("ApiKey=UNITEST\nApiHost=http://127.0.0.1:" + api.getAddress().getPort() + "/v1\n"
                + "VoiceCommand=vote\nIndividualRewardItemIds=57\nIndividualRewardItemCount=777\n"
                + "GlobalEnabled=true\nGlobalVoteInterval=100\nGlobalRewardItemIds=6673\n"
                + "GlobalRewardItemCount=2\nGlobalInitialDelaySeconds=5\nHwidProtection=false\n"
                + "MsgRewarded=Reward OK\n").getBytes(StandardCharsets.UTF_8));

        L2PcInstance p1 = new L2PcInstance(1, "10.0.0.1");
        L2PcInstance p2 = new L2PcInstance(2, "10.0.0.2");
        L2World.getInstance().add(p1);
        L2World.getInstance().add(p2);

        // Boot exactly like a real pack would.
        GameServer.boot();

        eq(1, VoicedCommandHandler.getInstance().size(), "handler registered into the pack registry via dynamic proxy");

        // Dispatch through the pack's own registry, not through our classes.
        boolean handled = VoicedCommandHandler.getInstance().dispatch("vote", p1, null);
        eq(true, handled, "pack registry dispatched .vote to the proxy");
        await(() -> p1.received.get() > 0, 5000);
        eq(777L, p1.received.get(), "individual reward delivered through discovered addItem(int count)");
        await(() -> p1.last().contains("Reward OK"), 3000);
        eq(true, p1.last().contains("Reward OK"), "custom MsgRewarded honoured: " + p1.last());

        eq(false, VoicedCommandHandler.getInstance().dispatch("notvote", p1, null), "unknown command not claimed");

        // Global milestone: exercises Map-returning getAllPlayers discovery.
        Object mgr = l2topzone.universal.VoteRewardManager.getInstance();
        java.lang.reflect.Method m = Class.forName("l2topzone.L2TopzoneRewardBase").getDeclaredMethod("checkGlobal");
        m.setAccessible(true);
        m.invoke(mgr);                       // baseline at 100
        totalVotes = 300;                    // two milestones
        m.invoke(mgr);
        eq(4L, p2.received.get(), "global reward reached a player via Map-returning getAllPlayers (2 milestones x 2)");

        System.out.println("\n==================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("==================================");
        api.stop(0);
        System.exit(failed > 0 ? 1 : 0);
    }

    interface Cond { boolean ok(); }

    static void await(Cond c, long ms)
    {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end)
        {
            if (c.ok()) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
        }
    }

    static void eq(Object exp, Object act, String what)
    {
        boolean ok = exp == null ? act == null : exp.equals(act);
        if (!ok && exp instanceof Number && act instanceof Number)
            ok = ((Number) exp).longValue() == ((Number) act).longValue();
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what + " (expected=" + exp + " actual=" + act + ")"); }
    }
}
