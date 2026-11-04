package content.minigame.pest_control

import content.bot.Bot
import content.entity.combat.Combat
import content.entity.combat.target
import content.skill.melee.weapon.attackRange
import content.skill.prayer.PrayerConfigs.ACTIVE_PRAYERS
import org.rsmod.game.pathfinder.PathFinder
import org.rsmod.game.pathfinder.StepValidator
import org.rsmod.game.pathfinder.collision.CollisionStrategies
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.definition.InterfaceDefinitions
import world.gregs.voidps.engine.entity.Despawn
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.move.Movement
import world.gregs.voidps.engine.entity.character.mode.move.findPath
import world.gregs.voidps.engine.entity.character.mode.move.target.TargetStrategy
import world.gregs.voidps.engine.entity.character.move.running
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.get
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.equipment
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.map.collision.canFit
import world.gregs.voidps.engine.map.spiral
import world.gregs.voidps.network.client.DummyClient
import world.gregs.voidps.network.login.protocol.visual.update.player.EquipSlot

internal enum class PestRole(val title: String) {
    PORTAL("Raider"),
    KNIGHT("Guard"),
    GATE("Archer"),
    SPINNER("Magus"),
}

internal val Player.pestRole: PestRole? get() = get("pest_crew_role")

internal fun PestDifficulty.boardCrew(role: PestRole): Player? {
    val accounts = get<AccountManager>()
    val steps = get<StepValidator>()
    val berth = boat.spiral(2).asSequence().firstOrNull {
        it != boat && onBoat(it) && steps.canFit(it, CollisionStrategies.Normal, 1, 0) && Players.at(it).isEmpty()
    } ?: boat
    val bot = Player(tile = berth, accountName = "pest-control-crew-${nextCrewId++}")
    if (!accounts.index(bot)) return null
    bot["bot"] = Bot(bot)
    bot["display_name"] = "${role.title} ${bot.index}"
    bot["pest_crew_role"] = role
    bot["skip_level_up"] = true
    val client = DummyClient()
    accounts.setup(bot, client, 0, viewport = false)
    accounts.spawn(bot, client)
    val mage = this == PestDifficulty.VETERAN && (role == PestRole.KNIGHT || role == PestRole.SPINNER)
    for (skill in pestRewardSkills + Skill.Summoning) {
        val value = when {
            skill == Skill.Constitution -> crewLevel * 10
            skill == Skill.Magic && mage -> 94
            else -> crewLevel
        }
        bot.experience.set(skill, Level.experience(skill, value))
    }
    bot.levels.clear()
    val amulet = if (this == PestDifficulty.VETERAN) "amulet_of_fury" else "amulet_of_glory"
    bot.equipment.transaction { set(EquipSlot.Amulet.index, Item(amulet)) }
    when {
        this == PestDifficulty.NOVICE -> {
            bot.kit(
                "rune_full_helm",
                "rune_platebody",
                "rune_platelegs",
                if (role == PestRole.PORTAL) "dragon_scimitar" else "dragon_longsword",
                cape = if (role == PestRole.PORTAL) "cape_red" else "cape_blue",
                gloves = "culinaromancers_gloves_8",
                boots = "rune_boots",
            )
            bot.equipment.transaction { set(EquipSlot.Shield.index, Item("rune_kiteshield")) }
        }
        mage -> {
            bot.kit(
                "ahrims_hood",
                "ahrims_robe_top",
                "ahrims_robe_skirt",
                "ahrims_staff",
                cape = if (role == PestRole.SPINNER) "zamorak_cape" else "saradomin_cape",
                gloves = "infinity_gloves",
                boots = "infinity_boots",
            )
            bot.inventory.add("water_rune", 3000)
            bot.inventory.add("death_rune", 2000)
            bot.inventory.add("blood_rune", 1000)
            bot["desert_treasure"] = "completed"
            bot.open("ancient_spellbook")
            bot["autocast"] = InterfaceDefinitions.getComponent("ancient_spellbook", "ice_barrage")!!.get<Int>("cast_id")
            bot["autocast_spell"] = "ice_barrage"
            bot.attackRange = 8
        }
        this == PestDifficulty.VETERAN && role == PestRole.PORTAL -> bot.kit("guthans_helm", "guthans_platebody", "guthans_chainskirt", "guthans_warspear", cape = "fire_cape", gloves = "culinaromancers_gloves_10", boots = "dragon_boots")
        this == PestDifficulty.VETERAN -> {
            bot.kit("karils_coif", "karils_top", "karils_skirt", "karils_crossbow", cape = "avas_accumulator", gloves = "black_dragonhide_vambraces", boots = "ranger_boots")
            bot.equipment.transaction { set(EquipSlot.Ammo.index, Item("bolt_rack", 500)) }
        }
        role == PestRole.PORTAL -> bot.kit("dharoks_helm", "dharoks_platebody", "dharoks_platelegs", "dharoks_greataxe", cape = "obsidian_cape", gloves = "culinaromancers_gloves_10", boots = "dragon_boots")
        else -> {
            bot.kit("coif", "black_dragonhide_body", "black_dragonhide_chaps", "magic_shortbow", cape = "avas_accumulator", gloves = "black_dragonhide_vambraces", boots = "snakeskin_boots")
            bot.equipment.transaction { set(EquipSlot.Ammo.index, Item("rune_arrow", 500)) }
        }
    }
    bot.running = true
    bot["auto_retaliate"] = false
    return bot
}

