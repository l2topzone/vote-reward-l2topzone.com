package l2topzone.universal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import l2topzone.L2TopzoneRewardBase;

/**
 * L2Topzone vote-reward integration for <b>any</b> L2j fork.
 *
 * <p>This build imports nothing from the server pack. It discovers the pack's
 * voiced-command registry, its player world and its command interface at runtime,
 * so the same JAR runs on forks this project has never been compiled against —
 * L2jOrion, L2jHellas, L2jEnergy, L2jLisvus, L2jFree, L2jArchid, L2jDream,
 * L2jPhoenix, older aCis revisions, and private forks with renamed packages.</p>
 *
 * <h2>How detection works</h2>
 * <ol>
 *   <li><b>Caller package.</b> {@code getInstance()} is called from your
 *       {@code GameServer}, so the stack trace names the pack's own package
 *       (e.g. {@code com.l2jorion.gameserver.GameServer}). Every prefix of that
 *       package is tried as a root. This is why no configuration is normally needed.</li>
 *   <li><b>Known roots.</b> A built-in list of the common forks, as a fallback for
 *       when the hook is placed somewhere unusual.</li>
 *   <li><b>{@code PackBasePackage}</b> in the properties file, for anything else.</li>
 * </ol>
 *
 * <p>The command interface itself is never guessed: it is read from the parameter
 * type of the registry's own {@code registerHandler} method, and a
 * {@link Proxy} is generated to implement it. That makes the integration correct
 * by construction on any pack whose registry follows the standard L2j shape.</p>
 *
 * <p>Hook in GameServer.java:
 * <pre>l2topzone.universal.VoteRewardManager.getInstance();</pre>
 *
 * <p>If your pack is in the supported matrix, prefer its dedicated JAR — it binds
 * directly and fails at compile time rather than at boot. Use this one when your
 * fork is not listed, or when a pack-specific JAR reports it cannot find a class.</p>
 */
public final class VoteRewardManager extends L2TopzoneRewardBase
{
    private static VoteRewardManager INSTANCE;

    /** Package roots checked when the caller's own package yields nothing. */
    private static final String[] KNOWN_ROOTS =
    {
        "net.sf.l2j",          // aCis (all revisions), L2jLisvus, L2jArchid
        "org.l2jmobius",       // L2jMobius
        "com.l2jserver",       // L2jServer / L2j DataPack
        "com.l2jfrozen",       // L2jFrozen
        "org.l2junity",        // L2jUnity
        "l2r",                 // L2jReunion, L2jSunrise
        "com.l2jhellas",       // L2jHellas
        "com.l2jorion",        // L2jOrion
        "l2e",                 // L2jEnergy / L2jEternity
        "com.l2jfree",         // L2jFree
        "com.l2jdream",        // L2jDream
        "com.l2jbrasil",       // L2jBrasil
        "com.l2jprime",        // L2jPrime
        "com.l2jhomeserver",   // L2jHomeserver
        "com.l2jarchid",       // L2jArchid
        "com.l2jteon",         // L2jTeon
        "com.dream",           // Dream-based forks
        "ru.catssoftware",     // CatsSoftware / L2jFree-RU
        "lineage2",            // misc private forks
    };

    /** Segments appended to a root when looking for the gameserver namespace. */
    private static final String[] MIDDLES = {"gameserver", "game", ""};

    private static final String[] REGISTER_METHODS =
    {
        "registerHandler", "registerVoicedCommandHandler", "registerCommandHandler", "addHandler"
    };

    private ScheduledExecutorService scheduler;
    private Method worldGetter;
    private Object worldInstance;
    private volatile boolean worldWarned = false;
    private String detectedRoot = "?";

    /** Package roots harvested from the call stack, captured before any thread hop. */
    private final List<String> callerRoots = new ArrayList<>();

    public static VoteRewardManager getInstance()
    {
        if (INSTANCE == null)
        {
            synchronized (VoteRewardManager.class)
            {
                if (INSTANCE == null)
                {
                    VoteRewardManager m = new VoteRewardManager();
                    // Must run on the caller's stack — that is where the pack package is visible.
                    m.captureCallerRoots();
                    m.init("./config/L2TopzoneVoteReward.properties", "./config/L2TopzoneVoteReward.store");
                    INSTANCE = m;
                }
            }
        }
        return INSTANCE;
    }

    // ---- pack discovery -------------------------------------------------------

