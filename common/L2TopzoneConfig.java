package l2topzone;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Loads L2TopzoneVoteReward.properties and exposes typed accessors.
 * Pack-agnostic — no L2j imports.
 *
 * <p>Every value is range-checked at load time. A config that would misbehave at
 * runtime (zero item counts, mismatched id/count lists, a 0-minute poll interval
 * that would hammer the API) is rejected at boot with an explicit log line rather
 * than failing silently hours later.</p>
 */
public final class L2TopzoneConfig
{
    private static final Logger LOG = Logger.getLogger("L2Topzone");

    public String apiHost;
    public String apiKey;
    /** Voiced command plus any aliases; all are registered with the pack handler. */
    public String[] voiceCommands;
    public int cooldownHours;
    public int minPlayerLevel;
    public int httpTimeoutSeconds;
    public int maxConcurrentRequests;
    /** Overrides the pack default when set; null keeps the manager-supplied path. */
    public String storeFile;
    /** Universal build only: pack root package when auto-detection cannot find it. */
    public String packBasePackage;

    public int[] individualItemIds;
    public long[] individualItemCounts;

    public boolean globalEnabled;
    public int globalVoteInterval;
    public int[] globalItemIds;
    public long[] globalItemCounts;
    public int globalCheckMinutes;
    public int globalInitialDelaySeconds;
    /**
     * Upper bound on milestones credited in one poll. Without it, a deleted store
     * file or a monthly counter reset can multiply the payout arbitrarily.
     */
    public int globalMaxMilestonesPerCheck;
    public boolean globalRewardOfflineTraders;

    public boolean hwidProtection;
    public int maxAccountsPerHwid;
    /** Window the HWID limit applies over. Defaults to cooldownHours. */
    public int hwidWindowHours;

    public boolean debugLog;

    private final Map<String, String> messages = new HashMap<>();

    /** @return the first configured command name — used for log lines and help text. */
    public String voiceCommand()
    {
        return voiceCommands.length > 0 ? voiceCommands[0] : "vote";
    }

