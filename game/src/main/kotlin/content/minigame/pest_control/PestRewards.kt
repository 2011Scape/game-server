package content.minigame.pest_control

import content.entity.combat.hit.directHit
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.close
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.data.definition.ItemDefinitions
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.exp.Experience
import world.gregs.voidps.engine.entity.character.player.skill.exp.exp
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.transact.operation.AddItem.add
import world.gregs.voidps.engine.inv.transact.operation.ReplaceItem.replace
import world.gregs.voidps.type.random
import kotlin.math.floor

internal val pestRewardKnights = "void_knight_pest_control,void_knight_2_pest_control,void_knight_3_pest_control,void_knight_4_pest_control"
internal val pestRewardSkills = listOf(Skill.Attack, Skill.Strength, Skill.Defence, Skill.Ranged, Skill.Magic, Skill.Constitution, Skill.Prayer)
internal data class PestReward(val cost: Int, val item: String = "", val amount: Int = 1, val skill: Skill? = null, val pack: String = "")
internal val pestRewards: Map<Int, PestReward> = buildMap {
    for ((first, points) in listOf(34 to 1, 49 to 10, 56 to 100)) {
        for ((i, skill) in pestRewardSkills.withIndex()) put(first + i, PestReward(points, skill = skill))
    }
    put(41, PestReward(250, "void_knight_mace"))
    put(42, PestReward(250, "void_knight_top"))
    put(43, PestReward(250, "void_knight_robe"))
    put(44, PestReward(150, "void_knight_gloves"))
    put(45, PestReward(30, pack = "herb"))
    put(46, PestReward(15, pack = "mineral"))
    put(48, PestReward(15, pack = "seed"))
    put(67, PestReward(200, "void_mage_helm"))
    put(68, PestReward(200, "void_ranger_helm"))
    put(69, PestReward(200, "void_melee_helm"))
    put(70, PestReward(10, "void_seal_8_8"))
    for ((first, charm) in listOf(75 to "spinner_charm", 78 to "torcher_charm", 81 to "ravager_charm", 84 to "shifter_charm")) {
        put(first, PestReward(2, charm))
        put(first + 1, PestReward(28, charm, 14))
        put(first + 2, PestReward(56, charm, 28))
    }
}

internal fun pestExperience(level: Int, skill: Skill, points: Int): Double {
    val factor = when (skill) {
        Skill.Prayer -> 18
        Skill.Ranged -> 32
        else -> 35
    }
    val bonus = when (points) {
        10 -> 1.01
        100 -> 1.1
        else -> 1.0
    }
    return floor((level * level / 600.0) * factor * points * bonus)
}

internal fun Player.pestShopAvailable(): Boolean {
    val knight = get<NPC>("pest_reward_npc") ?: return false
    return !dead &&
        pestMatch() == null &&
        interfaces.contains("pest_control_rewards") &&
        NPCs.indexed(knight.index) === knight &&
        knight.id in pestRewardKnights.split(',') &&
        tile.within(knight.tile, 5) &&
        tile.x in 2624..2687 &&
        tile.y in 2624..2687 &&
        tile.level == 0
}

internal fun Player.pestRewardLabel(reward: PestReward): String = when {
    reward.skill != null -> "${reward.cost} points of ${reward.skill} experience"
    reward.pack.isNotEmpty() -> "a ${reward.pack} pack"
    else -> "${reward.amount} x ${ItemDefinitions.get(reward.item).name}"
}

