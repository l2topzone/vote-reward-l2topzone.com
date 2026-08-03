package l2topzone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Persistent store for vote-reward state. Flat text format, one record per line:
 * <pre>
 *   VERSION  2
 *   GLOBAL   &lt;lastTotalVotes&gt;
 *   COOLDOWN &lt;charId&gt; &lt;unixSeconds&gt;
 *   HWID     &lt;hwid&gt; &lt;charId&gt; &lt;unixSeconds&gt;
 * </pre>
 *
 * <p>Writes are atomic: the file is rendered to a sibling {@code .tmp} and then
 * moved into place, so a crash mid-save can never leave a truncated store (which
 * would wipe every cooldown and let the whole server re-claim).</p>
 *
 * <p>Expired records are purged on load and on save, so the file stays proportional
 * to active players rather than growing for the lifetime of the server.</p>
 *
 * <p>Version 1 files (HWID lines without a timestamp) are still readable. Those
 * bindings were permanent by construction; they load as expired so the documented
 * "N characters per HWID per window" behaviour takes effect immediately.</p>
 *
 * Pack-agnostic.
 */
public final class L2TopzoneStore
{
    private static final Logger LOG = Logger.getLogger("L2Topzone");
    private static final int FORMAT_VERSION = 2;
    private static final long SAVE_INTERVAL_MS = 15_000L;

    private final File file;
    private final File tmpFile;

    private final Map<Long, Long> cooldownByCharId = new ConcurrentHashMap<>();
    /** hwid -> (charId -> unix seconds of the reward that bound it). */
    private final Map<String, Map<Long, Long>> hwidBindings = new ConcurrentHashMap<>();

    private volatile int lastGlobalVotes = 0;
    /** False until a poll has established a baseline; distinguishes "unknown" from "genuinely zero". */
    private volatile boolean globalBaselineSet = false;

    private volatile long lastSave = 0L;
    private volatile boolean dirty = false;
    private volatile boolean shuttingDown = false;