    public boolean matchesCommand(String command)
    {
        if (command == null)
        {
            return false;
        }
        String c = command.trim();
        for (String v : voiceCommands)
        {
            if (v.equalsIgnoreCase(c))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Localised message with {@code {placeholder}} substitution.
     * @param args alternating key/value pairs, e.g. {@code msg("Cooldown", "time", "01:23:45")}
     */
    public String msg(String key, String... args)
    {
        String text = messages.get(key);
        if (text == null)
        {
            return "";
        }
        for (int i = 0; (i + 1) < args.length; i += 2)
        {
            text = text.replace("{" + args[i] + "}", args[i + 1]);
        }
        return text;
    }

    public static L2TopzoneConfig load(String propertiesPath)
    {
        L2TopzoneConfig c = new L2TopzoneConfig();
        Properties p = new Properties();
        // Explicit UTF-8: message overrides routinely contain non-ASCII (RO/RU/GR servers)
        // and Properties.load(InputStream) would decode them as ISO-8859-1.
        try (FileInputStream fis = new FileInputStream(propertiesPath);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8))
        {
            p.load(reader);
        }
        catch (FileNotFoundException e)
        {
            LOG.severe("[L2Topzone] Config file not found: " + propertiesPath);
            return null;
        }
        catch (IOException e)
        {
            LOG.severe("[L2Topzone] Failed to read config: " + e.getMessage());
            return null;
        }

        c.apiHost = p.getProperty("ApiHost", "https://api.l2topzone.com/v1").trim();
        c.apiKey = p.getProperty("ApiKey", "").trim();
        c.voiceCommands = parseCommands(p.getProperty("VoiceCommand", "vote"));
        c.cooldownHours = clamp(parseInt(p, "CooldownHours", 12), 1, 24 * 30, "CooldownHours");
        c.minPlayerLevel = clamp(parseInt(p, "MinPlayerLevel", 1), 1, 99, "MinPlayerLevel");
        c.httpTimeoutSeconds = clamp(parseInt(p, "HttpTimeoutSeconds", 5), 1, 30, "HttpTimeoutSeconds");
        c.maxConcurrentRequests = clamp(parseInt(p, "MaxConcurrentRequests", 4), 1, 16, "MaxConcurrentRequests");
        String store = p.getProperty("StoreFile", "").trim();
        c.storeFile = store.isEmpty() ? null : store;
        c.packBasePackage = p.getProperty("PackBasePackage", "").trim();

        c.individualItemIds = parseIntList(p, "IndividualRewardItemIds", new int[]{57});
        c.individualItemCounts = parseLongList(p, "IndividualRewardItemCount", new long[]{1_000_000L});

        c.globalEnabled = parseBool(p, "GlobalEnabled", true);
        c.globalVoteInterval = clamp(parseInt(p, "GlobalVoteInterval", 100), 1, 1_000_000, "GlobalVoteInterval");
        c.globalItemIds = parseIntList(p, "GlobalRewardItemIds", new int[]{6673});
        c.globalItemCounts = parseLongList(p, "GlobalRewardItemCount", new long[]{5L});
        c.globalCheckMinutes = clamp(parseInt(p, "GlobalCheckMinutes", 5), 1, 1440, "GlobalCheckMinutes");
        c.globalInitialDelaySeconds = clamp(parseInt(p, "GlobalInitialDelaySeconds", 60), 5, 3600, "GlobalInitialDelaySeconds");
        c.globalMaxMilestonesPerCheck = clamp(parseInt(p, "GlobalMaxMilestonesPerCheck", 5), 1, 1000, "GlobalMaxMilestonesPerCheck");
        c.globalRewardOfflineTraders = parseBool(p, "GlobalRewardOfflineTraders", false);

        c.hwidProtection = parseBool(p, "HwidProtection", true);
        c.maxAccountsPerHwid = clamp(parseInt(p, "MaxAccountsPerHwid", 1), 1, 100, "MaxAccountsPerHwid");
        c.hwidWindowHours = clamp(parseInt(p, "HwidWindowHours", c.cooldownHours), 1, 24 * 365, "HwidWindowHours");

        c.debugLog = parseBool(p, "DebugLog", false);

        c.loadMessages(p);

        if (!c.validate(propertiesPath))
        {
            return null;
        }

        LOG.info("[L2Topzone] Config loaded. Command(s): ." + String.join(", .", c.voiceCommands)
            + ", cooldown: " + c.cooldownHours + "h"
            + ", HWID protection: " + (c.hwidProtection ? c.maxAccountsPerHwid + " char(s)/" + c.hwidWindowHours + "h" : "off")
            + ", global: " + (c.globalEnabled ? "every " + c.globalVoteInterval + " votes" : "off"));
        return c;
    }

    // ---- validation ----

    private boolean validate(String path)
    {
        if (apiKey.isEmpty() || apiKey.equals("REPLACE_WITH_YOUR_API_KEY"))
        {
            LOG.severe("[L2Topzone] ApiKey is empty in " + path + " — vote rewards disabled.");
            return false;
        }
        if (!apiHost.startsWith("http://") && !apiHost.startsWith("https://"))
        {
            LOG.severe("[L2Topzone] ApiHost must start with http:// or https:// (got '" + apiHost + "').");
            return false;
        }
        if (voiceCommands.length == 0)
        {
            LOG.severe("[L2Topzone] VoiceCommand is empty — vote rewards disabled.");
            return false;
        }
        if (!checkRewardList("Individual", individualItemIds, individualItemCounts, true))
        {
            return false;
        }
        if (globalEnabled && !checkRewardList("Global", globalItemIds, globalItemCounts, true))
        {
            return false;
        }
        return true;
    }

    private static boolean checkRewardList(String label, int[] ids, long[] counts, boolean required)
    {
        if (ids.length != counts.length)
        {
            LOG.severe("[L2Topzone] " + label + "RewardItemIds (" + ids.length + " values) and "
                + label + "RewardItemCount (" + counts.length + " values) must have the same length.");
            return false;
        }
        if (required && ids.length == 0)
        {
            LOG.severe("[L2Topzone] " + label + "RewardItemIds is empty — nothing to give.");
            return false;
        }
        for (int i = 0; i < ids.length; i++)
        {
            if (ids[i] <= 0)
            {
                LOG.severe("[L2Topzone] " + label + "RewardItemIds contains an invalid item id: " + ids[i]);
                return false;
            }
            if (counts[i] <= 0)
            {
                LOG.severe("[L2Topzone] " + label + "RewardItemCount[" + i + "] must be > 0 (got " + counts[i] + ").");
                return false;
            }
        }
        return true;
    }

    // ---- messages ----

    private void loadMessages(Properties p)
    {
        // No trailing space: property values are trimmed, so the separator is
        // added at send time instead of being carried in the value.
        put(p, "Prefix", "[L2Topzone]");
        put(p, "Checking", "Checking your vote, please wait...");
        put(p, "Rewarded", "Thank you for voting! Your reward has been delivered.");
        put(p, "NoVote", "No vote found for your IP. Vote at https://l2topzone.com and try again.");
        put(p, "AlreadyRewarded", "Your last vote was already rewarded. You can vote again in {time}.");
        put(p, "Cooldown", "You can claim again in {time}.");
        put(p, "Hwid", "A vote reward was already claimed from this machine.");
        put(p, "ApiDown", "The vote service is unreachable right now. Please try again in a minute.");
        put(p, "ApiError", "The vote service refused the request. Please contact an administrator.");
        put(p, "NoIp", "Could not resolve your IP address. Please relog and try again.");
        put(p, "Busy", "Too many vote checks in progress. Please try again in a few seconds.");
        put(p, "InProgress", "Your vote check is already running.");
        put(p, "InventoryFull", "Reward could not be delivered. Free some inventory space and try again.");
        put(p, "LowLevel", "You must be at least level {level} to claim vote rewards.");
        put(p, "GlobalReward", "The server reached {votes} total votes! Global reward delivered.");
        put(p, "Reloaded", "Vote reward configuration reloaded.");
        put(p, "ReloadFailed", "Reload failed — the config file is invalid. Previous settings kept.");
    }

    /**
     * A missing key takes the English default; a key present but empty means the
     * admin deliberately wants that message silenced (or no prefix at all).
     */
    private void put(Properties p, String key, String def)
    {
        String v = p.getProperty("Msg" + key);
        messages.put(key, v == null ? def : v.trim());
    }

    // ---- parsers ----

    private static String[] parseCommands(String raw)
    {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(","))
        {
            String v = part.trim();
            // Tolerate people writing ".vote" — the pack registry expects it without the dot.
            while (v.startsWith("."))
            {
                v = v.substring(1);
            }
            if (!v.isEmpty() && !out.contains(v))
            {
                out.add(v);
            }
        }
        return out.toArray(new String[0]);
    }

