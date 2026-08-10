# Hosting a Game

A match needs a map with four biome regions, configured areas, and a `[Nations]` sign.

## Build the map

One world with four biome regions (plains, mountain, nether, ocean) and a lobby area. Each biome needs room for a team spawn, deposits, and a lifeline block where applicable.

## Register regions

All commands are operator-only. Three input patterns:

- **Area commands** use `pos1` and `pos2` run at opposite corners.
- **Target commands** register the next block or entity the operator right-clicks.
- **Position commands** use the operator's current position.

```text
/regions setlobby pos1
/regions setlobby pos2
/regions setbiomebounds <biome> pos1
/regions setbiomebounds <biome> pos2
/regions setspawn <biome>
/regions setconduit
/regions setfurnace
/regions setcomposter
/regions settrader <biome>
/regions addoredeposit <time>
/regions addmobdeposit <mob> <time>
```

`pos1` marks the high corner (max X, Y, Z). `pos2` marks the low corner (min X, Y, Z). The area is the cuboid between them.

For target commands, run the command then right-click the intended block or entity. The next right-click registers the target. `addoredeposit` infers the ore type from the clicked block.

All registered areas are mine and place protected.

## Place a [Nations] sign

Place a sign outside the lobby with:

```text
Row 1: [Nations]
Row 2: <min_players>
```

Row 1 must be `[Nations]`. Row 2 must be a positive integer and a multiple of 4.

Players right-click to join, or run `/nations join` from anywhere to be queued at the fullest lobby automatically. When the join count reaches the minimum, all joiners teleport to the lobby and the match begins.

Saving a valid sign registers it, so `/nations join` finds it before anyone has clicked it. Signs that predate the mod register on their first right-click.

## Admin commands during a match

| Command | Effect |
| --- | --- |
| `/regions abort` | End the match |
| `/regions skip-season` | Force next phase change |
| `/regions status` | Print match state |
