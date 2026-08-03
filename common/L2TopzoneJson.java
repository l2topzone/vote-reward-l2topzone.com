package l2topzone;

/**
 * Minimal dependency-free JSON reader.
 *
 * <p>Unlike a naive {@code indexOf("\"key\"")} scan, this walks the document as
 * a token stream: it knows the difference between a key and a string value, so
 * a key name that happens to appear <i>inside</i> a string value can never be
 * mistaken for a real key. Values are returned for the FIRST occurrence of the
 * key in document order (the outer object wins over nested ones).</p>
 *
 * <p>Only what the vote API needs is implemented: object keys, string / number /
 * boolean / null values. Nested objects and arrays are skipped correctly but not
 * exposed.</p>
 */
final class L2TopzoneJson
{
    private final String src;

    private L2TopzoneJson(String src)
    {
        this.src = src;
    }

    static L2TopzoneJson of(String body)
    {
        return (body == null || body.isEmpty()) ? null : new L2TopzoneJson(body);
    }

    // ---- typed accessors -----------------------------------------------------

    /** @return unescaped value, or {@code def} if the key is absent. */
    String text(String key, String def)
    {
        String v = value(key);
        return v == null ? def : v;
    }

    /**
     * Accepts {@code true}, {@code "true"}, {@code 1}, {@code "1"}, {@code yes},
     * {@code on} — case-insensitive. PHP/Laravel backends serialise booleans in
     * all of these forms depending on the driver, so all of them are honoured.
     */
    boolean bool(String key, boolean def)
    {
        Boolean b = boolOrNull(key);
        return b == null ? def : b.booleanValue();
    }

    /** @return null when the key is absent, so callers can tell "false" from "missing". */
    Boolean boolOrNull(String key)
    {
        String v = value(key);
        if (v == null)
        {
            return null;
        }
        v = v.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
        {
            return Boolean.TRUE;
        }
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off") || v.equals("null"))
        {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Accepts numbers and quoted numbers; a fractional part is truncated. */
    long number(String key, long def)
    {
        String v = value(key);
        if (v == null)
        {
            return def;
        }
        v = v.trim();
        int end = 0;
        int n = v.length();
        if (end < n && (v.charAt(end) == '-' || v.charAt(end) == '+'))
        {
            end++;
        }
        int digitsStart = end;
        while (end < n && v.charAt(end) >= '0' && v.charAt(end) <= '9')
        {
            end++;
        }
        if (end == digitsStart)
        {
            return def;
        }
        try
        {
            return Long.parseLong(v.charAt(0) == '+' ? v.substring(1, end) : v.substring(0, end));
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }

    // ---- scanner -------------------------------------------------------------

    /** Raw (unquoted, unescaped) value of the first object key equal to {@code key}. */
    String value(String key)
    {
        final int n = src.length();
        int i = 0;
        while (i < n)
        {
            char c = src.charAt(i);
            if (c != '"')
            {
                i++;
                continue;
            }
            int[] end = new int[1];
            String token = readString(i, end);
            i = end[0];
            int j = skipWs(i);
            if (j < n && src.charAt(j) == ':')
            {
                // `token` was an object key.
                int valueStart = skipWs(j + 1);
                if (key.equals(token))
                {
                    return readValue(valueStart, end);
                }
                i = skipValue(valueStart);
            }
        }
        return null;
    }

    /** @param start index of the opening quote. {@code endOut[0]} receives the index after the closing quote. */
    private String readString(int start, int[] endOut)
    {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        final int n = src.length();
        while (i < n)
        {
            char c = src.charAt(i);
            if (c == '\\' && (i + 1) < n)
            {
                char e = src.charAt(i + 1);
                switch (e)
                {
                    case 'n': sb.append('\n'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case 'b': sb.append('\b'); i += 2; break;
                    case 'f': sb.append('\f'); i += 2; break;
                    case 'u':
                        if ((i + 5) < n)
                        {
                            try
                            {
                                sb.append((char) Integer.parseInt(src.substring(i + 2, i + 6), 16));
                                i += 6;
                            }
                            catch (NumberFormatException ex)
                            {
                                i += 2;
                            }
                        }
                        else
                        {
                            i += 2;
                        }
                        break;
                    default: sb.append(e); i += 2; break;
                }
                continue;
            }
            if (c == '"')
            {
                endOut[0] = i + 1;
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        endOut[0] = n;
        return sb.toString();
    }

    /** Reads the scalar starting at {@code p}. Objects/arrays yield null. */
    private String readValue(int p, int[] scratch)
    {
        final int n = src.length();
        if (p >= n)
        {
            return null;
        }
        char c = src.charAt(p);
        if (c == '"')
        {
            return readString(p, scratch);
        }
        if (c == '{' || c == '[')
        {
            return null;
        }
        int end = p;
        while (end < n)
        {
            char d = src.charAt(end);
            if (d == ',' || d == '}' || d == ']' || d == ' ' || d == '\n' || d == '\r' || d == '\t')
            {
                break;
            }
            end++;
        }
        return src.substring(p, end);
    }

    /** @return index just past the value beginning at {@code p} (handles nesting). */
    private int skipValue(int p)
    {
        final int n = src.length();
        if (p >= n)
        {
            return n;
        }
        char c = src.charAt(p);
        if (c == '"')
        {
            int[] end = new int[1];
            readString(p, end);
            return end[0];
        }
        if (c == '{' || c == '[')
        {
            int depth = 0;
            int i = p;
            while (i < n)
            {
                char d = src.charAt(i);
                if (d == '"')
                {
                    int[] end = new int[1];
                    readString(i, end);
                    i = end[0];
                    continue;
                }
                if (d == '{' || d == '[')
                {
                    depth++;
                }
                else if (d == '}' || d == ']')
                {
                    depth--;
                    if (depth == 0)
                    {
                        return i + 1;
                    }
                }
                i++;
            }
            return n;
        }
        int i = p;
        while (i < n)
        {
            char d = src.charAt(i);
            if (d == ',' || d == '}' || d == ']')
            {
                break;
            }
            i++;
        }
        return i;
    }

    private int skipWs(int p)
    {
        int i = p;
        final int n = src.length();
        while (i < n)
        {
            char c = src.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t')
            {
                break;
            }
            i++;
        }
        return i;
    }
}
