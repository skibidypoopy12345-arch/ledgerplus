# Ledger+ (Fabric 1.21.11, client-side)

Tracks every /pay you send and receive: sessions, per-player stats, rakeback, live HUD,
a themeable tabbed UI, optional "confirm large /pay" guard.

## Build
1. Easiest: generate a template at https://fabricmc.net/develop (1.21.11, Yarn, Fabric API),
   unzip it, then copy this project's `src/` folder, `build.gradle`, and `gradle.properties` over it
   (or copy the `gradle/` wrapper + `gradlew` files from the template into this folder).
2. If Gradle says a version doesn't exist, update `gradle.properties` with the numbers shown on the Develop page.
3. `./gradlew build` -> jar in `build/libs/` (use the one WITHOUT `-sources`). Drop it in `mods/` with Fabric API.

## Use
- Open UI: Right Shift (rebind under Controls -> Ledger+) or `/ledger`
- `/ledger start | end | reload | confirm | cancel`
- Config: `config/ledgerplus.json` (chat regexes, rakeback overrides, ignored players)
- Data: `config/ledgerplus-data.json`; CSV export button in History

## No local setup? Build the .jar on GitHub
Push this folder to a new GitHub repo. The Actions tab runs "Build mod jar" automatically;
download the `ledgerplus-jar` artifact (use the jar without `-sources`).
