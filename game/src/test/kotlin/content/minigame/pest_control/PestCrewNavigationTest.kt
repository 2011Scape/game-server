package content.minigame.pest_control

import WorldTest
import content.entity.combat.target
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.type.setRandom
import kotlin.random.Random

internal class PestCrewNavigationTest : WorldTest() {
    private val control get() = scripts.filterIsInstance<PestControl>().single()

    @AfterEach
    fun cleanup() = control.reset()

    private fun launch(difficulty: PestDifficulty): PestMatch {
        settings["pestControl.crewEnabled"] = "true"
        setRandom(Random(634))
        val human = createPlayer(difficulty.dock)
        for (skill in pestRewardSkills) human.experience.set(skill, Level.experience(90))
        human.levels.clear()
        assertTrue(control.join(human, difficulty))
        tick(2)
        val match = control.launch(difficulty)!!
        World.timers.clear("pest_control")
        tick(2)
        return match
    }

    @ParameterizedTest
    @EnumSource(PestDifficulty::class)
    fun `nearby brawlers do not distract an attacker from a reachable portal`(difficulty: PestDifficulty) {
        val match = launch(difficulty)
        val attacker = match.players.single { it.pestRole == PestRole.PORTAL }
        val portal = match.portals[0]
        portal["pest_shield"] = false
        attacker.tele(match.at(pestSpawns[0].addX(5)))
        match.spawn("brawler_level_51", pestSpawns[0].addX(7))
        tick(1)

        match.commandCrew()

        assertSame(portal, attacker.target)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `Veteran spinner hunter damages pests on each portal approach without human gate help`(index: Int) {
        val match = launch(PestDifficulty.VETERAN)
        val attacker = match.players.single { it.pestRole == PestRole.PORTAL }
        match.leave(attacker, teleport = false)
        attacker.retireCrew()
        val mage = match.players.single { it.pestRole == PestRole.SPINNER }
        match.portals[index]["pest_shield"] = false
        val spinner = match.spawn("spinner_level_92", pestSpawns[index])
        spinner["pest_origin"] = index
        tick(1)
        repeat(30) {
            if (mage.get("pest_damage", 0) == 0) {
                match.commandCrew()
                tick(5)
            }
        }
        assertTrue(mage.get("pest_damage", 0) > 0, "Mage must reach and damage spinner $index")
    }

    companion object {
        @JvmStatic fun approaches() = PestDifficulty.entries.flatMap { difficulty ->
            (0..3).map { Arguments.of(difficulty, it) }
        }.stream()
    }

    @ParameterizedTest
    @MethodSource("approaches")
    fun `attacker opens its own gate and damages every portal from the landing`(difficulty: PestDifficulty, index: Int) {
        val match = launch(difficulty)
        val attacker = match.players.single { it.pestRole == PestRole.PORTAL }
        val portal = match.portals[index]
        portal["pest_shield"] = false
        val health = portal.levels.get(Skill.Constitution)
        assertTrue(match.barriers.filter { it.gate }.all { !it.open })
        repeat(30) {
            if (portal.levels.get(Skill.Constitution) == health) {
                match.commandCrew()
                tick(5)
            }
        }
        assertTrue(match.barriers.any { it.gate && it.open }, "Attacker must open a gate")
        assertTrue(portal.levels.get(Skill.Constitution) < health, "Attacker must reach and damage portal $index without human help")
    }
}
