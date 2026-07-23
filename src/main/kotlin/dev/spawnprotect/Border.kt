package dev.spawnprotect

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * World-border-style containment for the protected region, run each server tick. A non-op crossing
 * the XZ edge gets an inward shove, a sound and a puff, with particle rows marking the nearest wall.
 * Particles render only near players and the shove has a per-player cooldown, so leaning on the wall
 * does not machine-gun it. Y is unbounded, as with a vanilla world border, and ops are exempt.
 */
object Border {

    private const val SHOVE_COOLDOWN = 6L               // ticks between velocity flaps per player
    private val WALL_HEIGHTS = doubleArrayOf(0.0, 1.5, 3.0)  // particle rows, relative to feet
    private const val WALL_HALF_SPAN = 4.0              // blocks of wall drawn either side of player
    private val lastShove = HashMap<UUID, Long>()

    fun tick(server: MinecraftServer, config: SpawnProtectionConfig, isOp: (ServerPlayer) -> Boolean) {
        if (!config.enabled || !config.borderEnabled) return
        val region = config.region ?: return
        // Continuous-space footprint: block minX..maxX spans [minX, maxX+1].
        val minX = region.minX.toDouble(); val maxX = (region.maxX + 1).toDouble()
        val minZ = region.minZ.toDouble(); val maxZ = (region.maxZ + 1).toDouble()

        for (player in server.playerList.players) {
            val level = player.level() as? ServerLevel ?: continue
            if (level.dimension().identifier().toString() != region.dimension) continue
            if (isOp(player) && !config.borderAffectsOps) continue

            val x = player.x
            val z = player.z

            if (config.borderWall) {
                drawWall(level, player, minX, maxX, minZ, maxZ, config.borderWallRange.toDouble())
            }

            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue // inside

            // Outside the edge: flap them back with velocity, throttled so it doesn't machine-gun.
            val now = level.gameTime
            val last = lastShove[player.uuid]
            if (last != null && now - last < SHOVE_COOLDOWN) continue
            lastShove[player.uuid] = now
            shove(level, player, x, z, minX, maxX, minZ, maxZ, config.borderPush, config.borderPushY)
        }
    }

    private fun shove(
        level: ServerLevel, player: ServerPlayer, x: Double, z: Double,
        minX: Double, maxX: Double, minZ: Double, maxZ: Double,
        push: Double, pushY: Double,
    ) {
        // Inward normal: only the axes the player has actually crossed contribute.
        var dx = 0.0
        var dz = 0.0
        if (x < minX) dx = 1.0 else if (x > maxX) dx = -1.0
        if (z < minZ) dz = 1.0 else if (z > maxZ) dz = -1.0
        val len = sqrt(dx * dx + dz * dz)
        if (len == 0.0) return
        dx = dx / len * push
        dz = dz / len * push

        player.setDeltaMovement(dx, pushY, dz)
        SpawnProtectionMod.LOG.info(
            "Border shove: {} push=({}, {}, {}) at ({}, {})",
            player.name.string, "%.2f".format(dx), "%.2f".format(pushY), "%.2f".format(dz),
            "%.1f".format(x), "%.1f".format(z),
        )
        // Send the motion to the player's OWN client. hurtMarked only notifies other trackers, and you
        // do not track yourself, so this packet is what actually moves them.
        player.connection.send(ClientboundSetEntityMotionPacket(player))

        level.playSound(
            null, player.x, player.y, player.z,
            SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.8f, 1.0f,
        )
        level.sendParticles(
            ParticleTypes.CLOUD, player.x, player.y + 0.1, player.z,
            12, 0.3, 0.05, 0.3, 0.02,
        )
    }

    /** Short vertical particle curtains along whichever edge(s) the player is near. */
    private fun drawWall(
        level: ServerLevel, player: ServerPlayer,
        minX: Double, maxX: Double, minZ: Double, maxZ: Double, range: Double,
    ) {
        if (level.gameTime % 4L != 0L) return // throttle to every 4 ticks
        val px = player.x
        val pz = player.z
        val py = player.y

        // X-facing edges (constant x), drawn along Z.
        for (edgeX in doubleArrayOf(minX, maxX)) {
            if (abs(px - edgeX) <= range && pz >= minZ - range && pz <= maxZ + range) {
                var zz = maxOf(minZ, pz - WALL_HALF_SPAN)
                val end = minOf(maxZ, pz + WALL_HALF_SPAN)
                while (zz <= end) {
                    for (h in WALL_HEIGHTS) {
                        level.sendParticles(ParticleTypes.END_ROD, edgeX, py + h, zz, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                    zz += 1.0
                }
            }
        }
        // Z-facing edges (constant z), drawn along X.
        for (edgeZ in doubleArrayOf(minZ, maxZ)) {
            if (abs(pz - edgeZ) <= range && px >= minX - range && px <= maxX + range) {
                var xx = maxOf(minX, px - WALL_HALF_SPAN)
                val end = minOf(maxX, px + WALL_HALF_SPAN)
                while (xx <= end) {
                    for (h in WALL_HEIGHTS) {
                        level.sendParticles(ParticleTypes.END_ROD, xx, py + h, edgeZ, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                    xx += 1.0
                }
            }
        }
    }
}
