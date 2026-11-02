package content.area.asgarnia.burthorpe.warriors_guild

import content.entity.obj.door.openDoor
import content.entity.player.dialogue.type.statement
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.data.definition.Areas
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.remove
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.engine.timer.toTicks
import world.gregs.voidps.type.Tile
import java.util.concurrent.TimeUnit

class CyclopsRoom : Script {
    init {
        objectOperate("Open", "door_334_closed,door_335_closed") { (target) ->
            val entrance = target.tile
            if (entrance.level != 2 || entrance.x != 2847 || entrance.y !in 3540..3541) {
                openDoor(target)
                return@objectOperate
            }
            if (tile in Areas[ROOM]) {
                tele(OUTSIDE)
            } else if (!inventory.contains(TOKEN, ENTRY_REQUIREMENT)) {
                message("You need at least 100 Warrior Guild Tokens to enter the cyclops room.")
            } else {
                tele(Tile(2848, target.tile.y, 2))
            }
        }

        entered(ROOM) { admit(this) }
        exited(ROOM) { endSession(this) }
        playerDespawn { endSession(this) }
        playerDeath { endSession(this) }
        playerSpawn {
            refreshProgress(this)
            if (tile in Areas[ROOM]) {
                admit(this)
            }
        }

        timerStart(TIMER) { MINUTE }
        timerTick(TIMER) {
            if (!eligible(this)) {
                endSession(this)
                Timer.CANCEL
            } else if (!inventory.remove(TOKEN, COST)) {
                eject(this)
                Timer.CANCEL
            } else {
                Timer.CONTINUE
            }
        }

        canAttack(CYCLOPES) { target ->
            if (target.tile !in Areas[ROOM] || !eligible(this)) {
                message("You must enter the cyclops room and pay with Warrior Guild Tokens first.")
                false
            } else {
                true
            }
        }

        for (inventory in listOf("inventory", "worn_equipment", "bank")) {
            itemAdded(DEFENDERS.joinToString(","), inventory) { update ->
                unlock(this, DEFENDERS.indexOf(update.item.id) + 1)
            }
        }

        npcOperate("Talk-to", "kamfreena") {
            refreshProgress(this)
            val next = DEFENDERS[get(TIER, 0).coerceIn(0, DEFENDERS.lastIndex)].replace('_', ' ')
            statement("Bring 100 tokens to enter. It costs 10 tokens on entry and 10 each minute. Collect each defender to unlock the next. Your next reward is a $next.")
        }
    }

    private fun admit(player: Player) {
        if (player[PAID, false]) {
            return
        }
        if (!player.inventory.contains(TOKEN, ENTRY_REQUIREMENT) || !player.inventory.remove(TOKEN, COST)) {
            eject(player)
            return
        }
        refreshProgress(player)
        player[PAID] = true
        player.softTimers.start(TIMER)
        player.message("10 tokens are spent. Another 10 will be spent each minute you stay here.")
    }

    private fun eject(player: Player) {
        endSession(player)
        player.tele(OUTSIDE)
        player.message("You do not have enough Warrior Guild Tokens to stay in the cyclops room.")
    }

    private fun endSession(player: Player) {
        player.clear(PAID)
        player.softTimers.stop(TIMER)
    }

    private fun refreshProgress(player: Player) {
        for ((index, defender) in DEFENDERS.withIndex()) {
            if (listOf("inventory", "worn_equipment", "bank").any { player.inventories.inventory(it).contains(defender) }) {
                unlock(player, index + 1)
            }
        }
    }

    private fun unlock(player: Player, tier: Int) {
        if (tier > player[TIER, 0]) {
            player[TIER] = tier
        }
    }

    companion object {
        const val ROOM = "warriors_guild_cyclops_room"
        const val TOKEN = "warrior_guild_token"
        const val PAID = "warriors_guild_cyclops_paid"
        const val TIER = "warriors_guild_defender_tier"
        const val TIMER = "warriors_guild_token_drain"
        const val ENTRY_REQUIREMENT = 100
        const val COST = 10
        val MINUTE = TimeUnit.MINUTES.toTicks(1)
        val OUTSIDE = Tile(2846, 3540, 2)
        val DEFENDERS = listOf("bronze", "iron", "steel", "black", "mithril", "adamant", "rune", "dragon").map { "${it}_defender" }
        const val CYCLOPES = "cyclopse_hammer,cyclopse_hammer_steel,cyclopse_club_lepard,cyclopse_club,cyclopse_club_mohawk,cyclopse_knife"

        fun eligible(player: Player) = player[PAID, false] && player.tile in Areas[ROOM]
    }
}
