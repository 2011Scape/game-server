package content.minigame.pest_control

import FakeRandom
import WorldTest
import content.activity.event.random.RandomEvents
import content.area.wilderness.inMultiCombat
import content.entity.combat.Combat
import content.entity.combat.hit.directHit
import content.entity.combat.hit.hit
import content.entity.effect.clearTransform
import content.entity.effect.toxin.poison
import content.entity.effect.toxin.poisonDamage
import interfaceOption
import itemOption
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.rsmod.game.pathfinder.StepValidator
import org.rsmod.game.pathfinder.collision.CollisionStrategies
import world.gregs.voidps.engine.client.instruction.handle.interactObject
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.SaveQueue
import world.gregs.voidps.engine.data.file.FileStorage
import world.gregs.voidps.engine.entity.Despawn
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.entity.obj.GameObjects
import world.gregs.voidps.engine.get
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.clear
import world.gregs.voidps.engine.inv.equipment
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.map.collision.canFit
import world.gregs.voidps.engine.map.instance.Instances
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.setRandom
import java.io.File
import kotlin.random.Random

class PestControlTest : WorldTest() {
    @TempDir lateinit var directory: File
    private val control get() = scripts.filterIsInstance<PestControl>().single()

    @AfterEach
    fun cleanupMatches() = control.reset()

    private fun ready(name: String = "pest-player"): Player = createPlayer(pestDock, name).also { player ->
        for (skill in pestRewardSkills) player.experience.set(skill, Level.experience(60))
        player.levels.clear()
    }

    private fun start(player: Player): PestMatch {
        assertTrue(control.join(player))
        val match = control.launch()!!
        tick(2)
        return match
    }

    private fun shop(player: Player) {
        player.tele(Tile(2654, 2663))
        val knight = createNPC("void_knight_pest_control", player.tile.addX(1))
        player["pest_reward_npc"] = knight
        player.open("pest_control_rewards")
    }

    @Test
    fun `native novice gangplank joins and leaves the lander`() {
        val player = ready()
        val plank = GameObjects.find(Tile(2658, 2639), "pest_novice_gangplank")
        player.interactObject(plank, "Cross")
        tick(5)
        assertTrue(player in control.waiting)
        assertTrue(player.onPestBoat())
        assertTrue(player.interfaces.contains("pest_control_waiting"))
        player.interactObject(plank, "Cross")
        tick(5)
        assertFalse(player in control.waiting)
        assertEquals(pestDock, player.tile)
    }

    @Test
    fun `low combat and remote players cannot join`() {
        assertFalse(control.join(createPlayer(pestDock)))
        val player = ready()
        player.tele(Tile(3200, 3200))
        assertFalse(control.join(player))
    }

    @Test
    fun `configured lander timer starts a solo novice game`() {
        settings["pestControl.waitTicks"] = "5"
        val player = ready()
        assertTrue(control.join(player))
        tick(8)
        assertNotNull(player.pestMatch())
        assertFalse(player in control.waiting)
    }

    @Test
    fun `minimum team size holds departure until enough players board`() {
        settings["pestControl.minimumPlayers"] = "2"
        settings["pestControl.waitTicks"] = "3"
        val one = ready("one")
        control.join(one)
        tick(6)
        assertNull(one.pestMatch())
        val two = ready("two")
        control.join(two)
        tick(5)
        assertSame(one.pestMatch(), two.pestMatch())
        assertNotNull(two.pestMatch())
    }

    @Test
    fun `two groups get different islands and cannot damage each others actors`() {
        val one = ready("one")
        val first = start(one)
        val two = ready("two")
        val second = start(two)
        assertNotEquals(first.region, second.region)
        val pest = first.spawn("shifter_level_38", pestLanding)
        tick(1)
        val health = pest.levels.get(Skill.Constitution)
        pest.directHit(two, 100)
        assertEquals(health, pest.levels.get(Skill.Constitution))
        assertEquals(0, two["pest_damage", 0])
    }

