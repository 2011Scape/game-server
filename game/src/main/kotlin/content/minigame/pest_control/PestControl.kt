package content.minigame.pest_control

import content.bot.combat.PestControlBotContext
import content.entity.combat.target
import content.entity.effect.clearTransform
import content.entity.effect.toxin.curePoison
import content.entity.effect.toxin.poisonDamage
import content.entity.player.dialogue.Neutral
import content.entity.player.dialogue.type.choice
import content.entity.player.dialogue.type.npc
import content.entity.player.inv.item.addOrDrop
import content.quest.instance
import content.skill.summoning.follower
import content.skill.summoning.pet.pet
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.close
import world.gregs.voidps.engine.client.ui.closeInterfaces
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.Death
import world.gregs.voidps.engine.entity.character.jingle
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.combatLevel
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.item.floor.FloorItems
import world.gregs.voidps.engine.entity.obj.GameObjects
import world.gregs.voidps.engine.get
import world.gregs.voidps.engine.map.collision.Collisions
import world.gregs.voidps.engine.map.collision.clear
import world.gregs.voidps.engine.map.instance.Instances
import world.gregs.voidps.engine.map.zone.DynamicZones
import world.gregs.voidps.type.Region
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random

internal const val PEST_POINT_CAP = 500
internal val pestDock = PestDifficulty.NOVICE.dock
internal val pestBoat = PestDifficulty.NOVICE.boat
internal val pestIsland = Region(41, 40)
internal val pestLanding = Tile(2656, 2614)
internal val pestKnight = Tile(2656, 2592)
internal val pestColours = listOf("purple", "blue", "yellow", "red")
internal val pestPortals = listOf(Tile(2628, 2591), Tile(2680, 2588), Tile(2669, 2570), Tile(2645, 2569))

internal val pestSpawns = listOf(Tile(2629, 2589), Tile(2678, 2589), Tile(2670, 2574), Tile(2646, 2573))
internal fun Player.pestMatch(): PestMatch? = get<PestMatch>("pest_match")?.takeIf { it.active && this in it.players }
internal fun NPC.pestMatch(): PestMatch? = get<PestMatch>("pest_match")?.takeIf { it.active && this in it.actors }

class PestControl : Script {
    internal val waiting = linkedSetOf<Player>()
    internal val matches = mutableListOf<PestMatch>()
    private val crews = PestControlBotContext()
    private val queued = mutableMapOf<Player, PestDifficulty>()
    private val countdowns = IntArray(PestDifficulty.entries.size) { -1 }

    init {
        teleportTakeOff("*") {
            if (pestMatch() != null || this in waiting || onPestBoat()) {
                message("You can't teleport out of Pest Control. Use the lander's ladder or ask the squire to leave.")
                false
            } else {
                true
            }
        }
        for (difficulty in PestDifficulty.entries) {
            objectApproach("Cross", difficulty.gangplank) {
                approachRange(3)
                if (tile.within(difficulty.dock, 5)) {
                    if (queued[this] == difficulty || difficulty.onBoat(tile)) leaveQueue(this) else join(this, difficulty)
                }
            }
            objectOperate("Cross", difficulty.gangplank) {
                if (queued[this] == difficulty || difficulty.onBoat(tile)) leaveQueue(this) else join(this, difficulty)
            }
            objectOperate("Climb", difficulty.ladder) {
                if (queued[this] == difficulty || difficulty.onBoat(tile)) leaveQueue(this)
            }
            npcOperate("Talk-to", difficulty.squire) {
                npc<Neutral>("The ${difficulty.id} lander requires combat level ${difficulty.combatLevel}. Protect the Void Knight and destroy the four portals. A victory with enough contribution earns ${difficulty.points} commendations. You keep your equipment if you die.")
            }
        }
        npcOperate("Leave", "squire_instance_pest_control") { (target) ->
            val match = pestMatch() ?: return@npcOperate
            if (target.pestMatch() !== match) return@npcOperate
            match.leave(this)
        }
        npcOperate("Talk-to", "squire_instance_pest_control") { (target) ->
            val match = pestMatch() ?: return@npcOperate
            if (target.pestMatch() !== match) return@npcOperate
            choice("Leave this game without a reward?") {
                option("Yes, take me back.") { match.leave(this) }
                option("No, I'll stay.")
            }
        }
        npcOperate("Travel", "squire_port_sarim") { tele(2659, 2676) }
        npcOperate("Talk-to", "squire_port_sarim") {
            choice("Sail to the Void Knights' Outpost?") {
                option("Yes, please.") { tele(2659, 2676) }
                option("Not now.")
            }
        }
        npcOperate("Travel", "squire_ship_island_pest_control") { tele(3041, 3202) }
        npcOperate("Talk-to", "squire_ship_island_pest_control") {
            choice("Sail to Port Sarim?") {
                option("Yes, please.") { tele(3041, 3202) }
                option("Not now.")
            }
        }
        worldTimerStart("pest_control") { 1 }
        worldTimerTick("pest_control") {
            pulse()
            if (waiting.isEmpty() && matches.isEmpty()) -1 else 1
        }
        worldTimerStop("pest_control") { shutdown -> if (shutdown) reset() }
        playerDespawn {
            if (this in waiting) leaveQueue(this)
            pestMatch()?.leave(this)
        }
        moved {
            if (this in waiting && queued[this]?.onBoat(tile) != true) leaveQueue(this, teleport = false)
            val match = pestMatch()
            if (match != null && !match.contains(tile)) match.leave(this, teleport = false)
        }
        playerSpawn {
            if (!contains("pest_control_games") && get("pest_control_points", 0) > 0) {
                set("pest_control_games", 1)
            }
            if (get("pest_control_return", false)) {
                val dock = pestLander().dock
                clear("pest_control_return")
                clear("pest_control_lander")
                clear("pest_match")
                clear("pest_damage")
                clear("instance_offset")
                clear("in_multi_combat")
                tele(dock)
                close("pest_control_waiting")
                close("pest_control_playing")
            }
        }
        playerDeath { death ->
            if (pestRole != null) death.dropItems = false
            val match = pestMatch()
            if (match != null) {
                death.dropItems = false
                death.teleport = match.at(pestLanding)
                match.deaths[this] = death
            } else if (this in waiting) {
                death.dropItems = false
                death.teleport = pestLander().dock
                leaveQueue(this, teleport = false)
            }
        }
    }

