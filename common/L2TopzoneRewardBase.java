package l2topzone;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Pack-agnostic vote-reward logic. Per-pack subclasses provide the small bits
 * that differ between L2j forks: how to schedule a periodic task, how to list
 * online players, and how to register the voiced command handler.
 *
 * <h2>Threading</h2>
 * <p>A voiced command runs on the packet-processing thread. The vote API is a
 * network call, so it is never made there — {@link #handle} does only cheap local
 * checks and then hands the request to a small bounded IO pool owned by this
 * class. That keeps a slow or unreachable API from stalling the game loop, and
 * the bound (see {@code MaxConcurrentRequests}) keeps a burst of {@code .vote}
 * spam from opening an unbounded number of sockets.</p>
 *
 * <h2>Double-reward safety</h2>
 * <p>Two guards, deliberately layered:</p>
 * <ol>
 *   <li>an in-flight set, so one character cannot have two API checks running;</li>
 *   <li>{@link L2TopzoneStore#tryClaim}, a compare-and-set on the cooldown that
 *       decides a single winner even if the first guard is somehow bypassed.</li>
 * </ol>
 * <p>The claim is taken <em>after</em> the vote is confirmed but <em>before</em>
 * items are handed out, and released again if delivery fails, so a failed reward
 * never burns the player's cooldown.</p>
 *
 * <p>All player interaction goes through {@link L2TopzonePlayers} reflection, so
 * the shipped JAR stays portable across pack revisions.</p>
 */
public abstract class L2TopzoneRewardBase
{
    protected static final Logger LOG = Logger.getLogger("L2Topzone");
    private static final String PROCESS = "L2TopzoneVote";

    protected volatile L2TopzoneConfig cfg;
    protected volatile L2TopzoneAPI api;
    protected L2TopzoneStore store;

    private String configPath;
    private ThreadPoolExecutor io;
    private ScheduledExecutorService housekeeping;

    /** Characters with an API check in progress. */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean globalCheckRunning =
        new java.util.concurrent.atomic.AtomicBoolean();

    private volatile boolean hwidWarned = false;

    // ---- pack-specific hooks --------------------------------------------------

    /** Register the voiced command handler with the pack's handler registry. */
    protected abstract void registerHandler();

    /** Schedule {@code task} every {@code periodSec} seconds, first run after {@code initialSec}. */
    protected abstract void schedulePeriodic(Runnable task, long initialSec, long periodSec);

    /** Iterate currently online players. */
    protected abstract Collection<?> getOnlinePlayers();

    /** Optional pack name for log messages. */
    protected String packName()
    {
        String pkg = getClass().getPackage() == null ? "" : getClass().getPackage().getName();
        return pkg.startsWith("l2topzone.") ? pkg.substring("l2topzone.".length()) : "generic";
    }

    // ---- lifecycle ------------------------------------------------------------

    public final void init(String configPath, String storePath)
    {
        this.configPath = configPath;
        L2TopzoneConfig loaded = L2TopzoneConfig.load(configPath);
        if (loaded == null)
        {
            LOG.severe("[L2Topzone] Vote rewards DISABLED.");
            return;
        }
        this.cfg = loaded;
        this.api = newApi(loaded);
        this.store = new L2TopzoneStore(loaded.storeFile != null ? loaded.storeFile : storePath);

        startExecutors();

        try
        {
            registerHandler();
        }
        catch (Throwable t)
        {
            LOG.severe("[L2Topzone] Could not register the voiced command handler: " + t
                + " — players will not be able to use ." + cfg.voiceCommand() + ".");
        }

        if (cfg.globalEnabled)
        {
            // The pack's scheduler only triggers the poll; the HTTP call itself is
            // handed to the IO pool so no pack thread ever blocks on the network.
            schedulePeriodic(this::submitGlobalCheck, cfg.globalInitialDelaySeconds, cfg.globalCheckMinutes * 60L);
        }

        // Confirm the key and IP whitelist now, so misconfiguration shows up in the
        // boot log instead of as "no vote found" for every player who tries.
        io.execute(api::logConnectivity);

        LOG.info("[L2Topzone] " + packName() + " vote-reward manager ready. Command(s): ."
            + String.join(", .", cfg.voiceCommands));
    }

    private static L2TopzoneAPI newApi(L2TopzoneConfig c)
    {
        return new L2TopzoneAPI(c.apiHost, c.apiKey, c.httpTimeoutSeconds, c.httpTimeoutSeconds);
    }

    private void startExecutors()
    {
        ThreadFactory factory = new ThreadFactory()
        {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "L2Topzone-" + n.incrementAndGet());
                t.setDaemon(true);
                // Below normal: vote HTTP must never compete with the game loop.
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };
        io = new ThreadPoolExecutor(1, cfg.maxConcurrentRequests, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), factory, new ThreadPoolExecutor.AbortPolicy());
        io.allowCoreThreadTimeOut(true);

        housekeeping = Executors.newSingleThreadScheduledExecutor(factory);
        // Bound state loss to ~30s if the process is killed without running shutdown hooks.
        housekeeping.scheduleWithFixedDelay(this::flushQuietly, 30L, 30L, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "L2Topzone-Shutdown"));
    }

    private void flushQuietly()
    {
        try
        {
            store.flush();
        }
        catch (Throwable t)
        {
            LOG.warning("[L2Topzone] Store flush failed: " + t.getMessage());
        }
    }

    /** Stops the worker pools and persists pending state. Idempotent. */
    public final void shutdown()
    {
        if (io != null)
        {
            io.shutdownNow();
        }
        if (housekeeping != null)
        {
            housekeeping.shutdownNow();
        }
        if (store != null)
        {
            store.flush();
        }
    }

    /**
     * Normalises a pack's online-player accessor result: forks variously return a
     * {@code Collection<Player>} or a {@code Map<Integer, Player>}.
     */
    protected static Collection<?> asPlayerCollection(Object worldResult)
    {
        return L2TopzonePlayers.asPlayerCollection(worldResult);
    }

    /** Command names to hand back to the pack's handler registry. */
    public final String[] voicedCommands()
    {
        L2TopzoneConfig c = cfg;
        return c == null ? new String[]{"vote"} : c.voiceCommands.clone();
    }

    // ---- voiced command handler entry point ----------------------------------

    /** @deprecated kept for source installs written against 1.x; prefer {@link #handle(String, String, Object)}. */
    @Deprecated
    public final boolean handle(String command, Object player)
    {
        return handle(command, null, player);
    }

    /**
     * Called by the per-pack shim from its IVoicedCommandHandler implementation.
     *
     * <p>Returns quickly in every path: the only work done on the calling thread
     * is local bookkeeping. The API round-trip happens on the IO pool.</p>
     *
     * @param player real player object (pack-specific type, treated as Object here)
     * @return true if the command was handled
     */
    public final boolean handle(String command, String params, Object player)
    {
        final L2TopzoneConfig c = cfg;
        if (c == null || player == null || !c.matchesCommand(command))
        {
            return false;
        }

        final int charId = L2TopzonePlayers.objectId(player);
        if (charId == 0)
        {
            return true;
        }

        String sub = params == null ? "" : params.trim();
        if (!sub.isEmpty() && L2TopzonePlayers.isGm(player))
        {
            return adminCommand(sub, player);
        }

        if (c.minPlayerLevel > 1 && L2TopzonePlayers.level(player) < c.minPlayerLevel)
        {
            tell(player, c.msg("LowLevel", "level", String.valueOf(c.minPlayerLevel)));
            return true;
        }

        if (store.isOnCooldown(charId, c.cooldownHours))
        {
            tell(player, c.msg("Cooldown", "time", formatHms(store.cooldownSecondsLeft(charId, c.cooldownHours))));
            return true;
        }

        final String hwid = resolveHwid(player, c);
        if (c.hwidProtection && hwid != null && !hwid.isEmpty()
            && !store.canRewardHwid(hwid, charId, c.maxAccountsPerHwid, c.hwidWindowHours))
        {
            tell(player, c.msg("Hwid"));
            return true;
        }

        final String ip = L2TopzonePlayers.resolveIp(player);
        if (ip == null)
        {
            tell(player, c.msg("NoIp"));
            LOG.warning("[L2Topzone] Could not resolve an IP for " + L2TopzonePlayers.name(player)
                + " (client class: " + describeClient(player) + ").");
            return true;
        }

        if (!inFlight.add(charId))
        {
            tell(player, c.msg("InProgress"));
            return true;
        }

        try
        {
            io.execute(() ->
            {
                try
                {
                    processVote(c, player, charId, ip, hwid);
                }
                catch (Throwable t)
                {
                    LOG.warning("[L2Topzone] Vote processing failed for " + L2TopzonePlayers.name(player) + ": " + t);
                }
                finally
                {
                    inFlight.remove(charId);
                }
            });
            tell(player, c.msg("Checking"));
        }
        catch (RejectedExecutionException reject)
        {
            inFlight.remove(charId);
            tell(player, c.msg("Busy"));
        }
        return true;
    }

    /** Runs on the IO pool — blocking calls are expected here. */
    private void processVote(L2TopzoneConfig c, Object player, int charId, String ip, String hwid)
    {
        L2TopzoneAPI.VoteStatus s = api.getUserData(ip);

        if (s.status == L2TopzoneAPI.Status.TRANSPORT_ERROR)
        {
            tell(player, c.msg("ApiDown"));
            if (c.debugLog)
            {
                LOG.info("[L2Topzone] getUserData transport error for " + ip + ": " + s.error);
            }
            return;
        }
        if (s.status == L2TopzoneAPI.Status.API_ERROR)
        {
            tell(player, c.msg("ApiError"));
            LOG.warning("[L2Topzone] getUserData rejected (key " + api.maskedKey() + "): " + s.error);
            return;
        }

        if (!s.isVoted)
        {
            // voteTime is set when the IP did vote but that vote was already
            // rewarded — tell the player when the next one becomes available
            // instead of the misleading "you did not vote".
            long remaining = secondsUntilNextVote(s, c);
            tell(player, remaining > 0
                ? c.msg("AlreadyRewarded", "time", formatHms(remaining))
                : c.msg("NoVote"));
            return;
        }

        if (!L2TopzonePlayers.isOnline(player))
        {
            return; // logged out while we were waiting on the API
        }

        // Atomic claim: the single source of truth for "who gets paid".
        if (!store.tryClaim(charId, c.cooldownHours))
        {
            tell(player, c.msg("Cooldown", "time", formatHms(store.cooldownSecondsLeft(charId, c.cooldownHours))));
            return;
        }

        boolean delivered = true;
        for (int i = 0; i < c.individualItemIds.length; i++)
        {
            if (!L2TopzonePlayers.addItem(player, PROCESS, c.individualItemIds[i], c.individualItemCounts[i]))
            {
                delivered = false;
                break;
            }
        }

        if (!delivered)
        {
            // Give the cooldown back — the player did not receive the reward.
            store.releaseClaim(charId);
            tell(player, c.msg("InventoryFull"));
            return;
        }

        if (hwid != null && !hwid.isEmpty())
        {
            store.recordHwid(hwid, charId);
        }

        if (!api.confirmVote(ip))
        {
            // The reward is already delivered; the local cooldown prevents a repeat
            // here, but the vote stays claimable via another server-side path.
            LOG.warning("[L2Topzone] Reward delivered to " + L2TopzonePlayers.name(player)
                + " but confirmVote(" + ip + ") failed — the API may still list the vote as unrewarded.");
        }

        tell(player, c.msg("Rewarded"));
        if (c.debugLog)
        {
            LOG.info("[L2Topzone] Rewarded " + L2TopzonePlayers.name(player) + " (charId=" + charId + ", ip=" + ip + ")");
        }
    }

    /** Seconds until the IP may vote again, or 0 when that cannot be determined. */
    private static long secondsUntilNextVote(L2TopzoneAPI.VoteStatus s, L2TopzoneConfig c)
    {
        if (s.voteTime <= 0)
        {
            return 0L;
        }
        long nowSec = s.serverTime > 0 ? s.serverTime : System.currentTimeMillis() / 1000L;
        long next = s.voteTime + (long) c.cooldownHours * 3600L;
        return Math.max(0L, next - nowSec);
    }

    // ---- admin subcommands ----------------------------------------------------

    private boolean adminCommand(String sub, Object player)
    {
        if ("reload".equalsIgnoreCase(sub))
        {
            L2TopzoneConfig fresh = L2TopzoneConfig.load(configPath);
            if (fresh == null)
            {
                tell(player, cfg.msg("ReloadFailed"));
                return true;
            }
            String[] before = cfg.voiceCommands;
            cfg = fresh;
            api = newApi(fresh);
            tell(player, fresh.msg("Reloaded"));
            LOG.info("[L2Topzone] Configuration reloaded by " + L2TopzonePlayers.name(player) + ".");
            if (!java.util.Arrays.equals(before, fresh.voiceCommands))
            {
                tell(player, "Command names changed — a server restart is required for that.");
            }
            return true;
        }

        if ("status".equalsIgnoreCase(sub))
        {
            Map<String, Integer> st = store.stats();
            tell(player, "cooldowns: " + st.get("cooldowns")
                + ", machines: " + st.get("machines") + ", bindings: " + st.get("bindings")
                + ", global baseline: " + (store.hasGlobalBaseline() ? String.valueOf(store.getLastGlobalVotes()) : "not set"));
            io.execute(() ->
            {
                L2TopzoneAPI.ServerData sd = api.getServerData();
                tell(player, (sd.ok()
                    ? "API ok — total votes: " + sd.totalVotes + ", rank: " + sd.serverRank
                    : "API " + sd.status + ": " + sd.error));
            });
            return true;
        }

        tell(player, "Subcommands: reload, status");
        return true;
    }

    // ---- global milestone reward ---------------------------------------------

    /**
     * Hands the poll to the IO pool. The running flag stops a slow poll from
     * overlapping the next tick — packs that use {@code scheduleAtFixedRate} fire
     * on a fixed clock regardless of how long the previous run took.
     */
    private void submitGlobalCheck()
    {
        if (!globalCheckRunning.compareAndSet(false, true))
        {
            LOG.warning("[L2Topzone] Previous global poll is still running; skipping this tick.");
            return;
        }
        try
        {
            io.execute(() ->
            {
                try
                {
                    checkGlobal();
                }
                finally
                {
                    globalCheckRunning.set(false);
                }
            });
        }
        catch (RejectedExecutionException reject)
        {
            globalCheckRunning.set(false);
            LOG.warning("[L2Topzone] Global poll skipped — the request pool is saturated.");
        }
    }

    private void checkGlobal()
    {
        final L2TopzoneConfig c = cfg;
        if (c == null || !c.globalEnabled)
        {
            return;
        }
        try
        {
            L2TopzoneAPI.ServerData sd = api.getServerData();
            if (!sd.ok())
            {
                if (c.debugLog)
                {
                    LOG.info("[L2Topzone] Global poll skipped (" + sd.status + "): " + sd.error);
                }
                return;
            }

            if (!store.hasGlobalBaseline())
            {
                store.setLastGlobalVotes(sd.totalVotes);
                LOG.info("[L2Topzone] Global vote baseline set to " + sd.totalVotes + ".");
                return;
            }

            final int prev = store.getLastGlobalVotes();
            if (sd.totalVotes < prev)
            {
                // Toplist counters reset periodically; re-baseline instead of
                // computing a negative delta.
                LOG.info("[L2Topzone] Server vote total went " + prev + " -> " + sd.totalVotes
                    + " (counter reset); re-baselining.");
                store.setLastGlobalVotes(sd.totalVotes);
                return;
            }

            int passed = (sd.totalVotes / c.globalVoteInterval) - (prev / c.globalVoteInterval);
            if (passed > c.globalMaxMilestonesPerCheck)
            {
                LOG.warning("[L2Topzone] " + passed + " milestones crossed in one poll (" + prev + " -> "
                    + sd.totalVotes + "); capping at GlobalMaxMilestonesPerCheck=" + c.globalMaxMilestonesPerCheck + ".");
                passed = c.globalMaxMilestonesPerCheck;
            }

            if (passed > 0)
            {
                grantGlobal(c, sd.totalVotes, passed);
            }
            store.setLastGlobalVotes(sd.totalVotes);
        }
        catch (Throwable t)
        {
            LOG.warning("[L2Topzone] Global check failed: " + t);
        }
    }

    private void grantGlobal(L2TopzoneConfig c, int totalVotes, int multiplier)
    {
        Collection<?> online;
        try
        {
            online = getOnlinePlayers();
        }
        catch (Throwable t)
        {
            LOG.warning("[L2Topzone] Could not list online players: " + t);
            return;
        }
        if (online == null || online.isEmpty())
        {
            LOG.info("[L2Topzone] Global milestone at " + totalVotes + " votes, but nobody is online.");
            return;
        }

        final String announce = c.msg("GlobalReward", "votes", String.valueOf(totalVotes));
        int rewarded = 0;
        for (Object p : online)
        {
            if (p == null)
            {
                continue;
            }
            if (!c.globalRewardOfflineTraders && L2TopzonePlayers.isOfflineTrader(p))
            {
                continue;
            }
            boolean any = false;
            for (int i = 0; i < c.globalItemIds.length; i++)
            {
                if (L2TopzonePlayers.addItem(p, PROCESS, c.globalItemIds[i], c.globalItemCounts[i] * multiplier))
                {
                    any = true;
                }
            }
            if (any)
            {
                tell(p, announce);
                rewarded++;
            }
        }
        LOG.info("[L2Topzone] Global milestone at " + totalVotes + " votes (x" + multiplier + ") — "
            + rewarded + " player(s) rewarded.");
    }

    // ---- helpers --------------------------------------------------------------

    private void tell(Object player, String text)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }
        L2TopzoneConfig c = cfg;
        String prefix = c == null ? "" : c.msg("Prefix");
        L2TopzonePlayers.sendMessage(player, prefix.isEmpty() ? text : prefix + " " + text);
    }

    private String resolveHwid(Object player, L2TopzoneConfig c)
    {
        String hwid = L2TopzonePlayers.resolveHwid(player);
        if (hwid == null && c.hwidProtection && !hwidWarned)
        {
            hwidWarned = true;
            LOG.warning("[L2Topzone] This pack/revision does not expose a HWID — HWID protection is inactive."
                + " The per-character cooldown and the API's per-IP window still apply.");
        }
        return hwid;
    }

    private static String describeClient(Object player)
    {
        Object client = L2TopzonePlayers.call(player, "getClient");
        return client == null ? "getClient() returned null" : client.getClass().getName();
    }

    protected static String formatHms(long sec)
    {
        long s = Math.max(0L, sec);
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