    private void captureCallerRoots()
    {
        for (StackTraceElement el : Thread.currentThread().getStackTrace())
        {
            String cn = el.getClassName();
            if (cn.startsWith("l2topzone.") || cn.startsWith("java.") || cn.startsWith("jdk.")
                || cn.startsWith("sun.") || cn.equals("java.lang.Thread"))
            {
                continue;
            }
            int lastDot = cn.lastIndexOf('.');
            if (lastDot <= 0)
            {
                continue;
            }
            // com.l2jorion.gameserver.GameServer -> com.l2jorion.gameserver, com.l2jorion, com
            String pkg = cn.substring(0, lastDot);
            while (pkg.indexOf('.') > 0)
            {
                if (!callerRoots.contains(pkg))
                {
                    callerRoots.add(pkg);
                }
                pkg = pkg.substring(0, pkg.lastIndexOf('.'));
            }
            if (!pkg.isEmpty() && !callerRoots.contains(pkg))
            {
                callerRoots.add(pkg);
            }
        }
    }

    /** Candidate package roots, most specific first. */
    private Set<String> candidateRoots()
    {
        Set<String> roots = new LinkedHashSet<>();
        // An explicit PackBasePackage always wins — it is the escape hatch for
        // forks whose layout defeats both auto-detection strategies.
        if (cfg != null && cfg.packBasePackage != null && !cfg.packBasePackage.isEmpty())
        {
            roots.add(cfg.packBasePackage);
        }
        roots.addAll(callerRoots);
        Collections.addAll(roots, KNOWN_ROOTS);
        return roots;
    }

    private Class<?> findClass(String... simpleNamesUnderGameserver)
    {
        for (String root : candidateRoots())
        {
            for (String middle : MIDDLES)
            {
                String base = middle.isEmpty() ? root : root + "." + middle;
                for (String tail : simpleNamesUnderGameserver)
                {
                    Class<?> c = tryLoad(base + "." + tail);
                    if (c != null)
                    {
                        detectedRoot = root;
                        return c;
                    }
                }
            }
        }
        return null;
    }

