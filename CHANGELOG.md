## 1.20.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`).
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.19.0 — 2026-11-16

- Tab completion for `/rails line` (actions, then the names of your lines) and for `/rails departures` (station names).

## 1.18.0 — 2026-11-15

- Tutorial pages show build animations (GIFs) next to the text when opened as a Vystorm screen (Core 0.59.0).

- Rebuilt the rail network and favorite stations as Core Panels, with station search, sorting, paging and selection, map zoom and panning, and confirmed favorite removal. Both original chest menus remain as fallbacks when Panels are unavailable. No item-transfer menus were found.

## 1.17.1 — 2026-11-14

- Fixed: on Paper 1.20.6 Rails listened to a deprecated event, which logged "Server performance will be affected" at every start and made the server fire it for every removed entity. There it now uses Paper's own removal event. Newer servers and Spigot behave as before.

## 1.17.0 — 2026-11-12

- Tickets and fares (needs a Vault economy): admins set a fare per station with `/rails fare <station> <amount|off>` (`/rails fare` lists them). Every rider whose cart leaves that station pays it; who cannot pay is set down at the station. A day ticket (`/rails ticket buy`, 50 by default) makes all rides free for 24 hours; `/rails ticket` shows how long it is still valid. Optional payee for the fares; permission `vystormrails.fare.free` rides free. Settings under `fares`, also in the first-run setup.

## 1.16.0 — 2026-11-11

- Freight: a rail sign with `freight` on line 2 (`load`, `unload` or `both` on line 3, stop seconds on line 4) makes chest and hopper minecarts stop and swap items with the chest, barrel or hopper next to the rail – up to 256 items per stop (`freight.*`). Build automatic supply lines between farms and storage.

## 1.15.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- /rails without arguments opens the network map; the rail network is in the main menu (/vmenu → World).

## 1.14.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.13.1 — 2026-11-08

- Protect network saves from failed writes, delayed autosaves and shutdown data loss; reject damaged network and timetable files.
- Handle renamed or duplicate stations and long destination names correctly, preserve scheduled destinations when boarding, and show accurate arrival messages and departure waits.
- Restore cart physics on unload and disable; respect disabled trains, stop partially unloaded trains and repair circular saved couplings.
- Keep nearby unloaded chunks unloaded, recheck map selections and close plugin menus on disable.
- Reject non-finite physics settings and invalid saved departures; correct timetable help and permission guidance in all seven languages.

## 1.13.0 — 2026-11-07

- Add persistent scheduled train lines with ordered stops, recurring departures and station timetables; preserve existing cart ownership and route behavior.
- Include all seven bundled languages and targeted persistence, ownership and cancellation tests.
- Preserve Bukkit/Spigot fallback paths and existing Paper behavior.

## 1.12.1 - Isolate sign caches by world

- Fix cached signs at identical coordinates in different worlds sharing actions or destinations. Empty blocks are isolated too.
- Keep sign editing, placement and removal invalidation local to the affected world.
- Add regression coverage for distinct destinations, cached empty blocks and world-local invalidation.
- Validation: full build, older API compatibility check and 71 automated tests passed with no failures or skips.

## 1.12.0 - Spigot and CraftBukkit compatibility

- Require Vystorm Core 0.51.0 or newer.
- Retain passengers during Bukkit minecart teleports, use portable sign text and minecart item types, and keep existing Paper adapters guarded.
- Preserve plain-text parsing of colored saved signs after cache refresh on both Bukkit and Paper.
- Validation: 68 automated checks (0 skipped), plus combined startup, listener registration and Core diagnostics/help on official Spigot26.3 and CraftBukkit26.3.
- Platform detail: The older-server visual-fire fallback retains vanilla flames; heat protection remains active. Native Paper TriState visual-fire overrides remain optional.

## 1.11.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.10.0 (2026-11-05)

- Favorite station icons now show horizontal straight-line distance and XYZ coordinates for the nearest known station of that name when the menu opens or refreshes. Uses the learned network without loading chunks; missing stations remain removable. English, German and Simplified Chinese labels.

# Changelog

## 1.9.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode, with newer API adapters packaged in the same jar and selected by feature detection. Requires Vystorm Core 0.48.0+ for compatibility helpers.
- On 1.20.6, W/A/S/D cart controls are unavailable (no player input API); ice sliding, lava floating and heat protection remain active. Vanilla lava flames remain visible because the older fire API cannot suppress them. Missing materials are skipped. Newer servers keep existing behavior.
- Added offline `compat1206` to `check` and tests for adapter selection, fallback behavior and loading against the actual 1.20.6 API.

## 1.8.0 (2026-11-02)

- Personal station favorites: `/rails favorite NAME` saves a known station; `/rails favorites` opens a menu to choose a destination with left-click or remove it with right-click. Favorites persist in player data across restarts, separately for each world. Missing stations remain removable; no chunks are loaded. Uses `vystormrails.use`, with English, German and Simplified Chinese commands and messages.
- Config `favorites.max-per-world` (default 18, range 0–54) is added to existing configs. Lowering the limit preserves existing favorites; 0 prevents new entries.

## 1.7.0 (2026-11-02)

- Station directory: `/rails stations [SEARCH]` lists the ten nearest matching known named stations in the current world, with horizontal straight-line distance and XYZ coordinates. Search accepts partial names and spaces, ignoring case; the total match count helps narrow large networks. Uses `vystormrails.use`, with German and Simplified Chinese commands and messages. No new configuration required.

