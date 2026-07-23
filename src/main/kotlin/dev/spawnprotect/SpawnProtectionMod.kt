package dev.spawnprotect

import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.literal
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Server-side region protection. Define a cuboid in-game with the wand; while enabled, non-opped
 * players can't break or place blocks inside it, but interactions (right-click use) still pass so
 * mods like sit-on-stairs keep working. Ops bypass everything.
 */
object SpawnProtectionMod : DedicatedServerModInitializer {

    val LOG = LoggerFactory.getLogger("spawn-protection")

    lateinit var config: SpawnProtectionConfig
        private set

    private lateinit var configPath: Path

    override fun onInitializeServer() {
        configPath = FabricLoader.getInstance().configDir.resolve("spawn-protection.json")
        config = SpawnProtectionConfig.load(configPath)

        registerProtection()
        registerCommand()

        LOG.info("Spawn Protection enabled={} region={}", config.enabled, config.region)
    }

    // --- protection + wand event hooks ---

    private fun registerProtection() {
        // Left-click a block: wand corner 1 (ops), else block-break protection (non-ops).
        AttackBlockCallback.EVENT.register(AttackBlockCallback { player, world, hand, pos, _ ->
            if (world.isClientSide || hand != InteractionHand.MAIN_HAND) return@AttackBlockCallback InteractionResult.PASS
            val sp = player as? ServerPlayer ?: return@AttackBlockCallback InteractionResult.PASS

            if (isOp(world, sp)) {
                if (isWand(sp, hand)) {
                    if (sp.isShiftKeyDown) toggleOverride(sp, world, pos, "DENY")
                    else setCorner(sp, world, pos, first = true)
                    return@AttackBlockCallback InteractionResult.FAIL // don't actually break
                }
                return@AttackBlockCallback InteractionResult.PASS // ops build freely
            }

            if (breakBlocked(world, pos)) {
                deny(sp, "You can't break blocks here.")
                return@AttackBlockCallback InteractionResult.FAIL
            }
            InteractionResult.PASS
        })

        // Authoritative break cancel for non-ops (covers held-mining + instant breaks).
        PlayerBlockBreakEvents.BEFORE.register(PlayerBlockBreakEvents.Before { world, player, pos, _, _ ->
            val sp = player as? ServerPlayer ?: return@Before true
            if (isOp(world, sp)) return@Before true
            if (breakBlocked(world, pos)) {
                deny(sp, "You can't break blocks here.")
                return@Before false
            }
            true
        })

        // Right-click a block: wand corner 2 (ops), else block-place protection (non-ops).
        // Interactions (non-block items) always pass so sit mods / doors / buttons work.
        UseBlockCallback.EVENT.register(UseBlockCallback { player, world, hand, hit ->
            if (world.isClientSide) return@UseBlockCallback InteractionResult.PASS
            val sp = player as? ServerPlayer ?: return@UseBlockCallback InteractionResult.PASS
            val pos = hit.blockPos

            if (isOp(world, sp)) {
                if (hand == InteractionHand.MAIN_HAND && isWand(sp, hand)) {
                    if (sp.isShiftKeyDown) toggleOverride(sp, world, pos, "ALLOW")
                    else setCorner(sp, world, pos, first = false)
                    return@UseBlockCallback InteractionResult.FAIL
                }
                return@UseBlockCallback InteractionResult.PASS
            }

            when (overrideAt(world, pos)) {
                "ALLOW" -> return@UseBlockCallback InteractionResult.PASS // fully editable
                "DENY" -> {
                    deny(sp, "This block is protected.")
                    return@UseBlockCallback InteractionResult.FAIL // blocks interaction too (signs/trapdoors)
                }
            }

            if (isProtected(world, pos) && sp.getItemInHand(hand).item is BlockItem) {
                deny(sp, "You can't place blocks here.")
                return@UseBlockCallback InteractionResult.FAIL
            }
            InteractionResult.PASS // interactions allowed
        })

        // Optional safe zone: cancel damage to players inside the region, but ops bypass, so an
        // op can still hit players in the zone.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(ServerLivingEntityEvents.AllowDamage { entity, source, _ ->
            if (entity !is ServerPlayer) return@AllowDamage true
            if (config.playerDamage) return@AllowDamage true
            if (!isProtected(entity.level(), entity.blockPosition())) return@AllowDamage true
            // safe zone active: allow the hit only if the attacker is an op.
            val attacker = source.entity
            if (attacker is ServerPlayer && isOp(entity.level(), attacker)) return@AllowDamage true
            false // otherwise block the damage
        })

        // World-border-style containment: shove non-ops back inside the region each tick.
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            Border.tick(server, config) { sp -> isOp(sp.level(), sp) }
        })
    }

    // --- helpers ---

    private fun isOp(world: Level, player: ServerPlayer): Boolean {
        val server = (world as? ServerLevel)?.getServer() ?: return false
        return server.playerList.isOp(player.nameAndId())
    }

    private fun isWand(player: ServerPlayer, hand: InteractionHand): Boolean =
        itemId(player.getItemInHand(hand)) == config.wandItem

    private fun itemId(stack: ItemStack): String =
        BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun dimensionId(world: Level): String =
        world.dimension().identifier().toString()

    private fun isProtected(world: Level, pos: BlockPos): Boolean {
        if (!config.enabled) return false
        val region = config.region ?: return false
        return region.contains(dimensionId(world), pos.x, pos.y, pos.z)
    }

    private fun posKey(world: Level, pos: BlockPos): String =
        "${dimensionId(world)};${pos.x},${pos.y},${pos.z}"

    /** "ALLOW", "DENY", or null for this block. */
    private fun overrideAt(world: Level, pos: BlockPos): String? =
        config.blockOverrides[posKey(world, pos)]?.uppercase()

    /** Whether a non-op break should be blocked, factoring in per-block overrides. */
    private fun breakBlocked(world: Level, pos: BlockPos): Boolean = when (overrideAt(world, pos)) {
        "ALLOW" -> false
        "DENY" -> true
        else -> isProtected(world, pos)
    }

    /** Toggle a per-block override (clears it if already set to that mode). Persists. */
    private fun toggleOverride(player: ServerPlayer, world: Level, pos: BlockPos, mode: String) {
        val key = posKey(world, pos)
        if (config.blockOverrides[key].equals(mode, ignoreCase = true)) {
            config.blockOverrides.remove(key)
            notify(player, "&eCleared override at &f(${pos.x}, ${pos.y}, ${pos.z})")
        } else {
            config.blockOverrides[key] = mode
            val label = if (mode == "DENY") "&cfully protected (DENY)" else "&abypass (ALLOW)"
            notify(player, "&fBlock (${pos.x}, ${pos.y}, ${pos.z}) -> $label")
        }
        config.save(configPath)
    }

    private fun setCorner(player: ServerPlayer, world: Level, pos: BlockPos, first: Boolean) {
        val sel = Selections.of(player)
        sel.dimension = dimensionId(world)
        if (first) sel.pos1 = pos.immutable() else sel.pos2 = pos.immutable()
        notify(player, "&aCorner ${if (first) 1 else 2} set: &f(${pos.x}, ${pos.y}, ${pos.z})")
    }

    private fun deny(player: ServerPlayer, msg: String) {
        // action bar so held-mining doesn't spam chat
        player.sendOverlayMessage(Component.literal("§c$msg"))
    }

    private fun notify(player: ServerPlayer, legacy: String) {
        player.sendSystemMessage(Component.literal(legacy.replace('&', '§')))
    }

    // --- command ---

    private fun registerCommand() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            dispatcher.register(
                literal("spawnprotect")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // op level 2
                    .executes { status(it.source); 1 }
                    .then(literal("status").executes { status(it.source); 1 })
                    .then(literal("wand").executes { giveWand(it); 1 })
                    .then(literal("set").executes { setRegion(it); 1 })
                    .then(literal("clear").executes { clearRegion(it); 1 })
                    .then(literal("toggle").executes { toggle(it); 1 })
                    .then(
                        literal("damage")
                            .then(literal("on").executes { setDamage(it, true); 1 })
                            .then(literal("off").executes { setDamage(it, false); 1 })
                    )
                    .then(
                        literal("border")
                            .then(literal("on").executes { setBorder(it, true); 1 })
                            .then(literal("off").executes { setBorder(it, false); 1 })
                    )
                    .then(
                        literal("blocks")
                            .then(literal("list").executes { listBlocks(it.source); 1 })
                            .then(literal("clear").executes {
                                val n = config.blockOverrides.size
                                config.blockOverrides.clear()
                                config.save(configPath)
                                it.source.sendSuccess({ Component.literal("[SpawnProtect] cleared $n block override(s).") }, false)
                                1
                            })
                    )
                    .then(literal("reload").executes {
                        config = SpawnProtectionConfig.load(configPath)
                        it.source.sendSuccess({ Component.literal("[SpawnProtect] reloaded.") }, false)
                        1
                    })
            )
        })
    }

    private fun status(source: CommandSourceStack) {
        val region = config.region?.toString() ?: "none set"
        val damage = if (config.playerDamage) "allowed" else "blocked (safe zone)"
        source.sendSuccess(
            {
                Component.literal(
                    "[SpawnProtect] enabled=${config.enabled}, wand=${config.wandItem}, " +
                        "playerDamage=$damage, border=${if (config.borderEnabled) "on" else "off"}, " +
                        "overrides=${config.blockOverrides.size}, region=$region",
                )
            },
            false,
        )
    }

    private fun listBlocks(source: CommandSourceStack) {
        val overrides = config.blockOverrides
        if (overrides.isEmpty()) {
            source.sendSuccess({ Component.literal("[SpawnProtect] no per-block overrides set.") }, false)
            return
        }
        source.sendSuccess({ Component.literal("[SpawnProtect] ${overrides.size} block override(s):") }, false)
        overrides.entries.take(20).forEach { (key, mode) ->
            source.sendSuccess({ Component.literal(" - $key -> $mode") }, false)
        }
        if (overrides.size > 20) {
            source.sendSuccess({ Component.literal(" ...and ${overrides.size - 20} more") }, false)
        }
    }

    private fun setDamage(ctx: CommandContext<CommandSourceStack>, allow: Boolean): Int {
        config.playerDamage = allow
        config.save(configPath)
        val state = if (allow) "allowed" else "blocked (safe zone)"
        ctx.source.sendSuccess({ Component.literal("[SpawnProtect] player damage in region: $state") }, false)
        return 1
    }

    private fun setBorder(ctx: CommandContext<CommandSourceStack>, on: Boolean): Int {
        config.borderEnabled = on
        config.save(configPath)
        ctx.source.sendSuccess({ Component.literal("[SpawnProtect] region border: ${if (on) "on" else "off"}") }, false)
        return 1
    }

    private fun giveWand(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return playersOnly(ctx)
        val item = BuiltInRegistries.ITEM
            .getOptional(Identifier.tryParse(config.wandItem))
            .orElse(Items.WOODEN_AXE)
        player.inventory.add(ItemStack(item))
        player.sendSystemMessage(Component.literal("§aGiven the protection wand (${config.wandItem}). Left-click = corner 1, right-click = corner 2, then /spawnprotect set."))
        return 1
    }

    private fun setRegion(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return playersOnly(ctx)
        val sel = Selections.of(player)
        if (!sel.complete) {
            player.sendSystemMessage(Component.literal("§cSelect both corners with the wand first."))
            return 1
        }
        val p1 = sel.pos1!!
        val p2 = sel.pos2!!
        config.region = SpawnProtectionConfig.Region.of(sel.dimension!!, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z)
        config.save(configPath)
        player.sendSystemMessage(Component.literal("§aProtected region set: §f${config.region}"))
        return 1
    }

    private fun clearRegion(ctx: CommandContext<CommandSourceStack>): Int {
        config.region = null
        config.save(configPath)
        ctx.source.sendSuccess({ Component.literal("[SpawnProtect] region cleared.") }, false)
        return 1
    }

    private fun toggle(ctx: CommandContext<CommandSourceStack>): Int {
        config.enabled = !config.enabled
        config.save(configPath)
        ctx.source.sendSuccess({ Component.literal("[SpawnProtect] enabled=${config.enabled}") }, false)
        return 1
    }

    private fun playersOnly(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("[SpawnProtect] that subcommand must be run by a player.") }, false)
        return 1
    }
}
