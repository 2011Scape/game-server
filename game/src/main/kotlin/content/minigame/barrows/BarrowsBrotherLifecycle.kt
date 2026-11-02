package content.minigame.barrows

import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.clearHinted
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.timer.toTicks
import java.util.concurrent.TimeUnit

class BarrowsBrotherLifecycle : Script {
    init {
        npcCombatAttack(BROTHERS) {
            refreshDespawn()
        }
        npcCombatDamage(BROTHERS) {
            refreshDespawn()
        }
        npcDespawn(BROTHERS) {
            clearHinted()
            val player = owner ?: return@npcDespawn
            val variable = "${id.substringBefore("_the_")}_spawn"
            if (player.get<Int>(variable) == index) {
                player.clear(variable)
            }
        }
    }

    private fun NPC.refreshDespawn() {
        if (!dead && !hide && lifecycle < 0) {
            despawn(IDLE_TICKS)
        }
    }

    companion object {
        internal const val BROTHERS = "ahrim_the_blighted,dharok_the_wretched,guthan_the_infested,karil_the_tainted,torag_the_corrupted,verac_the_defiled"
        internal val IDLE_TICKS = TimeUnit.SECONDS.toTicks(60)
    }
}
