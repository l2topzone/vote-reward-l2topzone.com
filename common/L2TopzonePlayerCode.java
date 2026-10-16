package l2topzone;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A character's player code: the L2Topzone standard of exactly 24 letters and
 * digits, derived from the API key and the
 * character id (HMAC-SHA256, base32). Always the same for a character, nothing
 * to store, and nobody can work it out without your API key.
 *
 * <p>The code goes into the character's personal vote link
 * ({@code VoteUrl?player_code=CODE}); L2Topzone links it to the first account
 * that votes with it, so the reward check can find the vote even when the
 * player's browser and game reach the internet from different IPs.</p>
 *
 * @author L2Topzone (MIT License)
 */
public final class L2TopzonePlayerCode
{
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no I, O, 0, 1
    public static final int LENGTH = 24;

    private L2TopzonePlayerCode()
    {
    }

    public static String of(String apiKey, int charId)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("l2topzone-player-code|" + apiKey).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(String.valueOf(charId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++)
            {
                sb.append(ALPHABET[(h[i] & 0xFF) % ALPHABET.length]);
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** The character's personal vote link. */
    public static String link(String voteUrl, String code)
    {
        return voteUrl + (voteUrl.contains("?") ? "&" : "?") + "player_code=" + code;
    }
}
