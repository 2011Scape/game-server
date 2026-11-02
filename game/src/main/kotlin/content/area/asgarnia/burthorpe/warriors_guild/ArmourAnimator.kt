package content.area.asgarnia.burthorpe.warriors_guild

import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.obj.GameObject
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.transact.operation.AddItem.add
import world.gregs.voidps.engine.inv.transact.operation.RemoveItem.remove
import world.gregs.voidps.engine.timer.toTicks
import java.util.concurrent.TimeUnit

class ArmourAnimator : Script {
    private val active = mutableMapOf<String, NPC>()

    init {
        itemOnObjectOperate(obj = "magical_animator") { (target, item) ->
            val metal = METALS.firstOrNull { item.id in pieces(it) }
            if (metal == null) {
                message("Use an unnoted full helm, platebody or platelegs from a bronze to rune armour set.")
                return@itemOnObjectOperate
            }
            animate(this, target, metal)
        }

        npcSpawn(ARMOUR) {
            val account = get<String>("owner") ?: return@npcSpawn
            val player = owner
            if (player == null || active[account] !== this) {
                NPCs.remove(this)
            } else {
                interactPlayer(player, "Attack")
            }
        }
        npcCombatAttack(ARMOUR) { refreshDespawn() }
        npcCombatDamage(ARMOUR) { refreshDespawn() }
        npcDeath(ARMOUR) { it.respawn = false }
        npcDespawn(ARMOUR) {
            val account = get<String>("owner") ?: return@npcDespawn
            if (active[account] === this) {
                active.remove(account)
            }
        }
        exited("warriors_guild") { abandon(this) }
        playerDespawn { abandon(this) }
        playerDeath { abandon(this) }
        worldSpawn { active.clear() }
    }

    private fun animate(player: Player, animator: GameObject, metal: String) {
        if (active.containsKey(player.accountName)) {
            player.message("You must finish fighting your animated armour first.")
            return
        }
        val pieces = pieces(metal)
        if (!player.inventory.transaction { pieces.forEach { remove(it) } }) {
            player.message("You need an unnoted $metal full helm, platebody and platelegs in your inventory.")
            return
        }
        val npc = NPCs.add("animated_${metal}_armour", animator.tile.addY(1), ticks = IDLE_TICKS, owner = player)
        if (npc.index < 0) {
            player.inventory.transaction { pieces.forEach { add(it) } }
            player.message("The animator cannot start right now. Please try again.")
            return
        }
        active[player.accountName] = npc
        player.message("The armour comes to life! Defeat it to recover your equipment and earn tokens.")
    }

    private fun abandon(player: Player) {
        val npc = active.remove(player.accountName) ?: return
        if (!npc.dead && !npc.hide && NPCs.indexed(npc.index) === npc) {
            NPCs.remove(npc)
        }
    }

    private fun NPC.refreshDespawn() {
        if (!dead && !hide && lifecycle < 0) {
            despawn(IDLE_TICKS)
        }
    }

    companion object {
        val METALS = listOf("bronze", "iron", "steel", "black", "mithril", "adamant", "rune")
        val ARMOUR = METALS.joinToString(",") { "animated_${it}_armour" }
        val IDLE_TICKS = TimeUnit.MINUTES.toTicks(2)

        fun pieces(metal: String) = listOf("${metal}_full_helm", "${metal}_platebody", "${metal}_platelegs")
    }
}
