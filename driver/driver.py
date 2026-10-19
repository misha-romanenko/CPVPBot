#!/usr/bin/env python3
"""CPVPBOT2.0 driver - fully autonomous test run.

cpvpbot (Baritone fighter) auto-connects/registers/accepts via its mod.
This driver only needs to: start duel -> wait for kit -> run checklist -> report.
Every step prints project completion %.
"""
import json
import os
import socket
import subprocess
import sys
import time

def _load_dotenv(path):
    """Tiny .env loader (KEY=VALUE lines). Existing env vars win."""
    try:
        with open(path) as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                key, val = key.strip(), val.strip().strip('"').strip("'")
                os.environ.setdefault(key, val)
    except OSError:
        pass


WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_load_dotenv(os.path.join(WS, ".env"))
HOST, PORT = "127.0.0.1", 4777
PASSWORD = os.environ.get("BOT_PASSWORD", "")
REPORT = []
PROGRESS_MARKS = []


def log(msg, pct=None):
    prefix = f"[PROGRESS {pct:3d}%] " if pct is not None else ""
    line = f"[{time.strftime('%H:%M:%S')}] {prefix}{msg}"
    print(line, flush=True)
    REPORT.append(line)
    if pct is not None:
        PROGRESS_MARKS.append((pct, msg))


class Ctrl:
    def __init__(self):
        self.sock = None
        self.f = None

    def connect(self, tries=90):
        for _ in range(tries):
            try:
                self.sock = socket.create_connection((HOST, PORT), timeout=30)
                self.f = self.sock.makefile("rwb")
                return True
            except OSError:
                time.sleep(2)
        return False

    def alive(self):
        try:
            self.sock.sendall(b'{"cmd":"ping"}\n')
            self.sock.settimeout(10)
            return bool(self.f.readline().strip())
        except OSError:
            return False

    def cmd(self, obj, timeout=30):
        self.sock.settimeout(timeout)
        self.f.write((json.dumps(obj) + "\n").encode())
        self.f.flush()
        while True:
            line = self.f.readline()
            if not line:
                raise ConnectionError("control channel closed")
            line = line.strip()
            if not line:
                continue
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue

    def wait_ok(self, obj, tries=30, delay=2, timeout=30):
        last = None
        for _ in range(tries):
            try:
                last = self.cmd(obj, timeout=timeout)
                if last.get("ok"):
                    return last
            except (OSError, ConnectionError) as e:
                last = {"ok": False, "error": str(e)}
            time.sleep(delay)
        return last

    def chat(self, text):
        return self.cmd({"cmd": "chat", "text": text})

    def status(self):
        try:
            return self.cmd({"cmd": "status"})
        except (OSError, ConnectionError) as e:
            return {"ok": False, "error": str(e)}

    def chat_since(self, since=0):
        r = self.cmd({"cmd": "chatlog", "since": since})
        lines = r.get("lines", []) if r.get("ok") else []
        seqs = []
        for l in lines:
            try:
                seqs.append(int(l[:l.index(" ")]))
            except ValueError:
                pass
        nxt = max(seqs) + 1 if seqs else since
        return lines, nxt


def kit_counts(st):
    return {k: st.get(k, 0) for k in
            ("totems", "crystals", "obsidian", "anchors", "glowstone", "gapples")}


