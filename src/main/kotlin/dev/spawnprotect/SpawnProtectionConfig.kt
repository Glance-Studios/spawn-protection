package dev.spawnprotect

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

/**
 * On-disk config (JSON via Minecraft's bundled Gson, so no extra dependency). One protected cuboid
 * region, set in-game with the wand, inside which non-ops cannot break or place. Right-click
 * interaction is always allowed, so things like sit mods keep working.
 */
class SpawnProtectionConfig {

    /** Master on/off switch. */
    @JvmField
    var enabled: Boolean = true

    /** Item that acts as the selection wand (registry id). Op-only; like WorldEdit's axe. */
    @JvmField
    var wandItem: String = "minecraft:wooden_axe"

    /** Whether players can take damage inside the region. false = safe zone (damage cancelled). */
    @JvmField
    var playerDamage: Boolean = true

    /** The protected cuboid, or null until set with `/spawnprotect set`. */
    @JvmField
    var region: Region? = null

    /**
     * Per-block exceptions, keyed by "dimension;x,y,z". Values: "ALLOW" (bypass, fully editable)
     * or "DENY" (fully protected, break/place/interact all blocked). Takes precedence over the
     * region default. Set with sneak + wand click.
     */
    @JvmField
    var blockOverrides: MutableMap<String, String> = linkedMapOf()

    /**
     * World-border-style containment. When on, non-op players who cross the region's horizontal
     * (XZ) edge get a graceful inward velocity shove (with a slight upward pop), a dragon-flap
     * sound, and a cloud puff at their feet. Y is unbounded, like a vanilla world border.
     */
    @JvmField
    var borderEnabled: Boolean = false

    /** Horizontal strength of the shove-back. */
    @JvmField
    var borderPush: Double = 0.6

    /** Upward pop added to the shove (the little Y lift). */
    @JvmField
    var borderPushY: Double = 0.35

    /** Draw a couple of particle rows along the nearest edge when a player gets close to it. */
    @JvmField
    var borderWall: Boolean = true

    /** How close (in blocks) a player must be to an edge before the wall particles appear. */
    @JvmField
    var borderWallRange: Int = 6

    /** If true, the border also affects operators (normally ops bypass it). Handy for testing. */
    @JvmField
    var borderAffectsOps: Boolean = false

    /** Inclusive cuboid in one dimension. */
    class Region {
        @JvmField var dimension: String = "minecraft:overworld"
        @JvmField var minX: Int = 0
        @JvmField var minY: Int = 0
        @JvmField var minZ: Int = 0
        @JvmField var maxX: Int = 0
        @JvmField var maxY: Int = 0
        @JvmField var maxZ: Int = 0

        fun contains(dim: String, x: Int, y: Int, z: Int): Boolean =
            dim == dimension &&
                x in minX..maxX &&
                y in minY..maxY &&
                z in minZ..maxZ

        override fun toString(): String =
            "$dimension ($minX,$minY,$minZ)-($maxX,$maxY,$maxZ)"

        companion object {
            /** Build from two arbitrary corners (normalizes min/max). */
            fun of(dim: String, ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int): Region =
                Region().apply {
                    dimension = dim
                    minX = min(ax, bx); maxX = max(ax, bx)
                    minY = min(ay, by); maxY = max(ay, by)
                    minZ = min(az, bz); maxZ = max(az, bz)
                }
        }
    }

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun load(path: Path): SpawnProtectionConfig {
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, SpawnProtectionConfig::class.java) }
                }.getOrNull()?.let { return it }
            }
            val fresh = SpawnProtectionConfig()
            runCatching { fresh.save(path) }
            return fresh
        }
    }
}
