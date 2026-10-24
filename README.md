# ⚔️ SurvivalDuel

**Lightweight survival-world duels using your own gear.** Challenge a player or lead your group into a fair fight—right where you are.

## ✨ Features

- 1v1 and group-versus-group duels; groups are in-memory and configurable.
- Clickable challenges and group invitations that expire automatically.
- Three-second actionbar countdown, duel boss bar, and vanilla death, drops, and XP.
- Only opponents can damage each other during a duel; group members cannot inflict friendly fire.
- No arenas, kits, teleports, databases, or external dependencies.

## 🎮 Commands

| Command | Description |
| --- | --- |
| `/duel <player>` | Challenge a player or their group |
| `/duel accept [player]` · `/duel deny [player]` | Respond to a challenge |
| `/duel cancel` · `/duel forfeit` | Cancel your challenge or forfeit your duel |
| `/duel group create` | Create a group |
| `/duel group invite <player>` | Invite a player |
| `/duel group accept` · `/duel group deny` | Respond to an invitation |
| `/duel group leave` · `/duel group kick <player>` | Leave or remove a member |
| `/duel group list` · `/duel group disband` | View or disband your group |
| `/duel reload` | Reload configuration *(admin)* |

Group leaders start and answer group challenges. Teams must have equal online player counts unless `allow-uneven` is enabled.

## 🔐 Permissions

- `survivalduel.use` — use duel commands *(default: everyone)*
- `survivalduel.group` — manage duel groups *(default: everyone)*
- `survivalduel.admin` — reload the plugin *(default: operators)*

## ⚙️ Configuration

Edit `plugins/SurvivalDuel/config.yml` to set challenge expiry, countdown, command cooldown, maximum group size, uneven-team rules, and MiniMessage text.

## 📦 Requirements

Paper 1.21.11. Groups, challenges, and duels are held in memory and are not saved across restarts.