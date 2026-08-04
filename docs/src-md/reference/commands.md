# Commands

All commands are operator-only. Exact argument syntax is finalized in the implementation spec.

## Area commands

Run at each corner. `pos1` is the high corner (max X, Y, Z). `pos2` is the low corner (min X, Y, Z). The area is the cuboid between them.

| Command | Effect |
| --- | --- |
| `/regions setlobby pos1` | Mark lobby high corner |
| `/regions setlobby pos2` | Mark lobby low corner |
| `/regions unsetlobby` | Clear the registered lobby bounds |
| `/regions setbiomebounds <biome> pos1` | Mark biome high corner |
| `/regions setbiomebounds <biome> pos2` | Mark biome low corner |
| `/regions unsetbiomebounds <biome>` | Clear a biome's bounds |

## Protected area commands

Named rectangular zones where block-mining is blocked for every block **except registered ore deposits**. Deposits inside a protected area continue to function normally — mined, cobbled, and regenerated on schedule. See [Protected Areas](protected-areas.md).

| Command | Effect |
| --- | --- |
| `/regions addprotectedarea <name> pos1` | Mark high corner of a new named protected area |
| `/regions addprotectedarea <name> pos2` | Mark low corner; area is created once both corners are set |
| `/regions removeprotectedarea <name>` | Delete a named protected area |
| `/regions listprotectedareas` | Print all registered protected areas with their bounds |

## Trial chamber commands

A single fixed arena at the center of the map. Block-mining is blocked everywhere inside the bounds. See [Trial Chamber](../gameplay/trial-chamber.md).

| Command | Effect |
| --- | --- |
| `/regions setchamberbounds pos1` | Mark trial chamber high corner |
| `/regions setchamberbounds pos2` | Mark trial chamber low corner |
| `/regions unsetchamberbounds` | Clear the trial chamber bounds |
| `/regions addchamberspawn` | Register the current position as a mob spawn point for trial waves. Returns the new index. |
| `/regions removechamberspawn <index>` | Remove a mob spawn point by index |
| `/regions listchamberspawns` | Print all registered chamber mob spawn points with their positions |

## Target commands

Click-to-register commands: run the command, then right-click the intended block or entity. The next right-click registers the target. Removal commands also use a right-click to identify which deposit to remove. Unset and list commands act on the current registration without a click.

| Command | Effect |
| --- | --- |
| `/regions setconduit` | Register the next right-clicked block as the ocean conduit |
| `/regions unsetconduit` | Clear the registered ocean conduit |
| `/regions setfurnace` | Register the next right-clicked block as the nether furnace |
| `/regions unsetfurnace` | Clear the registered nether furnace |
| `/regions setcomposter` | Register the next right-clicked block as the plains composter |
| `/regions unsetcomposter` | Clear the registered plains composter |
| `/regions settrader <biome>` | Register the next right-clicked entity as a biome's trader |
| `/regions unsettrader <biome>` | Clear a biome's registered trader |
| `/regions listtraders` | Print every biome's registered trader (entity ID and position) |
| `/regions addoredeposit <time>` | Start an ore-deposit registration session with the given regen time. The first right-clicked ore block locks the ore type for the session and is registered as a deposit; each further right-click on a matching ore block registers another deposit. Registered blocks display as emerald blocks for the duration of the session as a visual marker. End the session with `/regions done` to revert the markers to their original ore appearance. |
| `/regions removeoredeposit` | Remove the next right-clicked ore deposit |
| `/regions listoredeposits` | Print every registered ore deposit with position, ore type, and regen time |
| `/regions addblockdeposit <time>` | Same flow as `/regions addoredeposit`, but accepts any block type. The first right-click locks the block type for the session; each further click on a matching block registers another deposit. Unlike ore deposits, a mined block deposit breaks fully (drops its item, leaves air) — no filler block — then regenerates back to the registered block when the timer elapses. |
| `/regions removeblockdeposit` | Remove the next right-clicked block deposit |
| `/regions listblockdeposits` | Print every registered block deposit with position, block type, and regen time |
| `/regions addmobdeposit <mob> <time>` | Register the next right-clicked block as a mob spawn point with spawn interval |
| `/regions removemobdeposit` | Remove the next right-clicked mob deposit |
| `/regions listmobdeposits` | Print every registered mob deposit with position, mob type, and spawn interval |
| `/regions done` | End the current registration session (e.g. `/regions addoredeposit`, `/regions addblockdeposit`). Reverts any visual markers and clears the pending click target. |

## Position commands

Run at the target position. Removal, unset, and list commands act on existing registrations and don't depend on the sender's location.

| Command | Effect |
| --- | --- |
| `/regions setlobbyspawn` | Mark current position as the lobby spawn point — where joiners teleport when a `[Nations]` sign trips |
| `/regions unsetlobbyspawn` | Clear the registered lobby spawn point |
| `/regions spawntrader <biome>` | Spawn the biome-appropriate trader entity at the sender's position, register it as that biome's trader, and apply trader protection. Plains, ocean, and mountain spawn a Villager; nether spawns a Piglin. Replaces any existing trader for the biome. |
| `/regions setspawn <biome>` | Mark current position as a team spawn. Returns the new index. |
| `/regions removespawn <biome> <index>` | Remove a team spawn by index |
| `/regions listspawns` | Print every team's registered spawn points with positions and indices |
| `/regions setwardenspawn` | Mark current position as the mountain mass-event Warden spawn |
| `/regions unsetwardenspawn` | Clear the registered Warden spawn |
| `/regions setguardianspawn <index>` | Mark current position as an ocean mass-event Elder Guardian spawn slot. Any number of slots may be registered. |
| `/regions removeguardianspawn <index>` | Remove a Guardian spawn slot by index |
| `/regions listguardianspawns` | Print every registered Guardian spawn slot with position and index |
| `/regions setwitherspawn` | Mark current position as the nether mass-event Wither spawn |
| `/regions unsetwitherspawn` | Clear the registered Wither spawn |

