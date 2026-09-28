# Spawn Protection

A protected cuboid around spawn. Non-ops cannot break or place inside it, ops pass through
untouched, and an optional soft border pushes players back if they try to leave through the sides.

Select the region in-game with a wand, rather than typing coordinates:

```
/spawnprotect wand            get the selection tool (a wooden axe by default)
/spawnprotect set             save the current selection as the region
/spawnprotect status          region, toggles, override count
/spawnprotect toggle          protection on or off
/spawnprotect clear           forget the region
/spawnprotect damage on|off   player damage inside the region
/spawnprotect border on|off   the containment wall
/spawnprotect blocks list     per-block overrides
/spawnprotect blocks clear
/spawnprotect reload
```

Left-click a block for one corner, right-click for the other. Selections live in memory per player -
only the saved region persists.

## Right-click still works

Breaking and placing are blocked; interaction is not. Doors, buttons, chairs from a sit mod, any
right-click use - all still work for everyone. Protecting spawn should not mean nobody can open a
door there.

## Per-block overrides

Two escape hatches, keyed by `dimension;x,y,z`:

| Value | Effect |
| --- | --- |
| `ALLOW` | fully editable, even inside the region |
| `DENY` | fully protected, even outside it - break, place and interact all blocked |

Overrides win over the region, so a single chest can stay usable inside a locked area, or one block
outside it can be sealed without protecting the whole chunk.

## The border

Off by default. When on, a non-op crossing the region's horizontal edge gets shoved back in with a
small upward pop, a dragon-flap sound and a puff at their feet, and particle rows draw along the
nearest wall when someone is close enough to see them.

Y is unbounded, exactly like a vanilla world border - this is a wall, not a box, so you can still
dig down or fly up. Particles only render near players and the shove has a per-player cooldown, so
leaning on the wall does not machine-gun it.

## Config (`config/spawn-protection.json`)

```json
{
  "enabled": true,
  "wandItem": "minecraft:wooden_axe",
  "playerDamage": true,
  "region": { "dimension": "minecraft:overworld", "minX": 0, "minY": 0, "minZ": 0, "maxX": 0, "maxY": 0, "maxZ": 0 },
  "blockOverrides": {},
  "borderEnabled": false,
  "borderPush": 0.6,
  "borderPushY": 0.35,
  "borderWall": true,
  "borderWallRange": 6,
  "borderAffectsOps": false
}
```

`playerDamage` false makes the region a safe zone. `borderWallRange` is how many blocks away a
player has to be before the wall particles draw.

## Server-side only - players install nothing

## Requirements

Drop these into your **server's** `mods/` folder:

| Mod | Version |
| --- | --- |
| `spawn-protection-0.1.0.jar` | this mod |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.155.2+26.2` (or compatible) |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `1.13.12+kotlin.2.4.0` (or compatible) |

Minecraft **26.2**, Fabric Loader **0.19.3+**, **Java 25**.

## Limits

One region, one dimension. Ops bypass everything except a `DENY` override, so a safe zone does not
protect an op from their own mistakes. Explosions and other non-player block changes are not
covered.