    internal fun join(player: Player, difficulty: PestDifficulty = PestDifficulty.NOVICE): Boolean {
        if (player.dead || player in waiting || player.pestMatch() != null || player.instance() != null || !player.tile.within(difficulty.dock, 5)) return false
        if (player.combatLevel < difficulty.combatLevel) {
            player.message("You need a combat level of ${difficulty.combatLevel} to board the ${difficulty.id} lander.")
            return false
        }
        if (player.follower != null || player.pet != null) {
            player.message("Leave your companion behind before boarding the lander.")
            return false
        }
        if (queuedPlayers(difficulty).size >= 25) {
            player.message("The lander is full. Please wait for the next departure.")
            return false
        }
        player.closeInterfaces()
        waiting.add(player)
        queued[player] = difficulty
        player["pest_control_return"] = true
        player["pest_control_lander"] = difficulty.ordinal
        player.tele(difficulty.boat)
        player.open("pest_control_waiting")
        if (countdowns[difficulty.ordinal] < 0) countdowns[difficulty.ordinal] = waitTicks()
        updateLander()
        World.timers.start("pest_control")
        return true
    }

    internal fun leaveQueue(player: Player, teleport: Boolean = true) {
        val difficulty = queued.remove(player) ?: PestDifficulty.entries.firstOrNull { it.onBoat(player.tile) } ?: player.pestLander()
        waiting.remove(player)
        player.clear("pest_control_return")
        player.clear("pest_control_lander")
        player.close("pest_control_waiting")
        if (teleport) player.tele(difficulty.dock)
        if (queuedPlayers(difficulty).isEmpty()) countdowns[difficulty.ordinal] = -1
        updateLander()
    }

    private fun waitTicks() = Settings["pestControl.waitTicks", 100].coerceAtLeast(1)
    private fun minimumPlayers() = Settings["pestControl.minimumPlayers", 1].coerceIn(1, 25)
    private fun queuedPlayers(difficulty: PestDifficulty) = waiting.filter { queued[it] == difficulty }

    internal fun pulse() {
        for (player in waiting.toList()) if (Players.indexed(player.index) !== player || queued[player]?.onBoat(player.tile) != true || player.dead) leaveQueue(player, teleport = false)
        for (difficulty in PestDifficulty.entries) {
            val humans = queuedPlayers(difficulty).size
            crews.prepare(difficulty, humans)
            if (humans >= minimumPlayers()) {
                countdowns[difficulty.ordinal]--
                if (countdowns[difficulty.ordinal] <= 0 || humans == 25) launch(difficulty)
            } else {
                countdowns[difficulty.ordinal] = if (humans == 0) -1 else waitTicks()
            }
        }
        matches.toList().forEach { it.pulse() }
        matches.removeAll { !it.active }
        updateLander()
    }

