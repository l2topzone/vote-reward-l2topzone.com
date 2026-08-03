package l2r.gameserver;
public class ThreadPoolManager { private static final ThreadPoolManager I = new ThreadPoolManager(); public static ThreadPoolManager getInstance() { return I; } public void scheduleGeneralAtFixedRate(Runnable r, long initialMs, long periodMs) {} }
