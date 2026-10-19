# CPVPBot

An autonomous **Crystal PvP bot** for Minecraft (protocol `26.2`) that connects to a
server by itself, accepts duels, and demonstrates the core Crystal PvP mechanics:
Baritone-assisted movement, placing obsidian, placing & detonating end crystals,
charging anchors with golden apples / glowstone, eating golden apples, and
surviving lethal damage via totems of undying.

Tested end-to-end on `valkyriemc.net` (Velocity proxy, offline-mode names, AuthMe).

## How it works

There are three independent pieces. You start each one; the bot does the rest.

```
                 ┌──────────────────────────────┐
   driver.py ───►│ 127.0.0.1:4777 (TCP, JSON)   │◄─── cpvpbot mod (control server)
   (orchestrator)└──────────────────────────────┘        │
                                                          ▼
        dummy.js  ◄────────── server chat ─────────►  cpvpbot (real MC client
        (headless target,                             + Baritone + Fabric mod)
         auto-accepts duels)
```

| Piece | What it is | How it runs |
|-------|-----------|-------------|
| **cpvpbot** | A real Minecraft `26.2` game window running a Fabric mod (Baritone + a TCP control server on port `4777`). Logs itself in, auto-accepts duels. | `./launch-cpvpbot.sh` |
| **dummy** | A headless `mineflayer` bot named `dummy`. It joins, accepts duels from `cpvpbot`, and wanders as a target. | `node dummy/dummy.js` |
| **driver** | A Python orchestrator that talks to port `4777` to run the whole test checklist and print progress + a report. | `python3 driver/driver.py` |

## Setup

### 1. Secrets

The AuthMe password is **not** in the repo. Copy the example and fill it in:

```bash
cp .env.example .env
# edit .env and set BOT_PASSWORD=<your server password>
```

`.env` is gitignored. Every component reads `BOT_PASSWORD` from the environment
(the launcher sources `.env` automatically).

### 2. Build the mod

```bash
GRADLE_USER_HOME=$PWD/toolchain/gradle-home \
  $PWD/toolchain/gradle/bin/gradle build --no-daemon \
  -Dfabric.loom.disableObfuscation=true -p cpvpbot-mod
```

The compiled jar lands in `cpvpbot-mod/build/libs/`. Copy it into the game
instance's `mods/` folder:

```bash
cp cpvpbot-mod/build/libs/cpvpbot-*.jar \
   /run/media/configg_/HDD/MinecraftInstances/instances/cpvpbot/.minecraft/mods/
```

**Game instance prerequisites** (a Fabric `26.2` instance, e.g. via Prism Launcher):

- `fabric-loader` (`KnotClient` entrypoint)
- `fabric-api`
- `baritone-api-fabric-1.19.0.jar` — the **API** superset jar. Do **not** also put the
  standalone/obfuscated Baritone jar in `mods/`; they conflict.

> `cpvpbot-mod/libs/` contains a 480-byte `identity-mappings.jar` stub (Minecraft 26.2
> is unobfuscated, so mappings are the identity function) which is required to build.
> The 4.8 MB `baritone-api-fabric-1.19.0.jar` is gitignored — drop it into
> `cpvpbot-mod/libs/` yourself to compile.

### 3. Install the dummy's dependencies

```bash
cd dummy && npm install   # mineflayer + pathfinder
```

## Running

Open three terminals in the project root:

```bash
# 1. the target (headless, leave running)
node dummy/dummy.js

# 2. the fighter (opens a game window; connects + logs in by itself)
./launch-cpvpbot.sh

# 3. the orchestrator (starts a duel and runs the full checklist)
python3 driver/driver.py
```

The driver prints step-by-step progress with completion percentages and writes
`logs/driver-report.txt`.

## Driving it manually

The mod exposes a JSON-lines control protocol on `127.0.0.1:4777`. Each line in
is one JSON object; the response is one JSON object out.

```bash
printf '{"cmd":"status"}\n' | nc -q1 127.0.0.1 4777
```

| Command | Purpose |
|---------|---------|
| `status` | name, position, dimension, health, absorption, food, and item counts (totems/crystals/obsidian/anchors/glowstone/gapples) |
| `block` | read a block's id + full state at `x,y,z` |
| `place` | place `item` at `x,y,z` on face `UP`/`DOWN`/… |
| `use`   | right-click the held item (charge/detonate anchors, eat) |
| `attack`| attack an entity (`target: nearest_crystal`) |
| `select`| move an item into the main hand (or `offhand: true`) |
| `goto`  | Baritone pathfind to `x,y,z` |
| `screen`| current screen class + disconnect reason (debugging) |
| `connect` | connect to `address` (mod normally self-connects) |

## Autonomy

The mod runs fully unattended:

- **Self-connect** with exponential backoff (`15s → 30s → 60s → 120s → 240s → 300s`
  cap). This matters because the Velocity proxy answers rapid re-login attempts with
  *"You are logging in too fast, try again later."* — aggressive retries keep you
  permanently throttled.
- **Auto register/login** on AuthMe prompts (`/register`, `/login`).
- **Auto-accept duels**: any `duel request from <name>` triggers `/duel accept <name>`.
- **Action logging**: every move / place / attack / totem-pop is appended to
  `cpvpbot-actions.log` in the game dir, used as runtime evidence.

## Test checklist

`driver.py` starts a `crystal` duel against `dummy`, waits for the kit to appear,
then verifies each mechanic against real game state (block reads + inventory counts,
not just client-side "success"):

- [x] Baritone movement (`goto` a far goal)
- [x] Place obsidian (block readback confirms `minecraft:obsidian`)
- [x] Place + pop an end crystal (bot takes real explosion damage)
- [x] Place an anchor and interact with glowstone *(charge is a no-op in the
      overworld by vanilla rule — needs a Nether arena to detonate)*
- [x] Eat a golden apple (absorption appears)
- [x] Take lethal damage and pop a totem (totem count drops, mod logs `TOTEM_POP`)

## Notes & limitations

- The bot has **no combat strategy AI** — it proves every *mechanic* works, and will
  auto-accept a challenge from any player, but it does not actively fight back yet.
  Building a real crystal-PvP combat loop on top of these primitives is the natural
  next step.
- Anchor **detonation** requires a Nether-dimension arena (vanilla restricts anchor
  charging to the Nether); the duel arena used here is an overworld temp-world.
- Never run more than one `dummy` instance — duplicate logins cause proxy kicks.
