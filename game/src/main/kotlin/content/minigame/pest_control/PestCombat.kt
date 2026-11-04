package content.minigame.pest_control

import content.entity.combat.Combat
import content.entity.combat.hit.directHit
import content.entity.combat.target
import content.entity.effect.toxin.poison
import content.entity.effect.transform
import content.entity.obj.door.Door
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.combat.CombatMovement
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.obj.GameObject
import world.gregs.voidps.engine.entity.obj.GameObjects
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.equipment
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.remove
import world.gregs.voidps.engine.map.collision.GameObjectCollisionRemove
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random

internal fun PestMatch.actPests() {
    for (npc in actors.toList()) {
        if (npc.id !in pestTypes || npc.dead || NPCs.indexed(npc.index) !== npc) continue
        val nearby = players.filter { !it.dead && it.tile.level == npc.tile.level && it.tile.within(npc.tile, 8) }
        when (npc.id.substringBefore("_level_")) {
            "spinner" -> {
                val portal = portals.getOrNull(npc["pest_origin", -1]) ?: continue
                if (!portal.dead && portal.levels.get(Skill.Constitution) > 0) {
                    npc.target = null
                    if (npc.tile.within(portal.tile, 4)) {
                        npc.mode = EmptyMode
                        npc.anim("pest_spinner_heal")
                        portal.levels.restore(Skill.Constitution, 50)
                    } else {
                        npc.walkTo(portal.tile.addY(3))
                    }
                }
            }
            "splatter" -> {
                val barrier = barriers.filter { it.health > 0 }.minByOrNull { it.original.tile.distanceTo(npc.tile) }
                val target = barrier?.original?.tile ?: knight.tile
                if (npc.tile.within(target, 2)) explode(npc) else npc.walkTo(target)
            }
            "ravager" -> {
                val barrier = barriers.filter { it.health > 0 }.minByOrNull { it.original.tile.distanceTo(npc.tile) }
                if (barrier != null) {
                    if (npc.tile.within(barrier.original.tile, 2)) {
                        npc.mode = EmptyMode
                        npc.anim("pest_ravager_attack")
                        barrier.damage(50)
                    } else {
                        npc.walkTo(barrier.original.tile)
                    }
                } else if (npc.mode !is CombatMovement) {
                    Combat.combat(npc, knight)
                }
            }
            "brawler" -> {
                val player = nearby.minByOrNull { it.tile.distanceTo(npc.tile) }
                if (player != null && npc.mode !is CombatMovement) Combat.combat(npc, player)
            }
            else -> {
                val target = nearby.minByOrNull { it.tile.distanceTo(npc.tile) } ?: knight
                if (npc.id.startsWith("shifter_level_") && ticks % 25 == 0 && !npc.tile.within(target.tile, 2)) {
                    npc.gfx("portal")
                    npc.tele(target.tile.addX(-1))
                }
                if (npc.target?.dead != false || npc.mode !is CombatMovement || (npc.target !== knight && npc.target !in players)) Combat.combat(npc, target)
            }
        }
    }
    actors.removeAll { it.id in pestTypes && it.dead && NPCs.indexed(it.index) !== it }
}

internal fun PestMatch.explode(npc: NPC, poison: Boolean = false, dying: Boolean = false) {
    if (npc.get("pest_exploded", false)) return
    npc["pest_exploded"] = true
    npc.anim(if (poison) "pest_spinner_death" else "pest_splatter_death")
    val source = if (npc.dead) knight else npc
    val blastMax = npc.def["max_hit_melee", 30] * 2
    for (player in players.toList()) {
        if (!player.dead && player.tile.within(npc.tile, 2)) {
            player.directHit(source, random.nextInt(blastMax / 3, blastMax + 1))
            if (poison) source.poison(player, 20)
        }
    }
    if (!poison) {
        if (!knight.dead && knight.tile.within(npc.tile, 2)) knight.directHit(source, blastMax * 2 / 3)
        barriers.filter { it.original.tile.within(npc.tile, 2) }.forEach { it.damage(100) }
    }
    if (!dying && !npc.dead) npc.directHit(npc, npc.levels.get(Skill.Constitution) + 1000)
}

