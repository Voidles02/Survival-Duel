package kdi.survivalduel

// Author: voidles02
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.projectiles.ProjectileSource
import org.bukkit.scheduler.BukkitTask
import java.util.LinkedHashSet
import java.util.UUID

class DuelManager(private val plugin: SurvivalDuelPlugin, private val groups: GroupManager) : Listener {
    private val challengesByTarget = HashMap<UUID, Challenge>()
    private val challengesByMember = HashMap<UUID, Challenge>()
    private val duelsByMember = HashMap<UUID, Duel>()
    private val lastCommandAt = HashMap<UUID, Long>()

    fun takeCommandCooldown(player: Player): Boolean {
        val playerId = player.uniqueId
        val now = System.nanoTime()
        val cooldownNanos = plugin.config.getLong("command-cooldown-seconds", 2).coerceIn(0, 3_600) * 1_000_000_000L
        val last = lastCommandAt[playerId]
        if (last != null && now - last < cooldownNanos) {
            val remaining = ((cooldownNanos - (now - last) + 999_999_999L) / 1_000_000_000L).coerceAtLeast(1)
            plugin.send(player, "cooldown", mapOf("seconds" to remaining.toString()))
            return false
        }
        lastCommandAt[playerId] = now
        return true
    }

    fun challenge(challenger: Player, target: Player) {
        val challengerId = challenger.uniqueId
        val targetId = target.uniqueId
        val challengerLeader = groups.leaderOf(challengerId)
        val targetGroupLeader = groups.leaderOf(targetId)

        if (challengerLeader != null && challengerLeader != challengerId) {
            plugin.send(challenger, "group-leader-only")
            return
        }
        if (challengerId == targetId || (challengerLeader != null && challengerLeader == targetGroupLeader)) {
            plugin.send(challenger, "self-duel")
            return
        }
        if (targetGroupLeader != null && Bukkit.getPlayer(targetGroupLeader) == null) {
            plugin.send(challenger, "group-leader-offline")
            return
        }
        val actualChallengerLeader = challengerLeader ?: challengerId
        val actualTargetLeader = targetGroupLeader ?: targetId
        val challengerSide = onlineRoster(actualChallengerLeader)
        val targetSide = onlineRoster(actualTargetLeader)
        if (challengerSide.isEmpty() || targetSide.isEmpty()) {
            plugin.send(challenger, "player-not-found")
            return
        }
        if (containsDuelist(challengerSide) || containsDuelist(targetSide)) {
            plugin.send(challenger, "already-dueling")
            return
        }
        if (hasPendingParticipant(challengerSide) || hasPendingParticipant(targetSide)) {
            plugin.send(challenger, "challenge-pending")
            return
        }
        if (!plugin.config.getBoolean("allow-uneven", false) && challengerSide.size != targetSide.size) {
            plugin.send(challenger, "uneven-sides")
            return
        }

        val challenge = Challenge(
            actualChallengerLeader,
            actualTargetLeader,
            challengerSide,
            targetSide,
            groups.hasGroup(actualChallengerLeader),
            groups.hasGroup(actualTargetLeader)
        )
        val timeout = plugin.config.getLong("challenge-timeout-seconds", 30).coerceIn(1, 86_400)
        challenge.task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { expireChallenge(challenge) }, timeout * 20L)
        challengesByTarget[actualTargetLeader] = challenge
        challenge.participants.forEach { challengesByMember[it] = challenge }
        plugin.send(challenger, "challenge-sent", mapOf("player" to plugin.playerName(actualTargetLeader)))
        plugin.send(actualTargetLeader, "challenge-received", mapOf("player" to plugin.playerName(actualChallengerLeader)))
    }

    fun respond(responder: Player, accept: Boolean, challengerName: String?) {
        val responderId = responder.uniqueId
        val responderLeader = groups.leaderOf(responderId)
        if (responderLeader != null && responderLeader != responderId) {
            plugin.send(responder, "group-leader-only")
            return
        }
        val challenge = challengesByTarget[responderId]
        if (challenge == null) {
            plugin.send(responder, "no-pending-challenge")
            return
        }
        if (challengerName != null) {
            val specified = Bukkit.getPlayerExact(challengerName)
            val specifiedLeader = specified?.let { groups.leaderOf(it.uniqueId) ?: it.uniqueId }
            if (specifiedLeader != challenge.challengerLeader) {
                plugin.send(responder, "no-pending-challenge")
                return
            }
        }
        if (!accept) {
            clearChallenge(challenge)
            notifyChallengeSides(challenge, "challenge-denied")
            return
        }

        val challengerSide = validOnlineSide(challenge.challengerLeader, challenge.challengerGrouped, challenge.challengerSide)
        val targetSide = validOnlineSide(challenge.targetLeader, challenge.targetGrouped, challenge.targetSide)
        if (challengerSide == null || targetSide == null || challengerSide.isEmpty() || targetSide.isEmpty()
            || containsDuelist(challengerSide) || containsDuelist(targetSide)
            || (!plugin.config.getBoolean("allow-uneven", false) && challengerSide.size != targetSide.size)
        ) {
            clearChallenge(challenge)
            notifyChallengeSides(challenge, "challenge-invalid")
            return
        }

        clearChallenge(challenge)
        notifyChallengeSides(challenge, "challenge-accepted")
        val duel = Duel(challenge, challengerSide, targetSide)
        duel.participants.forEach { duelsByMember[it] = duel }
        beginCountdown(duel)
    }

    fun cancelChallenge(player: Player) {
        val playerId = player.uniqueId
        val leader = groups.leaderOf(playerId)
        if (leader != null && leader != playerId) {
            plugin.send(player, "group-leader-only")
            return
        }
        val actualLeader = leader ?: playerId
        val challenge = challengesByMember[actualLeader]
        if (challenge == null || challenge.challengerLeader != actualLeader) {
            plugin.send(player, "no-pending-challenge")
            return
        }
        clearChallenge(challenge)
        notifyChallengeSides(challenge, "challenge-cancelled")
    }

    fun forfeit(player: Player) {
        val playerId = player.uniqueId
        val duel = duelsByMember[playerId]
        if (duel == null) {
            plugin.send(player, "not-in-duel")
            return
        }
        duel.participants.filter { it != playerId }.forEach {
            plugin.send(it, "duel-forfeit", mapOf("player" to player.name))
        }
        removeDuelist(duel, playerId, player)
    }

    fun shutdown() {
        challengesByTarget.values.toList().forEach(::clearChallenge)
        duelsByMember.values.toSet().forEach { duel ->
            if (!duel.ended) {
                duel.ended = true
                cancelCountdown(duel)
                removeBars(duel)
            }
        }
        duelsByMember.clear()
        lastCommandAt.clear()
    }

    fun invalidateChallengesForMembershipChange(memberIds: Collection<UUID>) {
        memberIds.mapNotNull(challengesByMember::get).toSet().forEach { challenge ->
            clearChallenge(challenge)
            notifyChallengeSides(challenge, "challenge-invalid")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victimId = (event.entity as? Player)?.uniqueId
        val attackerId = resolvePlayer(event.damager)?.uniqueId
        if (victimId != null && attackerId != null && groups.sameGroup(victimId, attackerId)) {
            event.isCancelled = true
            return
        }

        val victimDuel = victimId?.let(duelsByMember::get)
        val attackerDuel = attackerId?.let(duelsByMember::get)
        if (victimDuel == null && attackerDuel == null) return
        if (victimDuel == null || attackerDuel !== victimDuel || !victimDuel.started
            || !areOpponents(victimDuel, victimId, attackerId)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val playerId = player.uniqueId
        cancelChallengesFor(playerId)
        duelsByMember[playerId]?.let { removeDuelist(it, playerId, player) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = handleDeparture(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) = handleDeparture(event.player)

    private fun handleDeparture(player: Player) {
        val playerId = player.uniqueId
        cancelChallengesFor(playerId)
        duelsByMember[playerId]?.let { removeDuelist(it, playerId, player) }
        groups.handleDeparture(playerId)
        lastCommandAt.remove(playerId)
    }

    private fun beginCountdown(duel: Duel) {
        val seconds = plugin.config.getInt("countdown-seconds", 3).coerceIn(1, 10)
        sendCountdown(duel, seconds)
        for (remaining in seconds - 1 downTo 1) {
            val delay = seconds - remaining
            duel.countdownTasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!duel.ended && !duel.started) sendCountdown(duel, remaining)
            }, delay * 20L)
        }
        duel.countdownTasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable { startDuel(duel) }, seconds * 20L)
    }

    private fun sendCountdown(duel: Duel, seconds: Int) {
        val component = plugin.message("countdown", mapOf("seconds" to seconds.toString()))
        duel.activeMembers().forEach { Bukkit.getPlayer(it)?.sendActionBar(component) }
    }

    private fun startDuel(duel: Duel) {
        if (duel.ended || duel.started) return
        cancelCountdown(duel)
        if (duel.sideA.isEmpty() || duel.sideB.isEmpty()) {
            finishDuel(duel, sideAWon = duel.sideA.isNotEmpty())
            return
        }
        duel.started = true
        val titleKey = if (duel.groupDuel) "bossbar-group-title" else "bossbar-player-title"
        duel.barA = createBossBar(titleKey, duel.leaderB)
        duel.barB = createBossBar(titleKey, duel.leaderA)
        addPlayers(duel.barA!!, duel.sideA)
        addPlayers(duel.barB!!, duel.sideB)
        notifyStart(duel, duel.sideA, duel.leaderB)
        notifyStart(duel, duel.sideB, duel.leaderA)
    }

    private fun createBossBar(titleKey: String, opponentLeader: UUID): BossBar {
        val title = plugin.legacyMessage(titleKey, mapOf("player" to plugin.playerName(opponentLeader)))
        return Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID).apply { progress = 1.0 }
    }

    private fun addPlayers(bar: BossBar, members: Collection<UUID>) {
        members.forEach { Bukkit.getPlayer(it)?.let(bar::addPlayer) }
    }

    private fun notifyStart(duel: Duel, side: Collection<UUID>, opponentLeader: UUID) {
        val key = if (duel.groupDuel) "duel-start-group" else "duel-start-player"
        side.forEach { id ->
            Bukkit.getPlayer(id)?.let { player ->
                plugin.send(player, key, mapOf("player" to plugin.playerName(opponentLeader)))
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f)
            }
        }
    }

    private fun removeDuelist(duel: Duel, playerId: UUID, player: Player) {
        if (duel.ended || playerId !in duel.participants) return
        val removed = duel.sideA.remove(playerId) || duel.sideB.remove(playerId)
        if (!removed) return
        duelsByMember.remove(playerId)
        duel.barA?.removePlayer(player)
        duel.barB?.removePlayer(player)
        if (duel.sideA.isEmpty() || duel.sideB.isEmpty()) {
            finishDuel(duel, sideAWon = duel.sideA.isNotEmpty())
        }
    }

    private fun finishDuel(duel: Duel, sideAWon: Boolean) {
        if (duel.ended) return
        duel.ended = true
        cancelCountdown(duel)
        removeBars(duel)
        duel.participants.forEach(duelsByMember::remove)
        val winner = if (sideAWon) duel.leaderA else duel.leaderB
        duel.participants.forEach { plugin.send(it, "duel-winner", mapOf("player" to plugin.playerName(winner))) }
    }

    private fun removeBars(duel: Duel) {
        duel.barA?.removeAll()
        duel.barB?.removeAll()
        duel.barA = null
        duel.barB = null
    }

    private fun cancelCountdown(duel: Duel) {
        duel.countdownTasks.forEach(BukkitTask::cancel)
        duel.countdownTasks.clear()
    }

    private fun expireChallenge(challenge: Challenge) {
        if (challengesByTarget[challenge.targetLeader] !== challenge) return
        clearChallenge(challenge)
        notifyChallengeSides(challenge, "challenge-expired")
    }

    private fun cancelChallengesFor(playerId: UUID) {
        challengesByMember[playerId]?.let { challenge ->
            clearChallenge(challenge)
            notifyChallengeSides(challenge, "challenge-cancelled")
        }
    }

    private fun clearChallenge(challenge: Challenge) {
        challenge.task?.cancel()
        challengesByTarget.remove(challenge.targetLeader, challenge)
        challenge.participants.forEach { challengesByMember.remove(it, challenge) }
    }

    private fun notifyChallengeSides(challenge: Challenge, key: String) {
        challenge.challengerSide.forEach {
            plugin.send(it, key, mapOf("player" to plugin.playerName(challenge.targetLeader)))
        }
        challenge.targetSide.forEach {
            plugin.send(it, key, mapOf("player" to plugin.playerName(challenge.challengerLeader)))
        }
    }

    private fun validOnlineSide(leader: UUID, grouped: Boolean, originalSide: List<UUID>): List<UUID>? {
        if (grouped && groups.leaderOf(leader) != leader) return null
        if (!grouped && groups.hasGroup(leader)) return null
        val online = ArrayList<UUID>()
        for (member in originalSide) {
            if (Bukkit.getPlayer(member) == null) continue
            if (grouped && groups.leaderOf(member) != leader) return null
            online += member
        }
        return online
    }

    private fun onlineRoster(leader: UUID): List<UUID> {
        val members = groups.membersOf(leader)
        if (members.isEmpty()) return if (Bukkit.getPlayer(leader) == null) emptyList() else listOf(leader)
        return members.filter { Bukkit.getPlayer(it) != null }
    }

    private fun containsDuelist(members: Collection<UUID>): Boolean = members.any { it in duelsByMember }

    private fun hasPendingParticipant(members: Collection<UUID>): Boolean = members.any { it in challengesByMember }

    private fun areOpponents(duel: Duel, first: UUID?, second: UUID?): Boolean =
        first != null && second != null &&
            ((first in duel.sideA && second in duel.sideB) || (first in duel.sideB && second in duel.sideA))

    private fun resolvePlayer(entity: Entity): Player? {
        if (entity is Player) return entity
        if (entity is Projectile) {
            val shooter: ProjectileSource? = entity.shooter
            if (shooter is Player) return shooter
        }
        if (entity is TNTPrimed && entity.source is Player) return entity.source as Player
        if (entity is AreaEffectCloud && entity.source is Player) return entity.source as Player
        return null
    }

    private class Challenge(
        val challengerLeader: UUID,
        val targetLeader: UUID,
        val challengerSide: List<UUID>,
        val targetSide: List<UUID>,
        val challengerGrouped: Boolean,
        val targetGrouped: Boolean,
        var task: BukkitTask? = null
    ) {
        val participants: List<UUID> = challengerSide + targetSide
    }

    private class Duel(challenge: Challenge, challengerSide: List<UUID>, targetSide: List<UUID>) {
        val leaderA = challenge.challengerLeader
        val leaderB = challenge.targetLeader
        val groupDuel = challenge.challengerGrouped || challenge.targetGrouped
        val sideA = LinkedHashSet(challengerSide)
        val sideB = LinkedHashSet(targetSide)
        val participants = challengerSide + targetSide
        val countdownTasks = ArrayList<BukkitTask>()
        var started = false
        var ended = false
        var barA: BossBar? = null
        var barB: BossBar? = null

        fun activeMembers(): List<UUID> = (sideA + sideB).toList()
    }
}