## 1.6.0 (unreleased)

- Arrival: when a cart stops at the station that is its destination, the riders get a title "you have reached your destination" with a chime, and the destination of the cart, its train and its riders is cleared, so the next ride does not head back there by itself. Config `arrival.title` and `arrival.clear-destination` (both on).
- The speedometer now shows how far away the destination is (straight line to the nearest station of that name, e.g. `➜ Farm 420 m`).

## 1.5.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation
- `/rails` now runs through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/rails ziel`, `/rails karte`, `/rails 目的地` work next to the English names for everyone), a clickable `/rails help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/schienen` (German) and `/铁轨` (Chinese) from the language files (`commands.rails.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/rails`, `/vrails` and `/cart` stay. The command is no longer declared in plugin.yml.

## 1.4.0 (2026-10-30)

- Trains: right-click a cart with a chain (iron or copper), then a second cart within 3 blocks, and they are coupled; up to 8 carts per train, any cart type. The front cart drives – powered rails, speed blocks, stations, signals and braking before curves work as before – and the others follow 1.6 blocks apart; pushing the last cart pushes the whole train, and it can drive in both directions. Sneak + right-click with the chain or `/rails uncouple` uncouples; a coupling breaks when carts end up more than 6 blocks apart. Signals ignore the own train, coupled carts are not picked up on exit and not collected by `collect` signs; jump, bounce, shift, spin, elevator and lava-boat blocks leave trains alone. Couplings are stored on the carts and survive restarts. New permission `vystormrails.train` (everyone), config `trains.*`, `/rails info` shows the train length.
- Auto-routing: a cart with a destination sets every junction without a switch sign by itself to the shortest way to its station – no switch signs needed any more. A junction is a plain rail with three or four rails leading to it (parallel tracks are not mistaken for one); `switch` signs keep priority. The search follows the loaded track and estimates the rest in a straight line, results are cached until rails change. Coupled trains route by the destination of any cart or rider in the train. New in the Vystorm Core player menu (Core 0.43.0+): sneak + right-click a player → *Travel to …* makes the station closest to them your destination. Config `routing.enabled`, `routing.search-limit`, `routing.player-menu`.

Fixed:

- Couplings heal themselves: a cart that is broken or removed while its partner's chunk is not loaded no longer leaves the partner coupled to nothing forever. Removed carts are uncoupled at once (also by other plugins); a partner that loads later drops the link right away, or – after a restart – once the chunks around it are loaded and the other cart still does not show up for 2 seconds.
- An unreadable network map file (`network/<world>.bin`) was only logged as a warning, the world started with an empty map and the next save replaced the file. The file is now moved aside as `<world>.bin.broken-<date-time>`, the console explains it, and that world's map is not saved until the next restart.

## 1.3.1 (2026-10-29)

- Vystorm Core now checks SpigotMC for new Rails versions (resource 140076).

## 1.3.0 (2026-10-29)

- New cart block – signal: a rail on a redstone lamp stops every cart (also chest and hopper carts) while the lamp is powered by redstone, or while another cart is on the next 8 rails ahead (follows curves and slopes). The cart leaves on its own once the way is clear; riders see why they wait in the action bar. Config `cart-blocks.signal.block` and `cart-blocks.signal.clear-ahead` (0 = redstone only, `block: none` switches it off).
- `/rails blocks` lists which blocks under and beside the rails do what on this server, read from the current `config.yml` (speed blocks with their km/h, ice, honey, jump, bounce, shift, spin, elevator, signal, lava boat).

## 1.2.0 (2026-10-28)

- Cart blocks, all under `cart-blocks` in `config.yml`:
  - Ice, packed ice and blue ice keep a cart sliding after it leaves the track; the rider steers with A/D, brakes with S and pushes a little with W. Honey catches a cart (soft landing).
  - Jump: the last rail on a slime block throws the cart into the air – the faster, the higher and further. No braking before such a track end.
  - Bounce: a slime block right behind the end of the track sends the cart back.
  - Shift: a slime block beside the rail moves the cart onto the parallel track on the other side.
  - Spin: a rail on magma spins the cart once and sends it back.
  - Elevator: a scaffolding column at the end of the track lifts or lowers the cart to the track at its other end.
  - Lava boat: carts swim on lava like a boat (W/A/S/D); rider and cart do not burn. Flames on the cart can be switched off with `float.visual-fire: false`.
- Stations keep the rider in the cart while centring it.

## 1.1.0 (2026-10-28)

- Network map: `/rails map` opens a chest menu of the rail network around you – terrain as coloured glass, rails, stations and you, north up; zoom from 2 to 64 blocks per field, scroll with the arrows. Click a station to make it your destination. No resource pack needed.
- Rails learns the network from rails placed by players and from carts driving over existing track; stations come from station signs. Saved per world in `network/`.
- `/rails dest` tab-completes the station names.

## 1.0.0 (2026-10-28)

- First release: top speed per block under the rail (copper, gold, diamond faster, soul sand slower) and `speed` signs, braking before curves, slopes and track ends.
- `[rails]` signs: `station` (wait, announce, depart), `dest`, `switch` by destination, `collect`; `/rails dest NAME` routes your cart through switches.
- Speedometer in the action bar, mobs pushed off the track, ridden carts pass through players, empty carts no longer pick up mobs, your cart back into the inventory on exit or break.
- Nothing vanilla already does: launching stays with powered rails, ejecting with activator rails, loading with hoppers.
- Built on Vystorm Core: language files, in-game config editor, task groups.