    private static Class<?> tryLoad(String fqn)
    {
        try
        {
            return Class.forName(fqn, false, VoteRewardManager.class.getClassLoader());
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // ---- handler registration -------------------------------------------------

    @Override
    protected void registerHandler()
    {
        Class<?> registryClass = findClass("handler.VoicedCommandHandler", "handler.voiced.VoicedCommandHandler");
        if (registryClass == null)
        {
            LOG.severe("[L2Topzone] Could not locate VoicedCommandHandler on this pack."
                + " Tried roots: " + candidateRoots()
                + ". Set PackBasePackage in L2TopzoneVoteReward.properties to your pack's root package"
                + " (the part before '.gameserver'), e.g. PackBasePackage = com.l2jorion");
            return;
        }

        Object registry = registryInstance(registryClass);
        if (registry == null)
        {
            LOG.severe("[L2Topzone] Found " + registryClass.getName() + " but could not obtain its instance.");
            return;
        }

        Method register = null;
        for (String name : REGISTER_METHODS)
        {
            for (Method m : registryClass.getMethods())
            {
                if (m.getName().equals(name) && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isInterface())
                {
                    register = m;
                    break;
                }
            }
            if (register != null)
            {
                break;
            }
        }
        if (register == null)
        {
            LOG.severe("[L2Topzone] " + registryClass.getName() + " exposes no single-argument register method"
                + " — cannot attach the voiced command on this pack.");
            return;
        }

        // The interface is taken from the registry's own signature, never guessed.
        final Class<?> iface = register.getParameterTypes()[0];
        Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, new Dispatcher(iface));

        try
        {
            register.setAccessible(true);
            register.invoke(registry, proxy);
            LOG.info("[L2Topzone] Universal build attached to '" + detectedRoot + "' via "
                + registryClass.getSimpleName() + "." + register.getName() + "(" + iface.getSimpleName() + ")");
        }
        catch (Throwable t)
        {
            LOG.severe("[L2Topzone] Failed to register the voiced command handler: "
                + (t.getCause() != null ? t.getCause() : t));
        }
    }

    private static Object registryInstance(Class<?> registryClass)
    {
        for (String getter : new String[]{"getInstance", "instance", "getRegistry"})
        {
            try
            {
                Method m = registryClass.getMethod(getter);
                if (Modifier.isStatic(m.getModifiers()))
                {
                    m.setAccessible(true);
                    Object o = m.invoke(null);
                    if (o != null)
                    {
                        return o;
                    }
                }
            }
            catch (Throwable ignored)
            {
                // try the next shape
            }
        }
        // A few forks expose a public static INSTANCE field instead of a getter.
        for (String field : new String[]{"INSTANCE", "instance"})
        {
            try
            {
                java.lang.reflect.Field f = registryClass.getField(field);
                if (Modifier.isStatic(f.getModifiers()))
                {
                    Object o = f.get(null);
                    if (o != null)
                    {
                        return o;
                    }
                }
            }
            catch (Throwable ignored)
            {
                // give up below
            }
        }
        return null;
    }

    /** Implements the pack's IVoicedCommandHandler, whatever shape it has. */
    private final class Dispatcher implements InvocationHandler
    {
        private final Class<?> iface;

        Dispatcher(Class<?> iface)
        {
            this.iface = iface;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            String name = method.getName();

            if ("getVoicedCommandList".equals(name) || "getCommandList".equals(name))
            {
                return voicedCommands();
            }
            if (name.startsWith("useVoicedCommand") || "useCommand".equals(name))
            {
                return dispatchCommand(args);
            }
            // Object methods, so the registry can put the proxy in a collection.
            if ("equals".equals(name))
            {
                return proxy == (args == null ? null : args[0]);
            }
            if ("hashCode".equals(name))
            {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name))
            {
                return "L2TopzoneVoiceHandler(" + iface.getName() + ")";
            }
            return defaultValue(method.getReturnType());
        }

        /**
         * Signatures vary: {@code (String, Player)}, {@code (String, Player, String)}
         * and occasionally with the arguments reordered. The command is the first
         * String, the target/params the last, and the player is the sole non-String.
         */
        private Object dispatchCommand(Object[] args)
        {
            if (args == null || args.length < 2)
            {
                return Boolean.FALSE;
            }
            String command = null;
            String params = null;
            Object player = null;
            for (Object a : args)
            {
                if (a instanceof String)
                {
                    if (command == null)
                    {
                        command = (String) a;
                    }
                    else
                    {
                        params = (String) a;
                    }
                }
                else if (a != null && player == null)
                {
                    player = a;
                }
            }
            if (command == null || player == null)
            {
                return Boolean.FALSE;
            }
            return handle(command, params, player);
        }
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive())
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == void.class)
        {
            return null;
        }
        if (type == long.class)
        {
            return 0L;
        }
        if (type == double.class)
        {
            return 0d;
        }
        if (type == float.class)
        {
            return 0f;
        }
        if (type == char.class)
        {
            return (char) 0;
        }
        return 0;
    }

    // ---- scheduling -----------------------------------------------------------

    /**
     * Uses an own daemon scheduler rather than the pack's thread pool: pool class
     * names and method shapes differ far more across forks than anything else, and
     * a single low-frequency poller does not need to live in the game's pool.
     */
    @Override
    protected void schedulePeriodic(Runnable task, long initialSec, long periodSec)
    {
        if (scheduler == null)
        {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
            {
                @Override
                public Thread newThread(Runnable r)
                {
                    Thread t = new Thread(r, "L2Topzone-Universal-Poller");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        scheduler.scheduleWithFixedDelay(task, initialSec, periodSec, TimeUnit.SECONDS);
    }

    // ---- online players -------------------------------------------------------

    @Override
    protected Collection<?> getOnlinePlayers()
    {
        if (worldGetter == null && !resolveWorld())
        {
            return Collections.emptyList();
        }
        try
        {
            return asPlayerCollection(worldGetter.invoke(worldInstance));
        }
        catch (Throwable t)
        {
            LOG.warning("[L2Topzone] Could not read the online player list: " + t);
            return Collections.emptyList();
        }
    }

    private boolean resolveWorld()
    {
        Class<?> worldClass = findClass("model.World", "model.L2World", "world.World", "model.world.World");
        if (worldClass == null)
        {
            warnNoWorld("no World/L2World class found");
            return false;
        }
        Object instance = registryInstance(worldClass);
        if (instance == null)
        {
            warnNoWorld(worldClass.getName() + " has no accessible instance");
            return false;
        }
        for (String getter : new String[]{"getPlayers", "getAllPlayers", "getAllPlayersArray", "getPlayersList"})
        {
            try
            {
                Method m = worldClass.getMethod(getter);
                Object sample = m.invoke(instance);
                if (sample instanceof Collection || sample instanceof java.util.Map)
                {
                    m.setAccessible(true);
                    worldGetter = m;
                    worldInstance = instance;
                    return true;
                }
            }
            catch (Throwable ignored)
            {
                // try the next getter
            }
        }
        warnNoWorld(worldClass.getName() + " exposes no usable player-list getter");
        return false;
    }

    private void warnNoWorld(String why)
    {
        if (!worldWarned)
        {
            worldWarned = true;
            LOG.warning("[L2Topzone] Global milestone rewards are inactive on this pack (" + why + ")."
                + " Individual .vote rewards are unaffected. Set GlobalEnabled = false to silence this.");
        }
    }

    @Override
    protected String packName()
    {
        return "universal";
    }
}
