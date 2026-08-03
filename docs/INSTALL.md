# Step-by-step installation guide

Works for all supported packs (aCis, L2jMobius, L2jUnity, L2jFrozen, L2jReunion, L2jServer/Sunrise) **and for any other fork via the `universal` build**. Only the source path and the `GameServer.java` hook differ — everything else is identical.

There are **two install methods**. **Method A (JAR) is recommended** — no source merge, no recompile of your gameserver source tree.

---

## Method A — Pre-compiled JAR (recommended)

### A.1 Download

Go to the [Releases page](https://github.com/l2topzone/vote-reward-l2topzone.com/releases/latest) and download:

| File | What |
|---|---|
| `l2topzone-vote-reward-<pack>.jar` | The JAR for your pack |
| `L2TopzoneVoteReward.properties.template` | Config template |

Where `<pack>` ∈ {`acis`, `mobius`, `unity`, `frozen`, `reunion`, `l2jserver`}.

**Not on that list?** Take `l2topzone-vote-reward-universal.jar` — it imports nothing from
the pack and detects your fork at runtime (L2jOrion, L2jHellas, L2jEnergy, L2jLisvus,
L2jFree, older aCis revisions, private forks…). See
[packs/universal/README.md](../packs/universal/README.md).

### A.2 Install the JAR

Drop the JAR into the **same folder your gameserver loads other library JARs from**. Most common locations:

| Pack | Typical folder |
|---|---|
| Universal | wherever your other JARs live |
| aCis | `gameserver/libs/` |
| L2jMobius | `gameserver/libs/` |
| L2jUnity | `gameserver/libs/` |
| L2jFrozen | `GameServer/libs/` |
| L2jReunion / Sunrise | `Server/libs/` |
| L2jServer | `gameserver/libs/` |

If your launcher script uses a `lib/`-style classpath wildcard (`-cp libs/*`), you're done — the JAR is now on the classpath.
If your launcher hard-codes JAR names, edit it to include `libs/l2topzone-vote-reward-<pack>.jar`.

### A.3 Drop the config

```bash
mv L2TopzoneVoteReward.properties.template gameserver/config/L2TopzoneVoteReward.properties
```

Edit it and set at minimum:
- `ApiKey` — from your L2Topzone admin panel
- `IndividualRewardItemIds` / `IndividualRewardItemCount`
- `GlobalRewardItemIds` / `GlobalRewardItemCount`

The id and count lists must have the same number of comma-separated values, and every
count must be greater than zero — the manager refuses to start otherwise, and says why.

See [docs/CONFIG.md](CONFIG.md) for every available key.

### A.4 Hook into GameServer.java

Open your `GameServer.java` (the one with `main(...)`) and add **one** line near the other manager initializations (anywhere late in the boot sequence is fine):

```java
// pick the line matching your pack:
l2topzone.universal.VoteRewardManager.getInstance();   // any fork not listed below
l2topzone.acis.VoteRewardManager.getInstance();        // aCis
l2topzone.mobius.VoteRewardManager.getInstance();      // L2jMobius
l2topzone.unity.VoteRewardManager.getInstance();       // L2jUnity
l2topzone.frozen.VoteRewardManager.getInstance();      // L2jFrozen
l2topzone.reunion.VoteRewardManager.getInstance();     // L2jReunion / Sunrise l2r.*
l2topzone.l2jserver.VoteRewardManager.getInstance();   // L2jServer / Sunrise com.l2jserver.*
```

If you can't (or don't want to) touch `GameServer.java`, you can also load it from any always-loaded script — but the GameServer hook is the simplest.

### A.5 Restart and test

Restart the gameserver. You should see:
```
[L2Topzone] <pack> vote-reward manager ready. Voiced cmd: .vote
```

In game, type `.vote` — you should receive either the cooldown message, the "no vote found" message, or the reward.

Done. No source merge, no recompile of the L2j source tree.

---

## Method B — Build from source

Use this if you want to customize the reward logic, add packs not in the matrix, or you're already maintaining a custom fork.



1. **Get your API key** from your L2Topzone admin panel: Dashboard → Server → API Key.
2. **Whitelist your gameserver IP** in the same panel (otherwise the API returns `403 Forbidden`).
3. **Clone the repo:**
   ```bash
   git clone https://github.com/l2topzone/vote-reward-l2topzone.com.git
   cd vote-reward-l2topzone.com
   ```

---

## Step 1 — Identify your pack