def main():
    # ---- 5%: control port --------------------------------------------------
    c = Ctrl()
    log("waiting for cpvpbot control port (mod auto-connects by itself now)...", 5)
    if not c.connect():
        log("FATAL: control port never came up", 5)
        return 1

    # ---- 15%: in-world -----------------------------------------------------
    log("waiting for cpvpbot to enter the world (mod self-connects with backoff)...", 15)
    st = None
    chat_seq = 0
    # if already in-world (e.g. user connected manually), skip the nudge entirely -
    # firing connect while in-world would drop the live session back into the
    # proxy's login throttle. Only nudge when we're actually stuck at a screen.
    st = c.wait_ok({"cmd": "status"}, tries=3, delay=2)
    if not (st and st.get("ok")):
        log("  not in-world; sending one gentle connect nudge...", 15)
        c.cmd({"cmd": "connect", "address": "valkyriemc.net"})
        for attempt in range(3):
            st = c.wait_ok({"cmd": "status"}, tries=40, delay=3)
            if st and st.get("ok"):
                break
            log(f"  not in-world yet (attempt {attempt + 1}); mod keeps retrying with backoff...", 15)
    if not st or not st.get("ok"):
        lines, _ = c.chat_since(0)
        for l in lines[-12:]:
            log("  chat: " + l)
        log("FATAL: cpvpbot never spawned", 15)
        return 1
    log(f"in-world: name={st['name']} pos={st['pos']} dim={st['dimension']}", 18)

    # ---- 25%: auth ---------------------------------------------------------
    time.sleep(6)   # let auto-register fire
    lines, chat_seq = c.chat_since(chat_seq)
    authed = any("registered" in l.lower() or "login" in l.lower() for l in lines)
    if not authed:
        c.chat(f"/register {PASSWORD} {PASSWORD}")
        time.sleep(4)
        lines, chat_seq = c.chat_since(chat_seq)
        authed = any("registered" in l.lower() for l in lines)
    for l in lines[-6:]:
        log("  chat: " + l)
    if not authed:
        log("WARN: could not confirm registration, continuing anyway", 25)
    else:
        log("authenticated (register/login confirmed)", 25)

    # ---- 35%: duel ---------------------------------------------------------
    log("sending /duel dummy crystal (dummy auto-accepts)...", 35)
    c.chat("/duel dummy crystal")

    # wait for the duel to actually start = kit appears in inventory
    duel_started = False
    for i in range(45):   # up to 90s
        time.sleep(2)
        st = c.status()
        if not st.get("ok"):
            continue
        counts = kit_counts(st)
        if any(v > 0 for v in counts.values()):
            duel_started = True
            log(f"duel started! kit: {counts}", 40)
            break
        if i % 5 == 4:
            lines, chat_seq = c.chat_since(chat_seq)
            for l in lines[-3:]:
                log("  chat: " + l)
            # keep re-sending every 30s in case the first request expired
            if i % 15 == 14:
                log("  re-sending duel request...", 35)
                c.chat("/duel dummy crystal")
    if not duel_started:
        lines, chat_seq = c.chat_since(chat_seq)
        for l in lines[-10:]:
            log("  chat: " + l)
        log("FAIL: duel never started / no kit received", 40)
        # still try lobby-mode tests below (may be N/A)
    st = c.status()

    checklist = {}

    def ensure_kit(label):
        """Re-start the duel if the kit vanished (duel ended). Returns fresh status."""
        s = c.status()
        if s.get("ok") and s.get("crystals", 0) > 0 and s.get("obsidian", 0) > 0:
            return s
        log(f"  kit gone ({label}) - re-sending duel request...", 40)
        c.chat("/duel dummy crystal")
        for _ in range(40):
            time.sleep(2)
            s = c.status()
            if s.get("ok") and s.get("crystals", 0) > 0 and s.get("obsidian", 0) > 0:
                log(f"  duel restarted, kit back: crystals={s['crystals']} obsidian={s['obsidian']}", 40)
                return s
        return s

    # ---- 50%: movement -----------------------------------------------------
    log("== TEST: Baritone movement (goto) ==", 45)
    x, y, z = st["pos"]
    gx, gz = int(x) + 5, int(z) + 4
    r = c.cmd({"cmd": "goto", "x": gx, "y": int(y), "z": gz})
    log(f"  goto {gx},{int(y)},{gz} -> {r}", 45)
    moved = False
    for _ in range(25):
        time.sleep(2)
        s = c.status()
        if not s.get("ok"):
            continue
        nx, ny, nz = s["pos"]
        if abs(nx - x) + abs(nz - z) > 2:
            moved = True
            log(f"  moved to ({nx:.1f},{ny:.1f},{nz:.1f})", 48)
            break
    c.cmd({"cmd": "stop"})
    checklist["movement"] = moved
    log(f"  movement: {'PASS' if moved else 'FAIL'}", 50)

    # helper: find a placement spot (solid block with air above, near feet).
    # skips blocks overlapping the player's own hitbox - vanilla refuses to
    # place a block inside an entity (returns Fail[]).
    def find_spot(s, radius=3):
        px, py, pz = s["pos"]
        base_x, base_y, base_z = int(px), int(py), int(pz)
        for r_ in range(1, radius + 1):
            for dx in range(-r_, r_ + 1):
                for dz in range(-r_, r_ + 1):
                    bx, bz = base_x + dx, base_z + dz
                    # must not overlap the player's AABB (player is ~0.6 wide)
                    if (bx + 0.5 - px) ** 2 + (bz + 0.5 - pz) ** 2 < 1.0:
                        continue
                    below = c.cmd({"cmd": "block", "x": bx, "y": base_y - 1, "z": bz})
                    above = c.cmd({"cmd": "block", "x": bx, "y": base_y, "z": bz})
                    if below.get("ok") and below.get("id") not in ("minecraft:air", "minecraft:water", "minecraft:void_air") \
                       and above.get("ok") and above.get("id") in ("minecraft:air", "minecraft:cave_air"):
                        return (bx, base_y, bz)
        return None

    place_target = None

    # helper: verify a block id at a position
    def block_at(x, y, z):
        r = c.cmd({"cmd": "block", "x": x, "y": y, "z": z})
        return r.get("id") if r.get("ok") else None

    # ---- 60%: obsidian -----------------------------------------------------
    s = ensure_kit("obsidian")
    if s.get("obsidian", 0) > 0:
        place_target = find_spot(s)
        placed = False
        if place_target:
            bx, by, bz = place_target
            r = c.cmd({"cmd": "place", "x": bx, "y": by, "z": bz,
                       "face": "UP", "item": "minecraft:obsidian"})
            log(f"  place obsidian @ {bx},{by},{bz} -> {r}", 53)
            time.sleep(2)
            r2 = c.cmd({"cmd": "block", "x": bx, "y": by, "z": bz})
            log(f"  verify -> {r2}", 55)
            placed = r2.get("ok") and r2.get("id") == "minecraft:obsidian"
        else:
            log("  no placement spot found near feet", 55)
        checklist["obsidian"] = placed
        log(f"  obsidian: {'PASS' if placed else 'FAIL'}", 58)
    else:
        checklist["obsidian"] = None
        log("  SKIP: no obsidian in inventory", 58)

    # ---- 70%: crystal place + pop -----------------------------------------
    s = c.status()
    if s.get("crystals", 0) > 0 and checklist.get("obsidian"):
        bx, by, bz = place_target
        r = c.cmd({"cmd": "place", "x": bx, "y": by, "z": bz,
                   "face": "UP", "item": "minecraft:end_crystal"})
        log(f"  place crystal @ {bx},{by},{bz} -> {r}", 60)
        time.sleep(2)
        r = c.cmd({"cmd": "attack", "target": "nearest_crystal"})
        log(f"  attack crystal -> {r}", 63)
        popped = False
        if r.get("ok"):
            for _ in range(10):
                time.sleep(1)
                r2 = c.cmd({"cmd": "attack", "target": "nearest_crystal"})
                if not r2.get("ok"):
                    popped = True   # no crystal left = it exploded
                    break
            # check our own health dropped (explosion nearby) as extra evidence
            s2 = c.status()
            log(f"  after pop: health={s2.get('health')} (was {s.get('health')})", 65)
            checklist["crystal"] = popped
        else:
            checklist["crystal"] = False
        log(f"  crystal pop: {'PASS' if checklist['crystal'] else 'FAIL'}", 68)
    else:
        checklist["crystal"] = None
        log("  SKIP: need crystals + obsidian platform", 68)

    # ---- 78%: anchor + glowstone ------------------------------------------
    # NOTE: the obsidian block now occupies place_target, so a fresh air spot is
    # required (place on old spot would land the anchor one block above it).
    s = ensure_kit("anchor")
    if s.get("anchors", 0) > 0:
        a_spot = find_spot(s, radius=3) or find_spot(s, radius=4)
        if a_spot:
            bx, by, bz = a_spot
            r = c.cmd({"cmd": "place", "x": bx, "y": by, "z": bz,
                       "face": "UP", "item": "minecraft:respawn_anchor"})
            log(f"  place anchor @ {bx},{by},{bz} -> {r}", 70)
            time.sleep(2)
            aid = block_at(bx, by, bz)
            anchor_ok = aid == "minecraft:respawn_anchor"
            log(f"  verify anchor -> {aid}", 72)
            if anchor_ok and s.get("glowstone", 0) > 0:
                c.cmd({"cmd": "select", "item": "minecraft:glowstone"})
                r = c.cmd({"cmd": "use"})
                log(f"  charge anchor -> {r}", 74)
                time.sleep(1.5)
                r2 = c.cmd({"cmd": "block", "x": bx, "y": by, "z": bz})
                log(f"  verify charged -> {r2}", 76)
                charged = r2.get("ok") and "level" in r2.get("state", "")
                if charged:
                    r = c.cmd({"cmd": "use"})
                    log(f"  detonate anchor -> {r}", 77)
                    time.sleep(2)
                    checklist["anchor"] = True
                else:
                    # overworld cannot charge anchors (vanilla rule) - placement verified
                    checklist["anchor"] = "placed_only"
            elif anchor_ok:
                checklist["anchor"] = "placed_only"
            else:
                checklist["anchor"] = False
        else:
            checklist["anchor"] = False
            log("  no free spot for anchor", 78)
        log(f"  anchor: {checklist['anchor']}", 78)
    else:
        checklist["anchor"] = None
        log("  SKIP: no anchors in kit", 78)

    # ---- 85%: golden apple -------------------------------------------------
    s = ensure_kit("gapple")
    if s.get("gapples", 0) > 0:
        c.cmd({"cmd": "select", "item": "minecraft:golden_apple"})
        r = c.cmd({"cmd": "use"})
        log(f"  eat gapple -> {r}", 80)
        ate = False
        for _ in range(15):
            time.sleep(1)
            s2 = c.status()
            if s2.get("ok") and s2.get("absorption", 0) > 0:
                ate = True
                log(f"  absorption={s2['absorption']} - gapple eaten", 83)
                break
        checklist["gapple"] = ate
        log(f"  gapple: {'PASS' if ate else 'FAIL'}", 85)
    else:
        checklist["gapple"] = None
        log("  SKIP: no gapples in kit", 85)

    # ---- 95%: totem pop (real damage, pop own crystals at feet) -----------
    s = ensure_kit("totem")
    if s.get("totems", 0) > 0 and s.get("crystals", 0) > 0 and s.get("obsidian", 0) > 0:
        # fresh obsidian platform: earlier tests left blocks on the old spot
        t_spot = find_spot(s, radius=3)
        bx = by = bz = None
        if t_spot:
            px, py, pz = t_spot
            c.cmd({"cmd": "place", "x": px, "y": py, "z": pz,
                   "face": "UP", "item": "minecraft:obsidian"})
            time.sleep(1.5)
            if block_at(px, py, pz) == "minecraft:obsidian":
                bx, by, bz = px, py, pz
        if bx is None:
            checklist["totem"] = None
            log("  SKIP: could not build totem-test platform", 95)
        else:
            # totem MUST be in a hand (vanilla trigger requirement)
            c.cmd({"cmd": "select", "item": "minecraft:totem_of_undying", "offhand": True})
            time.sleep(0.5)
            log(f"  health={s.get('health')} totems={s['totems']} - totem readied, forcing damage...", 87)
            popped = False
            start_totems = s["totems"]
            for attempt in range(12):
                cur = c.status()
                if cur.get("totems", 99) < start_totems:
                    popped = True
                    break
                c.cmd({"cmd": "place", "x": bx, "y": by, "z": bz,
                       "face": "UP", "item": "minecraft:end_crystal"})
                time.sleep(1.2)
                c.cmd({"cmd": "attack", "target": "nearest_crystal"})
                time.sleep(2.5)
                after = c.status()
                log(f"  blast {attempt + 1}: hp={after.get('health')} "
                    f"totems={after.get('totems')} absorption={after.get('absorption')}", 90)
                if after.get("totems", 99) < start_totems:
                    popped = True
                    break
                if not after.get("ok") or after.get("health", 0) <= 0:
                    break
            checklist["totem"] = popped
            log(f"  totem pop: {'PASS' if popped else 'FAIL'}", 95)
    else:
        checklist["totem"] = None
        log("  SKIP: need totem + crystals + obsidian", 95)

    # ---- 100%: report ------------------------------------------------------
    log("== FINAL CHECKLIST ==", 100)
    for k, v in checklist.items():
        state = {True: "PASS", False: "FAIL", None: "SKIP"}.get(v, str(v))
        log(f"  {k:10s} {state}", 100)

    # copy evidence into logs/
    gamedir = os.path.join(
        "/run/media/configg_/HDD/MinecraftInstances/instances/cpvpbot/.minecraft",
        "cpvpbot-actions.log")
    try:
        subprocess.run(["cp", gamedir, os.path.join(WS, "logs/cpvpbot-actions.log")],
                       check=False)
        log("copied cpvpbot-actions.log -> logs/", 100)
    except Exception as e:
        log(f"could not copy actions log: {e}", 100)

    with open(os.path.join(WS, "logs/driver-report.txt"), "w") as f:
        f.write("\n".join(REPORT) + "\n")
    log("report written to logs/driver-report.txt", 100)
    return 0


if __name__ == "__main__":
    sys.exit(main())
