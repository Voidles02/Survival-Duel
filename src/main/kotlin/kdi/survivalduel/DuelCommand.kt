package kdi.survivalduel

// Author: voidles02
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

class DuelCommand(
    private val plugin: SurvivalDuelPlugin,
    private val groups: GroupManager,
    private val duels: DuelManager
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("reload", ignoreCase = true) == true) {
            if (!sender.hasPermission("survivalduel.admin")) {
                send(sender, "no-permission")
                return true
            }
            plugin.reloadConfig()
            send(sender, "reloaded")
            return true
        }
        if (!sender.hasPermission("survivalduel.use")) {
            send(sender, "no-permission")
            return true
        }
        val player = sender as? Player ?: run {
            send(sender, "player-only")
            return true
        }
        if (args.isEmpty()) {
            send(player, "usage")
            return true
        }
        if (args[0].equals("group", ignoreCase = true) && !player.hasPermission("survivalduel.group")) {
            send(player, "no-permission")
            return true
        }
        if (!duels.takeCommandCooldown(player)) return true

        when (args[0].lowercase(Locale.ROOT)) {
            "accept" -> if (args.size <= 2) duels.respond(player, true, args.getOrNull(1)) else send(player, "usage")
            "deny" -> if (args.size <= 2) duels.respond(player, false, args.getOrNull(1)) else send(player, "usage")
            "cancel" -> duels.cancelChallenge(player)
            "forfeit" -> duels.forfeit(player)
            "group" -> handleGroup(player, args)
            else -> {
                if (args.size != 1) {
                    send(player, "usage")
                    return true
                }
                val target = Bukkit.getPlayerExact(args[0])
                if (target == null) send(player, "player-not-found") else duels.challenge(player, target)
            }
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.firstOrNull()?.equals("group", ignoreCase = true) == true
            && !sender.hasPermission("survivalduel.group")
        ) return emptyList()

        if (args.size == 1) {
            val options = mutableListOf("accept", "deny", "cancel", "forfeit")
            if (sender.hasPermission("survivalduel.group")) options += "group"
            if (sender.hasPermission("survivalduel.admin")) options += "reload"
            if (sender.hasPermission("survivalduel.use")) options += Bukkit.getOnlinePlayers().map(Player::getName)
            return matching(options, args[0])
        }
        if (args.size == 2 && (args[0].equals("accept", true) || args[0].equals("deny", true))) {
            return matching(onlineNames(), args[1])
        }
        if (args.size == 2 && args[0].equals("group", true)) {
            return matching(listOf("create", "invite", "accept", "deny", "leave", "kick", "list", "disband"), args[1])
        }
        if (args.size == 3 && args[0].equals("group", true)
            && (args[1].equals("invite", true) || args[1].equals("kick", true))
        ) {
            return matching(onlineNames(), args[2])
        }
        return emptyList()
    }

    private fun handleGroup(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            send(player, "group-usage")
            return
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "create" -> requireLength(player, args, 2) { groups.create(player) }
            "invite" -> {
                if (args.size != 3) {
                    send(player, "group-usage")
                    return
                }
                val target = Bukkit.getPlayerExact(args[2])
                if (target == null) send(player, "player-not-found") else groups.invite(player, target)
            }
            "accept" -> requireLength(player, args, 2) { groups.acceptInvite(player) }
            "deny" -> requireLength(player, args, 2) { groups.denyInvite(player) }
            "leave" -> requireLength(player, args, 2) { groups.leave(player) }
            "kick" -> {
                if (args.size != 3) {
                    send(player, "group-usage")
                    return
                }
                val target = Bukkit.getPlayerExact(args[2])
                if (target == null) send(player, "player-not-found") else groups.kick(player, target)
            }
            "list" -> requireLength(player, args, 2) { groups.list(player) }
            "disband" -> requireLength(player, args, 2) { groups.disband(player) }
            else -> send(player, "group-usage")
        }
    }

    private fun requireLength(player: Player, args: Array<out String>, expected: Int, action: () -> Unit) {
        if (args.size != expected) send(player, "group-usage") else action()
    }

    private fun onlineNames(): List<String> = Bukkit.getOnlinePlayers().map(Player::getName)

    private fun matching(options: List<String>, input: String): List<String> {
        val prefix = input.lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(prefix) }
    }

    private fun send(sender: CommandSender, key: String) {
        if (sender is Player) plugin.send(sender, key) else sender.sendMessage(plugin.message(key))
    }
}