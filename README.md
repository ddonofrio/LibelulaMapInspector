# LibelulaMapInspector

**The anti-grief plugin built to identify griefers and revert their damage with only 100 MiB of RAM, even on a busy Minecraft server with 200 players constantly building, without adding gameplay lag.**

LibelulaMapInspector is a Spigot plugin focused on one mission: make block history lookups feel instant, even at scale. The plugin is built so administrators can inspect suspicious edits fast, react fast, and restore confidence fast on active building servers.

That means administrators can investigate grief faster and keep the server responsive while large areas are being checked.

## Current Features

The plugin currently includes the first admin-facing foundation:

- A configurable `capture` section in `config.yml` for enabling block, fluid, sign, and TNT capture listeners
- A configurable `capture.excluded-worlds` list for worlds that must never be monitored
- A configurable `chunks.limits` section for disk budget and history retention
- A configurable `index` section in `config.yml` for tuning memory size, write buffer size, false positive disk access rate, and persistence interval
- An admin wand tool based on the `WAXED_LIGHTNING_ROD` for instant single-block inspection in creative mode
- An admin `/lmi discover [radius]` command for listing every player who edited a cube around the current position
- An admin `/lmi undo <player> [radius|world]` workflow for removing one player's recorded edits while preserving later edits from other players
- A startup console summary showing recorded events, disk usage, disk limit, and retention
- An admin-only `/lmi` command, including a protected reset flow with `/lmi clear-db` and `/lmi confirm`

## Vision

This is only the first step.

The long-term goal is a journal-style block history system able to:

- Identify who placed or removed blocks
- Inspect history at scale
- Help revert malicious edits
- Remain lightweight enough to run on active building servers

## Documentation Policy

This README is part of the active project documentation and will be updated as the plugin evolves.

A full end-user guide is also available in `USER_MANUAL.md`.

Automated unit tests now cover index persistence rules, invalid configuration fallbacks, chunk storage persistence and cleanup, binary roundtrips for special block payloads, in-memory history compaction, storage reset behavior, RAM + disk history retrieval, area actor lookup, capture configuration, TNT and fluid attribution, capture listeners, and the protected admin reset flow.

## Technical Foundation

### Configuration

The `commands` section currently supports:

- `default-radius`
- `undo-blocks-per-tick`

Default values:

- `default-radius: 10`
- `undo-blocks-per-tick: 10`

The `capture` section currently supports:

- `excluded-worlds`
- `blocks.block-break`
- `blocks.block-place`
- `blocks.block-multi-place`
- `blocks.sign-change`
- `fluids.bucket-empty`
- `fluids.bucket-fill`
- `fluids.fluid-grief-tracking`
- `explosions.tnt-explosions`

Default values:

- `excluded-worlds: [world_creative]`
- all core capture listeners enabled
- `fluids.fluid-grief-tracking: false`

The `chunks.limits` section currently supports:

- `max-total-size-megabytes`
- `max-record-age-days`

Default values:

- `max-total-size-megabytes: 1024`
- `max-record-age-days: 365`

The `index` section currently supports:

- `megabytes`
- `buffer-megabytes`
- `false-positive-rate`
- `persist-interval-minutes`

Default values:

- `megabytes: 100`
- `buffer-megabytes: 50`
- `false-positive-rate: 0.01`
- `persist-interval-minutes: 60`

Megabytes are always interpreted as `value * 1024 * 1024`.

### Index

LibelulaMapInspector uses a Bloom filter as a global in-memory membership test for block history.

The Bloom filter does **not** store the current state of a block. Instead, it stores whether a block position has **ever** received history in the plugin database.

This model is ideal for block auditing:

- Positions are added when history first appears
- Positions never need to be removed
- Negative lookups are definitive
- Positive lookups are cheap candidates for deeper disk inspection

By design, this dramatically reduces unnecessary database work during large inspections.

### Persistence

LibelulaMapInspector now persists both the index and the buffered block history foundation:

- when the plugin shuts down
- periodically on a dedicated write thread

The index uses two binary files:

- one for the index contents
- one for the index configuration metadata

The buffered block history store is written into a dedicated disk layout under the plugin folder:

- `index/` for the persisted index files
- `chunks/store.meta.bin` for global chunk storage metadata, including the recorded event counter
- `chunks/<world-name>/world.meta.bin` for per-world metadata
- `chunks/<world-name>/region/r.<regionX>.<regionZ>.lmi` for region-style history files

Every region batch now carries lightweight timestamp metadata so startup cleanup can skip young batches quickly and only fully parse mixed batches when it really has to.

If the configured chunk limits are exceeded on startup, the plugin launches chunk maintenance in the background, cleans the oldest history first, rewrites affected region files, and compacts consecutive entries from the same actor for the same block when rewriting.