internal fun Player.buyPestReward(component: Int): Boolean {
    if (!pestShopAvailable()) return false
    val reward = pestRewards[component] ?: return false
    val points = get("pest_control_points", 0)
    if (points < reward.cost) return false.also { message("You need ${reward.cost} commendations for this reward.") }
    if (reward.item.startsWith("void_") && reward.item != "void_seal_8_8") {
        val skills = pestRewardSkills.filter { it != Skill.Prayer }
        if (skills.any { levels.getMax(it) < if (it == Skill.Constitution) 420 else 42 } || levels.getMax(Skill.Prayer) < 22) {
            message("Void equipment requires level 42 Attack, Strength, Defence, Constitution, Ranged and Magic, and level 22 Prayer.")
            return false
        }
    }
    if (reward.skill != null) {
        val skill = reward.skill
        val level = levels.getMax(skill) / if (skill == Skill.Constitution) 10 else 1
        if (level < 25 || experience.blocked(skill) || experience.direct(skill) >= Experience.MAXIMUM_EXPERIENCE) {
            message("You must be level 25 or above and able to gain ${skill.name} experience to buy this reward.")
            return false
        }
        dec("pest_control_points", reward.cost)
        exp(skill, pestExperience(level, skill, reward.cost))
    } else {
        val items = when (reward.pack) {
            "herb" -> listOf(Item("grimy_ranarr_noted", 2), Item("grimy_irit_noted", 3), Item("grimy_avantoe_noted", 3), Item("grimy_kwuarm_noted", 2))
            "mineral" -> listOf(Item("iron_ore_noted", 10), Item("coal_noted", 20), Item("silver_ore_noted", 5))
            "seed" -> listOf(Item("sweetcorn_seed", 10), Item("tomato_seed", 10), Item("limpwurt_seed", 5))
            else -> listOf(Item(reward.item, reward.amount))
        }
        if (!inventory.transaction { items.forEach { add(it.id, it.amount) } }) {
            message("You need more inventory space for that reward.")
            return false
        }
        dec("pest_control_points", reward.cost)
    }
    message("You spend ${reward.cost} commendations on ${pestRewardLabel(reward)}.")
    interfaces.sendText("pest_control_rewards", "title", "Void Knight Rewards - ${get("pest_control_points", 0)} points")
    return true
}

class PestRewards : Script {
    init {
        for (option in listOf("Exchange", "Talk-to")) {
            npcOperate(option, pestRewardKnights) { (target) ->
                if (dead || pestMatch() != null || !tile.within(target.tile, 5)) return@npcOperate
                set("pest_reward_npc", target)
                clear("pest_reward_selection")
                open("pest_control_rewards")
                interfaces.sendText("pest_control_rewards", "title", "Void Knight Rewards - ${get("pest_control_points", 0)} points")
                interfaces.sendText("pest_control_rewards", "selection", "Choose a reward above, then confirm.")
            }
        }
        interfaceOption("*", "pest_control_rewards:reward_*") {
            if (!pestShopAvailable()) return@interfaceOption
            val component = it.component.removePrefix("reward_").toIntOrNull() ?: return@interfaceOption
            val reward = pestRewards[component] ?: return@interfaceOption
            set("pest_reward_selection", component)
            interfaces.sendText("pest_control_rewards", "selection", "Confirm ${pestRewardLabel(reward)} for ${reward.cost} points.")
        }
        interfaceOption("Confirm", "pest_control_rewards:confirm") {
            val selection = remove<Int>("pest_reward_selection") ?: return@interfaceOption
            buyPestReward(selection)
            interfaces.sendText("pest_control_rewards", "selection", "Choose a reward above, then confirm.")
        }
        interfaceOption("Close", "pest_control_rewards:close") { close("pest_control_rewards") }
        interfaceClosed("pest_control_rewards") {
            clear("pest_reward_selection")
            clear("pest_reward_npc")
        }
        for (option in listOf("Rub", "Operate")) {
            itemOption(option, "void_seal_*", "*") {
                val match = pestMatch() ?: return@itemOption
                if (dead || hasClock("pest_seal")) return@itemOption
                val charges = it.item.id.removePrefix("void_seal_").substringBefore('_').toIntOrNull() ?: return@itemOption
                if (charges !in 1..8) return@itemOption
                val next = if (charges == 1) "" else "void_seal_${charges - 1}_${charges - 1}"
                if (!inventories.inventory(it.inventory).transaction { replace(it.slot, it.item.id, next) }) return@itemOption
                start("pest_seal", 5)
                for (npc in match.actors.toList()) if (npc.id in pestTypes && !npc.dead && npc.tile.within(tile, 2)) npc.directHit(this, random.nextInt(50, 101))
                message("The seal releases its power against the nearby pests.")
            }
        }
    }
}
