# Companion Drone — Mindustry mod (Java)

A small Poly-like drone follows every player, repairs damaged buildings nearby and shows speech bubbles (enemies, attacked core, wave soon, low health, idle lines).
It is not a real unit: never saved, no unit cap, cannot be targeted. Multiplayer: every client moves and draws the drones of all players itself; the side that owns the world (singleplayer, host, dedicated server) does the repairs for all drones and the game syncs building health; lines and hearts of a player's drone go to the others as the mod packet `companion-drone-say` (client -> server -> all clients).

## Build
- `.\build.ps1` → `build\companion-drone.jar`; `.\build.ps1 -Install` copies it to `C:\Program Files (x86)\Steam\steamapps\common\Mindustry\saves\mods\companion-drone.jar`.
- No Gradle: javac from JDK 25 (`%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot`) with `--release 17`, classpath = the game's own `jre\desktop.jar` (Mindustry 160.7). `java` on PATH is Java 8 — never use it.
- Check API with `javap -cp <desktop.jar> <class>` instead of guessing.
- Game log: `...\Mindustry\saves\last_log.txt`. Mod logs are prefixed `[CompanionDrone]`.

## saves\ is synced by Steam Cloud
- Never move or delete anything in `Mindustry\saves\` — Steam Cloud brings removed files back. Install only by overwriting files in place.

## Layout
- `src/companiondrone/CompanionDroneMod.java` — everything: settings, movement, repair, chatter, drawing.
- `assets/` — `mod.hjson`, `bundles/` (en + ru). Message lines are `companion.<kind>.<n>`; the count per kind is in `variants`.
- Settings (Settings → Game): `companion-drone-enabled`, `companion-drone-chatter`, `companion-drone-repair`.

## Rules
- Tunable numbers are `public static final` constants at the top of the class — the author tunes them himself.
- No hand-drawn art: the drone is the vanilla Poly sprite, scaled and recolored.
- Keep Exogenesis compatibility in mind.
- Code, comments, bundles: plain and neutral. Bundles in English and Russian.