internal class PestBarrier(val original: GameObject) {
    var health = when (original.intId) {
        in 14230..14232 -> 0
        in 14227..14229 -> 100
        else -> 200
    }
    var open = false
    var current = original
    val gate = original.intId == 14233 || original.intId == 14235
    fun damage(amount: Int) {
        if (health <= 0 || open) return
        health = (health - amount).coerceAtLeast(0)
        redraw()
    }
    fun toggle() {
        if (!gate || health == 0) return
        open = !open
        redraw()
    }
    fun repair(): Int {
        val restored = (200 - health).coerceIn(0, 50)
        health += restored
        if (restored > 0) redraw()
        return restored
    }

    private fun redraw() {
        val id: String
        val shape: Int
        if (gate) {
            val stage = when {
                health >= 200 -> 0
                health > 100 -> 1
                health > 0 -> 2
                else -> 3
            }
            val value = original.intId + stage * 4 + if (open) 1 else 0
            id = when (value) {
                14233 -> "pest_gate_left"
                14235 -> "pest_gate_right"
                14234 -> "pest_gate_open_left"
                14236 -> "pest_gate_open_right"
                else -> "pest_gate_$value"
            }
            shape = if (health == 0) 22 else original.shape
        } else {
            val variant = (original.intId - 14224) % 3
            val stage = when {
                health >= 200 -> 0
                health > 0 -> 1
                else -> 2
            }
            id = "pest_barrier_${14224 + variant + stage * 3}"
            shape = if (health == 0) {
                22
            } else if (variant == 0) {
                0
            } else {
                9
            }
        }
        GameObjects.remove(current)
        GameObjects.remove(original)
        val tile = if (gate && open) Door.tile(original, 1) else original.tile
        val rotation = if (gate && open) {
            (original.rotation + if (original.intId == 14233) 1 else 3) and 0x3
        } else {
            original.rotation
        }
        current = GameObjects.add(id, tile, shape = shape, rotation = rotation)
        if (health == 0) GameObjectCollisionRemove().modify(current)
    }
}

internal fun PestMatch.loadBarriers() {
    for (x in 2635..2677) {
        for (y in 2570..2602) {
            for (obj in GameObjects.at(at(Tile(x, y)))) if (obj.intId in 14224..14233 || obj.intId == 14235) barriers.add(PestBarrier(obj))
        }
    }
}

internal fun PestMatch.setGate(player: Player, gate: PestBarrier, open: Boolean) {
    if (player.pestMatch() !== this || !player.tile.within(gate.current.tile, 2) || !gate.gate) return
    barriers.filter { it.gate && it.original.tile.within(gate.original.tile, 1) && it.open != open }.forEach { it.toggle() }
}

internal fun PestMatch.repair(player: Player, obj: GameObject): Boolean {
    if (player.pestMatch() !== this || player.dead || player.hasClock("pest_repair") || !player.tile.within(obj.tile, 2) || !GameObjects.contains(obj)) return false
    val barrier = barriers.firstOrNull { it.current == obj } ?: return false
    if (barrier.health == 200) return false.also { player.message("This defence does not need repairing.") }
    if (!player.inventory.contains("hammer") || !player.inventory.contains("logs")) return false.also { player.message("You need a hammer and some normal logs to repair this defence.") }
    if (!player.inventory.remove("logs")) return false
    val restored = barrier.repair()
    player.inc("pest_damage", restored)
    player.start("pest_repair", 5)
    player.message("You repair the defence, contributing $restored points towards your reward.")
    return true
}

