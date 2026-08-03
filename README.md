# L2Topzone Vote Reward Integrations

Open-source Java vote-reward scripts for the most popular Lineage II Java server packs, integrating with the [L2Topzone](https://l2topzone.com) public vote API.

## Features

- Per-player **individual vote reward** via `.vote` voiced command (12h cooldown)
- **Global total-votes reward** (auto-grant items every X total votes site-wide)
- **HWID protection** to limit reward farming across alts on the same machine (windowed, configurable, falls back gracefully on packs without HWID support)
- **Never blocks the game thread** — every API call runs on a bounded background pool
- **Double-reward proof** — an atomic claim means `.vote` spam still pays out exactly once
- **Crash-safe state** — the cooldown store is written atomically, so a hard kill cannot wipe it
- Fully **admin-configurable** rewards: any item ID + count combination, plus command aliases
- **Localizable** — every player-facing message is overridable in the properties file (UTF-8)
- In-game `.vote reload` / `.vote status` for GMs, no restart needed to change rewards
- Single shared HTTP/JSON helper (no external dependencies)
- Pack-specific Java target version (8, 11 or 17) matching upstream

## Supported packs

| Pack | Chronicle(s) | Java | Path |
|------|--------------|------|------|
| **Universal — any fork** | any | 8 | [`packs/universal/`](packs/universal/) |
| aCis | Interlude | 17 | [`packs/acis/`](packs/acis/) |
| L2jMobius | All modern (Classic, Essence, Live, Interlude, H5) | 17 | [`packs/mobius/`](packs/mobius/) |
| L2jUnity | Classic / High Five | 11 | [`packs/unity/`](packs/unity/) |
| L2jFrozen | Interlude | 8 | [`packs/frozen/`](packs/frozen/) |
| L2jReunion / Sunrise (`l2r.*`) | Interlude / H5 | 8 | [`packs/reunion/`](packs/reunion/) |
| L2jServer / Sunrise (`com.l2jserver.*`) | Interlude / H5 | 8 | [`packs/l2jserver/`](packs/l2jserver/) |

### Your pack isn't listed?

Use the **universal** build. It imports nothing from the server pack and discovers the
voiced-command registry, the command interface, the player world and the `addItem`
overload at runtime — detecting your pack's root package from the `GameServer` hook itself.

That covers **L2jOrion, L2jHellas, L2jEnergy / Eternity (`l2e.*`), L2jLisvus, L2jFree,
L2jArchid, L2jDream, L2jBrasil, L2jPrime, L2jTeon, older aCis revisions**, and private
forks with renamed packages. One line to install, same as any other pack:

```java
l2topzone.universal.VoteRewardManager.getInstance();
```

See [`packs/universal/README.md`](packs/universal/README.md) for how detection works and
what to set if it can't find your registry.

## Install options

You have two ways to install. Pick the one that matches your comfort level.

### Option A — Pre-compiled JAR (recommended, no compilation needed)

1. Go to **[Releases](https://github.com/l2topzone/vote-reward-l2topzone.com/releases)** and download:
   - `l2topzone-vote-reward-<pack>.jar` matching your pack
   - `L2TopzoneVoteReward.properties.template`
2. Drop the JAR into your gameserver's classpath (`libs/`, `lib/` or whatever folder your launcher script includes).
3. Copy the template to `config/L2TopzoneVoteReward.properties` and fill in your API key + items.
4. Add **one line** to `GameServer.java` (next to the other manager `.getInstance()` calls):
   ```java
   l2topzone.universal.VoteRewardManager.getInstance();  // any other fork
   l2topzone.acis.VoteRewardManager.getInstance();       // for aCis
   l2topzone.mobius.VoteRewardManager.getInstance();     // for L2jMobius
   l2topzone.unity.VoteRewardManager.getInstance();      // for L2jUnity
   l2topzone.frozen.VoteRewardManager.getInstance();     // for L2jFrozen
   l2topzone.reunion.VoteRewardManager.getInstance();    // for L2jReunion / Sunrise (l2r.*)
   l2topzone.l2jserver.VoteRewardManager.getInstance();  // for L2jServer / Sunrise (com.l2jserver.*)
   ```
5. Recompile the gameserver (or just restart if your launcher hot-loads `libs/`) and test in-game with `.vote`.

No source merge, no patches, works on any revision of the supported packs (the JAR uses reflection for any pack-API method whose signature drifts between revisions).

### Option B — Build from source

1. Get your **API key** from your server's dashboard: open the **API documentation** button
   (`https://l2topzone.com/server/<your-server-id>/api-documentation`). That page holds the key
   and the endpoint reference for your server.
2. Whitelist your gameserver public IP in your server's dashboard.
3. Copy `common/*.java` + `packs/<your-pack>/VoteRewardManager.java` into your server source tree (paths documented in each pack's README).
4. Edit `config/L2TopzoneVoteReward.properties` with your API key, reward items and limits.
5. Recompile the gameserver. Reward Manager auto-loads at boot.
6. In-game test with `.vote`.

> **Full step-by-step installation guide for both options:** [docs/INSTALL.md](docs/INSTALL.md).

## API reference

Base URL: `https://api.l2topzone.com/v1`

| Endpoint | Purpose |
|----------|---------|
| `GET /server_{API_KEY}/getServerData` | Total votes + global rank for the server |
| `POST /server_{API_KEY}/getUserData` body `{ip}` | Has the player's IP voted in last 12h |
| `POST /server_{API_KEY}/confirmVote` body `{ip}` | Mark vote as rewarded (idempotent) |

All endpoints respect:
- **IP whitelist** — only your registered gameserver IP may call them.
- **Rate limit** — `throttle:vote-api` (60 req/min by default). `MaxConcurrentRequests` caps how fast the manager can hit it.

Sandbox mode (no auth, mock data) at `/v1/sandbox/...` for dev/testing.

A non-2xx response is always treated as a refusal, even if the body says `ok:true` — a
401/403 means the key or the IP whitelist is wrong, and rewards must not be granted off
the back of a rejected request. The in-game message distinguishes *"service unreachable"*
(transient) from *"service refused"* (an admin has to act), and the boot log states which
one it is with the API key masked.

## In-game admin commands

Available to GMs only:

| Command | Effect |
|---|---|
| `.vote reload` | Re-reads the properties file — rewards, limits and messages apply immediately. Changing the *command names* still needs a restart, and the command says so. |
| `.vote status` | Prints store counters (cooldowns, machines, HWID bindings, global baseline) and live API reachability. |

A bad config on reload is rejected and the previous settings are kept.

## Building

```bash
./build.sh all           # every pack into dist/
./build.sh universal     # just one
```

JDK 17 builds all targets; `javac --release N` cross-compiles to 8 / 11 / 17 per pack.

## Tests

```bash
./test/run-tests.sh
```

No framework and no downloads — plain `javac` plus a mock API on the JDK's own HTTP
server. Covers the JSON reader, store concurrency and persistence, `addItem` overload
resolution across pack shapes, and the full reward flow, including the two cases that
matter most in production: **24 simultaneous `.vote` commands pay out exactly once**, and
`handle()` returns in ~1ms even when the API takes 700ms. A second suite boots the
universal build against a fake fork to prove runtime detection. See [test/](test/).

## License

MIT — see [LICENSE](LICENSE).

## Contributing

PRs welcome for additional packs (Sunrise newer revisions, L2jOlympiad, custom forks). Please match the existing structure and document any pack-specific quirks in the pack's README.
