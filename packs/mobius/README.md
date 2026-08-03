# L2jMobius — L2Topzone Vote Reward

Java **17** • All chronicles (Classic, Essence, Live, Interlude, High Five).

## Files

| Source | Destination |
|--------|-------------|
| `common/L2Topzone*.java` (6 files) | `gameserver/data/scripts/l2topzone/` *(or `gameserver/java/l2topzone/` if you compile alongside core)* |
| `packs/mobius/VoteRewardManager.java` | `.../l2topzone/mobius/VoteRewardManager.java` |
| `common/L2TopzoneVoteReward.properties`  | `gameserver/config/L2TopzoneVoteReward.properties` |

## Hook

Either add the script to `gameserver/data/scripts.cfg`:

```
l2topzone/mobius/VoteRewardManager.java
```

Or call the manager from `GameServer.java`:

```java
l2topzone.mobius.VoteRewardManager.getInstance();
```

## HWID

Mobius exposes hardware info via `client.getHardwareInfo().getMacAddress()` on most chronicles. The manager probes this via reflection and falls back gracefully if your specific build uses a different accessor (`client.getHwid()`).

## Voiced command

`.vote` — 12h cooldown.
