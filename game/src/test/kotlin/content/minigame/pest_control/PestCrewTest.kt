package content.minigame.pest_control

import WorldTest
import content.bot.isBot
import content.entity.combat.Combat
import content.entity.combat.target
import content.entity.effect.frozen
import content.skill.melee.weapon.weapon
import content.skill.prayer.PrayerConfigs.ACTIVE_PRAYERS
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import world.gregs.voidps.engine.client.update.player.PlayerUpdateTask
import world.gregs.voidps.engine.client.update.view.Viewport
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.combat.CombatApi
import world.gregs.voidps.engine.entity.character.mode.combat.CombatAttack
import world.gregs.voidps.engine.entity.character.move.running
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.inv.*
import world.gregs.voidps.network.client.DummyClient
import world.gregs.voidps.type.setRandom
import kotlin.random.Random

internal class PestCrewTest : WorldTest() {
    private val control get() = scripts.filterIsInstance<PestControl>().single()

    @BeforeEach fun enableCrew() {
        settings["pestControl.crewEnabled"] = "true"
    }

    @AfterEach fun cleanup() = control.reset()

    private fun board(difficulty: PestDifficulty): Player {
        val player = createPlayer(difficulty.dock)
        for (skill in pestRewardSkills) {
            player.experience.set(
                skill,
                Level.experience(
                    when (difficulty) {
                        PestDifficulty.NOVICE -> 65
                        PestDifficulty.INTERMEDIATE -> 80
                        PestDifficulty.VETERAN -> 90
                    },
                ),
            )
        }
        player.levels.clear()
        player.equipment.set(0, "rune_full_helm")
        player.equipment.set(4, "rune_platebody")
        player.equipment.set(7, "rune_platelegs")
        player.equipment.set(3, "dragon_scimitar")
        player.running = true
        assertTrue(control.join(player, difficulty))
        tick(2)
        return player
    }

    @ParameterizedTest
    @EnumSource(PestDifficulty::class)
    fun `crew boards the right boat in its loadout then leaves with the last human`(difficulty: PestDifficulty) {
        val player = board(difficulty)
        val crew = Players.filter { it.pestRole != null }
        assertEquals(difficulty.roles, crew.map { it.pestRole })
        assertEquals(crew.size + 1, (crew + player).map { it.tile }.distinct().size)
        assertTrue(crew.all { it.isBot && it.client is DummyClient && difficulty.onBoat(it.tile) })
        val weapons = when (difficulty) {
            PestDifficulty.NOVICE -> listOf("dragon_scimitar", "dragon_longsword")
            PestDifficulty.INTERMEDIATE -> listOf("dharoks_greataxe", "magic_shortbow", "magic_shortbow")
            PestDifficulty.VETERAN -> listOf("guthans_warspear", "ahrims_staff", "karils_crossbow", "ahrims_staff")
        }
        assertEquals(weapons, crew.map { it.weapon.id })
        val uniforms = when (difficulty) {
            PestDifficulty.NOVICE -> listOf("rune_platebody", "rune_platebody")
            PestDifficulty.INTERMEDIATE -> listOf("dharoks_platebody", "black_dragonhide_body", "black_dragonhide_body")
            PestDifficulty.VETERAN -> listOf("guthans_platebody", "ahrims_robe_top", "karils_top", "ahrims_robe_top")
        }
        assertEquals(uniforms, crew.map { it.equipment[4].id })
        for (bot in crew) {
            assertEquals(difficulty.crewLevel, bot.levels.get(Skill.Defence))
            assertEquals(difficulty.crewLevel, bot.levels.get(Skill.Ranged))
            assertEquals(difficulty.crewLevel * 10, bot.levels.get(Skill.Constitution))
            if (bot.weapon.id == "magic_shortbow") assertEquals("rune_arrow", bot.equipment[13].id)
            if (bot.weapon.id == "karils_crossbow") assertEquals("bolt_rack", bot.equipment[13].id)
        }
        val accessories = when (difficulty) {
            PestDifficulty.NOVICE -> listOf(
                listOf("cape_red", "culinaromancers_gloves_8", "rune_boots"),
                listOf("cape_blue", "culinaromancers_gloves_8", "rune_boots"),
            )
            PestDifficulty.INTERMEDIATE -> listOf(
                listOf("obsidian_cape", "culinaromancers_gloves_10", "dragon_boots"),
                listOf("avas_accumulator", "black_dragonhide_vambraces", "snakeskin_boots"),
                listOf("avas_accumulator", "black_dragonhide_vambraces", "snakeskin_boots"),
            )
            PestDifficulty.VETERAN -> listOf(
                listOf("fire_cape", "culinaromancers_gloves_10", "dragon_boots"),
                listOf("saradomin_cape", "infinity_gloves", "infinity_boots"),
                listOf("avas_accumulator", "black_dragonhide_vambraces", "ranger_boots"),
                listOf("zamorak_cape", "infinity_gloves", "infinity_boots"),
            )
        }
        for ((bot, expected) in crew.zip(accessories)) {
            assertEquals(if (difficulty == PestDifficulty.VETERAN) "amulet_of_fury" else "amulet_of_glory", bot.equipment[2].id)
            assertEquals(expected, listOf(1, 9, 10).map { bot.equipment[it].id })
        }
        val match = control.launch(difficulty)!!
        assertTrue(crew.all { it.pestMatch() === match })
        for (mage in crew.filter { it.weapon.id == "ahrims_staff" }) {
            assertEquals(94, mage.levels.get(Skill.Magic))
            assertEquals(90, mage.levels.get(Skill.Defence))
            assertEquals("ice_barrage", mage.get<String>("autocast_spell"))
        }
        match.leave(player)
        tick(3)
        assertFalse(match.active)
        assertTrue(crew.none { Players.indexed(it.index) === it })
    }

