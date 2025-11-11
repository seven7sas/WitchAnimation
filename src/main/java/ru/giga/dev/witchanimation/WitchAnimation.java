package ru.giga.dev.witchanimation;

import blib.com.mojang.serialization.Codec;
import blib.com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.by1337.bc.CaseBlock;
import dev.by1337.bc.animation.AbstractAnimation;
import dev.by1337.bc.animation.AnimationContext;
import dev.by1337.bc.annotations.SyncOnly;
import dev.by1337.bc.engine.MoveEngine;
import dev.by1337.bc.particle.ParticleUtil;
import dev.by1337.bc.prize.Prize;
import dev.by1337.bc.prize.PrizeSelector;
import dev.by1337.bc.task.AsyncTask;
import dev.by1337.bc.world.WorldEditor;
import dev.by1337.bc.yaml.CashedYamlContext;
import dev.by1337.virtualentity.api.entity.EquipmentSlot;
import dev.by1337.virtualentity.api.virtual.VirtualEntity;
import dev.by1337.virtualentity.api.virtual.decoration.VirtualArmorStand;
import dev.by1337.virtualentity.api.virtual.item.VirtualFallingBlockEntity;
import dev.by1337.virtualentity.api.virtual.item.VirtualItem;
import dev.by1337.virtualentity.api.virtual.monster.VirtualWitch;
import dev.by1337.yaml.YamlMap;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.by1337.blib.configuration.YamlValue;
import org.by1337.blib.configuration.adapter.codec.YamlCodec;
import org.by1337.blib.configuration.serialization.BukkitCodecs;
import org.by1337.blib.configuration.serialization.DefaultCodecs;
import org.by1337.blib.geom.Vec3d;
import org.by1337.blib.geom.Vec3i;
import org.by1337.blib.util.Version;

import java.util.*;

public class WitchAnimation extends AbstractAnimation {

    private static final Particle BLOCK_CRACK = Version.is1_21_5orNewer() ? Particle.BLOCK_CRACK : Particle.valueOf("BLOCK");

    private final Prize winner;
    private final Set<Vec3i> cauldrons = new HashSet<>();
    private volatile Vec3i selected;
    private WorldEditor worldEditor;
    private volatile boolean waitClick;
    private final Config config;

    public WitchAnimation(CaseBlock caseBlock, AnimationContext context, Runnable onEndCallback, PrizeSelector prizeSelector, CashedYamlContext yaml, Player player) {
        super(caseBlock, context, onEndCallback, prizeSelector, yaml, player);
        config = yaml.get("settings", v -> YamlCodec.codecOf(Config.CODEC).decode(v));
        winner = prizeSelector.getRandomPrize();
    }

    @Override
    @SyncOnly
    protected void onStart() {
        caseBlock.hideHologram();
        worldEditor = new WorldEditor(world);
    }

