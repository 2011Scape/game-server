package content.skill.thieving

import content.entity.combat.underAttack
import content.skill.summoning.familiarBoost
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.chat.toIntRange
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.data.definition.ObjectDefinitions
import world.gregs.voidps.engine.entity.character.mode.interact.PlayerOnObjectInteract
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.chat.inventoryFull
import world.gregs.voidps.engine.entity.character.player.equip.equipped
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.exp.exp
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.entity.character.player.skill.level.Level.has
import world.gregs.voidps.engine.entity.item.drop.DropTables
import world.gregs.voidps.engine.entity.obj.replace
import world.gregs.voidps.engine.event.Wildcard
import world.gregs.voidps.engine.event.Wildcards
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.transact.TransactionError
import world.gregs.voidps.engine.inv.transact.operation.AddItem.add
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.network.login.protocol.visual.update.player.EquipSlot
import world.gregs.voidps.type.random
import java.util.concurrent.TimeUnit

class Stalls(val drops: DropTables) : Script {

    init {
        val seedStalls = ObjectDefinitions.definitions.filter { stallConfigId(it) == "seed_stall" }.map { it.stringId }
        val stalls = Wildcards.get("*_stall", Wildcard.Object) + seedStalls
        val steal: suspend Player.(PlayerOnObjectInteract) -> Unit = steal@{ (target) ->
            if (underAttack) {
                message("You can't do this while you're in combat.")
                return@steal
            }
            if (equipped(EquipSlot.Weapon).id.endsWith("_greegree")) {
                message("You wouldn't like to blow your cover by getting caught stealing.")
                return@steal
            }
            val targetDef = target.def(this)
            val stallId = stallConfigId(targetDef)
            val def = if (stallId == targetDef.stringId) targetDef else ObjectDefinitions.get(stallId)
            val level: Int = def.getOrNull("level") ?: return@steal
            if (!has(Skill.Thieving, level, " to steal from this stall")) {
                return@steal
            }
            if (inventory.isFull()) {
                inventoryFull()
                return@steal
            }
            start("thieving", 2)
            set("stall_level", level)
            anim("take")
            delay(2)
            val chance: String? = def.getOrNull("chance")
            if (chance != null) {
                val range = chance.toIntRange(inclusive = true)
                if (!Level.success(levels.get(Skill.Thieving) + familiarBoost(Skill.Thieving), range)) {
                    message("You attempt to steal from the stall but you miss your chance...")
                    return@steal
                }
            }
            if (!hasClock("${stallId}_thief")) {
                start("${stallId}_thief", TimeUnit.MINUTES.toTicks(random.nextInt(20, 30)))
            }
            val table = drops.get("${stallId}_drop_table")
            if (table != null) {
                val drops = table.roll().map { it.toItem() }
                inventory.transaction {
                    for (item in drops) {
                        add(item.id, item.amount)
                    }
                }
                when (inventory.transaction.error) {
                    is TransactionError.Full -> {
                        inventoryFull()
                        return@steal
                    }
                    TransactionError.None -> for (item in drops) {
                        Stole.stole(this, target, item)
                    }
                    else -> {}
                }
            }
            val exp: Double = def.getOrNull("exp") ?: return@steal
            exp(Skill.Thieving, exp)
            val restock: Int = def.getOrNull("restock") ?: return@steal
            target.replace(targetDef["empty", def["empty", "${stallId}_empty"]], ticks = restock)
        }
        for (option in listOf("Steal-from", "Steal from")) {
            objectOperate(option, stalls.joinToString(","), handler = steal)
        }
    }
}

internal fun stallConfigId(def: ObjectDefinition): String = if (
    def.name.equals("Seed stall", ignoreCase = true) &&
    def.options?.any { it == "Steal-from" || it == "Steal from" } == true
) {
    "seed_stall"
} else {
    def.stringId
}
