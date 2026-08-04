# Boss Bars

The mod repurposes vanilla Minecraft boss bars — the bar UI normally reserved for boss fights — to show live match state. Every player carries two: the global timer and their own team's lifeline bar. Spectators carry the timer plus all four lifeline bars.

## Visibility

| Viewer | Bars shown |
| --- | --- |
| Ocean player | Timer, Conduit |
| Nether player | Timer, Furnace |
| Mountain player | Timer, Blood Tribute (cold seasons only) |
| Plains player | Timer, Quota |
| Spectator | Timer, Conduit, Furnace, Blood Tribute (cold seasons only), Quota |

## Timer

Shown to everyone. Text is the current phase, a 1-based season number, and time remaining in the phase: `Warm <n> — <mm:ss>` / `Cold <n> — <mm:ss>`. Season numbers follow [Timeline](../gameplay/timeline.md) — Warm 1, Cold 1, Warm 2, Cold 2, then alternating indefinitely.

The bar starts full at the beginning of each phase and drains to empty by the end. Color is yellow during Warm phases, blue during Cold phases.

Once total match time passes 60:00, PVP goes permanent (see [Timeline](../gameplay/timeline.md)) and the suffix `  ·  PvP permanent` is appended to the timer text for the rest of the match.

## Ocean: Conduit

Notched bar — 20 notches across the conduit's 200 max HP, so each notch is 10 HP.

| State | Text | Color |
| --- | --- | --- |
| Alive | `Conduit — <hp> / 200` | Blue |
| Destroyed (0 HP) | `CONDUIT DESTROYED — next death is final` | Red |

Progress tracks HP directly and drains with every enemy break. At 0 HP, the next death for any ocean player is final regardless of remaining lives — see [Ocean](../teams/ocean.md).

## Nether: Furnace

| State | Text | Color |
| --- | --- | --- |
| Lit | `Furnace — <mm:ss> remaining`, with `  (2x burn)` appended during cold season | Yellow |
| Out | `FURNACE OUT — 1 heart / 30s` | Red |

The bar is normalized against a fixed window rather than against whatever fuel item is currently loaded: it reads full whenever more burn time remains than the window, then drains across that final window down to empty exactly as the furnace goes out. Window length is `furnaceBarWindowTicks` in the config, default `6000` ticks (5 minutes).

This is deliberate. Fuel items burn anywhere from 1600 to 20000 ticks depending on what's fed in, so there's no natural "full" value to measure a fuel item against. A fixed window turns the bar into an urgency gauge — how close is the furnace to going out — instead of a fuel gauge, which would need to know what was loaded to mean anything.

During cold season the furnace burns fuel twice as fast (see [Cold Season](../gameplay/cold-season.md)), so the bar visibly drains at double rate.

## Mountain: Blood Tribute

Hidden entirely during Warm phases. The bar appears the moment cold season starts.

| State | Text | Color |
| --- | --- | --- |
| Unsatisfied | `Blood Tribute — <mm:ss> left` | Red |
| Satisfied | `Blood Tribute paid` | Green, full |

Counts down the same way the Timer does, draining across the cold phase, until satisfied — at which point it jumps to full and turns green.

The bar turning green is the only immediate confirmation that the tribute landed — satisfying it only logs server-side, with no chat message at that instant. A chat confirmation does follow, but not until the end of cold season, when a satisfied tribute broadcasts `Blood Tribute satisfied — no penalty.` to the mountain team. Until then, the bar is the only real-time signal. See [Mountain](../teams/mountain.md) for what satisfies the tribute.

## Plains: Quota

| State | Text | Color |
| --- | --- | --- |
| No quota active | `Quota — none active` | Green, empty |
| Unmet | `Quota — <paid> / <quota> emeralds` | Green |
| Unmet, final minute before the check | `Quota — <paid> / <quota> emeralds` | Red |
| Met | `Quota met` | Green, full |

"No quota active" covers the case where Plains has no living members. Progress fills up as emeralds are paid in — the opposite direction from the countdown bars above. Unlike the Blood Tribute bar, the Quota bar stays visible during both phases.

The red warning state applies only while the quota is active and unmet, during the Warm phase, with 60 seconds or less remaining before it ends. See [Plains](../teams/plains.md) for how the quota amount is calculated and paid.
