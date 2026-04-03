# LibelulaMapInspector User Manual

## The Definitive Anti-Grief Solution

**Want to know who built an offensive structure? Who placed a suspicious sign? Who broke part of a house? Who flooded a roof with water and lava? Who blew up a wall with TNT?**

LibelulaMapInspector was built for exactly that.

This is not just another block logger. It is a purpose-built anti-grief tool designed to help administrators inspect damage fast, identify the responsible player fast, and undo the damage fast, all while staying lightweight enough for real, active building servers.

There is no other tool quite like it: LibelulaMapInspector was designed around performance from day one. It keeps lookups fast, stores history efficiently, and gives admins practical inspection tools instead of forcing them through heavy, clumsy workflows.

If your server needs answers, accountability, and a clean way to investigate grief without turning moderation into a chore, this is what the plugin is for.

## What The Plugin Does

LibelulaMapInspector records player-caused world changes and lets admins inspect them later.

Right now, the plugin can track:

- Who placed a block
- Who removed a block
- Who edited a sign
- Who placed or removed water and lava with buckets
- Who ignited TNT that later destroyed blocks
- Who caused certain fluid grief patterns when optional fluid tracking is enabled

It also gives admins:

- Fast single-block inspection tools
- Area discovery tools to see which players edited a zone
- A selective undo command to remove one player's edits while preserving later edits from other players

## Who Can Use It

LibelulaMapInspector is an **admin-only** plugin.

Only players with the permission below can use `/lmi`:

`libelulamapinspector.admin`

By default, that permission is granted to server operators.

Normal players cannot use any LibelulaMapInspector command or tool.

## Quick Start

1. Install the plugin.
2. Restart the server.
3. Make sure your admin account has `libelulamapinspector.admin`.
4. Run `/lmi` to check the plugin status.
5. Run `/lmi wand` to receive the admin tools.
6. Use `/lmi discover` or the tools to inspect suspicious blocks and areas.

## Commands

### `/lmi`

Shows the current LibelulaMapInspector status.

This includes:

- Recorded event count
- Disk usage
- Disk limits
- Retention
- Default radius
- Undo speed
- Available subcommands

Use this as your first sanity check after startup.

### `/lmi wand`

Gives you the three LibelulaMapInspector admin tools, or repositions them if you already have them.

The tools are placed in:

- Slot 1: `LMI Wand Tool`
- Slot 2: `Removed Block Detector`
- Slot 3: `Block History`

If the tools are already in the correct slots, the command tells you so.

If the inventory does not have enough room to rearrange items safely, the command refuses to force anything.

### `/lmi discover [radius]`

Shows every player who edited the cube around your current position.

If no radius is provided, the plugin uses the configured default radius.

Example:

```text
/lmi discover
/lmi discover 20
```

Typical use case:

- stand in the damaged area
- run `/lmi discover`
- get a quick list of players who edited that zone

The response looks like this:

```text
Players who have edited this area: Alex, Steve
```

If the plugin has no history for that area, it reports:

```text
Players who have edited this area: nobody
```

### `/lmi undo <player> [radius|world]`

Removes one player's edits from the selected area while preserving later edits made by other players.

If the last visible state of a block belongs to another player, that block is left alone.

This is the command you use when you know who caused the grief and you want to roll back their changes without destroying legitimate repairs done by someone else afterwards.

Examples:

```text
/lmi undo Alex
/lmi undo Alex 20
/lmi undo Alex world
```

Behavior:

- no scope argument: uses the configured default radius around your current location
- number: uses that radius around your current location
- `world`: applies to the current world only

This command **always requires confirmation**.

After running it, the plugin warns you that the operation cannot be undone and asks you to use `/lmi confirm`.

### `/lmi clear-db`

Deletes all LibelulaMapInspector stored history.

This is a destructive administrative action and should only be used when you intentionally want to wipe the plugin database.

It is intentionally hidden from tab completion, but it is still available if typed manually.

This command **always requires confirmation**.

### `/lmi confirm`

Confirms the current pending destructive action.

It is used for:

- `/lmi clear-db`
- `/lmi undo ...`

If there is nothing pending, the plugin tells you so.

If you run any other command before confirming, the pending confirmation is cancelled.

## Admin Tools

### 1. `LMI Wand Tool`

- Material: `WAXED_LIGHTNING_ROD`
- Slot: `1`
- Purpose: inspect the latest recorded change on a block

Use it by breaking a block with the tool while in creative mode.

The block is **not** actually broken. The event is cancelled and the plugin simply tells you who last changed that block.

If information exists, the result is shown as:

```text
<timestamp> <player>
```

If no information exists, the result is:

```text
No information was found
```

### 2. `Removed Block Detector`

- Material: `REDSTONE_ORE`
- Slot: `2`
- Purpose: inspect who last removed a block at a position

This tool works in two ways:

- break a block with it
- place it on a block position with it

In both cases:

- the world change is cancelled
- the plugin checks the target position
- you are told who last removed the block there

This makes it useful both for existing blocks and for empty or suspicious positions where something may have been removed.

### 3. `Block History`

- Material: `PAPER`
- Slot: `3`
- Purpose: show the full recorded history for one block

Use it by breaking a block with the tool while in creative mode.

The block is **not** actually broken. The event is cancelled and the plugin prints the entire known history for that block in chronological order.