    @Override
    protected void animate() throws InterruptedException {
        for (Vec3i dest : config.positions) {
            spawnCauldron(blockPos.add(dest));
            sleepTicks(4);
        }
        sendTitle("", config.title, 5, 30, 10);

        VirtualWitch witch = VirtualWitch.create();
        witch.setPos(center.add(config.entityOffset));
        witch.setMotion(Vec3d.ZERO);
        witch.addTickTask(() -> witch.lookAt(new Vec3d(player.getLocation())));
        trackEntity(witch);

        waitClick = true;
        waitUpdate(10_000);
        if (selected == null) {
            selected = cauldrons.iterator().next();
        }

        witch.removeAllTickTask();
        witch.lookAt(selected.toVec3d());

        Block block = world.getBlockAt(selected.toLocation(world));
        ItemStack potionItem = createPotion();

        for (int i = 0; i < 3; i++) {
            witch.setEquipment(EquipmentSlot.MAINHAND, potionItem);
            sleepTicks(3);
            witch.setEquipment(EquipmentSlot.MAINHAND, null);
            spawnPotion(potionItem);
            int finalI = i;
            sync(() -> {
                Levelled cauldron = (Levelled) (Version.is1_17orNewer() ? Material.valueOf("WATER_CAULDRON") : Material.CAULDRON).createBlockData();
                cauldron.setLevel(Math.min(cauldron.getMaximumLevel(), finalI + 1));
                block.setBlockData(cauldron);
            }).start();
            sleepTicks(config.potion.interval);
        }

        var partTask = AsyncTask.create(() ->
                config.brew.particles.spawn(this, selected.toVec3d().add(0.5, 0, 0.5))
        ).timer().delay(2).start(this);

        var soundTask = AsyncTask.create(() ->
                playSound(Sound.BLOCK_BREWING_STAND_BREW, 1, 1)
        ).timer().delay(20).start(this);

        sleepTicks(60);
        partTask.cancel();
        soundTask.cancel();

        config.destroy.sound.play(this);
        config.destroy.particles.spawn(this, selected.toVec3d().add(0.5, 0.3, 0.5));

        worldEditor.setType(selected, Material.AIR);
        trackEntity(winner.createVirtualItem(selected.toVec3d().add(0.5, 0.3, 0.5)));

        new AsyncTask() {
            final Vec3d pos = selected.toVec3d().add(0.5, 0, 0.5);

            @Override
            public void run() {
                ParticleUtil.spawnBlockOutlining(pos, WitchAnimation.this, Particle.FLAME, 0.1);
            }
        }.timer().delay(6).start(this);

        sleepTicks(40);
        for (Vec3i spawnPoint : config.positions) {
            Vec3i pos = blockPos.add(spawnPoint);
            if (pos.equals(selected)) continue;
            worldEditor.setType(pos, Material.AIR);
            config.destroy.sound.play(this);
            config.destroy.particles.spawn(this, pos.toVec3d().add(0.5, 0.3, 0.5));
            trackEntity(prizeSelector.getRandomPrize().createVirtualItem(pos.toVec3d().add(0.5, 0.3, 0.5)));
        }
        sleepTicks(60);
    }

    private void spawnPotion(ItemStack item) throws InterruptedException {
        VirtualItem potion = VirtualItem.create();
        potion.setItem(item);
        potion.setPos(center.add(0, 2.5, 0));
        potion.setNoGravity(true);
        potion.setMotion(Vec3d.ZERO);
        trackEntity(potion);

        config.potion.goTo(potion, selected.toVec3d().add(0.5, 0, 0.5))
                .onEnd(() -> {
                    config.brew.sound.play(this);
                    removeEntity(potion);
                })
                .startSync(this);
    }

    private void spawnCauldron(Vec3i dest) {
        VirtualEntity cauldron = createCauldron();
        trackEntity(cauldron);

        cauldrons.add(dest);
        Vec3d destPos = new Vec3d(dest).add(0.5, 0.5, 0.5);
        playSound(Sound.BLOCK_PISTON_EXTEND, 1, 1);
        MoveEngine.goToParabola(cauldron, destPos, 2, 5)
                .onEnd(() -> {
                    config.soundPlace.play(this);
                    spawnParticle(BLOCK_CRACK, destPos, 15, Material.CAULDRON.createBlockData());
                    worldEditor.setType(dest, Material.CAULDRON);
                    removeEntity(cauldron);
                })
                .start(this);
    }

    private ItemStack createPotion() {
        ItemStack itemPotion = new ItemStack(Material.SPLASH_POTION);
        itemPotion.editMeta(meta -> {
            PotionMeta potionMeta = (PotionMeta) meta;
            potionMeta.setColor(config.potion.color);
        });
        return itemPotion;
    }

    private VirtualEntity createCauldron() {
        VirtualArmorStand armorStand = VirtualArmorStand.create();
        armorStand.setPos(center);
        armorStand.setNoBasePlate(true);
        armorStand.setSilent(true);
        armorStand.setMarker(true);
        armorStand.setSmall(true);
        armorStand.setInvisible(true);
        armorStand.setNoGravity(true);
        armorStand.setEquipment(EquipmentSlot.HEAD, new ItemStack(Material.GRAY_GLAZED_TERRACOTTA));
        return armorStand;
    }