    @Test
    fun `shield blocks damage and participation until it drops`() {
        val player = ready()
        val match = start(player)
        val portal = match.portals[match.shieldOrder.first()]
        val health = portal.levels.get(Skill.Constitution)
        portal.directHit(player, 100)
        assertEquals(health, portal.levels.get(Skill.Constitution))
        assertEquals(0, player["pest_damage", 0])
        while (match.ticks < 25) tick()
        assertFalse(portal["pest_shield", true])
        portal.directHit(player, 100)
        assertEquals(health - 100, portal.levels.get(Skill.Constitution))
        assertEquals(100, player["pest_damage", 0])
    }

    @Test
    fun `friendly attacks cannot harm the void knight`() {
        val player = ready()
        val match = start(player)
        match.knight.directHit(player, 500)
        assertEquals(2000, match.knight.levels.get(Skill.Constitution))
        assertEquals(0, player["pest_damage", 0])
    }

    @Test
    fun `all portal kills win once and return players with points and coins`() {
        val player = ready()
        val match = start(player)
        player["insta_kill"] = true
        for (portal in match.portals) {
            portal["pest_shield"] = false
            portal.clearTransform()
            portal.directHit(player, 10000)
        }
        tick(3)
        assertFalse(match.active)
        assertEquals(2, player["pest_control_points", 0])
        assertEquals(1, player["pest_control_games", 0])
        assertTrue(player.inventory.count("coins") > 0)
        assertEquals(pestDock, player.tile)
        assertFalse(Instances.isInstance(match.region))
        match.finish(true)
        assertEquals(2, player["pest_control_points", 0])
        assertEquals(1, player["pest_control_games", 0])
    }

    @Test
    fun `overkill counts only remaining health and AFK players get nothing`() {
        val active = ready("active")
        val idle = ready("idle")
        control.join(active)
        control.join(idle)
        val match = control.launch()!!
        tick(2)
        val pest = match.spawn("splatter_level_22", Tile(2640, 2609))
        tick(1)
        active["insta_kill"] = true
        pest.directHit(active, 50000)
        assertEquals(130, active["pest_damage", 0])
        active["pest_damage"] = 500
        match.finish(true)
        assertEquals(2, active["pest_control_points", 0])
        assertEquals(0, idle["pest_control_points", 0])
        assertEquals(0, idle.inventory.count("coins"))
    }

    @Test
    fun `death of knight loses the match without rewards`() {
        val player = ready()
        val match = start(player)
        player["pest_damage"] = 1000
        val pest = match.spawn("shifter_level_38", pestKnight.addX(-1))
        tick(1)
        pest["insta_kill"] = true
        match.knight.directHit(pest, 10000)
        tick(3)
        assertFalse(match.active)
        assertEquals(0, player["pest_control_points", 0])
        assertEquals(1, player["pest_control_games", 0])
        assertEquals(pestDock, player.tile)
    }

    @Test
    fun `twenty minute defence wins and commendations cap at 500`() {
        val player = ready()
        val match = start(player)
        player["pest_damage"] = 500
        player["pest_control_points"] = 499
        match.ticks = 1999
        match.pulse()
        assertEquals(500, player["pest_control_points", 0])
        assertFalse(match.active)
    }

    @Test
    fun `safe death respawns on the island and keeps inventory`() {
        val player = ready()
        val match = start(player)
        player.inventory.add("rune_scimitar")
        player.directHit(player, 10000)
        tick(12)
        assertFalse(player.dead)
        assertSame(match, player.pestMatch())
        assertTrue(match.contains(player.tile))
        assertTrue(player.inventory.contains("rune_scimitar"))
    }

    @Test
    fun `ending a match during death redirects the pending respawn safely`() {
        val player = ready()
        val match = start(player)
        player.inventory.add("rune_scimitar")
        player.directHit(player, 10000)
        match.finish(false)
        tick(12)
        assertEquals(pestDock, player.tile)
        assertNull(player.pestMatch())
        assertTrue(player.inventory.contains("rune_scimitar"))
        assertFalse(Instances.isInstance(match.region))
    }

    @Test
    fun `leaving last participant cleans all spawned actors and the island`() {
        val player = ready()
        val match = start(player)
        match.spawnWave()
        tick(1)
        match.leave(player)
        tick(3)
        assertFalse(match.active)
        assertTrue(NPCs.at(match.region.toLevel(0)).isEmpty())
        assertFalse(Instances.isInstance(match.region))
    }