Each line shows:

- timestamp
- player
- action

This is the tool you use when the simple “who touched this last?” answer is not enough.

## Tool Rules

All LibelulaMapInspector tools follow these rules:

- They require creative mode to use
- They cannot be dropped
- They cannot be placed as real world blocks
- They are admin tools only

If a player tries to use them outside creative mode, the action is cancelled and the plugin shows an error message.

If a player tries to drop them, the drop is cancelled.

## What The Plugin Tracks

The plugin currently records these types of changes:

- Direct block breaks
- Direct block placements
- Multi-block placements such as beds and doors
- Sign text changes
- Bucket-based water placement
- Bucket-based lava placement
- Bucket-based water removal
- Bucket-based lava removal
- TNT damage attributed to the player who ignited the TNT
- TNT primed by a recently placed redstone block

If optional fluid grief tracking is enabled, it can also attribute some generated blocks such as cobblestone, stone, or obsidian to the player who started the fluid grief.

## Configuration

## `commands`

These values affect admin workflows:

- `default-radius`
- `undo-blocks-per-tick`

Defaults:

```yml
commands:
  default-radius: 10
  undo-blocks-per-tick: 10
```

`default-radius` is used by commands such as `/lmi discover` and `/lmi undo` when no radius is provided.

`undo-blocks-per-tick` controls how quickly undo applies world changes.

## `capture`

This section controls what the plugin listens to.

Example:

```yml
capture:
  excluded-worlds:
    - world_creative

  blocks:
    block-break: true
    block-place: true
    block-multi-place: true
    sign-change: true

  fluids:
    bucket-empty: true
    bucket-fill: true
    fluid-grief-tracking: false

  explosions:
    tnt-explosions: true
```

### `excluded-worlds`

Worlds listed here are ignored by every capture listener.

This is useful for:

- creative worlds
- test worlds
- protected worlds
- worlds where player edits should not be monitored

### `fluid-grief-tracking`

This option is **disabled by default**.

It adds extra runtime work and should only be enabled if your server specifically needs water/lava chain-reaction attribution.

Keep it off unless you know you need it.

## `chunks`

This section controls on-disk history retention.

Defaults:

```yml
chunks:
  limits:
    max-total-size-megabytes: 1024
    max-record-age-days: 365
```

Meaning:

- total disk budget for stored history
- maximum record age before old history is cleaned up

If the limits are exceeded, the plugin cleans older history automatically.

## `index`

This section controls the in-memory index and buffers used to keep the plugin fast.

Defaults:

```yml
index:
  megabytes: 100
  false-positive-rate: 0.01
  buffer-megabytes: 50
  persist-interval-minutes: 60
```

If you do not know what these values do, do not change them.

These values are performance-related. Bad values can make the plugin slower or less efficient.

## Practical Admin Workflows

### Find Out Who Built Something Suspicious

1. Go to the area.
2. Run `/lmi discover`.
3. Use `LMI Wand Tool` on suspicious blocks.
4. Use `Block History` if you need the full timeline.

### Find Out Who Removed A Block

1. Run `/lmi wand`.
2. Select `Removed Block Detector`.
3. Use it on the suspicious position.

### Investigate Sign Abuse

1. Use the normal wand on the sign to see the latest actor.
2. Use `Block History` to see the full sequence if needed.

### Undo One Griefer Without Touching Legitimate Repairs

1. Stand in the affected area.
2. Run `/lmi discover`.
3. Identify the suspicious player.
4. Run `/lmi undo <player> [radius]`.
5. Confirm with `/lmi confirm`.

If another player repaired some of the damage later, those later repairs are preserved.

### Undo One Griefer Across The Whole Current World

1. Stand anywhere in the world you want to fix.
2. Run `/lmi undo <player> world`.
3. Read the warning carefully.
4. Run `/lmi confirm`.

## Current Undo Limitation

Undo in the current version does **not** reconstruct the original generated terrain exactly when there is no earlier stored history for a block.

Current behavior:

- if undo removes a player's placement and there is no earlier history, the block becomes `AIR`
- if undo removes a player's removal and there is no earlier history, the block is left as it is

This exists because exact terrain regeneration for one block is much more complicated than it looks with standard Spigot APIs.

A future version may improve this by storing a baseline snapshot before the first player ever changes a block.

## Startup Information

On startup, the plugin logs a summary in console with:

- recorded events
- disk usage
- disk limit
- retention
- enabled capture listeners
- startup cleanup state

This is the quickest way to verify that the plugin loaded correctly and is already collecting data.

## Troubleshooting

### “You do not have permission”

Your account does not have `libelulamapinspector.admin`.

### “You must be in creative mode”

LibelulaMapInspector tools are admin tools and only work in creative mode.

### “No information was found”

That position currently has no matching recorded history, or the relevant event was never captured.

### `/lmi confirm` says there is nothing to confirm

There is no pending protected action. Run `/lmi clear-db` or `/lmi undo ...` first.

### `/lmi clear-db` does not appear in tab completion

That is intentional. It is still available if you type it manually.

## Final Notes

LibelulaMapInspector is built for real moderation work:

- fast answers
- lightweight storage
- practical admin tools
- focused anti-grief workflows

If you want a plugin that helps you answer the question that matters most after damage happens, **“who did this?”**, this is what LibelulaMapInspector was made for.