    @Test fun `leaving the boat retires the reserves and an empty boat never sails`() {
        val player = board(PestDifficulty.NOVICE)
        val crew = Players.filter { it.pestRole != null }
        assertEquals(2, crew.size)
        control.leaveQueue(player)
        tick(5)
        assertTrue(crew.none { Players.indexed(it.index) === it })
        assertTrue(control.matches.isEmpty())
    }

    @Test fun `two simultaneous teams on the same boat receive separate crews`() {
        val first = control.launchAfterBoarding(PestDifficulty.INTERMEDIATE)
        val second = control.launchAfterBoarding(PestDifficulty.INTERMEDIATE)
        assertEquals(3, first.players.count { it.pestRole != null })
        assertEquals(3, second.players.count { it.pestRole != null })
        assertTrue(first.players.intersect(second.players).isEmpty())
        first.finish(false)
        assertTrue(second.players.all { Players.indexed(it.index) === it })
    }

    private fun PestControl.launchAfterBoarding(difficulty: PestDifficulty): PestMatch {
        board(difficulty)
        return launch(difficulty)!!
    }

    @ParameterizedTest
    @EnumSource(PestDifficulty::class)
    fun `boarding and leaving repeatedly keeps player updates in sync`(difficulty: PestDifficulty) {
        val human = board(difficulty)
        val viewport = Viewport()
        viewport.players.addSelf(human)
        viewport.seen(human)
        human.viewport = viewport
        human.client = DummyClient()
        val task = PlayerUpdateTask()
        fun updateView() {
            task.snapshot()
            task.run(human)
            viewport.shift()
            viewport.players.update()
        }

        repeat(2) {
            val crew = Players.filter { it.pestRole != null }
            assertEquals(difficulty.roles.size, crew.size)
            updateView()
            assertEquals((crew + human).map { it.index }.toSet(), viewport.players.locals.take(viewport.players.localCount).toSet())

            control.leaveQueue(human)
            control.pulse()
            assertTrue(crew.all { Players.indexed(it.index) == null })
            repeat(2) { updateView() }
            assertEquals(listOf(human.index), viewport.players.locals.take(viewport.players.localCount))
            assertEquals(difficulty.dock, human.tile)

            assertTrue(control.join(human, difficulty))
            control.pulse()
        }
        updateView()
        assertEquals(difficulty.roles.size + 1, viewport.players.localCount)
    }

    @Test fun `a Veteran winner can receive player updates through crew retirement and return to the dock`() {
        val human = board(PestDifficulty.VETERAN)
        val match = control.launch(PestDifficulty.VETERAN)!!
        tick(2)
        val crew = match.players.filter { it.pestRole != null }
        assertEquals(4, crew.size)

        val viewport = Viewport()
        viewport.players.addSelf(human)
        viewport.seen(human)
        human.viewport = viewport
        human.client = DummyClient()
        val task = PlayerUpdateTask()
        fun updateView() {
            task.snapshot()
            task.run(human)
            viewport.shift()
            viewport.players.update()
        }
        fun visible() = viewport.players.locals.take(viewport.players.localCount).toSet()

        updateView()
        assertEquals((crew + human).map { it.index }.toSet(), visible())
        human["pest_damage"] = 500
        match.portals.forEach(match::portalDied)
        match.pulse()
        assertFalse(match.active)
        assertTrue(crew.all { Players.indexed(it.index) == null })

        repeat(2) { updateView() }
        assertEquals(setOf(human.index), visible())
        tick(2)
        assertEquals(PestDifficulty.VETERAN.dock, human.tile)
        assertEquals(PestDifficulty.VETERAN.points, human["pest_control_points", 0])
        assertEquals(1, human["pest_control_games", 0])
    }

    @Test fun `crew can be disabled independently of normal boarding`() {
        settings["pestControl.crewEnabled"] = "false"
        val human = board(PestDifficulty.VETERAN)
        assertEquals(setOf(human), control.launch(PestDifficulty.VETERAN)!!.players)
    }

