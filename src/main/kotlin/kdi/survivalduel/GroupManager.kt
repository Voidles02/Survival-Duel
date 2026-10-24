package kdi.survivalduel

// Author: voidles02
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

class GroupManager(private val plugin: SurvivalDuelPlugin) {
    private val groupsById = LinkedHashMap<UUID, Group>()
    private val groupByMember = LinkedHashMap<UUID, Group>()
    private val invitesByTarget = LinkedHashMap<UUID, Invite>()
    private var membershipChangeHandler: ((Collection<UUID>) -> Unit)? = null

    fun setMembershipChangeHandler(handler: (Collection<UUID>) -> Unit) {
        membershipChangeHandler = handler
    }

    fun hasGroup(playerId: UUID): Boolean = playerId in groupByMember

    fun leaderOf(playerId: UUID): UUID? = groupByMember[playerId]?.leader

    fun membersOf(playerId: UUID): Set<UUID> = groupByMember[playerId]?.members?.toSet() ?: emptySet()

    fun sameGroup(first: UUID, second: UUID): Boolean {
        val firstGroup = groupByMember[first] ?: return false
        return firstGroup === groupByMember[second]
    }

    fun isLeader(playerId: UUID): Boolean = groupByMember[playerId]?.leader == playerId

    fun create(player: Player) {
        val playerId = player.uniqueId
        if (hasGroup(playerId)) {
            plugin.send(player, "group-already-in")
            return
        }
        val group = Group(playerId)
        group.members += playerId
        groupsById[group.id] = group
        groupByMember[playerId] = group
        membershipChangeHandler?.invoke(group.members)
        plugin.send(player, "group-created")
    }