    public L2TopzoneStore(String filePath)
    {
        this.file = new File(filePath);
        this.tmpFile = new File(filePath + ".tmp");
        load();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "L2Topzone-Store-Shutdown"));
    }

    // ---- cooldown -------------------------------------------------------------

    public boolean isOnCooldown(long charId, int hours)
    {
        Long ts = cooldownByCharId.get(charId);
        if (ts == null)
        {
            return false;
        }
        return (now() - ts) < (long) hours * 3600L;
    }

    public long cooldownSecondsLeft(long charId, int hours)
    {
        Long ts = cooldownByCharId.get(charId);
        if (ts == null)
        {
            return 0L;
        }
        return Math.max(0L, (long) hours * 3600L - (now() - ts));
    }

    /**
     * Atomically claims the reward slot for this character.
     *
     * <p>This is the concurrency gate for the whole reward flow: it both checks
     * and sets in one step, so two simultaneous {@code .vote} requests can never
     * both observe "not on cooldown" and both pay out.</p>
     *
     * @return true when the caller now owns the claim, false when a cooldown was already active.
     */
    public boolean tryClaim(long charId, int hours)
    {
        final long now = now();
        final long window = (long) hours * 3600L;
        while (true)
        {
            Long existing = cooldownByCharId.get(charId);
            if (existing != null && (now - existing) < window)
            {
                return false;
            }
            boolean won = (existing == null)
                ? cooldownByCharId.putIfAbsent(charId, now) == null
                : cooldownByCharId.replace(charId, existing, now);
            if (won)
            {
                markDirty();
                return true;
            }
            // Lost the race to a concurrent claim — re-read and re-evaluate.
        }
    }

    /** Releases a claim taken by {@link #tryClaim} when the reward could not be delivered. */
    public void releaseClaim(long charId)
    {
        if (cooldownByCharId.remove(charId) != null)
        {
            markDirty();
        }
    }

    // ---- HWID -----------------------------------------------------------------

    /**
     * @param maxAccounts distinct characters allowed per HWID inside the window
     * @param windowHours how far back bindings count
     * @return true when this character may be rewarded from this machine.
     */
    public boolean canRewardHwid(String hwid, long charId, int maxAccounts, int windowHours)
    {
        if (hwid == null || hwid.isEmpty())
        {
            return true;
        }
        Map<Long, Long> bound = hwidBindings.get(hwid);
        if (bound == null)
        {
            return true;
        }
        final long cutoff = now() - (long) windowHours * 3600L;
        int active = 0;
        for (Map.Entry<Long, Long> e : bound.entrySet())
        {
            if (e.getValue() < cutoff)
            {
                continue; // expired binding, ignore
            }
            if (e.getKey().longValue() == charId)
            {
                return true; // this character already holds a slot
            }
            active++;
        }
        return active < maxAccounts;
    }

    public void recordHwid(String hwid, long charId)
    {
        if (hwid == null || hwid.isEmpty())
        {
            return;
        }
        hwidBindings.computeIfAbsent(hwid, k -> new ConcurrentHashMap<>()).put(charId, now());
        markDirty();
    }

    // ---- global milestone -----------------------------------------------------

    public boolean hasGlobalBaseline()
    {
        return globalBaselineSet;
    }

    public int getLastGlobalVotes()
    {
        return lastGlobalVotes;
    }

    public void setLastGlobalVotes(int v)
    {
        if (!globalBaselineSet || lastGlobalVotes != v)
        {
            lastGlobalVotes = v;
            globalBaselineSet = true;
            markDirty();
        }
    }

    // ---- persistence ----------------------------------------------------------

    private void markDirty()
    {
        dirty = true;
        long ts = System.currentTimeMillis();
        if ((ts - lastSave) > SAVE_INTERVAL_MS)
        {
            save();
        }
    }

    /** Writes pending changes if there are any. Safe to call from a scheduler. */
    public void flush()
    {
        if (dirty)
        {
            save();
        }
    }

    private void shutdown()
    {
        shuttingDown = true;
        flush();
    }

    public synchronized void save()
    {
        if (!dirty && file.exists())
        {
            return;
        }
        try
        {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
            {
                parent.mkdirs();
            }

            purgeExpired();

            try (BufferedWriter w = Files.newBufferedWriter(tmpFile.toPath(), StandardCharsets.UTF_8))
            {
                w.write("# L2Topzone vote-reward state — do not edit while the server is running");
                w.newLine();
                w.write("VERSION " + FORMAT_VERSION);
                w.newLine();
                if (globalBaselineSet)
                {
                    w.write("GLOBAL " + lastGlobalVotes);
                    w.newLine();
                }
                for (Map.Entry<Long, Long> e : cooldownByCharId.entrySet())
                {
                    w.write("COOLDOWN " + e.getKey() + " " + e.getValue());
                    w.newLine();
                }
                for (Map.Entry<String, Map<Long, Long>> e : hwidBindings.entrySet())
                {
                    for (Map.Entry<Long, Long> b : e.getValue().entrySet())
                    {
                        w.write("HWID " + e.getKey() + " " + b.getKey() + " " + b.getValue());
                        w.newLine();
                    }
                }
                w.flush();
            }

            moveIntoPlace();
            lastSave = System.currentTimeMillis();
            dirty = false;
        }
        catch (IOException ex)
        {
            LOG.warning("[L2Topzone] Failed to save store " + file.getPath() + ": " + ex.getMessage());
        }
    }

    private void moveIntoPlace() throws IOException
    {
        Path src = tmpFile.toPath();
        Path dst = file.toPath();
        try
        {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            // Some Windows/network filesystems refuse ATOMIC_MOVE; a plain replace is
            // still far better than truncating the live file before writing it.
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Drops records that can no longer affect a decision. Cooldowns are kept for
     * 30 days and HWID bindings for a year — comfortably longer than any sane
     * window, so purging never changes behaviour, it only bounds the file size.
     */
    private void purgeExpired()
    {
        final long now = now();
        final long cooldownCutoff = now - 30L * 24L * 3600L;
        final long hwidCutoff = now - 365L * 24L * 3600L;

        cooldownByCharId.entrySet().removeIf(e -> e.getValue() < cooldownCutoff);

        Iterator<Map.Entry<String, Map<Long, Long>>> it = hwidBindings.entrySet().iterator();
        while (it.hasNext())
        {
            Map<Long, Long> bound = it.next().getValue();
            bound.entrySet().removeIf(e -> e.getValue() < hwidCutoff);
            if (bound.isEmpty())
            {
                it.remove();
            }
        }
    }

    private void load()
    {
        // Recover from a crash between "tmp written" and "moved into place".
        if (!file.exists() && tmpFile.exists())
        {
            try
            {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("[L2Topzone] Recovered store from an interrupted save.");
            }
            catch (IOException ignored)
            {
                // fall through — a missing store simply starts empty
            }
        }
        if (!file.exists())
        {
            return;
        }

        int version = 1;
        int legacyHwid = 0;
        List<String> malformed = new ArrayList<>();

        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
        {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null)
            {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#')
                {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2)
                {
                    continue;
                }
                try
                {
                    switch (parts[0])
                    {
                        case "VERSION":
                            version = Integer.parseInt(parts[1]);
                            break;
                        case "GLOBAL":
                            lastGlobalVotes = Integer.parseInt(parts[1]);
                            globalBaselineSet = true;
                            break;
                        case "COOLDOWN":
                            if (parts.length >= 3)
                            {
                                cooldownByCharId.put(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
                            }
                            break;
                        case "HWID":
                            if (parts.length >= 4)
                            {
                                hwidBindings.computeIfAbsent(parts[1], k -> new ConcurrentHashMap<>())
                                    .put(Long.parseLong(parts[2]), Long.parseLong(parts[3]));
                            }
                            else if (parts.length == 3)
                            {
                                // v1 record: no timestamp. Load as expired (see class javadoc).
                                hwidBindings.computeIfAbsent(parts[1], k -> new ConcurrentHashMap<>())
                                    .put(Long.parseLong(parts[2]), 0L);
                                legacyHwid++;
                            }
                            break;
                        default:
                            malformed.add("line " + lineNo + ": unknown record '" + parts[0] + "'");
                    }
                }
                catch (NumberFormatException nfe)
                {
                    malformed.add("line " + lineNo + ": " + line);
                }
            }
        }
        catch (IOException ex)
        {
            LOG.warning("[L2Topzone] Failed to load store " + file.getPath() + ": " + ex.getMessage()
                + " — starting with empty state.");
            return;
        }

        int hwidCount = 0;
        for (Map<Long, Long> m : hwidBindings.values())
        {
            hwidCount += m.size();
        }
        LOG.info("[L2Topzone] Store loaded (v" + version + "): " + cooldownByCharId.size()
            + " cooldown entries, " + hwidCount + " HWID bindings across " + hwidBindings.size() + " machines.");
        if (legacyHwid > 0)
        {
            LOG.info("[L2Topzone] Migrated " + legacyHwid + " v1 HWID binding(s) — they were permanent and are now"
                + " treated as expired, so HwidWindowHours applies from here on.");
        }
        if (!malformed.isEmpty())
        {
            LOG.warning("[L2Topzone] Skipped " + malformed.size() + " malformed store record(s), first: " + malformed.get(0));
        }
    }

    /** Snapshot counters for the admin status command. */
    public Map<String, Integer> stats()
    {
        int hwidCount = 0;
        for (Map<Long, Long> m : hwidBindings.values())
        {
            hwidCount += m.size();
        }
        Map<String, Integer> out = new HashMap<>();
        out.put("cooldowns", cooldownByCharId.size());
        out.put("machines", hwidBindings.size());
        out.put("bindings", hwidCount);
        return out;
    }

    public boolean isShuttingDown()
    {
        return shuttingDown;
    }

    private static long now()
    {
        return System.currentTimeMillis() / 1000L;
    }
}
