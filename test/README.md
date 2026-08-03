# Tests

```bash
./test/run-tests.sh
```

No framework, no downloads — plain `javac` plus the JDK's own `com.sun.net.httpserver`
for the mock API. If you can build the project, you can run these.

## Suite 1 — core (`src/l2topzone/CoreSelfTest.java`)

| Area | What it pins down |
|---|---|
| JSON reader | Quoted (`"true"`) and numeric (`1`) booleans, quoted numbers, nested objects, arrays, escapes — and that a key appearing inside a *string value* is not mistaken for a real key |
| Store | 32 threads racing for one claim yield **exactly one** winner; release/re-claim; HWID windowing; persistence round-trip; v1 format migration; atomic save leaves no `.tmp`; malformed lines skipped without aborting the load |
| `addItem` resolution | `long` vs `int` count overloads, over-range clamping, void returns, `null` return treated as failure, incompatible reference parameter passed as `null` |
| IP resolution | `InetAddress` path, IPv4-mapped IPv6 unwrapping, null client |
| End-to-end | `handle()` returns in ~1ms while the API takes 700ms; **24 concurrent `.vote` yield exactly one reward**; cooldown not consumed when there was no vote; HTTP 403 surfaces as "refused" not "unreachable"; level gate; milestone baseline, cap and counter-reset handling; overlapping polls skipped; offline traders excluded |

## Suite 2 — universal integration (`src/UniversalIntegrationTest.java`)

Boots the universal build against `test/fake-pack/` — a miniature L2j fork under
`com.faketest.gameserver`, a package **deliberately absent from the built-in root list**.
The only way the build can attach is by reading the caller's package off the stack.

It asserts the handler is registered into the fake pack's own registry via dynamic proxy,
that dispatching `.vote` through that registry delivers the reward, that a custom
`MsgRewarded` is honoured, and that the global reward reaches players through a
`Map`-returning `getAllPlayers()`.

## Adding a fork to the fake pack

Copy `test/fake-pack/` to a second namespace and vary what you want to cover — a
`Collection`-returning world, a `registerVoicedCommandHandler` name, an `int`-count
`addItem`. The universal build should attach to it unchanged; if it does not, that is a
detection gap worth fixing.