## Match control

| Command | Effect |
| --- | --- |
| `/regions start` | Start a match manually |
| `/regions abort` | End the current match |
| `/regions skip-season` | Force next phase change |
| `/regions status` | Print current match state |
| `/regions version` | Print mod version |
| `/regions reset all` | Clear every registered area, target, position, deposit, and spawn. Prompts for confirmation. Use when prepping a server that already has a configured map for a fresh setup. |

## Debug commands

Grouped under `/regions debug <...>`. Operator-only and intended for development, map testing, and live troubleshooting. Avoid using in scored matches.

### Visualization

Toggles particle overlays for registered zones and objects. Toggling a second time turns the overlay off.

| Command | Effect |
| --- | --- |
| `/regions debug show protected` | Outline every protected area in red particles |
| `/regions debug show deposits` | Highlight ore and mob deposits, with regen timer above each |
| `/regions debug show spawns` | Mark all team spawn points |
| `/regions debug show biomes` | Outline biome bounds in team colors |
| `/regions debug show lifelines` | Mark the Conduit, Furnace, and Composter with their current state |
| `/regions debug show lobby` | Outline the lobby area |
| `/regions debug show all` | Enable every overlay at once |
| `/regions debug show off` | Turn every overlay off |

### Match state

| Command | Effect |
| --- | --- |
| `/regions debug season <cold\|warm>` | Force the current season to cold or warm without changing the timer |
| `/regions debug pvp <on\|off\|permanent>` | Force the PVP flag state |
| `/regions debug seasontimer <seconds>` | Set the remaining seconds on the current season timer |
| `/regions debug bloodtribute <satisfy\|reset\|fail>` | Satisfy, reset, or fail the current Mountain Blood Tribute |
| `/regions debug state` | Dump full match state (season, timers, lifelines, player counts) to the command sender |
| `/regions debug reload` | Re-read configuration files without restarting the server |

### Build mode

| Command | Effect |
| --- | --- |
| `/regions debug buildmode <on\|off>` | Disable all block protections (protected areas, deposits, chamber, lifelines) for the sender. Lets an operator edit the map after registration. Other players are unaffected. |

### Mass events

| Command | Effect |
| --- | --- |
| `/regions debug massevent fire <biome>` | Force-trigger the biome's mass event regardless of permanent-loss count |
| `/regions debug massevent reset <biome>` | Reset a biome's mass event flag so it can fire again |

### Trial chamber

| Command | Effect |
| --- | --- |
| `/regions debug chamber wave start` | Force-start a trial wave immediately |
| `/regions debug chamber wave end` | End the active wave, awarding loot to the current leader (or splitting on tie) |
| `/regions debug chamber waves <on\|off>` | Suspend or resume the 10-minute wave cadence |

### Map repair

| Command | Effect |
| --- | --- |
| `/regions debug map repair` | Run the match-end map repair now without ending the match. Restores every block in the match dimension to the snapshot taken at `/regions start`. See [Match end and map repair](../gameplay/timeline.md#match-end-and-map-repair). |

### Lifelines

| Command | Effect |
| --- | --- |
| `/regions debug conduit hp <0-200>` | Set Ocean Conduit HP |
| `/regions debug furnace <lit\|unlit>` | Force Nether Furnace state |
| `/regions debug furnace fuel <ticks>` | Set remaining fuel ticks in the Nether Furnace |
| `/regions debug quota set <amount>` | Set the Plains quota's paid-so-far emerald count |
| `/regions debug quota info` | Print the Plains quota's paid/owed amounts, season, and living Plains member count |

### Deposits

| Command | Effect |
| --- | --- |
| `/regions debug deposits regen` | Instantly regenerate every ore deposit |
| `/regions debug deposits reset` | Reset every ore and mob deposit to its starting state and clear cooldowns |
| `/regions debug deposit info` | Print info on the next right-clicked deposit block (type, cooldown, regen time) |

### Players

| Command | Effect |
| --- | --- |
| `/regions debug team <player> <biome>` | Force-assign a player to a biome team |
| `/regions debug class <player> <class>` | Force a player's class (grants kit, clears previous class items) |
| `/regions debug lives <player> <count>` | Set a player's remaining lives |
| `/regions debug cooldowns <player> clear` | Clear every class-ability cooldown on a player |
| `/regions debug kit <player>` | Re-grant the player's current class kit |
| `/regions debug tp <player> <biome\|lobby>` | Teleport a player to a biome spawn or the lobby |
| `/regions debug spectate <player>` | Force a player into spectator state |

### Entities

| Command | Effect |
| --- | --- |
| `/regions debug trader respawn <biome>` | Respawn the biome trader at its registered location |
| `/regions debug mob kill <radius>` | Kill every mod-spawned mob in radius (does not touch vanilla mobs) |
