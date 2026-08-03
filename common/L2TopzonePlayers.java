package l2topzone;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Every interaction with a pack's player object, funnelled through reflection so
 * the shipped JAR never binds to a fork-specific class name.
 *
 * <p>Resolution results are cached per (class, operation). The previous version
 * walked {@code getMethods()} on every single call — for the global reward that
 * meant a full scan per item per online player, every poll.</p>
 *
 * <p>Everything here is best-effort by design: an unsupported pack yields a
 * neutral answer (empty name, level 0, "assume online") instead of an exception,
 * because a missing accessor must never stop a reward that is otherwise valid.</p>
 */
final class L2TopzonePlayers
{
    private static final Logger LOG = Logger.getLogger("L2Topzone");

    private L2TopzonePlayers()
    {
    }

    /** Cache of resolved zero-arg accessors; {@link #ABSENT} marks a confirmed miss. */
    private static final Map<String, Method> ACCESSORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, AddItem> ADD_ITEM = new ConcurrentHashMap<>();
    private static final Method ABSENT;

    static
    {
        try
        {
            ABSENT = L2TopzonePlayers.class.getDeclaredMethod("absentMarker");
        }
        catch (NoSuchMethodException e)
        {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused")
    private static void absentMarker()
    {
    }

    // ---- simple accessors -----------------------------------------------------

    static int objectId(Object player)
    {
        Object r = call(player, "getObjectId");
        return r instanceof Number ? ((Number) r).intValue() : 0;
    }

    static String name(Object player)
    {
        Object r = call(player, "getName");
        return r == null ? "?" : String.valueOf(r);
    }

    static int level(Object player)
    {
        Object r = call(player, "getLevel");
        return r instanceof Number ? ((Number) r).intValue() : 0;
    }

    /**
     * @return false only when the pack positively reports the player as gone.
     *         Unknown means true — refusing a reward because we could not read a
     *         flag would be worse than granting one to someone mid-logout.
     */
    static boolean isOnline(Object player)
    {
        Object r = call(player, "isOnline");
        if (r instanceof Boolean)
        {
            return ((Boolean) r).booleanValue();
        }
        if (r instanceof Number)
        {
            return ((Number) r).intValue() != 0;
        }
        return call(player, "getClient") != null || !has(player, "getClient");
    }

    static boolean isGm(Object player)
    {
        for (String m : new String[]{"isGM", "isGm", "isAdministrator"})
        {
            Object r = call(player, m);
            if (r instanceof Boolean)
            {
                return ((Boolean) r).booleanValue();
            }
        }
        return false;
    }

    /** Offline-shop characters: bodies in town with no player behind them. */
    static boolean isOfflineTrader(Object player)
    {
        for (String m : new String[]{"isInOfflineMode", "isOffline", "isOfflineTrade"})
        {
            Object r = call(player, m);
            if (r instanceof Boolean && ((Boolean) r).booleanValue())
            {
                return true;
            }
        }
        return false;
    }

    static void sendMessage(Object player, String text)
    {
        if (player == null || text == null || text.isEmpty())
        {
            return;
        }
        try
        {
            Method m = resolve(player.getClass(), "sendMessage", String.class);
            if (m != null)
            {
                m.invoke(player, text);
            }
        }
        catch (Throwable ignored)
        {
            // A player disconnecting mid-send is routine; nothing to report.
        }
    }

    // ---- item delivery --------------------------------------------------------

    /** A resolved {@code addItem} overload plus how to call it on this pack. */
    private static final class AddItem
    {
        Method method;
        boolean longCount;
        boolean hasReference;
        boolean referenceAcceptsPlayer;
        boolean hasSendMessageFlag;
        boolean returnsItem;
    }

    /**
     * Grants {@code count} of {@code itemId}.
     *
     * @return false when the pack exposes no usable {@code addItem}, when the call
     *         threw, or when it returned null (typically a full inventory). The
     *         caller must not consume the player's vote in that case.
     */
    static boolean addItem(Object player, String process, int itemId, long count)
    {
        AddItem a = ADD_ITEM.computeIfAbsent(player.getClass(), L2TopzonePlayers::findAddItem);
        if (a.method == null)
        {
            LOG.warning("[L2Topzone] No usable addItem() found on " + player.getClass().getName()
                + " — cannot deliver rewards on this pack. Please open an issue with your pack name and revision.");
            return false;
        }
        try
        {
            Object countArg = a.longCount ? (Object) Long.valueOf(count) : (Object) Integer.valueOf((int) Math.min(count, Integer.MAX_VALUE));
            Object[] args;
            if (a.hasReference && a.hasSendMessageFlag)
            {
                args = new Object[]{process, Integer.valueOf(itemId), countArg, a.referenceAcceptsPlayer ? player : null, Boolean.TRUE};
            }
            else if (a.hasReference)
            {
                args = new Object[]{process, Integer.valueOf(itemId), countArg, a.referenceAcceptsPlayer ? player : null};
            }
            else if (a.hasSendMessageFlag)
            {
                args = new Object[]{process, Integer.valueOf(itemId), countArg, Boolean.TRUE};
            }
            else
            {
                args = new Object[]{process, Integer.valueOf(itemId), countArg};
            }
            Object result = a.method.invoke(player, args);
            // Void overloads report nothing; treat "no exception" as delivered.
            return !a.returnsItem || result != null;
        }
        catch (Throwable t)
        {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            LOG.warning("[L2Topzone] addItem(" + itemId + " x" + count + ") failed for "
                + name(player) + ": " + cause);
            return false;
        }
    }

    /**
     * Picks the best {@code addItem} overload for a pack.
     *
     * <p>Preference order is (String, int, count, reference, boolean) — the near-universal
     * L2j signature — then progressively shorter variants. A {@code long} count wins over
     * {@code int} so modern packs keep full range; on {@code int}-only packs the count is
     * clamped at call time.</p>
     */
    private static AddItem findAddItem(Class<?> owner)
    {
        AddItem best = new AddItem();
        int bestScore = -1;
        for (Method m : owner.getMethods())
        {
            if (!"addItem".equals(m.getName()))
            {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 3 || p.length > 5 || p[0] != String.class || p[1] != int.class)
            {
                continue;
            }
            boolean longCount = p[2] == long.class;
            if (!longCount && p[2] != int.class)
            {
                continue;
            }

            boolean hasReference = false;
            boolean refAcceptsPlayer = false;
            boolean hasFlag = false;

            if (p.length == 5)
            {
                if (p[4] != boolean.class || p[3].isPrimitive())
                {
                    continue;
                }
                hasReference = true;
                refAcceptsPlayer = p[3].isAssignableFrom(owner);
                hasFlag = true;
            }
            else if (p.length == 4)
            {
                if (p[3] == boolean.class)
                {
                    hasFlag = true;
                }
                else if (!p[3].isPrimitive())
                {
                    hasReference = true;
                    refAcceptsPlayer = p[3].isAssignableFrom(owner);
                }
                else
                {
                    continue;
                }
            }

            int score = (p.length == 5 ? 40 : p.length == 4 ? 20 : 10) + (longCount ? 5 : 0)
                + (refAcceptsPlayer ? 2 : 0);
            if (score > bestScore)
            {
                bestScore = score;
                best.method = m;
                best.longCount = longCount;
                best.hasReference = hasReference;
                best.referenceAcceptsPlayer = refAcceptsPlayer;
                best.hasSendMessageFlag = hasFlag;
                best.returnsItem = m.getReturnType() != void.class;
            }
        }
        if (best.method != null)
        {
            makeAccessible(best.method);
        }
        return best;
    }

    // ---- network identity -----------------------------------------------------

    /**
     * Best-effort IP resolution. Probes, in order:
     * {@code client.getIp()}, {@code client.getHostAddress()},
     * {@code client.getConnectionAddress()/getInetAddress()} then
     * {@code client.getConnection().getInetAddress()}.
     */
    static String resolveIp(Object player)
    {
        Object client = call(player, "getClient");
        if (client == null)
        {
            return null;
        }
        for (String m : new String[]{"getIp", "getIP", "getHostAddress", "getIpAddress"})
        {
            Object r = call(client, m);
            if (r instanceof String && !((String) r).isEmpty())
            {
                return normalizeIp((String) r);
            }
        }
        for (String m : new String[]{"getConnectionAddress", "getInetAddress", "getSocketAddress"})
        {
            Object inet = call(client, m);
            String addr = hostAddress(inet);
            if (addr != null)
            {
                return addr;
            }
        }
        Object conn = call(client, "getConnection");
        if (conn != null)
        {
            for (String m : new String[]{"getInetAddress", "getRemoteAddress", "getAddress"})
            {
                String addr = hostAddress(call(conn, m));
                if (addr != null)
                {
                    return addr;
                }
            }
        }
        return null;
    }

    private static String hostAddress(Object inetLike)
    {
        if (inetLike == null)
        {
            return null;
        }
        if (inetLike instanceof java.net.InetSocketAddress)
        {
            java.net.InetAddress a = ((java.net.InetSocketAddress) inetLike).getAddress();
            return a == null ? null : normalizeIp(a.getHostAddress());
        }
        if (inetLike instanceof java.net.InetAddress)
        {
            return normalizeIp(((java.net.InetAddress) inetLike).getHostAddress());
        }
        Object r = call(inetLike, "getHostAddress");
        return r == null ? null : normalizeIp(String.valueOf(r));
    }

    /** Strips a scope id and unwraps IPv4-mapped IPv6 so the API sees a plain dotted quad. */
    private static String normalizeIp(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String ip = raw.trim();
        if (ip.startsWith("/"))
        {
            ip = ip.substring(1);
        }
        int scope = ip.indexOf('%');
        if (scope > 0)
        {
            ip = ip.substring(0, scope);
        }
        if (ip.startsWith("::ffff:") && ip.indexOf('.') > 0)
        {
            ip = ip.substring("::ffff:".length());
        }
        return ip.isEmpty() ? null : ip;
    }

    /** Best-effort HWID: direct getters on the client, then a HardwareInfo sub-object. */
    static String resolveHwid(Object player)
    {
        Object client = call(player, "getClient");
        if (client == null)
        {
            return null;
        }
        for (String m : new String[]{"getHWID", "getHwid", "getHWid", "getHardwareId", "getHardwareID"})
        {
            Object r = call(client, m);
            if (r instanceof String && !((String) r).isEmpty())
            {
                return (String) r;
            }
        }
        Object hw = call(client, "getHardwareInfo");
        if (hw != null)
        {
            for (String m : new String[]{"getMacAddress", "getHWID", "getHwid", "getHddSerial"})
            {
                Object r = call(hw, m);
                if (r instanceof String && !((String) r).isEmpty())
                {
                    return (String) r;
                }
            }
        }
        return null;
    }

    // ---- online player list ---------------------------------------------------

    /**
     * Normalises whatever the pack's world object returns — packs variously expose
     * a {@code Collection<Player>} or a {@code Map<Integer, Player>}.
     */
    static Collection<?> asPlayerCollection(Object worldResult)
    {
        if (worldResult instanceof Collection)
        {
            return (Collection<?>) worldResult;
        }
        if (worldResult instanceof Map)
        {
            return ((Map<?, ?>) worldResult).values();
        }
        return java.util.Collections.emptyList();
    }

    // ---- plumbing -------------------------------------------------------------

    static boolean has(Object target, String method)
    {
        return target != null && resolve(target.getClass(), method) != null;
    }

    /** Invokes a zero-arg method, returning null when it is absent or throws. */
    static Object call(Object target, String method)
    {
        if (target == null)
        {
            return null;
        }
        Method m = resolve(target.getClass(), method);
        if (m == null)
        {
            return null;
        }
        try
        {
            return m.invoke(target);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static Method resolve(Class<?> owner, String name, Class<?>... params)
    {
        StringBuilder key = new StringBuilder(owner.getName()).append('#').append(name);
        for (Class<?> p : params)
        {
            key.append('/').append(p.getName());
        }
        Method cached = ACCESSORS.computeIfAbsent(key.toString(), k ->
        {
            try
            {
                Method m = owner.getMethod(name, params);
                makeAccessible(m);
                return m;
            }
            catch (NoSuchMethodException e)
            {
                return ABSENT;
            }
        });
        return cached == ABSENT ? null : cached;
    }

    /**
     * A public method declared on a non-public class (common for pack-internal
     * client types) is not invokable without this, and fails with
     * IllegalAccessException at call time instead of at lookup time.
     */
    private static void makeAccessible(Method m)
    {
        try
        {
            m.setAccessible(true);
        }
        catch (RuntimeException ignored)
        {
            // Java 9+ module restrictions — the call may still work if the class is exported.
        }
    }
}
