# Configuration reference

All packs share the same `L2TopzoneVoteReward.properties` file, read as **UTF-8** (write
accented characters directly, no `\uXXXX` escaping).

Every value is validated at boot. Out-of-range numbers are clamped with a `[L2Topzone]`
warning; a config that cannot work at all (missing key, mismatched reward lists, zero
counts) disables the manager with an explicit `SEVERE` line rather than failing quietly later.

## Required

| Key | Description |
|-----|-------------|
| `ApiKey` | Your unique server API key, from the L2Topzone admin panel. Never appears in logs — only the last 4 characters are printed. |

## API & runtime

| Key | Default | Description |
|-----|---------|-------------|
| `ApiHost` | `https://api.l2topzone.com/v1` | Override only for sandbox testing. Must start with `http://` or `https://`. |
| `VoiceCommand` | `vote` | Trigger word without the dot. Comma-separate for aliases: `vote, votar, rasplata`. A leading dot is tolerated and stripped. |
| `CooldownHours` | `12` | Per-character cooldown matching the API window. 1–720. |
| `MinPlayerLevel` | `1` | Minimum level allowed to claim. Raise to blunt bot/alt farming. |
| `HttpTimeoutSeconds` | `5` | Connect and read timeout per request. 1–30. |
| `MaxConcurrentRequests` | `4` | Cap on simultaneous API calls. Also throttles a `.vote` spam burst against the published 60 req/min limit. 1–16. |

## Individual reward (`.vote` command)

| Key | Description |
|-----|-------------|
| `IndividualRewardItemIds` | Comma-separated item IDs |
| `IndividualRewardItemCount` | Comma-separated counts (same length, all > 0) |

## Global milestone reward

Triggered every time the **server total** crosses a multiple of `GlobalVoteInterval`.

| Key | Default | Description |
|-----|---------|-------------|
| `GlobalEnabled` | `true` | `false` to skip entirely |
| `GlobalVoteInterval` | `100` | Granularity |
| `GlobalRewardItemIds` | `6673` | Comma-separated item IDs |
| `GlobalRewardItemCount` | `5` | Comma-separated counts (same length, all > 0) |
| `GlobalCheckMinutes` | `5` | Polling interval, 1–1440 |
| `GlobalInitialDelaySeconds` | `60` | Wait before the first poll, 5–3600 |
| `GlobalMaxMilestonesPerCheck` | `5` | Safety cap on milestones paid in a single poll |
| `GlobalRewardOfflineTraders` | `false` | Whether offline-shop characters also receive it |

If several milestones are crossed between two polls, the reward is multiplied — **up to
`GlobalMaxMilestonesPerCheck`**. That cap matters: without it, a deleted store file or a
toplist counter reset turns one poll into an arbitrarily large payout to everyone online.

The first poll after a fresh install only records a baseline; it never pays out. If the
site's total ever goes *down* (monthly reset), the manager re-baselines instead of
computing a negative delta.

## HWID protection

| Key | Default | Description |
|-----|---------|-------------|
| `HwidProtection` | `true` | Enable the check |
| `MaxAccountsPerHwid` | `1` | Distinct characters per machine inside the window |
| `HwidWindowHours` | = `CooldownHours` | How far back bindings count |

Bindings are timestamped and expire after `HwidWindowHours`. A second character on the
same PC is limited *for the window*, not banned permanently.

> **Upgrading from 1.x:** the old format stored HWID bindings without a timestamp and they
> were permanent. They are migrated on first load as already-expired, so the documented
> windowed behaviour takes effect immediately. The boot log reports how many were migrated.

If your pack does not expose a HWID, the manager logs one warning at boot and skips the
check. **No farming is unlocked by that** — the per-character cooldown and the API's own
per-IP window still apply.

## Paths

| Key | Default | Description |
|-----|---------|-------------|
| `StoreFile` | *(pack default)* | Where cooldown/HWID state is persisted. Give each world its own file when several gameservers share a directory. |
| `PackBasePackage` | *(empty)* | **Universal build only.** Your pack's root package, i.e. the part before `.gameserver` (`com.l2jorion`). Normally auto-detected — set it only if the boot log says the registry was not found. |

## Messages

Every player-facing string is overridable. Delete a line to keep the English default; set
it **empty** to silence that message entirely.

| Key | Placeholders |
|-----|--------------|
| `MsgPrefix` | — (a space is inserted after it automatically) |
| `MsgChecking` | — |
| `MsgRewarded` | — |
| `MsgNoVote` | — |
| `MsgAlreadyRewarded` | `{time}` |
| `MsgCooldown` | `{time}` |
| `MsgHwid` | — |
| `MsgApiDown` | — |
| `MsgApiError` | — |
| `MsgNoIp` | — |
| `MsgBusy` | — |
| `MsgInProgress` | — |
| `MsgInventoryFull` | — |
| `MsgLowLevel` | `{level}` |
| `MsgGlobalReward` | `{votes}` |
| `MsgReloaded`, `MsgReloadFailed` | — |

`{time}` renders as `HH:MM:SS`.

## Misc

| Key | Default | Description |
|-----|---------|-------------|
| `DebugLog` | `false` | Logs each reward and the reason behind each API-level skip. Recommended during setup. |

## Live reload

GMs can apply config changes without a restart:

```
.vote reload     re-read the properties file
.vote status     store counters + live API reachability
```

Rewards, cooldowns, limits and messages take effect immediately. **Command names** are
registered with the pack at boot, so changing `VoiceCommand` still needs a restart — the
reload command tells you when that applies. An invalid file is rejected and the previous
settings are kept.

## Multiple worlds (single API key, multiple gameservers)

If you registered each world with its own `api_code`, run a separate gameserver instance
per world. Give each one a distinct `StoreFile` if they share a config directory. The API
attributes votes per world based on the key used.
