# Vystorm Rails

## Freight (1.16.0)

A rail sign `[Rails]` / `freight` / `load|unload|both` / `3` makes chest and hopper minecarts stop and move items to or from the container next to the rail.

## Scheduled train lines (1.13.0)

Create a repeating line over existing named station signs:

```text
/rails line create commuter 300 Central@0|Mine@90|Harbor@210
/rails line show commuter
/rails line assign commuter
/rails departures Central
/rails line stop
/rails line delete commuter
```

`assign` and `stop` apply to the leading cart you ride or target, subject to existing cart ownership and `vystormrails.train`. Only the line owner or `vystormrails.admin` can delete a definition. `/rails line list` lists the world's lines. Station names may contain spaces; `|` separates stops and the final `@` introduces an offset in seconds.

Each line has 2–16 distinct ordered stations and a repeating period of 30–86,400 seconds. Offsets must increase and remain within the period. Departure slots are anchored to the Unix clock: a late cart waits for the next available slot, respects the station sign's minimum dwell, then targets the next stop and loops. `/rails departures [station]` is a chat station timetable showing scheduled departures, not a live arrival estimate. It does not spawn trains, reserve tracks or guarantee that disconnected tracks form a route; the existing sign/router network still handles movement.

Definitions persist atomically in `lines.yml` (16 per owner, 128 total); the world's UUID scopes its names. Cart bindings and station deadlines persist in entity data and are restored when entities load. Missing/deleted stations or invalid saved lines stop and unbind the cart. Unloaded tracks remain unloaded. Corrupt timetable files are preserved and disable only the new timetable service.

**Minecarts that are worth building rails for.** Faster tracks, stations, destinations, switches, trains and auto-routing – with blocks and signs, no extra plugins besides Vystorm Core.

- **Speed blocks:** the block under the rail sets the top speed – copper 58 km/h, gold 86 km/h, diamond 108 km/h, soul sand slows down. All in `config.yml`.
- **Safe at speed:** carts brake before curves, slopes and track ends so they don't derail.
- **Cart blocks** (all switchable in `config.yml`):
  - **Ice** keeps carts sliding off the track – steer with A/D, brake with S. **Honey** catches them.
  - **Jump:** the last rail on a slime block is a ramp – the faster, the further.
  - **Bounce:** a slime block behind the track end sends carts back.
  - **Shift:** a slime block beside the rail moves the cart onto the parallel track on the other side.
  - **Spin:** a rail on magma spins the cart once and sends it back.
  - **Elevator:** a scaffolding column at the end of the track lifts the cart to the track at its top (or lowers it).
  - **Lava boat:** carts swim on lava, W/A/S/D like a boat – nobody burns.
  - **Signal:** a rail on a redstone lamp holds carts while the lamp is powered or another cart is on the next 8 rails ahead – carts on one track no longer run into each other.
- **Signs** (line 1 `[rails]`, two blocks below the rail or on the side of the block carrying it):
  - `station` – line 3 seconds (default 5), line 4 departure direction and name: `e Farm`
  - `speed` – line 3 `0.8` or `60km/h`, `reset` for the default
  - `dest` – line 3 destination for every cart passing
  - `switch` – lines 3–4 `Farm:e, Mine:n`, `*:s` for everyone else
  - `collect` – empty carts go back to their owner