    @Test fun `crew switches a single protection prayer to match its attacker`() {
        val match = control.launchAfterBoarding(PestDifficulty.VETERAN)
        val mage = match.players.single { it.pestRole == PestRole.KNIGHT }
        val torcher = match.spawn("torcher_level_79", mage.tile.addX(1))
        val defiler = match.spawn("defiler_level_80", mage.tile.addY(1))
        tick(1)
        torcher.target = mage
        match.commandCrew()
        assertEquals(listOf("protect_from_magic"), mage.get<List<String>>(ACTIVE_PRAYERS))
        assertTrue(mage.softTimers.contains("prayer_drain"))
        torcher.target = null
        defiler.target = mage
        match.commandCrew()
        assertEquals(listOf("protect_from_missiles"), mage.get<List<String>>(ACTIVE_PRAYERS))
        mage.levels.set(Skill.Prayer, 0)
        match.commandCrew()
        assertTrue(mage.get<List<String>>(ACTIVE_PRAYERS).isNullOrEmpty())
    }

    @Test fun `barrage freezes adjacent pests but spares friendly targets and handles a splash`() {
        val match = control.launchAfterBoarding(PestDifficulty.VETERAN)
        val mage = match.players.single { it.pestRole == PestRole.KNIGHT }
        val main = match.spawn("shifter_level_90", pestKnight.addX(1))
        val other = match.spawn("torcher_level_79", pestKnight.addY(1))
        tick(1)
        val health = other.levels.get(Skill.Constitution)
        val cast = CombatAttack(main, -1, "magic", mage.weapon, "ice_barrage", false, 0)
        CombatApi.attack(mage, cast)
        assertEquals(health, other.levels.get(Skill.Constitution))
        assertFalse(other.frozen)
        setRandom(Random(634))
        CombatApi.attack(mage, cast.copy(damage = 100))
        assertTrue(other.levels.get(Skill.Constitution) < health && other.frozen)
        assertFalse(match.knight.frozen)
        assertEquals(2000, match.knight.levels.get(Skill.Constitution))
    }

    companion object {
        @JvmStatic fun trials() = PestDifficulty.entries.flatMap { difficulty ->
            (634..638).flatMap { seed ->
                listOf(true, false).map { active -> Arguments.of(difficulty, active, seed) }
            }
        }.stream()
    }

    @ParameterizedTest
    @MethodSource("trials")
    fun `crew plays a full match with an active or idle human`(difficulty: PestDifficulty, active: Boolean, seed: Int) {
        setRandom(Random(seed))
        val human = board(difficulty)
        val match = control.launch(difficulty)!!
        val crew = match.players.filter { it.pestRole != null }
        val damage = mutableMapOf<Player, Int>()
        val prayed = mutableSetOf<Player>()
        var humanDamage = 0
        var elapsed = 0
        while (match.active && elapsed < 2010) {
            elapsed += 5
            if (active && !human.dead) {
                if (human.levels.get(Skill.Prayer) > 0 && !human.containsVarbit(ACTIVE_PRAYERS, "protect_from_melee")) human.addVarbit(ACTIVE_PRAYERS, "protect_from_melee")
                val portal = match.portals.filterIndexed { index, portal -> index !in match.destroyed && !portal.dead && !portal.get("pest_shield", true) }
                    .minByOrNull { it.levels.get(Skill.Constitution) }
                val spinner = match.actors.firstOrNull { !it.dead && it.id.startsWith("spinner_") && portal != null && it.tile.within(portal.tile, 6) && it.get<Int>("pest_origin") == portal.get<Int>("pest_portal") }
                val enemy = spinner ?: portal ?: match.actors.filter { !it.dead && it.id in pestTypes }.minByOrNull { human.tile.distanceTo(it.tile) }
                if (enemy != null) {
                    match.barriers.firstOrNull { it.gate && !it.open && human.tile.within(it.current.tile, 2) }?.let { match.setGate(human, it, true) }
                    if (human.target !== enemy || human.mode === EmptyMode) Combat.combat(human, enemy)
                }
            }
            for (bot in crew) {
                damage[bot] = maxOf(damage[bot] ?: 0, bot.get("pest_damage", 0))
                if (!bot.get<List<String>>(ACTIVE_PRAYERS).isNullOrEmpty()) prayed.add(bot)
            }
            humanDamage = maxOf(humanDamage, human.get("pest_damage", 0))
            tick(5)
        }
        val won = match.destroyed.size == 4 || match.ticks >= 2000 && match.knight.levels.get(Skill.Constitution) > 0
        val points = human.get("pest_control_points", 0)
        println("PEST CREW $difficulty active=$active seed=$seed won=$won ticks=${match.ticks} portals=${match.destroyed.size} points=$points humanDamage=$humanDamage")
        assertFalse(match.active)
        assertTrue(damage.values.any { it > 0 })
        assertTrue(prayed.isNotEmpty())
        assertEquals(1, human.get("pest_control_games", 0))
        if (!active || !won) assertEquals(0, points)
        if (won && humanDamage >= 500) assertEquals(difficulty.points, points)
        assertTrue(crew.all { Players.indexed(it.index) !== it && it.get("pest_control_points", 0) == 0 })
    }
}