    @Test
    fun `spinner restores a damaged portal`() {
        val player = ready()
        val match = start(player)
        val portal = match.portals[0]
        portal.levels.set(Skill.Constitution, 2000)
        val spinner = match.spawn("spinner_level_36", pestPortals[0].addY(3))
        spinner["pest_origin"] = 0
        tick(1)
        match.actPests()
        assertTrue(portal.levels.get(Skill.Constitution) > 2000)
    }

    @Test
    fun `repair consumes logs and cannot farm an undamaged barrier`() {
        val player = ready()
        val match = start(player)
        assertTrue(match.barriers.isNotEmpty())
        val barrier = match.barriers.first { it.health == 200 }
        player.tele(barrier.original.tile.addX(1))
        player.inventory.add("hammer")
        player.inventory.add("logs", 2)
        assertFalse(match.repair(player, barrier.current))
        barrier.damage(100)
        assertTrue(match.repair(player, barrier.current))
        assertEquals(1, player.inventory.count("logs"))
        assertEquals(50, player["pest_damage", 0])
        assertFalse(match.repair(player, barrier.current))
    }

    @Test
    fun `reward confirmation purchases once even if repeated`() {
        val player = ready()
        shop(player)
        player["pest_control_points"] = 500
        player.interfaceOption("pest_control_rewards", "reward_42", "Void Knight Top")
        player.interfaceOption("pest_control_rewards", "confirm", "Confirm")
        player.interfaceOption("pest_control_rewards", "confirm", "Confirm")
        assertEquals(1, player.inventory.count("void_knight_top"))
        assertEquals(250, player["pest_control_points", 0])
    }

    @Test
    fun `reward checks currency requirements space and distance`() {
        val player = ready()
        shop(player)
        assertFalse(player.buyPestReward(42))
        player["pest_control_points"] = 500
        player.experience.set(Skill.Prayer, 0.0)
        assertFalse(player.buyPestReward(42))
        player.experience.set(Skill.Prayer, Level.experience(60))
        player.inventory.add("shark", 28)
        assertFalse(player.buyPestReward(42))
        assertEquals(500, player["pest_control_points", 0])
        player.inventory.clear()
        player.tele(Tile(3200, 3200))
        assertFalse(player.buyPestReward(42))
        assertEquals(500, player["pest_control_points", 0])
    }

    @Test
    fun `experience reward spends selected batch and grants the bonus`() {
        settings["world.experienceRate"] = "1.0"
        val player = ready()
        shop(player)
        player["pest_control_points"] = 100
        val before = player.experience.get(Skill.Attack)
        assertTrue(player.buyPestReward(56))
        assertEquals(0, player["pest_control_points", 0])
        assertEquals(before + pestExperience(60, Skill.Attack, 100), player.experience.get(Skill.Attack), 0.01)
    }

    @Test
    fun `all equipment charms and packs resolve to real usable items`() {
        val player = ready()
        shop(player)
        for ((component, reward) in pestRewards.filterValues { it.skill == null }) {
            player.inventory.clear()
            player["pest_control_points"] = 500
            assertTrue(player.buyPestReward(component), "reward $component")
            assertEquals(500 - reward.cost, player["pest_control_points", 0])
            assertFalse(player.inventory.isEmpty())
        }
    }

    @Test
    fun `pests naturally attack the knight through the combat engine`() {
        settings["pestControl.crewEnabled"] = "false" // Exercise the enemy route without allied bots intercepting it.
        setRandom(Random(123))
        val player = ready()
        val match = start(player)
        val shifter = match.spawn("shifter_level_38", pestKnight.addX(-1))
        val torcher = match.spawn("torcher_level_33", pestKnight.addX(5))
        tick(2)
        match.actPests()
        tick(35)
        assertTrue(shifter.get("max_hit", 0) > 0, "Shifter must make a normal swing")
        assertTrue(torcher.get("max_hit", 0) > 0, "Torcher must make a ranged magic attack")
        assertTrue(match.knight.levels.get(Skill.Constitution) < 2000)
        assertTrue(player.inMultiCombat)
        match.leave(player)
        assertFalse(player.inMultiCombat)
    }