- **Destinations:** `/rails dest Farm` and every switch on the way sends your cart there.
- **Trains:** right-click a cart with a chain, then the next cart – coupled. Up to 8 carts run as one train: the front cart drives (powered rails, speed blocks, stations, signals, braking before curves), the others follow at a fixed distance and are pushed along when you push the last one. Sneak + right-click with the chain (or `/rails uncouple`) uncouples. Works with every cart type – a chest-cart freight train or a passenger train for your friends. Couplings are saved on the carts and survive restarts.
- **Auto-routing:** with a destination set, your cart finds the way by itself – every junction without a switch sign (a plain rail with three or four rails leading to it) is set to the shortest way to your station as the cart arrives. No sign building needed; `switch` signs still win where you placed them. The route follows the real track as far as it is loaded and heads the right way beyond.
- **Travel to a player:** sneak + right-click a player (Vystorm Core player menu) → *Travel to …* makes the station closest to them your destination.
- **Network map:** `/rails map` shows rails, stations and you as a chest menu – zoom, scroll, click a station to travel there. Works without a resource pack.
- **Riding:** speedometer, destination and distance to it in the action bar, a title on arrival at the destination station, mobs are pushed off the track, ridden carts pass through players, empty carts no longer pick up mobs.
- **No clutter:** leaving or breaking your cart puts it straight back into your inventory.
- **Vanilla stays vanilla:** powered rails launch, activator rails eject, hoppers load – Rails does not duplicate them.
- Texts in seven languages: English, German, French, Italian, Brazilian Portuguese, Russian and Simplified Chinese (`lang/*.yml`). `config.yml` is editable in game with `/vadmin config`.
- Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2. Plugin bytecode requires Java 21+; use the Java version required by your server (Java 25 for current Paper). Needs [Vystorm Core](https://www.spigotmc.org/resources/139848/) 0.65.0+ (free).

## Feature switches (1.21.0)

Every feature has its own on/off switch in `config.yml`, listed with a one-line description in all seven languages in `/vadmin features`. Changes apply at once; only `tutorial.enabled` needs a restart. Core adds new switches to an existing `config.yml` with their comments; a missing switch counts as on.

- New in 1.21.0: `speed-blocks.enabled`, one switch per cart block (`cart-blocks.slide|jump|bounce|shift|spin|elevator|float|signal.enabled`; the word `none` and empty lists keep their meaning), `signs.enabled`, `destinations.enabled`, `map.enabled`, `stations.enabled`, `favorites.enabled`, `lines.enabled`, `info.enabled` (`/rails info`, `/rails blocks`), `tutorial.enabled`.
- Already there: `cart-blocks.steer.enabled`, `trains.enabled`, `routing.enabled`, `fares.enabled`, `freight.enabled` and the top-level `speedometer`.
- Older keys keep their names and now also show in `/vadmin features`: `speed.slow-before-curves`, `collisions.push-mobs`, `collisions.pass-through-players`, `collisions.empty-carts-pick-up-mobs`, `pickup.on-exit`, `pickup.on-break`, `arrival.title`, `arrival.clear-destination`, `routing.player-menu`, `cart-blocks.spin.reverse`, `cart-blocks.float.visual-fire`.

A command of a switched-off feature answers "This feature is switched off on this server."; it disappears from tab completion and help at once and comes back when the switch is on again. Saved stations, lines, favorites, fares and destinations stay.

## Placeholders (1.21.0)

With Vystorm Core 0.65.0 the plugin registers the namespace `rails`: `destination`, `speed_kmh`, `max_kmh`, `destination_distance`, `riding`, `ticket_remaining`, `has_ticket`, `favorites_count`, `stations_count`, `nearest_station`, `fare` (with a station name as argument). Each works as `%vystorm_rails_<key>%` (also through PlaceholderAPI) and as `{rails:<key>}` in Vystorm texts; `/vadmin placeholders rails` lists them. Examples: `%vystorm_rails_speed_kmh%` and `%vystorm_rails_fare_central%`. A value of a switched-off feature is empty. All values answer on the server thread only.

## Commands and permissions

| | |
|---|---|
| `/rails dest [NAME\|clear]` | your destination |
| `/rails map` | network map, click a station to make it your destination |
| `/rails stations [SEARCH]` | the ten nearest matching known named stations in your world, with horizontal straight-line distance and XYZ coordinates (`vystormrails.use`); partial name search ignores case |
| `/rails favorite NAME` | save a known named station as a personal favorite (`vystormrails.use`) |
| `/rails favorites` | favorites menu: left-click to set destination, right-click to remove (`vystormrails.use`) |
| `/rails info` | speed, top speed, destination, owner and train length of your cart |
| `/rails blocks` | which blocks under and beside the rails do what on this server |
| `/rails uncouple` | uncouple the cart you ride or look at from its train (`vystormrails.train`) |
| `/rails reload` | reload `config.yml` (`vystormrails.admin`, op) |
| `vystormrails.use` | ride with the improvements (default: everyone) |
| `vystormrails.sign` | create `[rails]` signs (op) |
| `vystormrails.train` | couple carts into trains with a chain, uncouple them (default: everyone) |

The station directory also includes known stations in unloaded chunks; it uses the learned network, without scanning undiscovered tracks. German: `/rails bahnhoefe`, Chinese: `/rails 车站`. Narrow the results with a search (spaces allowed), then use `/rails dest NAME` to travel.

Favorite icons also show horizontal straight-line distance and coordinates for the nearest known station of that name, sampled when the menu opens or refreshes. This uses the learned network without loading chunks; it is not rail-route distance.

Favorites are saved in player data, separately per world, across reconnects and restarts. Names with spaces work; duplicates ignore case. Unknown or renamed stations cannot be selected, but stay removable. German: `/rails favorit` and `/rails favoriten`; Chinese: `/rails 收藏` and `/rails 收藏夹`. Selecting a favorite sets the normal rail destination; it does not teleport.

`favorites.max-per-world` defaults to 18 (0–54) and is added to existing configs. Set 0 to prevent additions. Lowering the limit keeps existing favorites usable and removable.

## Configuration (new in 1.4.0)

```yaml
trains:
  enabled: true
  coupler: "*CHAIN"       # item to couple with; *NAME = every item ending in NAME (iron and copper chains)
  spacing: 1.6            # blocks from cart centre to cart centre
  couple-distance: 3      # how close the two carts must be when coupling
  break-distance: 6       # a coupling breaks beyond this (derailed carts)
  max-length: 8
routing:
  enabled: true           # carts with a destination set junctions without switch sign themselves
  search-limit: 4000      # rails searched per junction at most
  player-menu: true       # "Travel to" entry in the Vystorm Core player menu
```

Trains ignore the jump, bounce, shift, spin, elevator and lava-boat blocks – those stay single-cart fun.

## Building

```bash
./gradlew build -PvystormCoreJar=/path/to/Vystorm_Core.jar
```

The jar is `build/libs/VystormRails-<version>.jar`.

## Compatibility

On Paper 1.20.6, W/A/S/D steering on ice and lava is unavailable because the player input API is missing. Ice sliding, lava floating and heat protection still work. Lava carts retain vanilla flames; `float.visual-fire: false` requires the newer TriState API. Each limitation is logged once on startup. Materials absent on the server are skipped. All other features remain available, including chest menus, stations, trains, routing and favorites.

Build with JDK 25; all source sets target Java 21. The `modern` source set contains newer API adapters and is included in the same jar. `check` runs `compat1206` against the local Paper 1.20.6 libraries (default: `<home>/source/tools/smoke-1206/libraries`; override with `-Ppaper1206Libraries=/path/to/libraries`). These libraries are also required by the isolated compatibility test. No server is started.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.19.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.19.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.19.0.txt](LICENSE-MIT-until-1.19.0.txt)) and stay available under those terms.
