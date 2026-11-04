package content.minigame.pest_control

import WorldTest
import containsMessage
import continueDialogue
import interfaceOnPlayer
import interfaceOption
import itemOption
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Teleport
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.charges
import world.gregs.voidps.engine.inv.equipment
import world.gregs.voidps.engine.inv.inventory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PestTeleportTest : WorldTest() {
    private val control get() = scripts.filterIsInstance<PestControl>().single()

    @AfterEach fun cleanup() = control.reset()

    private fun ready(name: String): Player = createPlayer(pestDock, name).also { player ->
        for (skill in pestRewardSkills) player.experience.set(skill, Level.experience(60))
        player.levels.clear()
    }

    private fun enter(active: Boolean): Player {
        settings["pestControl.waitTicks"] = "1000"
        val player = ready("tele-pest")
        assertTrue(control.join(player))
        if (active) control.launch()
        tick(2)
        return player
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `all teleport categories are blocked in the lander and match`(active: Boolean) {
        val player = enter(active)
        for (type in listOf("modern", "ancient", "lunar", "dungeoneering", "jewellery", "tablet", "scroll", "kinship", "ectophial", "skull_sceptre", "teleother", "future_teleport_type")) {
            assertFalse(Teleport.takeOff(player, type, ""), type)
        }
        assertTrue(player.containsMessage("You can't teleport out of Pest Control"))
        if (active) player.pestMatch()!!.leave(player) else control.leaveQueue(player)
        assertEquals(pestDock, player.tile)
        assertTrue(Teleport.takeOff(player, "modern", "varrock_teleport"))
        assertTrue(Teleport.takeOff(player, "jewellery", ""))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `spell and home teleport cannot leave or consume runes`(active: Boolean) {
        val player = enter(active)
        val before = player.tile
        for (rune in listOf("law_rune", "air_rune", "fire_rune")) player.inventory.add(rune, 10)
        player.open("modern_spellbook")
        player.interfaceOption("modern_spellbook", "varrock_teleport", "Cast")
        tick(10)
        player.interfaceOption("modern_spellbook", "lumbridge_home_teleport", "Cast")
        tick(60)
        assertEquals(before, player.tile)
        for (rune in listOf("law_rune", "air_rune", "fire_rune")) assertEquals(10, player.inventory.count(rune))
        assertFalse(player.queue.contains("home_teleport"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `tablets seed pods and skull sceptres cannot teleport or lose charges`(active: Boolean) {
        val player = enter(active)
        val before = player.tile
        player.inventory.add("varrock_teleport")
        player.inventory.add("grand_seed_pod")
        player.inventory.add("skull_sceptre")
        val charges = player.inventory.charges(player, player.inventory.indexOf("skull_sceptre"))
        player.itemOption("Break", "varrock_teleport")
        tick(10)
        player.itemOption("Launch", "grand_seed_pod")
        tick(10)
        player.itemOption("Invoke", "skull_sceptre")
        tick(10)
        assertEquals(before, player.tile)
        assertEquals(1, player.inventory.count("varrock_teleport"))
        assertEquals(1, player.inventory.count("grand_seed_pod"))
        assertEquals(charges, player.inventory.charges(player, player.inventory.indexOf("skull_sceptre")))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `worn glory and ring of life stay charged when teleport is blocked`(active: Boolean) {
        val player = enter(active)
        val before = player.tile
        player.equipment.set(2, "amulet_of_glory_4")
        player.interfaceOption("worn_equipment", "amulet_slot", "Edgeville", optionIndex = 1, item = Item("amulet_of_glory_4"))
        tick(10)
        assertTrue(player.equipment.contains("amulet_of_glory_4"))
        player.equipment.set(12, "ring_of_life")
        player.levels.set(Skill.Constitution, 1)
        tick(10)
        assertEquals(before, player.tile)
        assertTrue(player.equipment.contains("ring_of_life"))
    }

    @Test
    fun `another player cannot offer or force an accepted teleother out of a match`() {
        val player = ready("target")
        val caster = ready("caster")
        control.join(player)
        control.join(caster)
        val match = control.launch()!!
        tick(2)
        caster.levels.set(Skill.Magic, 99)
        for (rune in listOf("law_rune", "soul_rune", "earth_rune")) caster.inventory.add(rune)
        caster.tele(player.tile.addX(1))
        caster.interfaceOnPlayer("modern_spellbook", "teleother_lumbridge", player)
        tick(2)
        assertFalse(player.interfaces.contains("teleport_other"))
        assertEquals(1, caster.inventory.count("law_rune"))
        player["tele_other_target"] = "lumbridge"
        player.open("teleport_other")
        player.continueDialogue("teleport_other", "continue", "Continue", 0)
        tick(6)
        assertTrue(match.contains(player.tile))
        assertEquals(match, player.pestMatch())
    }
}
