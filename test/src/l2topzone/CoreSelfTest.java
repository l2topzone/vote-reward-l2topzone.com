package l2topzone;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Verification harness for the reworked vote-reward core. Not shipped. */
public class CoreSelfTest
{
    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception
    {
        java.util.logging.Logger.getLogger("L2Topzone").setLevel(java.util.logging.Level.WARNING);

        jsonTests();
        storeTests();
        addItemOverloadTests();
        ipNormalizationTests();
        endToEndTests();

        System.out.println("\n==================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("==================================");
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- JSON

    static void jsonTests()
    {
        section("JSON reader");

        // The exact bug in v1: parseBoolean did not skip the opening quote.
        eq(true, L2TopzoneJson.of("{\"ok\":true,\"isVoted\":\"true\"}").bool("isVoted", false), "quoted boolean \"true\"");
        eq(true, L2TopzoneJson.of("{\"isVoted\":1}").bool("isVoted", false), "numeric boolean 1");
        eq(true, L2TopzoneJson.of("{\"isVoted\":\"1\"}").bool("isVoted", false), "quoted numeric boolean");
        eq(false, L2TopzoneJson.of("{\"isVoted\":0}").bool("isVoted", true), "numeric boolean 0");
        eq(true, L2TopzoneJson.of("{\"isVoted\" : true }").bool("isVoted", false), "whitespace around colon");
        eq(false, L2TopzoneJson.of("{\"isVoted\":false}").bool("isVoted", true), "plain false");
        eq(true, L2TopzoneJson.of("{\"isVoted\":\"YES\"}").bool("isVoted", false), "case-insensitive yes");

        // v1 used lastIndexOf, so a key appearing in a *value* hijacked the result.
        eq(5L, L2TopzoneJson.of("{\"totalVotes\":5,\"message\":\"see totalVotes:9999 in docs\"}")
            .number("totalVotes", -1), "key inside a string value is ignored");

        eq(1234L, L2TopzoneJson.of("{\"totalVotes\":\"1234\"}").number("totalVotes", -1), "quoted number");
        eq(1234L, L2TopzoneJson.of("{\"totalVotes\":1234.99}").number("totalVotes", -1), "fractional truncated");
        eq(-1L, L2TopzoneJson.of("{\"totalVotes\":null}").number("totalVotes", -1), "null -> default");
        eq(-1L, L2TopzoneJson.of("{\"other\":1}").number("totalVotes", -1), "missing -> default");

        // Nested: outer key must win, and inner objects must be skipped, not scanned.
        eq(7L, L2TopzoneJson.of("{\"rank\":7,\"data\":{\"rank\":99}}").number("rank", -1), "outer key wins over nested");
        eq(true, L2TopzoneJson.of("{\"data\":{\"x\":1},\"ok\":true}").bool("ok", false), "key after a nested object");
        eq(true, L2TopzoneJson.of("{\"list\":[1,2,{\"ok\":false}],\"ok\":true}").bool("ok", false), "key after an array");

        eq(null, L2TopzoneJson.of("{\"ok\":true}").boolOrNull("missing"), "boolOrNull distinguishes absent");
        eq(Boolean.FALSE, L2TopzoneJson.of("{\"ok\":false}").boolOrNull("ok"), "boolOrNull reads false");

        eq("hi \"there\"", L2TopzoneJson.of("{\"m\":\"hi \\\"there\\\"\"}").text("m", null), "escaped quotes");
        eq("a\nb", L2TopzoneJson.of("{\"m\":\"a\\nb\"}").text("m", null), "escaped newline");
        eq(null, L2TopzoneJson.of("garbage not json").text("m", null), "garbage -> default");
        eq(null, L2TopzoneJson.of(""), "empty body -> null document");
    }

    // --------------------------------------------------------------- STORE

    static void storeTests() throws Exception
    {
        section("Store");

        File dir = Files.createTempDirectory("l2tz").toFile();
        File f = new File(dir, "state.store");

        L2TopzoneStore s = new L2TopzoneStore(f.getPath());

        // --- atomic claim under concurrency: exactly one winner ---
        final int threads = 32;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < threads; i++)
        {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                if (s.tryClaim(4242L, 12)) winners.incrementAndGet();
                done.countDown();
            }).start();
        }
        start.countDown();
        done.await();
        eq(1, winners.get(), "tryClaim: exactly one winner out of " + threads + " concurrent claims");