The index is never rebuilt from chunk data. It is only created on the very first startup or after `/lmi clear-db`.

If chunk history already exists but the persisted index is missing or unreadable, the plugin does not create a replacement index automatically.

This is the storage foundation only. World listeners, history lookup commands, and restoration mechanics are still being added.

Storage work is now split across dedicated internal threads:

- `1` read thread for history lookups and area discovery
- `1` write thread for persistence and index snapshots
- `1` maintenance thread for cleanup and chunk rewriting

This keeps LibelulaMapInspector off the shared Bukkit async worker pool for block-history disk work.

### Block Store Foundation

The block store keeps per-block timelines in memory and preserves meaningful actor transitions.

That means:

- repeated edits by the same player can be compacted into the latest state
- edits from different players are kept as separate history entries
- the in-memory side and the chunk files are designed to expose the same history once lookups are connected

Special block payloads are already modeled for the next capture phase, including:

- signs
- containers and their stored contents when the block itself is removed or replaced
- double chests, including paired-block offsets for future restoration work

If the user changes the index shape in `config.yml` after data already exists, the plugin keeps using the persisted shape and warns in console that the database must be cleared before a new index shape can take effect.

### Retrieval Foundation

The internal retrieval layer can now:

- read the full timeline for one block position by merging buffered RAM data with persisted chunk history
- collect every actor who edited a box, even when the selection spans multiple chunks and regions
- resolve actor names from stored UUIDs for future admin-facing commands

This now powers `/lmi discover [radius]`, which reports the players who edited the cube around the admin's current position.

### Undo Foundation

LibelulaMapInspector now includes a first-pass selective undo workflow:

- `/lmi undo <player> [radius|world]`
- the command removes the selected player's history in the chosen scope
- later edits made by other players are preserved
- world changes are applied gradually in batches on the main thread
- history rewrites happen on the plugin's own storage threads

`v1` intentionally does **not** attempt exact world-generator regeneration when no earlier recorded history exists for a block.

That limitation exists because querying the original generated terrain precisely is much harder than it looks with plain Spigot API. The plugin does not have a clean public API to ask the normal live generator what exact block originally belonged at one coordinate, and creating a second real mirrored world just for that is not considered an acceptable solution here.

Because of that, `undo` currently uses a safe fallback:

- if undo removes a player's placement and there is no earlier recorded history, the block becomes `AIR`
- if undo removes a player's removal and there is no earlier recorded history, the block is left as it is

A future version could improve this by storing a baseline snapshot before the first player changes a block, so undo can restore from that saved baseline instead of trying to reconstruct the generated terrain later.

### Admin Tools

LibelulaMapInspector now includes three admin tools for quick single-block inspection.

- `/lmi wand` gives or repositions all three tools into slots `1`, `2`, and `3`
- the `WAXED_LIGHTNING_ROD` named `LMI Wand Tool` inspects the latest recorded change for one block
- the `REDSTONE_ORE` named `Removed Block Detector` inspects who last removed the block at the targeted position
- the `PAPER` named `Block History` shows the full recorded history for one block
- using these tools requires creative mode
- none of these tools can ever be placed as a real world block
- breaking a block with the wand is cancelled and replaced with a lookup of the latest recorded actor
- breaking a block with the removed block detector is cancelled and replaced with a lookup of the latest recorded removal at that position
- placing the removed block detector is cancelled and replaced with a lookup of the latest recorded removal at that position
- breaking a block with `Block History` is cancelled and prints the entire recorded history in chat
- dropping any of these tools is cancelled so these admin tools cannot be left lying around

### Capture Foundation

LibelulaMapInspector can now capture the first set of player-caused world changes:

- direct block breaks
- direct block placements
- multi-block placements such as doors or beds
- sign text changes
- bucket-based water and lava placement/removal
- TNT explosions attributed to the player who ignited the TNT, including simple redstone priming such as a recently placed redstone block

The optional `fluids.fluid-grief-tracking` mode adds extra attribution for water/lava chain reactions so generated cobblestone, stone, or obsidian can be linked back to the responsible player.

That option stays disabled by default because it adds extra runtime work and should only be enabled on servers that really need that grief pattern tracked.

Capture also supports an `excluded-worlds` list so worlds such as creative or otherwise protected maps can be ignored entirely by every capture listener.

### Reset Workflow

Resetting stored history is intentionally guarded.

The current flow is:

1. Run `/lmi clear-db`
2. Read the warning carefully
3. Run `/lmi confirm`

`/lmi confirm` is only shown while a reset confirmation is actually pending.

`/lmi clear-db` stays available and documented, but it is intentionally hidden from tab completion because it is a destructive administrative action.

If any other command is executed in between, the reset confirmation is cancelled.
