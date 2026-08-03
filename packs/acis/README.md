# aCis — L2Topzone Vote Reward

Java **17** • Tested on rev 400+ (Interlude).

## Files to copy

| Source (this repo) | Destination (your gameserver source tree) |
|--------------------|-------------------------------------------|
| `common/L2Topzone*.java` (6 files) | `gameserver/java/l2topzone/` |
| `packs/acis/VoteRewardManager.java` | `gameserver/java/l2topzone/acis/VoteRewardManager.java` |
| `common/L2TopzoneVoteReward.properties` | `gameserver/config/L2TopzoneVoteReward.properties` |

## Hook into startup

In `gameserver/java/net/sf/l2j/gameserver/GameServer.java`, near the bottom of the constructor (after `VoicedCommandHandler.getInstance()` is initialized):

```java
l2topzone.acis.VoteRewardManager.getInstance();
```

That's it. The manager registers its own voiced command handler and schedules the global poller.

## Configuration

Edit `config/L2TopzoneVoteReward.properties`:

```properties
ApiKey                = abc123...        # from https://l2topzone.com/billing
IndividualRewardItemIds   = 57           # adena
IndividualRewardItemCount = 1000000

GlobalEnabled         = true
GlobalVoteInterval    = 100              # every 100 server votes...
GlobalRewardItemIds   = 6673             # ...give a Festival Adena
GlobalRewardItemCount = 5

HwidProtection      = true
MaxAccountsPerHwid  = 1
```

## In-game

```
.vote
```

Cooldown is 12h (matches the API). HWID protection limits one reward per machine per cooldown window.

## Notes

- `client.getHWID()` exists on revisions that bundle PcCafe / Anti-Bot patches. On vanilla rev 400 base it may be missing — the manager logs a one-line warning and continues without HWID checks.
- The **store file** (`config/L2TopzoneVoteReward.store`) is plain text, auto-created. Safe to delete to reset cooldowns.
- IP resolution uses `player.getClient().getConnection().getInetAddress()` which is stable on aCis.
