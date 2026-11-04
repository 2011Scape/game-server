package world.gregs.voidps.engine.entity.character.mode.move.combat

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.entity.character.mode.combat.CombatApi
import world.gregs.voidps.engine.entity.character.mode.combat.CombatDamage
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.script
import kotlin.test.*

class NPCDamageModifierTest {
    @AfterEach fun clear() = CombatApi.close()

    @Test fun `modifiers compose before all damage observers and preserve attack context`() {
        val hit = CombatDamage(Player(), "magic", 80, Item.EMPTY, "water_wave", true)
        val seen = mutableListOf<CombatDamage>()
        script {
            npcCombatDamage("npc", "magic") { seen.add(it) }
            npcCombatDamage { seen.add(it) }
            npcDamageModifier("npc") { it.damage / 2 }
            npcDamageModifier("*") { it.damage - 5 }
        }
        CombatApi.damage(NPC("npc"), hit)
        assertEquals(listOf(hit.copy(damage = 35), hit.copy(damage = 35)), seen)
        assertEquals(80, hit.damage)
    }

    @Test fun `unrelated targets are untouched and close removes modifiers`() {
        val hit = CombatDamage(Player(), "slash", 80, Item.EMPTY, "", false)
        val seen = mutableListOf<Int>()
        script {
            npcDamageModifier("npc") { 0 }
            npcCombatDamage { seen.add(it.damage) }
        }
        CombatApi.damage(NPC("other"), hit)
        assertEquals(listOf(80), seen)
        CombatApi.close()
        script { npcCombatDamage { seen.add(it.damage) } }
        CombatApi.damage(NPC("npc"), hit)
        assertEquals(listOf(80, 80), seen)
    }
}