| Your pack | Use folder | Required JDK |
|---|---|---|
| **Anything else / unsure** | `packs/universal/` | 8+ |
| aCis (Interlude rev 400+) | `packs/acis/` | 17 |
| L2jMobius (any chronicle) | `packs/mobius/` | 17 |
| L2jUnity (Classic / High Five) | `packs/unity/` | 11 |
| L2jFrozen (Interlude) | `packs/frozen/` | 8 |
| L2jReunion / Sunrise `l2r.*` | `packs/reunion/` | 8 |
| L2jServer / Sunrise `com.l2jserver.*` | `packs/l2jserver/` | 8 |

> Not sure which one? Open any `.java` from your gameserver and look at the `package` line at the top — it tells you the namespace.

---

## Step 2 — Copy the sources into your gameserver

Final structure inside your gameserver source root:

```
<gameserver>/java/                        ← or head-src/ on legacy packs (Frozen)
└── l2topzone/
    ├── L2TopzoneAPI.java                 ← from common/
    ├── L2TopzoneConfig.java              ← from common/
    ├── L2TopzoneJson.java                ← from common/
    ├── L2TopzonePlayers.java             ← from common/
    ├── L2TopzoneRewardBase.java          ← from common/
    ├── L2TopzoneStore.java               ← from common/
    └── <pack>/
        └── VoteRewardManager.java        ← from packs/<pack>/
```

Copy **all six** `common/*.java` files — `cp common/L2Topzone*.java` covers them.

**Example (aCis):**
```bash
DEST=/path/to/aCis_gameserver/java/l2topzone
mkdir -p "$DEST/acis"
cp common/L2Topzone*.java "$DEST/"
cp packs/acis/VoteRewardManager.java "$DEST/acis/"
```

For **L2jFrozen** replace `java/` with `head-src/`. For all other packs use `java/`.

---

## Step 3 — Install the configuration file

```bash
cp common/L2TopzoneVoteReward.properties /path/to/<gameserver>/config/
```

Edit `config/L2TopzoneVoteReward.properties`:

```properties
# REQUIRED
ApiKey = <paste-your-api-key-here>

# Voiced command (without the leading dot)
VoiceCommand  = vote
CooldownHours = 12

# Per-character reward on .vote — 1M adena
IndividualRewardItemIds   = 57
IndividualRewardItemCount = 1000000

# Global milestone reward — every 100 total votes, 5 Festival Adena
GlobalEnabled         = true
GlobalVoteInterval    = 100
GlobalRewardItemIds   = 6673
GlobalRewardItemCount = 5
GlobalCheckMinutes    = 5

# Optional HWID protection (silently skipped if your pack does not expose HWID)
HwidProtection      = true
MaxAccountsPerHwid  = 1
```

**Rules:**
- `IndividualRewardItemIds` and `IndividualRewardItemCount` must have the **same number** of comma-separated values. Same for the global pair.
- Multiple items example: `57,6673,5575` paired with `1000000,5,10`.

See [CONFIG.md](CONFIG.md) for the full reference.

---

## Step 4 — Add the hook to `GameServer.java`

Open `GameServer.java` in your gameserver, find the constructor (next to other `XxxManager.getInstance()` calls), and add **one line**:

| Pack | Line to add |
|---|---|
| Universal | `l2topzone.universal.VoteRewardManager.getInstance();` |
| aCis | `l2topzone.acis.VoteRewardManager.getInstance();` |
| Mobius | `l2topzone.mobius.VoteRewardManager.getInstance();` |
| Unity | `l2topzone.unity.VoteRewardManager.getInstance();` |
| Frozen | `l2topzone.frozen.VoteRewardManager.getInstance();` |
| Reunion | `l2topzone.reunion.VoteRewardManager.getInstance();` |
| L2jServer | `l2topzone.l2jserver.VoteRewardManager.getInstance();` |

**Example (aCis):**
```java
public GameServer() throws Exception {
    // ... existing code ...
    BufferManager.getInstance();
    AnnouncementManager.getInstance();

    // L2Topzone vote rewards
    l2topzone.acis.VoteRewardManager.getInstance();

    System.out.println("GameServer started.");
}
```

> On Mobius you can alternatively register it as a script in `dist/game/data/scripts.cfg`, but the direct hook is simpler and pack-independent.

---

## Step 5 — Compile the gameserver

| Pack | Build |
|---|---|
| aCis, Mobius | `ant` or Eclipse → Project → Clean → Build |
| Unity | `gradle build` from `gameserver/` |
| Frozen, Reunion, L2jServer | `ant jar` from project root, or Eclipse |

**Common compile errors:**

