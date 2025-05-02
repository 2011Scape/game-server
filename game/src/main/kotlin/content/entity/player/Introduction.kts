package content.entity.player

import content.bot.isBot
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.event.interfaceClose
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.client.variable.stop
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.chat.ChatType
import world.gregs.voidps.engine.entity.character.player.flagAppearance
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.entity.playerSpawn
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.queue.queue
import content.entity.player.bank.bank
import content.entity.player.dialogue.type.statement

playerSpawn(priority = true) { player ->
    player.message("Welcome to ${Settings["server.name"]}.", ChatType.Welcome)
    if (player.contains("creation")) {
        return@playerSpawn
    }
    if (Settings["world.start.creation", true] && !player.isBot) {
        player["delay"] = -1
        World.queue("welcome_${player.name}", 1) {
            player.open("character_creation")
        }
    } else {
        player.flagAppearance()
        setup(player)
    }
}

interfaceClose("character_creation") { player ->
    player.flagAppearance()
    setup(player)
}

fun setup(player: Player) {
    player.queue("welcome") {
        statement("Welcome to Lumbridge! To get more help, simply click on the Lumbridge Guide or one of the Tutors - these can be found by looking for the question mark icon on your minimap. If you find you are lost at any time, look for a signpost or use the Lumbridge Home Teleport spell.")
    }
    player.stop("delay")
    player["creation"] = System.currentTimeMillis()

    if (!Settings["world.setup.gear", true]) {
        return
    }
    player.bank.add("coins", 25)
    player.bank.add("rune_platebody", 10000)
    player.bank.add("rune_platelegs", 10000)
    player.bank.add("rune_full_helm", 10000)
    player.bank.add("rune_kiteshield", 10000)
    player.bank.add("rune_scimitar", 10000)
    player.bank.add("dragon_dagger_p++", 10000)
    player.bank.add("dragon_scimitar", 10000)
    player.bank.add("dragon_claws", 10000)
    player.bank.add("amulet_of_strength", 10000)
    player.bank.add("ring_of_recoil", 10000)
    player.bank.add("climbing_boots", 10000)
    player.bank.add("rune_defender", 10000)
    player.bank.add("shark", 10000)
    player.bank.add("monkfish", 10000)
    player.bank.add("saradomin_brew_4", 10000)
    player.bank.add("super_restore_4", 10000)
    player.bank.add("prayer_potion_4", 10000)
    player.bank.add("super_attack_4", 10000)
    player.bank.add("super_strength_4", 10000)
    player.bank.add("super_defence_4", 10000)
    player.bank.add("green_dragonhide_body", 10000)
    player.bank.add("green_dragonhide_chaps", 10000)
    player.bank.add("green_dragonhide_vambraces", 10000)
    player.bank.add("magic_shortbow", 10000)
    player.bank.add("rune_crossbow", 10000)
    player.bank.add("broad_bolts", 10000)
    player.bank.add("rune_arrow", 10000)
    player.bank.add("amulet_of_glory", 10000)
    player.bank.add("coif", 10000)
    player.bank.add("snakeskin_boots", 10000)
    player.bank.add("mystic_robe_top_blue", 10000)
    player.bank.add("mystic_robe_bottom_blue", 10000)
    player.bank.add("mystic_hat_blue", 10000)
    player.bank.add("amulet_of_magic", 10000)
    player.bank.add("wizard_boots", 10000)
    player.bank.add("zamorak_cape", 10000)
    player.bank.add("staff_of_air", 10000)
    player.bank.add("staff_of_fire", 10000)
    player.bank.add("staff_of_water", 10000)
    player.bank.add("staff_of_earth", 10000)
    player.bank.add("air_rune", 10000)
    player.bank.add("water_rune", 10000)
    player.bank.add("earth_rune", 10000)
    player.bank.add("fire_rune", 10000)
    player.bank.add("mind_rune", 10000)
    player.bank.add("body_rune", 10000)
    player.bank.add("chaos_rune", 10000)
    player.bank.add("death_rune", 10000)
    player.bank.add("blood_rune", 10000)
    player.bank.add("law_rune", 10000)
    player.bank.add("nature_rune", 10000)
    player.bank.add("cosmic_rune", 10000)
    player.bank.add("astral_rune", 10000)
    player.bank.add("soul_rune", 10000)

    player.inventory.apply {
        add("bronze_hatchet")
        add("tinderbox")
        add("small_fishing_net")
        add("shrimp")
        add("bucket")
        add("empty_pot")
        add("bread")
        add("bronze_pickaxe")
        add("bronze_dagger")
        add("bronze_sword")
        add("wooden_shield")
        add("shortbow")
        add("bronze_arrow", 25)
        add("air_rune", 25)
        add("mind_rune", 15)
        add("water_rune", 6)
        add("earth_rune", 4)
        add("body_rune", 2)
    }
}