        eq(true, s.isOnCooldown(4242L, 12), "claim sets cooldown");
        eq(false, s.tryClaim(4242L, 12), "second claim refused while on cooldown");
        s.releaseClaim(4242L);
        eq(false, s.isOnCooldown(4242L, 12), "releaseClaim clears cooldown");
        eq(true, s.tryClaim(4242L, 12), "claim succeeds again after release");

        // --- HWID window ---
        s.recordHwid("HW-A", 1L);
        eq(false, s.canRewardHwid("HW-A", 2L, 1, 12), "second char blocked inside window (max 1)");
        eq(true, s.canRewardHwid("HW-A", 1L, 1, 12), "same char still allowed");
        eq(true, s.canRewardHwid("HW-A", 2L, 2, 12), "second char allowed when max is 2");
        eq(true, s.canRewardHwid("HW-B", 9L, 1, 12), "unknown hwid allowed");
        eq(true, s.canRewardHwid(null, 9L, 1, 12), "null hwid allowed");

        s.setLastGlobalVotes(500);
        s.flush();

        // --- persistence round-trip ---
        L2TopzoneStore s2 = new L2TopzoneStore(f.getPath());
        eq(true, s2.isOnCooldown(4242L, 12), "cooldown survives reload");
        eq(500, s2.getLastGlobalVotes(), "global total survives reload");
        eq(true, s2.hasGlobalBaseline(), "baseline flag survives reload");
        eq(false, s2.canRewardHwid("HW-A", 2L, 1, 12), "hwid binding survives reload");

        // --- baseline distinguishes "unset" from "zero" (the v1 sentinel bug) ---
        File f3 = new File(dir, "fresh.store");
        L2TopzoneStore s3 = new L2TopzoneStore(f3.getPath());
        eq(false, s3.hasGlobalBaseline(), "fresh store has no baseline");
        s3.setLastGlobalVotes(0);
        eq(true, s3.hasGlobalBaseline(), "baseline of zero is still a baseline");

        // --- v1 legacy migration: timestamp-less HWID lines load as expired ---
        File legacy = new File(dir, "legacy.store");
        Files.write(legacy.toPath(), ("# old\nGLOBAL 100\nCOOLDOWN 7 " + (System.currentTimeMillis() / 1000L)
            + "\nHWID OLDHW 11\nHWID OLDHW 12\n").getBytes(StandardCharsets.UTF_8));
        L2TopzoneStore s4 = new L2TopzoneStore(legacy.getPath());
        eq(true, s4.isOnCooldown(7L, 12), "v1 cooldown still honoured");
        eq(100, s4.getLastGlobalVotes(), "v1 global read");
        eq(true, s4.canRewardHwid("OLDHW", 99L, 1, 12), "v1 permanent hwid bans expire after migration");

        // --- atomic save: the live file is never truncated ---
        File f5 = new File(dir, "atomic.store");
        L2TopzoneStore s5 = new L2TopzoneStore(f5.getPath());
        for (int i = 0; i < 200; i++) s5.tryClaim(i, 12);
        s5.flush();
        eq(true, f5.length() > 0, "store file non-empty after save");
        eq(false, new File(f5.getPath() + ".tmp").exists(), "temp file removed after atomic move");
        List<String> lines = Files.readAllLines(f5.toPath());
        eq(true, lines.contains("VERSION 2"), "version header written");
        long cooldownLines = lines.stream().filter(l -> l.startsWith("COOLDOWN ")).count();
        eq(200L, cooldownLines, "all 200 cooldowns persisted");