| Error | Cause | Fix |
|---|---|---|
| `cannot find symbol: package l2topzone` | Files not on the classpath | Re-check Step 2 paths |
| `incompatible types: Player vs L2PcInstance` | Wrong pack folder copied | See pack table in Step 1 |
| `class file has wrong version` | JDK too old | Match the required JDK in Step 1 |
| `cannot find symbol: VoicedCommandHandler` | Pack uses a different handler class | Use the `universal` build — it resolves the handler at runtime |

---

## Step 6 — Start the server and verify

On startup you should see:

```
[L2Topzone] aCis vote-reward manager ready. Voiced cmd: .vote
```

If you see this instead, the config file is missing or `ApiKey` is empty:
```
[L2Topzone] Vote rewards DISABLED.
```

---

## Step 7 — In-game test

1. On the website, vote for your server.
2. Wait ~10 seconds for propagation.
3. In game, type in chat: `.vote`
4. You should get the reward and:
   ```
   [L2Topzone] Thank you for voting! Reward delivered.
   ```

**In-game error messages and what they mean:**

| Message | Cause | Fix |
|---|---|---|
| `Checking your vote, please wait...` | Normal — the API call runs in the background so the server never stalls | The result follows within a second or two |
| `No vote found for your IP` | Did not vote, or the client IP differs from the IP used on the website | Vote from the same network as the game client |
| `Your last vote was already rewarded. You can vote again in HH:MM:SS` | The IP voted, but that vote was already claimed | Normal — wait it out |
| `The vote service is unreachable right now` | No outbound internet, DNS or TLS failure | `curl https://api.l2topzone.com/v1/server_<KEY>/getServerData` from the gameserver shell |
| `The vote service refused the request` | **Configuration problem** — bad API key, or the server IP is not whitelisted | Check the boot log: it prints the exact HTTP status with the key masked |
| `You can claim again in HH:MM:SS` | Per-character cooldown is active | Normal — wait it out |
| `A vote reward was already claimed from this machine` | HWID limit reached inside `HwidWindowHours` | Raise `MaxAccountsPerHwid`/shorten `HwidWindowHours`, or set `HwidProtection = false` |
| `Reward could not be delivered. Free some inventory space` | `addItem` failed, usually a full inventory | The cooldown is **not** consumed — the player can retry after making room |
| `Could not resolve your IP address` | `getClient()` returned null, or an unknown client API | The log prints the client class name — open an issue with it |
| `Too many vote checks in progress` | More than `MaxConcurrentRequests` in flight | Transient; raise the value if it is frequent |

All messages are overridable/translatable — see the `Msg*` keys in [CONFIG.md](CONFIG.md).

---

## Step 8 — Global milestone rewards

Nothing to do — the manager polls every `GlobalCheckMinutes` (default 5 min) and pulls the server vote total. When it crosses a multiple of `GlobalVoteInterval` (default 100), **every online player** gets the global reward plus a broadcast:

```
[L2Topzone] Server hit 500 votes! Global reward delivered.
```

If multiple milestones are crossed between two polls (e.g. a vote burst), the per-player reward is multiplied accordingly.

---

## Maintenance

- **Cooldowns + HWID bindings** are persisted to `config/L2TopzoneVoteReward.store`. Back this file up if you reinstall. It is written atomically and flushed every 30s, so a crash cannot truncate it.
- **Reset everything:** stop the server → delete `L2TopzoneVoteReward.store` → restart. The global milestone re-baselines on the next poll instead of paying out.
- **Change rewards, limits or messages?** Edit `.properties`, then run `.vote reload` in game as a GM — no restart. An invalid file is rejected and the old settings stay active.
- **Change the command name?** That one still needs a restart (handlers are registered at boot); `.vote reload` says so when it detects the change.
- **Check it's alive?** `.vote status` as a GM prints store counters and live API reachability.
- **Multiple worlds, one server?** Run one gameserver process per world, and give each a distinct `StoreFile` if they share a config directory.

---

## Troubleshooting checklist

If something does not work, in order:

1. `[L2Topzone]` line appears in server boot log? → If no, the hook was not called.
2. `ApiKey` is set in `.properties`?
3. Gameserver IP is whitelisted in the L2Topzone panel?
4. `curl https://api.l2topzone.com/v1/server_<KEY>/getServerData` returns JSON with `totalVotes`?
5. The vote you made on the site shows up in your panel's vote log?
6. Your in-game character's client IP equals the IP you voted from? (NAT, VPN and proxies break this.)

If 1–6 all pass and rewards still do not arrive, open a GitHub issue with the boot log + the relevant `[L2Topzone]` lines (enable `DebugLog = true` first).

---

## Resources

- Repo: https://github.com/l2topzone/vote-reward-l2topzone.com
- Full config reference: [CONFIG.md](CONFIG.md)
- Pack-specific notes: `packs/<pack>/README.md`
