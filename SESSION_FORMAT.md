# Session JSON Format

Sessions are stored in `~/.runelite/gauntlet-history/sessions.json` as a JSON array, newest run first.

## Top-level session object

| Field | Type | Description |
|-------|------|-------------|
| `startTime` | string (ISO-8601) | When the player entered the Gauntlet |
| `bossStartTime` | string (ISO-8601) or `null` | When the boss fight started (Hunllef spawned) |
| `endTime` | string (ISO-8601) or `null` | When the session ended (left the Gauntlet, died, or logged out) |
| `corrupted` | boolean | `true` for Corrupted Gauntlet, `false` for Regular |
| `killedBoss` | boolean | `true` if Hunllef was killed this run |
| `diedInPrep` | boolean | `true` if the player died during the preparation phase |
| `diedInBoss` | boolean | `true` if the player died during the boss fight |
| `killCount` | integer | Gauntlet KC at the time of the kill; `-1` if not recorded |
| `prepTimeMs` | long (ms) | Preparation time in milliseconds from the game's completion message; `-1` if not available |
| `fightTimeMs` | long (ms) | Hunllef kill time in milliseconds from the game's completion message; `-1` if not available |
| `totalTimeMs` | long (ms) | Total challenge duration in milliseconds from the game's completion message; `-1` if not available |
| `loot` | array of `LootItem` | Items received from the chest; empty for non-kills |
| `perf` | `PerformanceData` or `null` | Boss-fight performance metrics; `null` if the boss was never reached or the plugin was not active during the fight |

> **Note on times:** `prepTimeMs`, `fightTimeMs`, and `totalTimeMs` are sourced from the game's own chat messages and are accurate to 0.1 s (one game tick ≈ 0.6 s). They are only present for runs completed after plugin version 1.1. Older sessions have `-1` here; times for those runs can be approximated from `startTime`, `bossStartTime`, and `endTime`.

---

## LootItem

| Field | Type | Description |
|-------|------|-------------|
| `id` | integer | RuneLite item ID, or `-1` for items recorded via chat message (no item ID available) |
| `name` | string | Item name as it appeared in the drop message |
| `quantity` | integer | Stack size |

---

## PerformanceData

Recorded only during the boss fight phase. All counts are for the current run only.

| Field | Type | Description |
|-------|------|-------------|
| `totalTicks` | integer | Total game ticks elapsed during the boss fight |
| `lostTicks` | integer | Ticks where the player was not attacking (idle ticks) |
| `playerAttacks` | integer | Number of attacks the player made |
| `wrongAttackStyle` | integer | Attacks made with the wrong style (not matching Hunllef's current phase) |
| `wrongOffPray` | integer | Ticks where the player attacked without the correct offensive prayer |
| `hunllefAttacks` | integer | Number of attacks Hunllef made against the player |
| `wrongDefPray` | integer | Hunllef attacks taken without the correct defensive prayer active |
| `hunllefStomps` | integer | Times the player was hit by Hunllef's stomp (standing on a tile Hunllef walked onto) |
| `tornadoHits` | integer | Times the player was hit by a tornado |
| `floorTileHits` | integer | Times the player was hit by a damaging floor tile |
| `damageTaken` | integer | Total damage received from all sources during the boss fight |
| `damageGiven` | integer | Total damage dealt to Hunllef |

### Derived values

These are not stored but can be computed from the fields above:

| Metric | Formula |
|--------|---------|
| Tick efficiency | `(totalTicks - lostTicks) / totalTicks × 100` |
| DPS taken | `damageTaken / (totalTicks × 0.6)` |
| DPS given | `damageGiven / (totalTicks × 0.6)` |

---

## Example

```json
[
  {
    "startTime": "2026-05-04T17:53:00Z",
    "bossStartTime": "2026-05-04T17:56:00Z",
    "endTime": "2026-05-04T17:59:11Z",
    "corrupted": false,
    "killedBoss": true,
    "diedInPrep": false,
    "diedInBoss": false,
    "killCount": 15,
    "prepTimeMs": 179400,
    "fightTimeMs": 190800,
    "totalTimeMs": 370200,
    "loot": [
      { "id": -1, "name": "Adamant arrow", "quantity": 440 },
      { "id": -1, "name": "Cosmic rune",   "quantity": 161 },
      { "id": -1, "name": "Crystal shard", "quantity": 6   }
    ],
    "perf": {
      "totalTicks": 321,
      "lostTicks": 46,
      "playerAttacks": 59,
      "wrongAttackStyle": 1,
      "wrongOffPray": 10,
      "hunllefAttacks": 58,
      "wrongDefPray": 0,
      "hunllefStomps": 0,
      "tornadoHits": 0,
      "floorTileHits": 0,
      "damageTaken": 284,
      "damageGiven": 603
    }
  }
]
```