    internal fun launch(difficulty: PestDifficulty = PestDifficulty.NOVICE): PestMatch? {
        val queue = queuedPlayers(difficulty)
        if (queue.size < minimumPlayers()) return null
        val players = queue.take(25)
        crews.prepare(difficulty, players.size)
        val match = PestMatch(players + crews.board(difficulty), difficulty)
        matches.add(match)
        waiting.removeAll(players.toSet())
        players.forEach { queued.remove(it) }
        countdowns[difficulty.ordinal] = if (queuedPlayers(difficulty).isEmpty()) -1 else waitTicks()
        match.start()
        return match
    }

    private fun updateLander() {
        for (player in waiting) {
            val difficulty = queued[player] ?: continue
            player.interfaces.sendText("pest_control_waiting", "departure", "Next Departure: ${((countdowns[difficulty.ordinal].coerceAtLeast(0) * 0.6).toInt())} sec")
            player.interfaces.sendText("pest_control_waiting", "players", "Players Ready: ${queuedPlayers(difficulty).size} (+${crews.count(difficulty)} crew)")
            player.interfaces.sendText("pest_control_waiting", "required", "Need ${minimumPlayers()} to 25 players")
            player.interfaces.sendText("pest_control_waiting", "points", "Points: ${player["pest_control_points", 0]}")
        }
    }

    internal fun reset() {
        matches.toList().forEach { it.finish(false, interrupted = true) }
        matches.clear()
        crews.reset()
        waiting.toList().forEach { leaveQueue(it) }
        queued.clear()
        countdowns.fill(-1)
    }
}

internal class PestMatch(entrants: List<Player>, val difficulty: PestDifficulty = PestDifficulty.NOVICE) {
    val players = entrants.toMutableSet()
    val actors = mutableListOf<NPC>()
    val deaths = mutableMapOf<Player, Death.OnDeath>()
    val region = Instances.small()
    private val offset = region.tile.delta(pestIsland.tile)
    val portals = mutableListOf<NPC>()
    val destroyed = mutableSetOf<Int>()
    val shieldOrder = listOf(listOf(0, 1, 3, 2), listOf(0, 3, 1, 2), listOf(1, 0, 2, 3), listOf(1, 2, 0, 3), listOf(2, 3, 0, 1), listOf(2, 0, 3, 1)).random(random)
    lateinit var knight: NPC
    var active = true
    var ticks = 0
    val barriers = mutableListOf<PestBarrier>()
    fun at(tile: Tile) = tile.add(offset)
    fun local(tile: Tile) = tile.minus(offset)
    fun contains(tile: Tile) = tile.region == region && tile.level in 0..3

    fun start() {
        get<DynamicZones>().copy(pestIsland, region, 4)
        knight = spawn("void_knight", pestKnight)
        knight["npc_combat_target"] = true
        for (i in pestColours.indices) {
            val portal = spawn("portal_${pestColours[i]}", pestPortals[i])
            portal["pest_portal"] = i
            portal["pest_shield"] = true
            portals.add(portal)
        }
        spawn("squire_instance_pest_control", Tile(2656, 2605))
        loadBarriers()
        for (player in players) {
            player["pest_match"] = this
            player["pest_damage"] = 0
            player["pest_control_return"] = true
            player["pest_control_lander"] = difficulty.ordinal
            player["instance_offset"] = offset.id
            player["in_multi_combat"] = true
            player.closeInterfaces()
            player.close("pest_control_waiting")
            player.tele(at(pestLanding))
            player.open("pest_control_playing")
            player.message("Protect the Void Knight! Destroy all four portals or defend him for 20 minutes. Deal at least 500 damage to earn rewards.")
        }
        update()
    }

    fun spawn(id: String, tile: Tile): NPC = NPCs.add(id, at(tile), ticks = -1).also {
        it.huntMode = ""
        it["pest_match"] = this
        it["in_multi_combat"] = true
        actors.add(it)
    }

    fun pulse() {
        if (!active) return
        for (player in players.toList()) if (Players.indexed(player.index) !== player || !contains(player.tile)) leave(player, teleport = false)
        if (!active) return
        if (players.none { it.pestRole == null }) {
            finish(false, interrupted = true)
            return
        }
        ticks++
        if (knight.dead || knight.levels.get(Skill.Constitution) <= 0) {
            finish(false)
            return
        }
        for (i in shieldOrder.indices) {
            if (ticks == 25 + i * 50) {
                val portal = portals[shieldOrder[i]]
                portal["pest_shield"] = false
                portal.clearTransform()
                players.forEach { it.message("The ${pestColours[shieldOrder[i]]} portal's shield has fallen!") }
            }
        }
        if (destroyed.size == 4 || ticks >= 2000) {
            finish(true)
            return
        }
        if (ticks % 20 == 0) spawnWave()
        if (ticks % 5 == 0) {
            actPests()
            commandCrew()
            update()
        }
    }