    private static int clamp(int value, int min, int max, String key)
    {
        if (value < min)
        {
            LOG.warning("[L2Topzone] " + key + "=" + value + " is below the minimum " + min + " — using " + min + ".");
            return min;
        }
        if (value > max)
        {
            LOG.warning("[L2Topzone] " + key + "=" + value + " is above the maximum " + max + " — using " + max + ".");
            return max;
        }
        return value;
    }

    private static int parseInt(Properties p, String k, int def)
    {
        String raw = p.getProperty(k);
        if (raw == null || raw.trim().isEmpty())
        {
            return def;
        }
        try
        {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e)
        {
            LOG.warning("[L2Topzone] " + k + "='" + raw.trim() + "' is not a number — using default " + def + ".");
            return def;
        }
    }

    private static boolean parseBool(Properties p, String k, boolean def)
    {
        String v = p.getProperty(k);
        if (v == null || v.trim().isEmpty())
        {
            return def;
        }
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    private static int[] parseIntList(Properties p, String k, int[] def)
    {
        String raw = p.getProperty(k);
        if (raw == null || raw.trim().isEmpty())
        {
            return def;
        }
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            try
            {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            catch (NumberFormatException e)
            {
                LOG.warning("[L2Topzone] " + k + " contains a non-numeric value ('" + parts[i].trim()
                    + "') — falling back to the default list.");
                return def;
            }
        }
        return out;
    }

    private static long[] parseLongList(Properties p, String k, long[] def)
    {
        String raw = p.getProperty(k);
        if (raw == null || raw.trim().isEmpty())
        {
            return def;
        }
        String[] parts = raw.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            try
            {
                out[i] = Long.parseLong(parts[i].trim());
            }
            catch (NumberFormatException e)
            {
                LOG.warning("[L2Topzone] " + k + " contains a non-numeric value ('" + parts[i].trim()
                    + "') — falling back to the default list.");
                return def;
            }
        }
        return out;
    }
}