    @Override
    @SyncOnly
    protected void onEnd() {
        if (worldEditor != null) worldEditor.close();
        caseBlock.showHologram();
        caseBlock.givePrize(winner, player);
    }

    @Override
    protected void onClick(VirtualEntity virtualEntity, Player player) {

    }

    @Override
    @SyncOnly
    public void onInteract(PlayerInteractEvent event) {
        if (!waitClick) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Vec3i pos = new Vec3i(block);
        if (!cauldrons.contains(pos)) return;
        selected = pos;
        waitClick = false;
        update();
    }

    private record Config(List<Vec3i> positions, Potion potion, Sound soundPlace, SoundParticles brew, SoundParticles destroy, Vec3d entityOffset, String title) {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3i.CODEC.listOf().fieldOf("positions").forGetter(Config::positions),
                Potion.CODEC.fieldOf("potion").forGetter(Config::potion),
                Sound.CODEC.fieldOf("sound_place").forGetter(Config::soundPlace),
                SoundParticles.CODEC.fieldOf("brew").forGetter(Config::brew),
                SoundParticles.CODEC.fieldOf("destroy").forGetter(Config::destroy),
                Vec3d.CODEC.fieldOf("witch_offset").forGetter(Config::entityOffset),
                Codec.STRING.fieldOf("title").forGetter(Config::title)
        ).apply(instance, Config::new));

        public record Potion(Vec3d offset, double speed, double height, Color color, long interval) {
            public static final Codec<Potion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Vec3d.CODEC.fieldOf("offset").forGetter(Potion::offset),
                    Codec.DOUBLE.fieldOf("speed").forGetter(Potion::speed),
                    Codec.DOUBLE.fieldOf("height").forGetter(Potion::height),
                    BukkitCodecs.COLOR.fieldOf("color").forGetter(Potion::color),
                    Codec.LONG.fieldOf("interval").forGetter(Potion::interval)
            ).apply(instance, Potion::new));

            public AsyncTask goTo(VirtualEntity entity, Vec3d center) {
                return MoveEngine.goToParabola(entity, center.add(offset), speed, height);
            }
        }

        public record SoundParticles(Sound sound, Particles particles) {
            public static final Codec<SoundParticles> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Sound.CODEC.fieldOf("sound").forGetter(SoundParticles::sound),
                    Particles.CODEC.fieldOf("particles").forGetter(SoundParticles::particles)
            ).apply(instance, SoundParticles::new));
        }

        public record Sound(org.bukkit.Sound bukkit, float volume, float pitch) {
            public final static Codec<Sound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    SoundFixer.CODEC.fieldOf("name").forGetter(Sound::bukkit),
                    Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(Sound::volume),
                    Codec.FLOAT.optionalFieldOf("pitch", 1f).forGetter(Sound::pitch)
            ).apply(instance, Sound::new));

            public void play(AbstractAnimation animation) {
                animation.playSound(bukkit, volume, pitch);
            }
        }

        public record Particles(Particle particle, Vec3d posOffset, Vec3d partOffset, int count, double speed) {
            public final static Codec<Particle> PARTICLE = DefaultCodecs.createAnyEnumCodec(Particle.class);
            public final static Codec<Particles> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    PARTICLE.fieldOf("name").forGetter(Particles::particle),
                    Vec3d.CODEC.optionalFieldOf("pos_offset", Vec3d.ZERO).forGetter(Particles::posOffset),
                    Vec3d.CODEC.fieldOf("part_offset").forGetter(Particles::partOffset),
                    Codec.INT.optionalFieldOf("count", 10).forGetter(Particles::count),
                    Codec.DOUBLE.optionalFieldOf("speed", 0.1).forGetter(Particles::speed)
            ).apply(instance, Particles::new));

            public void spawn(AbstractAnimation animation, Vec3d pos) {
                animation.spawnParticle(particle, pos.add(posOffset.toVector()), count, partOffset.x, partOffset.y, partOffset.z, speed);
            }
        }
    }
}