class PestCombat : Script {
    init {
        canAttack(pestActors) { target ->
            val match = target.pestMatch()
            match != null && pestMatch() === match && target.id != "void_knight" && !target.get("pest_shield", false)
        }
        combatPrepare {
            val npc = it as? NPC
            val match = npc?.pestMatch()
            if (match == null) pestMatch() == null else pestMatch() === match && npc.id != "void_knight" && !npc.get("pest_shield", false)
        }
        npcCombatPrepare(pestActors) { target ->
            val match = pestMatch()
            match != null &&
                id in pestTypes &&
                !dead &&
                when (target) {
                    is Player -> target.pestMatch() === match && match.contains(target.tile)
                    is NPC -> target === match.knight && !id.startsWith("spinner_level_") && !id.startsWith("brawler_level_")
                    else -> false
                }
        }
        npcDamageModifier(pestActors) { hit ->
            val match = pestMatch()
            when {
                match == null -> if (contains("pest_match")) 0 else hit.damage
                hit.type == "healed" -> hit.damage
                get("pest_shield", false) || dead -> 0
                id == "void_knight" -> if ((hit.source as? NPC)?.pestMatch() === match && hit.source !== this) hit.damage else 0
                hit.source is Player -> if ((hit.source as Player).pestMatch() === match && match.contains(hit.source.tile)) hit.damage else 0
                hit.source === this && get("pest_exploded", false) -> hit.damage
                else -> 0
            }
        }
        npcCombatDamage(pestActors) { hit ->
            clear("pest_damage_source")
            val player = hit.source as? Player ?: return@npcCombatDamage
            val match = pestMatch() ?: return@npcCombatDamage
            if (player.pestMatch() !== match || id == "void_knight" || get("pest_shield", false) || hit.type == "healed") return@npcCombatDamage
            if (hit.damage > 0) set("pest_damage_source", player)
        }
        npcLevelChanged(Skill.Constitution, pestActors) { _, from, to ->
            val player = remove<Player>("pest_damage_source") ?: return@npcLevelChanged
            val match = pestMatch() ?: return@npcLevelChanged
            if (player.pestMatch() === match) player.inc("pest_damage", (from.coerceAtLeast(0) - to.coerceAtLeast(0)).coerceAtLeast(0))
        }
        npcDeath(pestActors) { death ->
            val match = pestMatch() ?: return@npcDeath
            death.dropItems = false
            death.respawn = false
            when {
                contains("pest_portal") -> match.portalDied(this)
                id.startsWith("splatter_level_") -> match.explode(this, dying = true)
            }
        }
        npcSpawn {
            if (!contains("pest_match")) return@npcSpawn
            if (pestMatch() == null) {
                NPCs.remove(this)
            } else {
                mode = EmptyMode
                huntMode = ""
                if (get("pest_shield", false)) transform("portal_shield_${pestColours[get("pest_portal", 0)]}")
            }
        }
        for (option in listOf("Open", "Close")) {
            objectOperate(option, "pest_gate_*") { (obj) ->
                val match = pestMatch() ?: return@objectOperate
                val barrier = match.barriers.firstOrNull { it.current == obj } ?: return@objectOperate
                match.setGate(this, barrier, option == "Open")
            }
        }
        objectOperate("Repair", "pest_gate_*,pest_barrier_*") { (obj) -> pestMatch()?.repair(this, obj) }
        itemOnObjectOperate("logs", "pest_gate_*,pest_barrier_*") { (obj) -> pestMatch()?.repair(this, obj) }
        objectOperate("Climb", "pest_tower_ladder") { (obj) ->
            val match = pestMatch() ?: return@objectOperate
            if (!match.contains(obj.tile) || dead || !tile.within(obj.tile, 2)) return@objectOperate
            val destination = if (obj.rotation == 0) {
                obj.tile.addY(if (tile.y > obj.tile.y) -1 else 1)
            } else {
                obj.tile.addX(if (tile.x > obj.tile.x) -1 else 1)
            }
            anim("climb_up")
            tele(destination)
        }
        objectOperate("Chop down", "pest_tree_*") {
            if (pestMatch() == null || dead || hasClock("pest_chop")) return@objectOperate
            if (!(inventory.items + equipment.items).any { it.id.endsWith("_hatchet") }) {
                message("You need a hatchet to cut these trees.")
            } else if (inventory.add("logs")) {
                start("pest_chop", 5)
                message("You cut some logs for repairing the defences.")
            }
        }
    }
}