    @Test
    fun `player can attack an unshielded portal with normal combat`() {
        setRandom(Random(123))
        val player = ready()
        val match = start(player)
        val portal = match.portals[0]
        portal["pest_shield"] = false
        portal.clearTransform()
        player.tele(portal.tile.addX(-1))
        setRandom(object : FakeRandom() {
            override fun nextInt(from: Int, until: Int) = until - 1
        })
        Combat.combat(player, portal)
        tick(30)
        assertTrue(portal.levels.get(Skill.Constitution) < 2500, "A successful normal hit should damage the unshielded portal")
        assertTrue(player.get("pest_damage", 0) > 0)
    }

    @Test
    fun `gate opens both leaves and can be closed again`() {
        val player = ready()
        val match = start(player)
        val gate = match.barriers.first { it.gate }
        val pair = match.barriers.filter { it.gate && it.original.tile.within(gate.original.tile, 1) }
        assertEquals(2, pair.size)
        player.tele(gate.original.tile.addX(-1))
        player.interactObject(gate.current, "Open")
        tick(3)
        assertTrue(pair.all { it.open })
        player.interactObject(gate.current, "Close")
        tick(3)
        assertTrue(pair.none { it.open })
    }

    @Test
    fun `watchtower ladders allow a round trip on the native bridge plane`() {
        val player = ready()
        val match = start(player)
        for (local in listOf(Tile(2644, 2601), Tile(2647, 2586), Tile(2666, 2586), Tile(2669, 2601))) {
            val ladder = GameObjects.find(match.at(local), "pest_tower_ladder")
            val near = if (ladder.rotation == 0) ladder.tile.addY(1) else ladder.tile.addX(1)
            val far = if (ladder.rotation == 0) ladder.tile.addY(-1) else ladder.tile.addX(-1)
            player.tele(near)
            player.interactObject(ladder, "Climb")
            tick(3)
            assertEquals(far, player.tile)
            player.interactObject(ladder, "Climb")
            tick(3)
            assertEquals(near, player.tile)
        }
    }

    @Test
    fun `one player logging out leaves the other players match running`() {
        val one = ready("one")
        val two = ready("two")
        control.join(one)
        control.join(two)
        val match = control.launch()!!
        tick(2)
        Despawn.player(one)
        Players.remove(one)
        tick(3)
        assertTrue(match.active)
        assertSame(match, two.pestMatch())
        assertTrue(Instances.isInstance(match.region))
        assertEquals(pestDock, one.tile)
        Despawn.player(two)
        Players.remove(two)
        tick(3)
        assertFalse(match.active)
        assertEquals(0, one["pest_control_games", 0])
        assertEquals(0, two["pest_control_games", 0])
        assertTrue(NPCs.at(match.region.toLevel(0)).isEmpty())
    }

    @Test
    fun `server shutdown does not count an interrupted round for the quest`() {
        val player = ready()
        start(player)
        control.reset()
        assertEquals(0, player["pest_control_games", 0])
    }

    @Test
    fun `saved match recovers at dock and preserves points after restart`() {
        val player = ready()
        start(player)
        player["pest_control_points"] = 123
        player.inventory.add("rune_scimitar")
        settings["storage.disabled"] = false
        val storage = FileStorage(directory)
        runBlocking {
            val queue = SaveQueue(storage, scope = this)
            queue.save(player)
            queue.run()
            queue.awaitInFlight()
        }
        val save = storage.load(player.accountName)!!
        Despawn.player(player)
        Players.remove(player)
        tick(3)
        val restored = save.toPlayer()
        val accounts = get<AccountManager>()
        accounts.setup(restored, null, 0, viewport = true)
        assertTrue(accounts.index(restored))
        accounts.spawn(restored, null)
        tick(2)
        assertEquals(pestDock, restored.tile)
        assertEquals(123, restored["pest_control_points", 0])
        assertEquals(1, restored["pest_control_games", 0])
        assertFalse(restored.contains("pest_match"))
        assertFalse(restored.contains("instance_offset"))
        assertTrue(restored.inventory.contains("rune_scimitar"))
    }

