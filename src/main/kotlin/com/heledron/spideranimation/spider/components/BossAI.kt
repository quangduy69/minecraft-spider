package com.heledron.spideranimation.spider.components

import com.heledron.spideranimation.AppState
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.utilities.ecs.ECS
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Spider
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.plugin.java.JavaPlugin
import kotlin.random.Random

private const val MAX_HP = 300.0
private const val DAMAGE = 30.0
private const val DETECTION_RANGE = 24.0
private const val DETECTION_RANGE_SQ = DETECTION_RANGE * DETECTION_RANGE
private const val ATTACK_RANGE = 5.0 
private const val ATTACK_RANGE_SQ = ATTACK_RANGE * ATTACK_RANGE
private const val ATTACK_COOLDOWN = 40

private const val BOSS_SCALE = 6.0
private const val HITBOX_WIDTH = 6.0f
private const val HITBOX_HEIGHT = 4.5f

class BossAI {
    var hp = MAX_HP
    var lastAttackTime = System.currentTimeMillis()
    var hitbox: Interaction? = null
    var dead = false
}

fun setupBossAI(app: ECS, plugin: JavaPlugin) {
    app.onTick {
        for ((entity, spider, boss) in app.query<ECSEntity, SpiderBody, BossAI>()) {
            if (boss.dead) continue

            val loc = spider.location()

            if (boss.hitbox == null || boss.hitbox!!.isDead) {
                boss.hitbox = loc.world!!.spawn(loc, Interaction::class.java) {
                    it.interactionWidth = HITBOX_WIDTH
                    it.interactionHeight = HITBOX_HEIGHT
                    it.isPersistent = false
                }
            }
            boss.hitbox?.teleport(loc)

            val world = loc.world ?: continue
            val now = System.currentTimeMillis()

            val nearestPlayer = world.players
                .filter { !it.isDead && it.gameMode != GameMode.CREATIVE && it.gameMode != GameMode.SPECTATOR }
                .map { it to it.location.distanceSquared(loc) }
                .filter { it.second <= DETECTION_RANGE_SQ }
                .minByOrNull { it.second }?.first

            if (nearestPlayer != null) {
                val distSq = nearestPlayer.location.distanceSquared(loc)

                entity.removeComponent<StayStillBehaviour>()
                entity.replaceComponent<TargetBehaviour>(TargetBehaviour(
                    target = nearestPlayer.location.toVector(),
                    distance = ATTACK_RANGE
                ))

                if (distSq <= ATTACK_RANGE_SQ && now - boss.lastAttackTime >= ATTACK_COOLDOWN * 50L) {
                    nearestPlayer.damage(DAMAGE)
                    val kb = nearestPlayer.location.toVector()
                        .subtract(spider.position)
                        .setY(0.5)
                        .normalize()
                        .multiply(1.8)
                    nearestPlayer.velocity = kb
                    world.playSound(loc, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.5f, 0.8f)
                    boss.lastAttackTime = now
                }
            } else {
                entity.removeComponent<TargetBehaviour>()
                entity.replaceComponent<StayStillBehaviour>(StayStillBehaviour())
            }

            val percent = (boss.hp / MAX_HP).coerceIn(0.0, 1.0)
            val bars = (percent * 20).toInt()
            val bar = "§c${"█".repeat(bars)}§8${"█".repeat(20 - bars)}"
            val msg = "§4§l⚠ Spider Boss §r$bar §c${boss.hp.toInt()}§7/§c${MAX_HP.toInt()}"
            world.players
                .filter { it.location.distanceSquared(loc) <= DETECTION_RANGE_SQ * 4 }
                .forEach { it.sendActionBar(Component.text(msg)) }
        }
    }

    fun isSpaceSafe(loc: Location): Boolean {
        val world = loc.world ?: return false
        val startX = loc.blockX - 3
        val startY = loc.blockY
        val startZ = loc.blockZ - 3
        
        for (x in 0 until 6) {
            for (y in 0 until 6) {
                for (z in 0 until 6) {
                    if (!world.getBlockAt(startX + x, startY + y, startZ + z).type.isAir) {
                        return false 
                    }
                }
            }
        }
        return true
    }

    fun killBoss(entity: ECSEntity, spider: SpiderBody, boss: BossAI) {
        if (boss.dead) return
        boss.dead = true

        val loc = spider.location()
        val world = loc.world ?: return

        world.playSound(loc, Sound.ENTITY_WARDEN_DEATH, 1.5f, 0.7f)
        
        world.dropItemNaturally(loc, org.bukkit.inventory.ItemStack(Material.NETHERITE_INGOT, 4))
        world.dropItemNaturally(loc, org.bukkit.inventory.ItemStack(Material.EMERALD, 10))
        world.spawn(loc, org.bukkit.entity.ExperienceOrb::class.java) { it.experience = 12000 }

        boss.hitbox?.remove()
        boss.hitbox = null
        entity.remove()
    }

    plugin.server.pluginManager.registerEvents(object : Listener {
        @EventHandler
        fun onDamage(event: EntityDamageByEntityEvent) {
            val interaction = event.entity as? Interaction ?: return
            val damager = event.damager
            if (damager !is Player && damager !is Projectile) return

            val result = app.query<ECSEntity, SpiderBody, BossAI>()
                .firstOrNull { (_, _, b) -> b.hitbox?.entityId == interaction.entityId } ?: return

            val (entity, spider, boss) = result
            
            spider.location().world?.playSound(spider.location(), Sound.ENTITY_WARDEN_HURT, 1f, 1.2f)
            
            boss.hp -= event.finalDamage
            if (boss.hp <= 0) {
                killBoss(entity, spider, boss)
            }
        }

        @EventHandler
        fun onSpiderSpawn(event: CreatureSpawnEvent) {
            val entity = event.entity
            if (entity !is Spider) return
            
            val loc = event.location
            if (loc.blockY <= 20 && Random.nextInt(100) < 10 && isSpaceSafe(loc)) {
                event.isCancelled = true
                
                plugin.server.scheduler.runTask(plugin, Runnable {
                    AppState.options = com.heledron.spideranimation.spider.presets.octopod(4, BOSS_SCALE)
                    AppState.options.cloak.enabled = true
                    AppState.createSpider(loc)
                })
            }
        }
    }, plugin)
}
