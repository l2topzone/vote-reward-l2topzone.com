package l2topzone;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Minimal HTTP + JSON client for the L2Topzone vote API.
 * No external dependencies — works on Java 8 / 11 / 17.
 *
 * Endpoints:
 *   GET  https://api.l2topzone.com/v1/server_{KEY}/getServerData
 *   POST https://api.l2topzone.com/v1/server_{KEY}/getUserData   body: {"ip":"x.x.x.x"}
 *   POST https://api.l2topzone.com/v1/server_{KEY}/confirmVote   body: {"ip":"x.x.x.x"}
 *
 * <p>Every call returns a {@link Status} so callers can distinguish "the network
 * is down" (retry later, don't alarm the player) from "the API rejected us"
 * (bad key / IP not whitelisted — an admin must act). The old client collapsed
 * both into {@code null}, which made misconfiguration look like flaky internet.</p>
 *
 * <p>All calls block; never invoke them from a game thread. {@link L2TopzoneRewardBase}
 * runs them on its own bounded IO pool.</p>
 *
 * @author L2Topzone (MIT License)
 */
public final class L2TopzoneAPI
{
    private static final Logger LOG = Logger.getLogger("L2Topzone");

    private static final String DEFAULT_HOST = "https://api.l2topzone.com/v1";
    private static final String UA = "L2TopzoneVoteReward/2.0";
    /** Transport failures are retried once; HTTP-level errors are not (they are deterministic). */
    private static final int TRANSPORT_ATTEMPTS = 2;

    private final String apiHost;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public L2TopzoneAPI(String apiKey)
    {
        this(DEFAULT_HOST, apiKey, 5, 5);
    }

    public L2TopzoneAPI(String apiHost, String apiKey, int connectTimeoutSec, int readTimeoutSec)
    {
        this.apiHost = (apiHost == null || apiHost.isEmpty()) ? DEFAULT_HOST : stripTrailingSlash(apiHost);
        this.apiKey = apiKey;
        this.connectTimeoutMs = Math.max(1000, connectTimeoutSec * 1000);
        this.readTimeoutMs = Math.max(1000, readTimeoutSec * 1000);
    }

    public enum Status
    {
        /** Call succeeded and the payload is populated. */
        OK,
        /** DNS / TCP / TLS / timeout — transient, retry later. */
        TRANSPORT_ERROR,
        /** Reached the API but it refused: bad key, IP not whitelisted, rate limited. */
        API_ERROR
    }

    /** Total votes + rank for this server. */
    public static final class ServerData
    {
        public Status status = Status.TRANSPORT_ERROR;
        public int totalVotes;
        public int serverRank;
        public String error;

        public boolean ok()
        {
            return status == Status.OK;
        }
    }

    /** Whether an IP has a valid, not-yet-rewarded vote. */
    public static final class VoteStatus
    {
        public Status status = Status.TRANSPORT_ERROR;
        public boolean isVoted;
        /** Unix seconds of the last recorded vote, 0 when unknown. */
        public long voteTime;
        /** API-side clock in unix seconds — use this, not the local clock, for countdowns. */
        public long serverTime;
        public String error;

        public boolean ok()
        {
            return status == Status.OK;
        }
    }

    /**
     * Total votes for this server.
     */
    public ServerData getServerData()
    {
        ServerData out = new ServerData();
        Http r = request("GET", apiHost + "/server_" + apiKey + "/getServerData", null);
        L2TopzoneJson json = applyStatus(r, out);
        if (json == null)
        {
            return out;
        }
        out.totalVotes = (int) Math.max(0L, json.number("totalVotes", 0L));
        out.serverRank = (int) Math.max(0L, json.number("serverRank", 0L));
        return out;
    }

    /**
     * Whether {@code voterIp} has a valid (unrewarded) vote in the API window.
     */
    public VoteStatus getUserData(String voterIp)
    {
        VoteStatus out = new VoteStatus();
        Http r = request("POST", apiHost + "/server_" + apiKey + "/getUserData", ipBody(voterIp));
        L2TopzoneJson json = applyStatus(r, out);
        if (json == null)
        {
            return out;
        }
        out.isVoted = json.bool("isVoted", false);
        out.voteTime = json.number("voteTime", 0L);
        out.serverTime = json.number("serverTime", System.currentTimeMillis() / 1000L);
        return out;
    }

    /**
     * Confirms reward delivery so the same vote isn't rewarded twice.
     * @return true when the API acknowledged the confirmation.
     */
    public boolean confirmVote(String voterIp)
    {
        Http r = request("POST", apiHost + "/server_" + apiKey + "/confirmVote", ipBody(voterIp));
        if (r.transportError)
        {
            return false;
        }
        L2TopzoneJson json = L2TopzoneJson.of(r.body);
        Boolean ok = json == null ? null : json.boolOrNull("ok");
        return (r.code >= 200 && r.code < 300) && !Boolean.FALSE.equals(ok);
    }

