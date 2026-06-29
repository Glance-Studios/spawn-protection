package dev.spawnprotect

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** In-memory wand selections, per player (not persisted, only the saved region is). */
object Selections {

    class Selection {
        var pos1: BlockPos? = null
        var pos2: BlockPos? = null
        var dimension: String? = null

        val complete: Boolean get() = pos1 != null && pos2 != null && dimension != null
    }

    private val byPlayer = HashMap<UUID, Selection>()

    fun of(player: ServerPlayer): Selection = byPlayer.getOrPut(player.uuid) { Selection() }

    fun clear(player: ServerPlayer) {
        byPlayer.remove(player.uuid)
    }
}