    fun invite(sender: Player, target: Player) {
        val group = groupByMember[sender.uniqueId]
        if (group == null) {
            plugin.send(sender, "group-not-in")
            return
        }
        if (group.leader != sender.uniqueId) {
            plugin.send(sender, "group-leader-only")
            return
        }
        if (target.uniqueId in groupByMember) {
            plugin.send(sender, "group-already-in")
            return
        }
        if (target.uniqueId in invitesByTarget) {
            plugin.send(sender, "group-invite-pending")
            return
        }
        if (group.members.size >= plugin.config.getInt("max-group-size", 4)) {
            plugin.send(sender, "group-full")
            return
        }

        val invite = Invite(group.id, group.leader)
        invitesByTarget[target.uniqueId] = invite
        val timeout = plugin.config.getLong("challenge-timeout-seconds", 30).coerceIn(1, 86_400)
        invite.task = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            expireInvite(target.uniqueId, invite)
        }, timeout * 20L)
        plugin.send(sender, "group-invite-sent", mapOf("player" to target.name))
        plugin.send(target, "group-invite-received", mapOf("player" to sender.name))
    }

    fun acceptInvite(player: Player) {
        val playerId = player.uniqueId
        val invite = invitesByTarget.remove(playerId)
        if (invite == null) {
            plugin.send(player, "group-not-invited")
            return
        }
        invite.task?.cancel()
        val group = groupsById[invite.groupId]
        if (group == null || group.leader != invite.leader) {
            plugin.send(player, "group-invite-cancelled")
            return
        }
        if (playerId in groupByMember) {
            plugin.send(player, "group-already-in")
            return
        }
        if (group.members.size >= plugin.config.getInt("max-group-size", 4)) {
            plugin.send(player, "group-full")
            return
        }
        group.members += playerId
        groupByMember[playerId] = group
        membershipChangeHandler?.invoke(group.members)
        notifyGroup(group, "group-joined", mapOf("player" to player.name))
    }

    fun denyInvite(player: Player) {
        val invite = invitesByTarget.remove(player.uniqueId)
        if (invite == null) {
            plugin.send(player, "group-not-invited")
            return
        }
        invite.task?.cancel()
        val name = player.name
        plugin.send(player, "group-denied", mapOf("player" to name))
        groupsById[invite.groupId]?.let { plugin.send(it.leader, "group-denied", mapOf("player" to name)) }
    }

    fun leave(player: Player) {
        val playerId = player.uniqueId
        val group = groupByMember[playerId]
        if (group == null) {
            plugin.send(player, "group-not-in")
            return
        }
        plugin.send(player, "group-left")
        removeMember(group, playerId, notify = true)
    }

    fun kick(sender: Player, target: Player) {
        val group = groupByMember[sender.uniqueId]
        if (group == null) {
            plugin.send(sender, "group-not-in")
            return
        }
        if (group.leader != sender.uniqueId) {
            plugin.send(sender, "group-leader-only")
            return
        }
        if (sender.uniqueId == target.uniqueId) {
            plugin.send(sender, "group-self-kick")
            return
        }
        if (groupByMember[target.uniqueId] !== group) {
            plugin.send(sender, "group-not-member")
            return
        }
        plugin.send(target, "group-kicked", mapOf("player" to sender.name))
        removeMember(group, target.uniqueId, notify = true)
    }

    fun list(player: Player) {
        val group = groupByMember[player.uniqueId]
        if (group == null) {
            plugin.send(player, "group-not-in")
            return
        }
        val names = group.members.joinToString(", ") { plugin.playerName(it) }
        plugin.send(player, "group-list", mapOf("size" to group.members.size.toString(), "members" to names))
    }

    fun disband(player: Player) {
        val group = groupByMember[player.uniqueId]
        if (group == null) {
            plugin.send(player, "group-not-in")
            return
        }
        if (group.leader != player.uniqueId) {
            plugin.send(player, "group-leader-only")
            return
        }
        notifyGroup(group, "group-disbanded", mapOf("player" to player.name))
        membershipChangeHandler?.invoke(group.members.toSet())
        clearGroupInvites(group.id, notifyTargets = true)
        groupsById.remove(group.id)
        group.members.forEach { groupByMember.remove(it) }
        group.members.clear()
    }

    fun handleDeparture(playerId: UUID) {
        invitesByTarget.remove(playerId)?.task?.cancel()
        groupByMember[playerId]?.let { removeMember(it, playerId, notify = true) }
    }

    fun shutdown() {
        invitesByTarget.values.forEach { it.task?.cancel() }
        invitesByTarget.clear()
        groupsById.clear()
        groupByMember.clear()
    }

    private fun expireInvite(targetId: UUID, invite: Invite) {
        if (invitesByTarget[targetId] !== invite) return
        invitesByTarget.remove(targetId)
        plugin.send(targetId, "group-invite-expired", mapOf("player" to plugin.playerName(invite.leader)))
    }

    private fun removeMember(group: Group, memberId: UUID, notify: Boolean) {
        val wasLeader = group.leader == memberId
        val affectedMembers = group.members.toSet() + memberId
        group.members.remove(memberId)
        groupByMember.remove(memberId)
        if (group.members.isEmpty()) {
            clearGroupInvites(group.id, notifyTargets = true)
            groupsById.remove(group.id)
            membershipChangeHandler?.invoke(affectedMembers)
            return
        }
        if (wasLeader) {
            clearGroupInvites(group.id, notifyTargets = true)
            group.leader = group.members.first()
        }
        membershipChangeHandler?.invoke(affectedMembers)
        if (notify) {
            notifyGroup(group, "group-member-left", mapOf("player" to plugin.playerName(memberId)))
            if (wasLeader) {
                notifyGroup(group, "group-leader-changed", mapOf("player" to plugin.playerName(group.leader)))
            }
        }
    }

    private fun clearGroupInvites(groupId: UUID, notifyTargets: Boolean) {
        val targets = invitesByTarget.filterValues { it.groupId == groupId }.keys.toList()
        targets.forEach { target ->
            val invite = invitesByTarget.remove(target) ?: return@forEach
            invite.task?.cancel()
            if (notifyTargets) plugin.send(target, "group-invite-cancelled")
        }
    }

    private fun notifyGroup(group: Group, key: String, replacements: Map<String, String>) {
        plugin.sendToOnline(group.members, key, replacements)
    }

    private class Group(val id: UUID) {
        val members = LinkedHashSet<UUID>()
        var leader: UUID = id
    }

    private data class Invite(val groupId: UUID, val leader: UUID, var task: BukkitTask? = null)
}