    fun spawnWave() {
        if (!active) return
        val humans = players.count { it.pestRole == null }
        val limit = (8 + humans * 8 + (players.size - humans) * 4).coerceAtMost(100)
        var alive = actors.count { it.id in pestTypes && !it.dead }
        for (i in portals.indices) {
            if (i in destroyed) continue
            repeat((humans / 5 + 1).coerceAtMost(5)) {
                if (alive >= limit) return
                spawn(difficulty.randomPest(), pestSpawns[i])["pest_origin"] = i
                alive++
            }
        }
    }

    fun portalDied(portal: NPC) {
        val index = portal.get<Int>("pest_portal") ?: return
        if (!active || !destroyed.add(index)) return
        knight.levels.restore(Skill.Constitution, 500)
        players.forEach { it.message("The ${pestColours[index]} portal has been destroyed. The Void Knight regains strength!") }
        actors.filter { it.id.startsWith("spinner_level_") && !it.dead && it.tile.within(portal.tile, 5) }.forEach { explode(it, poison = true) }
    }

    fun leave(player: Player, teleport: Boolean = true) {
        if (!players.remove(player)) return
        deaths.remove(player)?.teleport = difficulty.dock
        player.clear("pest_match")
        player.clear("pest_damage")
        player.clear("pest_control_return")
        player.clear("pest_control_lander")
        player.clear("instance_offset")
        player.clear("in_multi_combat")
        while (player.queue.clear("hit")) {}
        if (player.poisonDamage > 0) player.curePoison()
        player.close("pest_control_playing")
        player.target = null
        if (!player.dead) player.mode = EmptyMode
        if (teleport) player.tele(difficulty.dock)
        if (active && player.pestRole != null && !player.get("logged_out", false)) {
            World.queue("retire_pest_crew_${player.index}", 1) { player.retireCrew() }
        }
        if (players.isEmpty() && active) finish(false)
    }

    fun finish(won: Boolean, interrupted: Boolean = false) {
        if (!active) return
        active = false
        for (player in players.toList()) {
            if (player.pestRole != null) {
                leave(player, teleport = false)
                player.retireCrew()
                continue
            }
            if (!interrupted) player.inc("pest_control_games")
            val eligible = won && player.get("pest_damage", 0) >= 500
            if (eligible) {
                val before = player["pest_control_points", 0]
                player["pest_control_points"] = (before + difficulty.points).coerceAtMost(PEST_POINT_CAP)
                player.message("Victory! You earn ${(PEST_POINT_CAP - before).coerceIn(0, difficulty.points)} commendations and ${player.combatLevel * 10} coins.")
            } else {
                player.message(if (won) "Victory, but you did not contribute enough damage or repairs to earn a reward." else "The Void Knight has fallen, or your team has left the island. No reward this time.")
            }
            player.jingle(if (won) "pest_control_win" else "pest_control_defeat")
            leave(player)
            if (eligible) player.addOrDrop("coins", player.combatLevel * 10)
            if (!player.dead) player.levels.clear()
        }
        actors.forEach {
            it.dead = true
            it.queue.clear()
            it.softTimers.stopAll()
        }
        for (level in 0..3) NPCs.clear(region.toLevel(level))
        for (zone in region.toCuboid().toZones()) {
            for (item in FloorItems.at(zone).flatten().toList()) FloorItems.remove(item)
            GameObjects.clear(zone)
            Collisions.clear(zone)
        }
        get<DynamicZones>().clear(region)
        Instances.free(region)
    }

    fun update() {
        for (player in players) {
            if (player.pestRole != null) continue
            if (!player.interfaces.contains("pest_control_playing")) player.open("pest_control_playing")
            player.interfaces.sendText("pest_control_playing", "time", "${((2000 - ticks).coerceAtLeast(0) * 0.6).toInt() / 60} Min")
            player.interfaces.sendText("pest_control_playing", "knight", (knight.levels.get(Skill.Constitution) / 10).toString())
            player.interfaces.sendText("pest_control_playing", "damage", player.get("pest_damage", 0).toString())
            for (i in portals.indices) {
                player.interfaces.sendText("pest_control_playing", "${pestColours[i]}_health", if (i in destroyed) "0" else (portals[i].levels.get(Skill.Constitution) / 10).toString())
                player.interfaces.sendVisibility("pest_control_playing", "${pestColours[i]}_shield", portals[i].get("pest_shield", true))
            }
        }
    }
}
