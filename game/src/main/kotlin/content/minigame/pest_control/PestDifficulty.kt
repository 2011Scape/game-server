package content.minigame.pest_control

import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random

internal enum class PestDifficulty(
    val id: String,
    val combatLevel: Int,
    val points: Int,
    val dock: Tile,
    val boat: Tile,
    private val boatX: IntRange,
    private val boatY: IntRange,
    private val tiers: IntRange,
    val crewLevel: Int,
    val roles: List<PestRole>,
) {
    NOVICE("novice", 40, 2, Tile(2657, 2639), Tile(2661, 2639), 2659..2664, 2637..2644, 0..0, 65, listOf(PestRole.PORTAL, PestRole.KNIGHT)),
    INTERMEDIATE("intermediate", 70, 3, Tile(2644, 2644), Tile(2640, 2644), 2637..2642, 2641..2647, 1..3, 80, listOf(PestRole.PORTAL, PestRole.KNIGHT, PestRole.GATE)),
    VETERAN("veteran", 100, 4, Tile(2638, 2653), Tile(2634, 2653), 2631..2636, 2648..2654, 2..4, 90, listOf(PestRole.PORTAL, PestRole.KNIGHT, PestRole.GATE, PestRole.SPINNER)),
    ;

    val gangplank = "pest_${id}_gangplank"
    val ladder = "pest_${id}_ladder"
    val squire = "squire_${id}_pest_control"

    fun onBoat(tile: Tile) = tile.level == 0 && tile.x in boatX && tile.y in boatY

    fun randomPest(): String {
        val family = pestFamilies.random(random)
        return family[tiers.filter { it in family.indices }.random(random)]
    }
}

internal val pestFamilies = listOf(
    listOf("brawler_level_51", "brawler_level_76", "brawler_level_101", "brawler_level_129"),
    listOf("defiler_level_33", "defiler_level_50", "defiler_level_66", "defiler_level_80", "defiler_level_97"),
    listOf("ravager_level_36", "ravager_level_53", "ravager_level_71", "ravager_level_89", "ravager_level_106"),
    listOf("shifter_level_38", "shifter_level_57", "shifter_level_76", "shifter_level_90", "shifter_level_104"),
    listOf("spinner_level_36", "spinner_level_55", "spinner_level_74", "spinner_level_88", "spinner_level_92"),
    listOf("splatter_level_22", "splatter_level_33", "splatter_level_44", "splatter_level_54", "splatter_level_65"),
    listOf("torcher_level_33", "torcher_level_49", "torcher_level_66", "torcher_level_79", "torcher_level_91"),
)
internal val pestTypes = pestFamilies.flatten().toSet()
internal val pestActors = "portal_purple,portal_blue,portal_yellow,portal_red,void_knight," + pestTypes.joinToString(",")
internal fun Player.pestLander() = PestDifficulty.entries.getOrNull(get("pest_control_lander", 0)) ?: PestDifficulty.NOVICE
internal fun Player.onPestBoat() = PestDifficulty.entries.any { it.onBoat(tile) }
