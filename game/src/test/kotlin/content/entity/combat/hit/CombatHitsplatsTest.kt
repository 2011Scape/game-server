package content.entity.combat.hit

import content.entity.combat.damageDealers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.entity.character.Character
import world.gregs.voidps.engine.entity.character.mode.combat.CombatApi
import world.gregs.voidps.engine.entity.character.mode.combat.CombatDamage
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Levels
import world.gregs.voidps.engine.entity.item.Item

class CombatHitsplatsTest {
    private val previousLoading = Script.loading
    private val previousSettings = Settings.load(emptyMap<String, String>()).clone() as java.util.Properties
    private lateinit var hitsplats: CombatHitsplats
    private val source = Player(index = 1)

    @BeforeEach
    fun setup() {
        Script.loading = true
        Settings.load(mapOf("combat.damageSoak" to "true"))
        hitsplats = CombatHitsplats()
    }

    @AfterEach
    fun cleanup() {
        CombatApi.close()
        Script.loading = previousLoading
        Settings.clear()
        Settings.load(previousSettings)
    }

    @Test
    fun `A hit equal to an ordinary monsters remaining health is lethal for every combat style`() {
        for (type in listOf("melee", "stab", "slash", "crush", "range", "magic", "damage")) {
            for (health in listOf(200, 210, 500, 1000)) {
                val target = npc(health)

                hit(target, health, type)

                assertEquals(0, target.levels.get(Skill.Constitution), "$type against $health health")
                val splat = target.visuals.hits.splats.filterNotNull().single()
                assertEquals(health, splat.amount)
                assertEquals(0, splat.percentage)
                assertEquals(health, target.damageDealers[source])
            }
        }
    }

    @Test
    fun `An unequipped player has no implicit absorption`() {
        val target = Player(index = 2)
        health(target, 500)

        hit(target, 500)

        assertEquals(0, target.levels.get(Skill.Constitution))
        assertEquals(500, target.visuals.hits.splats.filterNotNull().single().amount)
    }

    @Test
    fun `Configured absorption still reduces only damage above 200`() {
        val target = Player(index = 2)
        health(target, 500)
        target["absorb_melee"] = 20

        hit(target, 500)

        assertEquals(60, target.levels.get(Skill.Constitution))
        assertEquals(440, target.visuals.hits.splats.filterNotNull().single().amount)
        assertEquals(440, target.damageDealers[source])
    }

    @Test
    fun `Configured absorption does not affect hits at the threshold`() {
        val target = npc(500)
        target["absorb_melee"] = 20

        hit(target, 200)

        assertEquals(300, target.levels.get(Skill.Constitution))
    }

    @Test
    fun `Disabling absorption preserves the full hit`() {
        Settings.load(mapOf("combat.damageSoak" to "false"))
        val target = npc(500)
        target["absorb_melee"] = 20

        hit(target, 500)

        assertEquals(0, target.levels.get(Skill.Constitution))
    }

    @Test
    fun `Instant kill bypasses configured absorption`() {
        val target = npc(500)
        target["absorb_melee"] = 20
        source["insta_kill"] = true

        hit(target, 500)

        assertEquals(0, target.levels.get(Skill.Constitution))
    }

    @Test
    fun `Overkill is absorbed before being capped to remaining health`() {
        val target = npc(210)
        target["absorb_melee"] = 10

        hit(target, 500)

        assertEquals(0, target.levels.get(Skill.Constitution))
        assertEquals(210, target.visuals.hits.splats.filterNotNull().single().amount)
        assertEquals(210, target.damageDealers[source])
    }

    @Test
    fun `Queued attacks preserve overkill and report absorbed capped damage`() {
        for ((health, incoming, expected) in listOf(Triple(210, 500, 210), Triple(210, 210, 209), Triple(1000, 500, 470))) {
            val target = npc(health)
            target["absorb_melee"] = 10
            val attacker = NPC(id = "death_spawn", index = 3)

            val damage = attacker.hit(target, weapon = Item.EMPTY, offensiveType = "melee", delay = 0, spell = "", special = false, damage = incoming)
            target.queue.tick()

            assertEquals(expected, damage, "Reported damage for $incoming against $health HP")
            assertEquals(health - expected, target.levels.get(Skill.Constitution))
            assertEquals(expected, target.visuals.hits.splats.filterNotNull().single().amount)
            assertEquals(expected, target.damageDealers[attacker])
        }
    }

    @Test
    fun `Impact effects receive capped damage while absorption retains the full hit`() {
        val target = npc(210)
        target["absorb_melee"] = 10
        val attacker = NPC(id = "death_spawn", index = 3)
        var impactDamage = -1
        object : CombatApi {}.npcCombatDamage { event -> impactDamage = event.damage }

        attacker.hit(target, weapon = Item.EMPTY, offensiveType = "melee", delay = 0, spell = "", special = false, damage = 500)
        target.queue.tick()

        assertEquals(210, impactDamage)
        assertEquals(0, target.levels.get(Skill.Constitution))
    }

    @Test
    fun `Queued damage is capped to health at impact`() {
        val target = npc(1000)
        target["absorb_melee"] = 10
        val attacker = NPC(id = "death_spawn", index = 3)

        attacker.hit(target, weapon = Item.EMPTY, offensiveType = "melee", delay = 0, spell = "", special = false, damage = 500)
        target.levels.set(Skill.Constitution, 210)
        target.queue.tick()

        assertEquals(0, target.levels.get(Skill.Constitution))
        assertEquals(210, target.visuals.hits.splats.filterNotNull().single().amount)
        assertEquals(210, target.damageDealers[attacker])
    }

    @Test
    fun `Ordinary overkill credits only remaining health`() {
        val target = npc(210)

        hit(target, 500)

        assertEquals(0, target.levels.get(Skill.Constitution))
        assertEquals(210, target.visuals.hits.splats.filterNotNull().single().amount)
        assertEquals(210, target.damageDealers[source])
    }

    @Test
    fun `Misses and healing do not use damage absorption`() {
        val target = npc(500)
        target["absorb_melee"] = 10

        hit(target, -1)
        assertEquals(500, target.levels.get(Skill.Constitution))
        assertEquals(0, target.visuals.hits.splats.filterNotNull().single().amount)

        target.levels.set(Skill.Constitution, 210)
        hit(target, 500, "healed")
        assertEquals(500, target.levels.get(Skill.Constitution))
    }

    private fun hit(target: Character, damage: Int, type: String = "melee") {
        hitsplats.hit(target, CombatDamage(source, type, damage, Item.EMPTY, "", false))
    }

    private fun npc(health: Int) = NPC(id = "test_monster", index = 2).also { health(it, health) }

    private fun health(target: Character, health: Int) {
        target.levels.link(
            target,
            object : Levels.Level {
                override fun getMaxLevel(skill: Skill) = health
            },
        )
        target.levels.set(Skill.Constitution, health)
    }
}