    @Test
    fun `same tick cancellation removes pending NPC spawns`() {
        val player = ready()
        control.join(player)
        val match = control.launch()!!
        match.spawnWave()
        match.leave(player)
        tick(4)
        assertTrue(NPCs.at(match.region.toLevel(0)).isEmpty())
        assertFalse(Instances.isInstance(match.region))
    }

    @Test
    fun `partial space for a reward pack rolls back all items and points`() {
        val player = ready()
        shop(player)
        player["pest_control_points"] = 500
        player.inventory.add("shark", 26)
        assertFalse(player.buyPestReward(45))
        assertEquals(500, player["pest_control_points", 0])
        assertEquals(26, player.inventory.count("shark"))
        assertEquals(0, player.inventory.count("grimy_ranarr_noted"))
    }

    @Test
    fun `seal consumes one charge damages pests and cannot work outside a match`() {
        val player = ready()
        player.inventory.add("void_seal_8_8")
        player.itemOption("Rub", "void_seal_8_8")
        assertTrue(player.inventory.contains("void_seal_8_8"))
        val match = start(player)
        val pest = match.spawn("brawler_level_51", pestLanding.addX(1))
        tick(1)
        val health = pest.levels.get(Skill.Constitution)
        player.itemOption("Rub", "void_seal_8_8")
        assertTrue(player.inventory.contains("void_seal_7_7"))
        assertTrue(pest.levels.get(Skill.Constitution) < health)
        player.itemOption("Rub", "void_seal_7_7")
        assertTrue(player.inventory.contains("void_seal_7_7"))
    }

    @Test
    fun `cannon and familiar cannot be created in a match or consume their items`() {
        val player = ready()
        start(player)
        player["dwarf_cannon"] = "completed"
        for (part in listOf("cannon_base", "cannon_stand", "cannon_barrels", "cannon_furnace")) player.inventory.add(part)
        player.inventory.add("spirit_wolf_pouch")
        player.itemOption("Set-up", "cannon_base")
        player.itemOption("Summon", "spirit_wolf_pouch")
        tick(10)
        assertFalse(player.contains("cannon_tile"))
        assertTrue(player.inventory.contains("cannon_base"))
        assertTrue(player.inventory.contains("spirit_wolf_pouch"))
    }

    @Test
    fun `leaving cancels incoming hits and island poison`() {
        val one = ready("one")
        val two = ready("two")
        control.join(one)
        control.join(two)
        val match = control.launch()!!
        tick(2)
        val pest = match.spawn("torcher_level_33", pestLanding.addX(4))
        tick(1)
        pest.hit(one, damage = 100, delay = 64)
        pest.poison(one, 20)
        val health = one.levels.get(Skill.Constitution)
        match.leave(one)
        tick(5)
        assertEquals(health, one.levels.get(Skill.Constitution))
        assertEquals(0, one.poisonDamage)
        assertTrue(match.active)
    }

    @Test
    fun `destroyed and rebuilt barricade replaces collision and object layers`() {
        val player = ready()
        val match = start(player)
        val barrier = match.barriers.first { it.original.intId == 14227 }
        barrier.damage(200)
        assertEquals(0, barrier.health)
        assertEquals(14230, barrier.current.intId)
        assertFalse(GameObjects.contains(barrier.original))
        repeat(4) { barrier.repair() }
        assertEquals(200, barrier.health)
        assertEquals(14224, barrier.current.intId)
        assertEquals(listOf(barrier.current), GameObjects.at(barrier.current.tile).filter { it.intId in 14224..14232 })
        barrier.damage(200)
        assertFalse(GameObjects.at(barrier.current.tile).any { it.intId == 14224 })
    }

    @Test
    fun `worn seal can be operated and its last charge disappears`() {
        val player = ready()
        start(player)
        player.equipment.set(2, "void_seal_1_1")
        player.itemOption("Operate", "void_seal_1_1", id = "worn_equipment", component = "amulet_slot", optionIndex = 1, inventory = "worn_equipment", slot = 2)
        assertTrue(player.equipment[2].isEmpty())
    }