private var nextCrewId = 0L

private fun Player.kit(helm: String, chest: String, legs: String, weapon: String, cape: String, gloves: String, boots: String) {
    equipment.transaction {
        set(EquipSlot.Cape.index, Item(cape))
        set(EquipSlot.Hands.index, Item(gloves))
        set(EquipSlot.Feet.index, Item(boots))
        set(EquipSlot.Hat.index, Item(helm))
        set(EquipSlot.Chest.index, Item(chest))
        set(EquipSlot.Legs.index, Item(legs))
        set(EquipSlot.Weapon.index, Item(weapon))
    }
}

internal fun Player.retireCrew() {
    if (Players.indexed(index) !== this) return
    if (!get("logged_out", false)) {
        set("logged_out", true)
        Despawn.player(this)
    }
    Players.remove(this)
}

private val protections = listOf("protect_from_melee", "protect_from_magic", "protect_from_missiles")

private fun Player.protectFrom(threat: NPC?) {
    val prayer = when {
        threat == null || levels.get(Skill.Prayer) <= 0 -> null
        threat.id.startsWith("torcher_") -> protections[1]
        threat.id.startsWith("defiler_") -> protections[2]
        else -> protections[0]
    }
    if (prayer != null && containsVarbit(ACTIVE_PRAYERS, prayer)) return
    for (active in protections) if (containsVarbit(ACTIVE_PRAYERS, active)) removeVarbit(ACTIVE_PRAYERS, active)
    if (prayer != null) addVarbit(ACTIVE_PRAYERS, prayer)
}

internal fun PestMatch.commandCrew() {
    if (players.none { it.pestRole != null }) return
    val pests = actors.filter { it.id in pestTypes && !it.dead && NPCs.indexed(it.index) === it && it.levels.get(Skill.Constitution) > 0 }
    val standing = portals.filterIndexed { index, npc -> index !in destroyed && !npc.dead && npc.levels.get(Skill.Constitution) > 0 }
    val open = standing.filterNot { it.get("pest_shield", true) }
    val vanguard = players.firstOrNull { it.pestRole == PestRole.PORTAL }
    val portal = open.firstOrNull { it === vanguard?.target }
        ?: open.minByOrNull { it.tile.distanceTo(vanguard?.tile ?: knight.tile) }
    val front = portal ?: standing.firstOrNull()
    val frontPests = pests.filter { front != null && it.tile.within(front.tile, 8) }
    val healer = frontPests.filter { it.id.startsWith("spinner_") && it.get<Int>("pest_origin") == front?.get<Int>("pest_portal") }
        .minByOrNull { it.levels.get(Skill.Constitution) }
    for (bot in players) {
        val role = bot.pestRole ?: continue
        if (bot.dead || bot.levels.get(Skill.Constitution) == 0) continue
        val threat = pests.filter { it.target === bot }.minByOrNull { bot.tile.distanceTo(it.tile) }
        bot.protectFrom(threat)
        val post = when {
            role == PestRole.GATE -> at(pestKnight.addY(-4))
            role == PestRole.SPINNER && front != null -> at(pestSpawns[portals.indexOf(front)])
            else -> at(pestKnight.addX(1))
        }
        var enemy = when (role) {
            PestRole.KNIGHT, PestRole.GATE -> pests.filter { it.tile.within(post, 8) || it.target === knight && it.tile.within(knight.tile, 12) }
                .minWithOrNull(compareBy<NPC> { it.target !== knight }.thenBy { it.levels.get(Skill.Constitution) }.thenBy { it.tile.distanceTo(post) })
            PestRole.PORTAL -> if (difficulty == PestDifficulty.VETERAN) portal else healer ?: portal
            PestRole.SPINNER -> healer ?: frontPests.minByOrNull { bot.tile.distanceTo(it.tile) }
        }
        val strategy = TargetStrategy(bot, enemy ?: post)
        val range = when {
            enemy == null -> 1
            bot.attackRange == 1 -> -1
            else -> bot.attackRange
        }
        if (!Movement(bot, strategy).arrived(range)) {
            val route = get<PathFinder>().findPath(bot, strategy, null)
            if (!route.success || route.alternative) {
                if (openCrewGate(bot, strategy)) continue
                if (role == PestRole.PORTAL && enemy != null) {
                    enemy = pests.firstOrNull { it.id.startsWith("brawler_") && bot.tile.within(it.tile, 2) } ?: enemy
                }
            }
        }
        if (enemy != null) {
            if (bot.target !== enemy || bot.mode === EmptyMode) Combat.combat(bot, enemy)
        } else {
            bot.target = null
            if (!bot.tile.within(post, 1)) bot.walkTo(post) else bot.mode = EmptyMode
        }
    }
}

private fun PestMatch.openCrewGate(bot: Player, destination: TargetStrategy): Boolean {
    val finder = get<PathFinder>()
    val gate = barriers.asSequence()
        .filter { it.gate && !it.open && it.health > 0 }
        .sortedBy { bot.tile.distanceTo(it.current.tile) + it.current.tile.distanceTo(destination.tile) }
        .firstOrNull {
            val route = finder.findPath(bot, TargetStrategy(bot, it.current), null)
            route.success && !route.alternative
        } ?: return false
    bot.target = null
    if (bot.tile.within(gate.current.tile, 2)) {
        setGate(bot, gate, true)
    } else {
        bot.mode = Movement(bot, TargetStrategy(bot, gate.current))
    }
    return true
}
