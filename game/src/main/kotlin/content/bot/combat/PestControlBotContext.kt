package content.bot.combat

import content.minigame.pest_control.PestDifficulty
import content.minigame.pest_control.boardCrew
import content.minigame.pest_control.pestRole
import content.minigame.pest_control.retireCrew
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players

internal class PestControlBotContext {
    private val landers = mutableMapOf<PestDifficulty, MutableList<Player>>()

    fun count(difficulty: PestDifficulty) = landers[difficulty]?.size ?: 0

    fun prepare(difficulty: PestDifficulty, humans: Int) {
        val seats = if (humans > 0 && Settings["pestControl.crewEnabled", false]) 25 - humans else 0
        if (seats == 0 && difficulty !in landers) return
        val roles = difficulty.roles.take(seats)
        val crew = landers.getOrPut(difficulty) { mutableListOf() }
        for (bot in crew.toList()) {
            if (bot.pestRole !in roles || Players.indexed(bot.index) !== bot || !difficulty.onBoat(bot.tile)) {
                crew.remove(bot)
                bot.retireCrew()
            }
        }
        for (role in roles) {
            if (crew.none { it.pestRole == role }) crew.add(difficulty.boardCrew(role) ?: break)
        }
        if (crew.isEmpty()) landers.remove(difficulty)
    }

    fun board(difficulty: PestDifficulty): List<Player> = landers.remove(difficulty).orEmpty()

    fun reset() {
        landers.values.flatten().forEach { it.retireCrew() }
        landers.clear()
    }
}