    @Test
    fun `full lander starts 25 players and spawn waves stay bounded`() {
        settings["pestControl.waitTicks"] = "1000"
        val entrants = (0 until 25).map { ready("pest-$it").also { p -> assertTrue(control.join(p)) } }
        val match = control.launch()!!
        tick(2)
        assertEquals(25, match.players.size)
        assertTrue(entrants.all { it.pestMatch() === match })
        repeat(10) { match.spawnWave() }
        tick(2)
        assertEquals(100, match.actors.count { it.id in pestTypes && !it.dead })
        assertEquals(25, match.players.size)
    }

    @Test
    fun `random events cannot interrupt the lander or the shared match`() {
        settings["events.randomEvents.active"] = "true"
        val player = ready()
        assertTrue(control.join(player))
        player["random_event_cooldown"] = 1
        assertFalse(RandomEvents.roll(player, "beekeeper"))
        assertFalse(RandomEvents.start(player, "beekeeper"))
        val match = control.launch()!!
        tick(2)
        player["random_event_cooldown"] = 1
        assertFalse(RandomEvents.roll(player, "beekeeper"))
        assertFalse(RandomEvents.start(player, "beekeeper"))
        tick(10)
        assertSame(match, player.pestMatch())
        assertTrue(match.contains(player.tile))
        assertTrue(player.interfaces.contains("pest_control_playing"))
        match.leave(player)
        player.tele(Tile(3222, 3218))
        tick(2)
        player["random_event_cooldown"] = 1
        assertTrue(RandomEvents.roll(player, "beekeeper"))
    }

    @Test
    fun `every portal spawn fits both monster sizes and has a route off its approach`() {
        val match = start(ready())
        val steps = get<StepValidator>()
        val blue = match.portals[1]
        assertTrue(GameObjects.at(blue.tile).any { it.intId == 14328 })
        assertTrue(GameObjects.at(blue.tile.add(blue.size - 1, blue.size - 1)).any { it.intId == 14328 })
        val exits = listOf(Tile(2634, 2588), Tile(2674, 2589), Tile(2670, 2580), Tile(2646, 2580))
        for (i in pestSpawns.indices) {
            for (size in 1..2) {
                val spawn = match.at(pestSpawns[i])
                assertTrue(steps.canFit(spawn, CollisionStrategies.Normal, size, 0), "Spawn $i size $size")
                val queue = ArrayDeque<Tile>()
                val seen = hashSetOf(spawn.id)
                queue.add(spawn)
                var reached = false
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    if (at == match.at(exits[i])) {
                        reached = true
                        break
                    }
                    for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val next = at.add(dx, dy)
                        if (!next.within(spawn, 12) || next.id in seen) continue
                        if (steps.canTravel(at.level, at.x, at.y, dx, dy, size, 0, CollisionStrategies.Normal)) {
                            seen.add(next.id)
                            queue.add(next)
                        }
                    }
                }
                assertTrue(reached, "Portal $i size $size must have an exit")
            }
        }
    }

    @Test
    fun `east portal brawler leaves the enclosure and attacks a player`() {
        settings["pestControl.crewEnabled"] = "false" // Exercise the enemy route without allied bots intercepting it.
        setRandom(Random(123))
        val player = ready()
        val match = start(player)
        player.tele(match.at(Tile(2674, 2589)))
        val brawler = match.spawn("brawler_level_51", pestSpawns[1])
        tick(18)
        assertTrue(match.local(brawler.tile).x < pestSpawns[1].x, "Brawler must walk west out of the portal approach: ${match.local(brawler.tile)}")
        assertTrue(brawler.get("max_hit", 0) > 0, "Brawler must reach and attack the player: ${match.local(brawler.tile)} -> ${match.local(player.tile)}")
    }

    @Test
    fun `east portal torcher reaches and attacks the knight when defences are breached`() {
        settings["pestControl.crewEnabled"] = "false" // Exercise the enemy route without allied bots intercepting it.
        setRandom(Random(123))
        val match = start(ready())
        match.barriers.forEach { it.damage(200) }
        val torcher = match.spawn("torcher_level_33", pestSpawns[1])
        tick(60)
        assertTrue(match.local(torcher.tile).x < 2673, "Torcher must leave the portal area: ${match.local(torcher.tile)}")
        assertTrue(torcher.get("max_hit", 0) > 0, "Torcher must attack the knight through normal combat")
        assertTrue(match.knight.levels.get(Skill.Constitution) < 2000)
    }
}