        // --- malformed input does not abort the load ---
        File bad = new File(dir, "bad.store");
        Files.write(bad.toPath(), "GLOBAL abc\nCOOLDOWN x y\nCOOLDOWN 5 100\nWAT ever\n".getBytes(StandardCharsets.UTF_8));
        L2TopzoneStore s6 = new L2TopzoneStore(bad.getPath());
        eq(100L, s6.cooldownSecondsLeft(5L, 0) >= 0 ? 100L : -1L, "valid record read past malformed ones");
    }

    // ------------------------------------------------------- addItem overloads

    /** Mobius/aCis-style: long count, WorldObject reference, returns the item. */
    public static class ModernPlayer
    {
        public final List<String> got = new ArrayList<>();
        public int getObjectId() { return 1; }
        public String getName() { return "Modern"; }
        public Object addItem(String process, int itemId, long count, Object reference, boolean sendMessage)
        { got.add(itemId + "x" + count + (reference == this ? " ref=self" : " ref=null")); return new Object(); }
    }

    /** Frozen/L2jServer-style: int count. */
    public static class LegacyPlayer
    {
        public final List<String> got = new ArrayList<>();
        public int getObjectId() { return 2; }
        public Object addItem(String process, int itemId, int count, Object reference, boolean sendMessage)
        { got.add(itemId + "x" + count); return new Object(); }
    }

    /** Full inventory: addItem returns null. */
    public static class FullInventoryPlayer
    {
        public int getObjectId() { return 3; }
        public Object addItem(String process, int itemId, long count, Object reference, boolean sendMessage)
        { return null; }
    }

    /** Void-returning overload — success cannot be read from the result. */
    public static class VoidPlayer
    {
        public final List<String> got = new ArrayList<>();
        public int getObjectId() { return 4; }
        public void addItem(String process, int itemId, long count, Object reference, boolean sendMessage)
        { got.add(itemId + "x" + count); }
    }

    /** Both overloads present — the long one must win. */
    public static class DualPlayer
    {
        public final List<String> got = new ArrayList<>();
        public int getObjectId() { return 5; }
        public Object addItem(String p, int id, int c, Object r, boolean m) { got.add("INT " + id + "x" + c); return new Object(); }
        public Object addItem(String p, int id, long c, Object r, boolean m) { got.add("LONG " + id + "x" + c); return new Object(); }
    }

    /** Reference parameter that cannot accept the player — must be passed null, not the player. */
    public static class StrictRefPlayer
    {
        public final List<String> got = new ArrayList<>();
        public int getObjectId() { return 6; }
        public Object addItem(String p, int id, long c, String reference, boolean m)
        { got.add(id + "x" + c + " ref=" + reference); return new Object(); }
    }

    /** No usable addItem at all. */
    public static class NoItemPlayer
    {
        public int getObjectId() { return 7; }
    }

    static void addItemOverloadTests()
    {
        section("addItem overload resolution");

        ModernPlayer m = new ModernPlayer();
        eq(true, L2TopzonePlayers.addItem(m, "T", 57, 5_000_000_000L), "modern long-count addItem succeeds");
        eq("57x5000000000 ref=self", m.got.get(0), "long count preserved beyond int range, self reference passed");

        LegacyPlayer l = new LegacyPlayer();
        eq(true, L2TopzonePlayers.addItem(l, "T", 57, 1000L), "legacy int-count addItem succeeds");
        eq("57x1000", l.got.get(0), "legacy count delivered");
        L2TopzonePlayers.addItem(l, "T", 57, 5_000_000_000L);
        eq("57x" + Integer.MAX_VALUE, l.got.get(1), "over-range count clamped on int-only packs");

        eq(false, L2TopzonePlayers.addItem(new FullInventoryPlayer(), "T", 57, 1L), "null return reported as failure");

        VoidPlayer v = new VoidPlayer();
        eq(true, L2TopzonePlayers.addItem(v, "T", 57, 1L), "void overload treated as success");
        eq(1, v.got.size(), "void overload actually invoked");

        DualPlayer d = new DualPlayer();
        L2TopzonePlayers.addItem(d, "T", 57, 10L);
        eq("LONG 57x10", d.got.get(0), "long overload preferred over int");

        StrictRefPlayer sr = new StrictRefPlayer();
        L2TopzonePlayers.addItem(sr, "T", 57, 3L);
        eq("57x3 ref=null", sr.got.get(0), "incompatible reference type passed as null, not the player");

        eq(false, L2TopzonePlayers.addItem(new NoItemPlayer(), "T", 57, 1L), "missing addItem reported as failure");
    }

    // ------------------------------------------------------------ IP handling

    public static class ClientWithInet
    {
        public java.net.InetAddress getInetAddress() throws Exception
        { return java.net.InetAddress.getByName("203.0.113.7"); }
    }

    public static class PlayerWithInetClient
    {
        public int getObjectId() { return 8; }
        public Object getClient() { return new ClientWithInet(); }
    }

    public static class ClientV6Mapped
    {
        public String getIp() { return "::ffff:198.51.100.9"; }
    }

    public static class PlayerV6
    {
        public int getObjectId() { return 9; }
        public Object getClient() { return new ClientV6Mapped(); }
    }

    public static class PlayerNoClient
    {
        public int getObjectId() { return 10; }
        public Object getClient() { return null; }
    }

    static void ipNormalizationTests()
    {
        section("IP resolution");
        eq("203.0.113.7", L2TopzonePlayers.resolveIp(new PlayerWithInetClient()), "InetAddress path");
        eq("198.51.100.9", L2TopzonePlayers.resolveIp(new PlayerV6()), "IPv4-mapped IPv6 unwrapped");
        eq(null, L2TopzonePlayers.resolveIp(new PlayerNoClient()), "null client -> null ip");
    }

    // ------------------------------------------------------------ end-to-end

    public static class TestPlayer
    {
        final int id;
        final String ip;
        public final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        public final AtomicLong adenaReceived = new AtomicLong();
        public final AtomicInteger addItemCalls = new AtomicInteger();
        volatile boolean online = true;

        public TestPlayer(int id, String ip) { this.id = id; this.ip = ip; }

        public int getObjectId() { return id; }
        public String getName() { return "Tester" + id; }
        public int getLevel() { return 40; }
        public boolean isOnline() { return online; }
        public boolean isGM() { return false; }
        public void sendMessage(String s) { messages.add(s); }
        public Object getClient() { return new TestClient(ip); }
        public Object addItem(String process, int itemId, long count, Object ref, boolean sm)
        { addItemCalls.incrementAndGet(); adenaReceived.addAndGet(count); return new Object(); }

        String lastMessage()
        { synchronized (messages) { return messages.isEmpty() ? "" : messages.get(messages.size() - 1); } }
    }

    public static class TestClient
    {
        final String ip;
        public TestClient(String ip) { this.ip = ip; }
        public String getIp() { return ip; }
    }

    static class TestManager extends L2TopzoneRewardBase
    {
        final List<Object> world = Collections.synchronizedList(new ArrayList<>());
        @Override protected void registerHandler() { }
        @Override protected void schedulePeriodic(Runnable t, long i, long p) { }
        @Override protected Collection<?> getOnlinePlayers() { return world; }
        void runGlobalCheck() throws Exception
        {
            java.lang.reflect.Method m = L2TopzoneRewardBase.class.getDeclaredMethod("checkGlobal");
            m.setAccessible(true);
            m.invoke(this);
        }

        /** Goes through the real scheduler entry point (async + overlap guard). */
        void submitGlobalCheck() throws Exception
        {
            java.lang.reflect.Method m = L2TopzoneRewardBase.class.getDeclaredMethod("submitGlobalCheck");
            m.setAccessible(true);
            m.invoke(this);
        }
    }

    static final AtomicInteger getUserDataHits = new AtomicInteger();
    static final AtomicInteger confirmHits = new AtomicInteger();
    static volatile boolean apiIsVoted = true;
    static volatile int apiTotalVotes = 100;
    static volatile int apiDelayMs = 0;
    static volatile int apiHttpCode = 200;

    static void endToEndTests() throws Exception
    {
        section("End-to-end reward flow");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if (apiDelayMs > 0) { try { Thread.sleep(apiDelayMs); } catch (InterruptedException ignored) {} }
            String path = ex.getRequestURI().getPath();
            String body;
            if (path.endsWith("getServerData"))
                body = "{\"ok\":true,\"totalVotes\":" + apiTotalVotes + ",\"serverRank\":3}";
            else if (path.endsWith("getUserData"))
            {
                getUserDataHits.incrementAndGet();
                // Deliberately quoted boolean — the shape that broke v1.
                body = "{\"ok\":true,\"isVoted\":\"" + apiIsVoted + "\",\"voteTime\":" +
                    (System.currentTimeMillis() / 1000L - 3600) + ",\"serverTime\":" + (System.currentTimeMillis() / 1000L) + "}";
            }
            else { confirmHits.incrementAndGet(); body = "{\"ok\":true}"; }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(apiHttpCode, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        String host = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        File dir = Files.createTempDirectory("l2tz-e2e").toFile();
        File props = new File(dir, "cfg.properties");
        Files.write(props.toPath(), ("ApiKey=TESTKEY123\nApiHost=" + host + "\nVoiceCommand=vote,votar\n"
            + "CooldownHours=12\nIndividualRewardItemIds=57\nIndividualRewardItemCount=1000000\n"
            + "GlobalEnabled=true\nGlobalVoteInterval=100\nGlobalRewardItemIds=6673\nGlobalRewardItemCount=5\n"
            + "GlobalMaxMilestonesPerCheck=3\nHwidProtection=false\nDebugLog=false\n"
            + "MinPlayerLevel=20\n").getBytes(StandardCharsets.UTF_8));

        TestManager mgr = new TestManager();
        mgr.init(props.getPath(), new File(dir, "e2e.store").getPath());

        eq(true, mgr.cfg != null, "config loaded");
        eq(2, mgr.voicedCommands().length, "command aliases registered (vote, votar)");

        // --- the game thread must not block on the API ---
        apiDelayMs = 700;
        TestPlayer p1 = new TestPlayer(101, "1.2.3.4");
        long t0 = System.nanoTime();
        boolean handled = mgr.handle("vote", null, p1);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        eq(true, handled, "command handled");
        eq(true, elapsedMs < 200, "handle() returns without waiting on the API (took " + elapsedMs + "ms, API takes 700ms)");
        await(() -> p1.lastMessage().contains("Thank you"), 5000);
        eq(1_000_000L, p1.adenaReceived.get(), "reward delivered asynchronously");
        eq(true, p1.lastMessage().contains("Thank you"), "success message sent");
        apiDelayMs = 0;

        // --- alias works ---
        TestPlayer pa = new TestPlayer(199, "1.2.3.9");
        mgr.handle("votar", null, pa);
        await(() -> pa.adenaReceived.get() > 0, 5000);
        eq(1_000_000L, pa.adenaReceived.get(), "alias command rewards too");

        // --- cooldown blocks a second claim ---
        p1.messages.clear();
        mgr.handle("vote", null, p1);
        eq(true, p1.lastMessage().contains("claim again in"), "cooldown message on repeat claim");
        eq(1, p1.addItemCalls.get(), "no second reward while on cooldown");

        // --- THE RACE: 24 simultaneous .vote from one character ---
        getUserDataHits.set(0);
        apiDelayMs = 300;
        final TestPlayer racer = new TestPlayer(202, "5.6.7.8");
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch fin = new CountDownLatch(24);
        for (int i = 0; i < 24; i++)
        {
            new Thread(() -> {
                try { go.await(); } catch (InterruptedException ignored) {}
                mgr.handle("vote", null, racer);
                fin.countDown();
            }).start();
        }
        go.countDown();
        fin.await();
        Thread.sleep(2500);
        eq(1, racer.addItemCalls.get(), "24 concurrent .vote spam -> exactly ONE reward");
        eq(1_000_000L, racer.adenaReceived.get(), "exactly one payout amount");
        eq(true, getUserDataHits.get() <= 2, "in-flight guard collapsed the API calls (got " + getUserDataHits.get() + ")");
        apiDelayMs = 0;

        // --- not voted -> no reward ---
        apiIsVoted = false;
        TestPlayer p3 = new TestPlayer(303, "9.9.9.9");
        mgr.handle("vote", null, p3);
        await(() -> !p3.messages.isEmpty() && !p3.lastMessage().contains("Checking"), 5000);
        eq(0, p3.addItemCalls.get(), "no reward when the API reports no vote");
        eq(true, p3.lastMessage().contains("vote again in") || p3.lastMessage().contains("No vote found"),
            "informative not-voted message: " + p3.lastMessage());
        eq(false, mgr.store.isOnCooldown(303, 12), "cooldown not burned when there was no vote");
        apiIsVoted = true;

        // --- API error is distinguished from network failure ---
        apiHttpCode = 403;
        TestPlayer p4 = new TestPlayer(404, "8.8.8.8");
        mgr.handle("vote", null, p4);
        await(() -> !p4.messages.isEmpty() && !p4.lastMessage().contains("Checking"), 5000);
        eq(true, p4.lastMessage().contains("refused"), "403 surfaces as an API error, not 'unreachable': " + p4.lastMessage());
        eq(0, p4.addItemCalls.get(), "no reward on API error");
        apiHttpCode = 200;

        // --- level gate ---
        TestPlayer low = new TestPlayer(505, "7.7.7.7") { public int getLevel() { return 5; } };
        mgr.handle("vote", null, low);
        eq(true, low.lastMessage().contains("level 20"), "level gate enforced: " + low.lastMessage());
        eq(0, low.addItemCalls.get(), "no reward below MinPlayerLevel");

        // --- global milestone: baseline, then capped burst ---
        TestPlayer g1 = new TestPlayer(601, "1.1.1.1");
        TestPlayer g2 = new TestPlayer(602, "2.2.2.2");
        mgr.world.add(g1);
        mgr.world.add(g2);

        apiTotalVotes = 100;
        mgr.runGlobalCheck();
        eq(0, g1.addItemCalls.get(), "first poll only sets the baseline, no reward");
        eq(100, mgr.store.getLastGlobalVotes(), "baseline recorded");

        apiTotalVotes = 250;                       // crosses 200 -> 1 milestone
        mgr.runGlobalCheck();
        eq(1, g1.addItemCalls.get(), "one milestone -> one grant");
        eq(5L, g1.adenaReceived.get(), "milestone grant amount");
        eq(1, g2.addItemCalls.get(), "all online players rewarded");

        apiTotalVotes = 5000;                      // 47 milestones at once
        g1.adenaReceived.set(0);
        mgr.runGlobalCheck();
        eq(15L, g1.adenaReceived.get(), "milestone burst capped at 3 (3 x 5), not 47 x 5");

        apiTotalVotes = 10;                        // counter reset
        g1.adenaReceived.set(0);
        mgr.runGlobalCheck();
        eq(0L, g1.adenaReceived.get(), "counter reset re-baselines instead of paying out");
        eq(10, mgr.store.getLastGlobalVotes(), "re-baselined to the new total");

        // --- the scheduler entry point offloads to the IO pool and never overlaps ---
        apiTotalVotes = 10;
        mgr.runGlobalCheck();                     // baseline at 10
        apiDelayMs = 600;
        g1.adenaReceived.set(0);
        g2.adenaReceived.set(0);
        apiTotalVotes = 110;                      // one milestone
        long tGlobal = System.nanoTime();
        mgr.submitGlobalCheck();
        long globalMs = (System.nanoTime() - tGlobal) / 1_000_000L;
        eq(true, globalMs < 200, "scheduler tick returns immediately, poll runs on the IO pool (" + globalMs + "ms)");
        mgr.submitGlobalCheck();                  // must be skipped, not run twice
        mgr.submitGlobalCheck();
        await(() -> g1.adenaReceived.get() > 0, 5000);
        Thread.sleep(1200);
        eq(5L, g1.adenaReceived.get(), "overlapping ticks skipped — milestone paid exactly once (3 concurrent ticks would pay 15)");
        apiDelayMs = 0;

        // --- offline traders excluded by default ---
        apiTotalVotes = 110;
        mgr.runGlobalCheck();
        apiTotalVotes = 10;
        mgr.runGlobalCheck();
        OfflineTrader ot = new OfflineTrader(701, "3.3.3.3");
        mgr.world.add(ot);
        apiTotalVotes = 200;
        mgr.runGlobalCheck();
        eq(0, ot.addItemCalls.get(), "offline trader skipped by default");

        mgr.shutdown();
        server.stop(0);
        new Thread(() -> { try { Thread.sleep(1500); } catch (Exception e) {} System.exit(failed > 0 ? 1 : 0); }).start();
    }

    public static class OfflineTrader extends TestPlayer
    {
        public OfflineTrader(int id, String ip) { super(id, ip); }
        public boolean isInOfflineMode() { return true; }
    }

    // -------------------------------------------------------------- helpers

    interface Cond { boolean ok(); }

    static void await(Cond c, long timeoutMs)
    {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end)
        {
            if (c.ok()) return;
            try { Thread.sleep(25); } catch (InterruptedException ignored) { return; }
        }
    }

    static void section(String name)
    {
        System.out.println("\n--- " + name + " ---");
    }

    static void eq(Object expected, Object actual, String what)
    {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (!ok && expected instanceof Number && actual instanceof Number)
            ok = ((Number) expected).longValue() == ((Number) actual).longValue();
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what + "  (expected=" + expected + " actual=" + actual + ")"); }
    }
}
