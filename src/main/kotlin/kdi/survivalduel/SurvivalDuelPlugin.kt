package kdi.survivalduel

// Author: voidles02
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class SurvivalDuelPlugin : JavaPlugin() {
    private val miniMessage = MiniMessage.miniMessage()
    private lateinit var groupManager: GroupManager
    private lateinit var duelManager: DuelManager

    override fun onEnable() {
        saveDefaultConfig()
        groupManager = GroupManager(this)
        duelManager = DuelManager(this, groupManager)
        groupManager.setMembershipChangeHandler(duelManager::invalidateChallengesForMembershipChange)

        getCommand("duel")?.let { command ->
            val handler = DuelCommand(this, groupManager, duelManager)
            command.setExecutor(handler)
            command.tabCompleter = handler
        }

        server.pluginManager.registerEvents(duelManager, this)
        logger.info("SurvivalDuel enabled.")
    }

    override fun onDisable() {
        if (::duelManager.isInitialized) duelManager.shutdown()
        if (::groupManager.isInitialized) groupManager.shutdown()
        logger.info("SurvivalDuel disabled.")
    }

    fun message(key: String, replacements: Map<String, String> = emptyMap()): Component {
        var value = config.getString("messages.$key", "<red>Missing message: $key")
            ?: "<red>Missing message: $key"
        replacements.forEach { (name, replacement) -> value = value.replace("{$name}", replacement) }
        return miniMessage.deserialize(value)
    }

    fun send(player: Player, key: String, replacements: Map<String, String> = emptyMap()) {
        player.sendMessage(message(key, replacements))
    }

    fun send(playerId: UUID, key: String, replacements: Map<String, String> = emptyMap()) {
        Bukkit.getPlayer(playerId)?.let { send(it, key, replacements) }
    }

    fun legacyMessage(key: String, replacements: Map<String, String> = emptyMap()): String =
        LegacyComponentSerializer.legacySection().serialize(message(key, replacements))

    fun playerName(playerId: UUID): String {
        Bukkit.getPlayer(playerId)?.let { return it.name }
        val offlinePlayer: OfflinePlayer = Bukkit.getOfflinePlayer(playerId)
        return offlinePlayer.name ?: "Unknown"
    }

    fun sendToOnline(playerIds: Collection<UUID>, key: String, replacements: Map<String, String> = emptyMap()) {
        playerIds.forEach { send(it, key, replacements) }
    }
}