    // ============================================================
    // Response handling
    // ============================================================

    /**
     * Maps a raw response onto the caller's result object.
     *
     * <p>{@code ok} is honoured when the API sends it; when it is absent we fall
     * back to the HTTP status code, so the client keeps working against
     * deployments (and the sandbox) that omit the envelope.</p>
     *
     * @return the parsed document when the call succeeded, else null (status already set).
     */
    private L2TopzoneJson applyStatus(Http r, Object target)
    {
        if (r.transportError)
        {
            setStatus(target, Status.TRANSPORT_ERROR, r.error);
            return null;
        }
        L2TopzoneJson json = L2TopzoneJson.of(r.body);
        Boolean ok = json == null ? null : json.boolOrNull("ok");
        boolean httpOk = r.code >= 200 && r.code < 300;
        // Both signals must agree. A non-2xx response is authoritative even if the
        // body claims ok:true — 401/403 means the key or IP whitelist is wrong, and
        // handing out rewards off the back of a rejected request is the worse failure.
        boolean success = httpOk && !Boolean.FALSE.equals(ok);
        if (!success)
        {
            String msg = json == null ? null : json.text("message", json.text("error", null));
            setStatus(target, Status.API_ERROR, "HTTP " + r.code + (msg == null ? "" : " — " + msg));
            return null;
        }
        setStatus(target, Status.OK, null);
        return json;
    }

    private static void setStatus(Object target, Status s, String error)
    {
        if (target instanceof ServerData)
        {
            ((ServerData) target).status = s;
            ((ServerData) target).error = error;
        }
        else if (target instanceof VoteStatus)
        {
            ((VoteStatus) target).status = s;
            ((VoteStatus) target).error = error;
        }
    }

    // ============================================================
    // HTTP
    // ============================================================

    private static final class Http
    {
        int code;
        String body;
        boolean transportError;
        String error;
    }

    private Http request(String method, String urlStr, String jsonBody)
    {
        Http last = new Http();
        for (int attempt = 1; attempt <= TRANSPORT_ATTEMPTS; attempt++)
        {
            last = requestOnce(method, urlStr, jsonBody);
            if (!last.transportError)
            {
                return last;
            }
            if (attempt < TRANSPORT_ATTEMPTS)
            {
                try
                {
                    Thread.sleep(250L);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
        }
        return last;
    }

    private Http requestOnce(String method, String urlStr, String jsonBody)
    {
        Http out = new Http();
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null)
            {
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream())
                {
                    os.write(payload);
                    os.flush();
                }
            }
            out.code = conn.getResponseCode();
            out.body = read(out.code < 400 ? conn.getInputStream() : conn.getErrorStream());
            return out;
        }
        catch (Exception e)
        {
            out.transportError = true;
            out.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return out;
        }
        finally
        {
            if (conn != null)
            {
                conn.disconnect();
            }
        }
    }

    private static String read(InputStream is) throws java.io.IOException
    {
        if (is == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
        {
            char[] buf = new char[1024];
            int n;
            // Cap the read so a malformed/hostile response can't exhaust the heap.
            while (sb.length() < 64 * 1024 && (n = r.read(buf)) > 0)
            {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static String ipBody(String ip)
    {
        return "{\"ip\":\"" + escape(ip == null ? "" : ip) + "\"}";
    }

    private static String escape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripTrailingSlash(String s)
    {
        String out = s.trim();
        while (out.endsWith("/"))
        {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** API key with all but the last 4 characters masked — safe to put in a log line. */
    public String maskedKey()
    {
        if (apiKey == null || apiKey.length() <= 4)
        {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * One-shot boot check so misconfiguration is visible in the server log
     * instead of surfacing as "no vote found" for every player.
     */
    void logConnectivity()
    {
        ServerData sd = getServerData();
        switch (sd.status)
        {
            case OK:
                LOG.info("[L2Topzone] API reachable (key " + maskedKey() + "), server total votes: " + sd.totalVotes
                    + ", rank: " + sd.serverRank);
                break;
            case API_ERROR:
                LOG.severe("[L2Topzone] API rejected the request (key " + maskedKey() + "): " + sd.error
                    + " — check the API key and that this server's public IP is whitelisted.");
                break;
            default:
                LOG.warning("[L2Topzone] API not reachable at boot: " + sd.error
                    + " — vote checks will keep retrying.");
        }
    }
}
