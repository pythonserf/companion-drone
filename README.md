# Companion Drone

A Mindustry mod (v8, build 160): a cute little Poly-like drone follows you around, patches up damaged buildings nearby and comments on what is happening — enemies, waves, an attacked core. Tap it and it shows a heart.

- Not a real unit: it is never saved, does not count toward the unit cap and cannot be shot.
- Multiplayer: every player gets a drone and everyone sees all of them, with their lines and hearts. The server does the repairs. The mod must be installed on the server and on every client.
- Settings → Game: turn the drone, its chatter or its repairs on and off.

## Install
Download `companion-drone.jar` from [Releases](../../releases) and put it into the game's `mods` folder (or use *Import mod* in the game).

## Build
JDK 17+, no Gradle: `build.ps1` compiles against the game's own `jre/desktop.jar` (edit the paths at the top). `.\build.ps1 -Install` also copies the jar into the game's mods folder.

---

# Дрон-компаньон

Мод для Mindustry (v8, сборка 160): милый маленький дрон, похожий на Поли, летает за тобой, чинит повреждённые постройки рядом и комментирует происходящее: враги, волны, атака на ядро. Если на неё нажать, покажет сердечко.

- Это не настоящий юнит: не сохраняется, не входит в лимит юнитов, в неё нельзя попасть.
- Мультиплеер: у каждого игрока свой дрон, все видят всех дронов вместе с их фразами и сердечками. Чинит сервер. Мод нужен и на сервере, и у каждого игрока.
- Настройки → Игра: можно выключить дрона, его болтовню или починку.

## Установка
Скачай `companion-drone.jar` в [Releases](../../releases) и положи в папку `mods` игры (или «Импорт мода» в самой игре).

## Сборка
JDK 17+, без Gradle: `build.ps1` собирает мод с игровым `jre/desktop.jar` (пути в начале скрипта). `.\build.ps1 -Install` ещё и копирует jar в папку модов.
