package raider;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * Raider - плагин "Ядро Рейдера".
 * Ядро (блок) можно найти в сундуках или выдать командой. Поставил - значит база.
 * 1) Через WAIT_SEC приходят переговорщики и требуют ядро.
 * 2) Если отдать (ПКМ ядром по переговорщику) - мир. Если убить или не отдать - война.
 * 3) Волны мобов со всё более странными существами, пчёлы-смертники и техника.
 */
public final class Raider extends JavaPlugin implements Listener {

    // ===== Настройки (меняй тут) =====
    static final Material CORE_MAT = Material.LODESTONE;
    static final int MIN_DIST = 50;        // минимум блоков от базы до спавна
    static final int MAX_DIST = 75;
    static final int WAIT_SEC = 30;        // после установки ядра до прихода переговорщиков
    static final int NEGOTIATE_SEC = 120;  // сколько времени даётся на решение
    static final int WAVE_SEC = 90;        // интервал между волнами
    static final int MAX_ALIVE = 100;      // максимум рейдеров на одну базу
    static final int CHEST_CHANCE = 7;     // % шанс ядра в сундуке

    static final EntityType[][] TIERS = {
            {EntityType.ZOMBIE, EntityType.SKELETON},
            {EntityType.SPIDER, EntityType.CREEPER, EntityType.HUSK},
            {EntityType.WITCH, EntityType.STRAY, EntityType.CAVE_SPIDER, EntityType.DROWNED, EntityType.PILLAGER},
            {EntityType.VINDICATOR, EntityType.SLIME, EntityType.BOGGED, EntityType.ZOMBIFIED_PIGLIN, EntityType.ENDERMAN},
            {EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.MAGMA_CUBE, EntityType.PHANTOM, EntityType.BREEZE},
            {EntityType.RAVAGER, EntityType.EVOKER, EntityType.VEX, EntityType.ZOGLIN},
            {EntityType.PIGLIN_BRUTE, EntityType.SILVERFISH, EntityType.ENDERMITE, EntityType.RAVAGER, EntityType.EVOKER}
    };
    static final String[] STRANGE = {"Искажённый", "Мутант", "Пустотный", "Ржавый", "Безликий", "Древний", "Шепчущий"};

    enum State { WAIT, NEGOTIATE, WAR, PEACE }

    static final class Base {
        String world; int x, y, z;
        State state = State.WAIT;
        int countdown = WAIT_SEC;
        int wave = 0;
        Base(String w, int x, int y, int z) { world = w; this.x = x; this.y = y; this.z = z; }
        String key() { return world + ";" + x + ";" + y + ";" + z; }
        World w() { return Bukkit.getWorld(world); }
        Location loc() { return new Location(w(), x + 0.5, y, z + 0.5); }
    }

    static final class Stuck { Location last; int bad; int fly; }

    NamespacedKey CORE, ROLE, BASE;
    final Map<String, Base> bases = new HashMap<>();
    final Set<UUID> mobs = new HashSet<>();
    final Map<UUID, Integer> bees = new HashMap<>();
    final Map<UUID, Stuck> stuck = new HashMap<>();
    final Random rnd = new Random();
    File dataFile;
    int c = 0;

    // ===== Жизненный цикл =====
    @Override
    public void onEnable() {
        CORE = new NamespacedKey(this, "core");
        ROLE = new NamespacedKey(this, "role");
        BASE = new NamespacedKey(this, "base");
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (World w : Bukkit.getWorlds()) for (Entity en : w.getEntities()) rescan(en);
        Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::fastTick, 2L, 2L);
        getLogger().info("Raider включён. Баз: " + bases.size());
    }

    @Override
    public void onDisable() { save(); }

    // ===== Сохранение =====
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Base b : bases.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", b.world); m.put("x", b.x); m.put("y", b.y); m.put("z", b.z);
            m.put("state", b.state.name()); m.put("countdown", b.countdown); m.put("wave", b.wave);
            list.add(m);
        }
        y.set("bases", list);
        try { y.save(dataFile); } catch (Exception ex) { getLogger().warning("Не удалось сохранить: " + ex.getMessage()); }
    }

    void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (Map<?, ?> m : y.getMapList("bases")) {
            try {
                Base b = new Base((String) m.get("world"), ((Number) m.get("x")).intValue(),
                        ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
                b.state = State.valueOf((String) m.get("state"));
                b.countdown = ((Number) m.get("countdown")).intValue();
                b.wave = ((Number) m.get("wave")).intValue();
                bases.put(b.key(), b);
            } catch (Exception ignored) { }
        }
    }

    // ===== Утилиты =====
    String role(Entity e) { return e.getPersistentDataContainer().get(ROLE, PersistentDataType.STRING); }
    String baseOf(Entity e) { return e.getPersistentDataContainer().get(BASE, PersistentDataType.STRING); }
    String keyOf(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }
    boolean isBase(Block b) { return bases.containsKey(keyOf(b)); }

    void say(String s) { Bukkit.broadcastMessage(ChatColor.RED + "[Raider] " + s); }

    ItemStack core(int n) {
        ItemStack it = new ItemStack(CORE_MAT, n);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.DARK_PURPLE + "Ядро Рейдера");
        m.setLore(List.of(ChatColor.GRAY + "Его жаждут все враждебные мобы"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(CORE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isCore(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(CORE, PersistentDataType.BYTE);
    }

    Player nearest(Location l, double r) {
        Player best = null; double bd = r * r;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    boolean isFlyer(Entity e) {
        return e instanceof Phantom || e instanceof Vex || e instanceof Blaze || e instanceof Ghast;
    }

    Location spawnPoint(Base b, double up) {
        World w = b.w();
        for (int i = 0; i < 10; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = MIN_DIST + rnd.nextDouble() * (MAX_DIST - MIN_DIST);
            int x = b.x + (int) Math.round(Math.cos(ang) * d);
            int z = b.z + (int) Math.round(Math.sin(ang) * d);
            int y = w.getHighestBlockYAt(x, z);
            if (w.getBlockAt(x, y, z).isLiquid()) continue;
            return new Location(w, x + 0.5, y + 1 + up, z + 0.5);
        }
        int x = b.x + MIN_DIST;
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, b.z) + 1 + up, b.z + 0.5);
    }

    Base nearestBase(CommandSender s) {
        Base best = null; double bd = Double.MAX_VALUE;
        for (Base b : bases.values()) {
            if (b.w() == null) continue;
            if (s instanceof Player p) {
                if (!p.getWorld().equals(b.w())) continue;
                double d = p.getLocation().distanceSquared(b.loc());
                if (d < bd) { bd = d; best = b; }
            } else return b;
        }
        return best;
    }

    LivingEntity reg(LivingEntity e, String role, Base b) {
        var pdc = e.getPersistentDataContainer();
        pdc.set(ROLE, PersistentDataType.STRING, role);
        pdc.set(BASE, PersistentDataType.STRING, b.key());
        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        AttributeInstance fr = e.getAttribute(Attribute.FOLLOW_RANGE);
        if (fr != null) fr.setBaseValue(128);
        e.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        mobs.add(e.getUniqueId());
        return e;
    }

    void rescan(Entity en) {
        String r = role(en);
        if (r == null) return;
        if (r.equals("kamikaze")) bees.putIfAbsent(en.getUniqueId(), 0);
        else if (en instanceof LivingEntity) mobs.add(en.getUniqueId());
    }

    void removeMobs(String key, String onlyRole) {
        for (Iterator<UUID> it = mobs.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity en = Bukkit.getEntity(id);
            if (en == null) continue;
            if (!key.equals(baseOf(en))) continue;
            if (onlyRole != null && !onlyRole.equals(role(en))) continue;
            en.remove(); it.remove(); stuck.remove(id);
        }
        if (onlyRole == null) {
            for (Iterator<UUID> it = bees.keySet().iterator(); it.hasNext(); ) {
                Entity en = Bukkit.getEntity(it.next());
                if (en != null && key.equals(baseOf(en))) {
                    for (Entity p : new ArrayList<>(en.getPassengers())) p.remove();
                    en.remove(); it.remove();
                }
            }
        }
    }

    int alive(String key) {
        int n = 0;
        for (UUID id : mobs) {
            Entity en = Bukkit.getEntity(id);
            if (en != null && key.equals(baseOf(en))) n++;
        }
        return n;
    }

    // ===== Логика баз (раз в секунду) =====
    void secondTick() {
        boolean dirty = false;
        for (Base b : new ArrayList<>(bases.values())) {
            World w = b.w();
            if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) continue;
            if (b.loc().getBlock().getType() != CORE_MAT) {
                bases.remove(b.key()); removeMobs(b.key(), null); dirty = true; continue;
            }
            switch (b.state) {
                case WAIT -> {
                    if (--b.countdown <= 0) {
                        b.state = State.NEGOTIATE; b.countdown = NEGOTIATE_SEC;
                        spawnNegotiators(b); dirty = true;
                    }
                }
                case NEGOTIATE -> {
                    if (--b.countdown <= 0) { startWar(b, "Время вышло! Они не получили Ядро и идут за ним силой!"); dirty = true; }
                }
                case WAR -> {
                    if (--b.countdown <= 0) {
                        b.wave++; b.countdown = WAVE_SEC;
                        spawnWave(b); dirty = true;
                    }
                }
                default -> { }
            }
        }
        if (dirty) save();
    }

    void startWar(Base b, String msg) {
        b.state = State.WAR; b.countdown = 5;
        for (UUID id : mobs) {
            Entity en = Bukkit.getEntity(id);
            if (en != null && b.key().equals(baseOf(en)) && "negotiator".equals(role(en)))
                en.getPersistentDataContainer().set(ROLE, PersistentDataType.STRING, "raider");
        }
        say(msg);
    }

    void spawnNegotiators(Base b) {
        int n = 3 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
            Location l = spawnPoint(b, 0);
            Entity ent = b.w().spawnEntity(l, EntityType.PILLAGER);
            if (!(ent instanceof Pillager p)) { ent.remove(); continue; }
            p.getEquipment().setItemInMainHand(null);
            p.setCustomName(ChatColor.GOLD + (i == 0 ? "Вожак переговорщиков" : "Переговорщик"));
            p.setCustomNameVisible(true);
            AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) { hp.setBaseValue(40); p.setHealth(40); }
            reg(p, "negotiator", b);
        }
        say("К базе " + b.x + " " + b.y + " " + b.z + " идёт группа мобов. Они требуют Ядро Рейдера! "
                + "Отдайте его (ПКМ ядром по переговорщику) за " + NEGOTIATE_SEC + " сек. Если нападёте - начнётся война.");
    }

    // ===== Волны =====
    EntityType pickType(int wave) {
        int max = Math.min(TIERS.length - 1, (wave - 1) / 2);
        int idx = rnd.nextInt(3) == 0 ? max : rnd.nextInt(max + 1);
        EntityType[] t = TIERS[idx];
        return t[rnd.nextInt(t.length)];
    }

    void spawnWave(Base b) {
        int wave = b.wave;
        int want = Math.min(4 + wave * 2, 40);
        int room = MAX_ALIVE - alive(b.key());
        int n = Math.max(0, Math.min(want, room));
        for (int i = 0; i < n; i++) spawnRaider(b);
        if (wave >= 2) {
            int beeCount = Math.min(1 + wave / 2, 12);
            for (int i = 0; i < beeCount; i++) spawnBee(b);
        }
        String extra = wave == 4 ? " Появились автоматчики!" : wave == 7 ? " Появились танки!" : wave == 9 ? " Появились миномёты!" : "";
        say("Волна " + wave + " атакует базу!" + extra);
    }

    void spawnRaider(Base b) {
        int wave = b.wave;
        double r = rnd.nextDouble();
        if (wave >= 9 && r < 0.07) { spawnRole(b, "mortar"); return; }
        if (wave >= 7 && r < 0.14) { spawnRole(b, "tank"); return; }
        if (wave >= 4 && r < 0.30) { spawnRole(b, "gunner"); return; }
        EntityType t = pickType(wave);
        Location l = spawnPoint(b, isFlyerType(t) ? 10 : 0);
        Entity ent = b.w().spawnEntity(l, t);
        if (!(ent instanceof LivingEntity e)) { ent.remove(); return; }
        reg(e, "raider", b);
        mutate(e, t, wave);
        if (wave >= 10 && rnd.nextInt(5) == 0) {
            EntityType t2 = pickType(wave);
            Entity second = b.w().spawnEntity(l, t2);
            if (second instanceof LivingEntity e2) {
                reg(e2, "raider", b);
                mutate(e2, t2, wave);
                e.addPassenger(e2);
            } else second.remove();
        }
    }

    boolean isFlyerType(EntityType t) {
        return t == EntityType.PHANTOM || t == EntityType.VEX || t == EntityType.BLAZE || t == EntityType.GHAST;
    }

    void setScale(LivingEntity e, double s) {
        AttributeInstance a = e.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(s);
    }

    void mutate(LivingEntity e, EntityType t, int wave) {
        if (wave >= 3 && e instanceof Zombie z && rnd.nextInt(5) == 0) z.setBaby(true);
        if (wave >= 4 && (e instanceof Zombie || e instanceof Skeleton) && e.getEquipment() != null && rnd.nextInt(3) == 0) {
            e.getEquipment().setHelmet(new ItemStack(wave >= 9 ? Material.DIAMOND_HELMET : Material.IRON_HELMET));
            e.getEquipment().setHelmetDropChance(0f);
        }
        if (wave >= 5 && rnd.nextInt(4) == 0) {
            setScale(e, 0.5 + rnd.nextDouble() * (wave >= 10 ? 2.5 : 1.2));
        }
        if (e instanceof Creeper cr && wave >= 6 && rnd.nextInt(3) == 0) cr.setPowered(true);
        if (wave >= 6 && rnd.nextInt(4) == 0) e.setGlowing(true);
        if (wave >= 8 && rnd.nextInt(3) == 0) {
            PotionEffectType[] fx = {PotionEffectType.SPEED, PotionEffectType.STRENGTH, PotionEffectType.RESISTANCE, PotionEffectType.REGENERATION};
            e.addPotionEffect(new PotionEffect(fx[rnd.nextInt(fx.length)], PotionEffect.INFINITE_DURATION, 1, false, false));
        }
        if (wave >= 8 && rnd.nextInt(4) == 0) {
            e.setCustomName(ChatColor.DARK_RED + STRANGE[rnd.nextInt(STRANGE.length)] + " " + t.name().toLowerCase().replace('_', ' '));
        }
    }

    void spawnRole(Base b, String role) {
        Location l = spawnPoint(b, 0);
        World w = b.w();
        switch (role) {
            case "gunner" -> {
                Entity ent = w.spawnEntity(l, EntityType.SKELETON);
                if (!(ent instanceof Skeleton s)) { ent.remove(); return; }
                var eq = s.getEquipment();
                eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                eq.setItemInMainHand(new ItemStack(Material.BOW));
                eq.setHelmetDropChance(0f); eq.setChestplateDropChance(0f); eq.setItemInMainHandDropChance(0f);
                s.setCustomName(ChatColor.GRAY + "Автоматчик");
                reg(s, "gunner", b);
            }
            case "tank" -> {
                Entity ent = w.spawnEntity(l, EntityType.RAVAGER);
                if (!(ent instanceof Ravager rv)) { ent.remove(); return; }
                setScale(rv, 1.5);
                AttributeInstance hp = rv.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) { hp.setBaseValue(150); rv.setHealth(150); }
                rv.setCustomName(ChatColor.DARK_GREEN + "Танк");
                reg(rv, "tank", b);
            }
            case "mortar" -> {
                Entity ent = w.spawnEntity(l, EntityType.ZOMBIE);
                if (!(ent instanceof Zombie z)) { ent.remove(); return; }
                z.getEquipment().setHelmet(new ItemStack(Material.DISPENSER));
                z.getEquipment().setHelmetDropChance(0f);
                z.setCustomName(ChatColor.RED + "Миномёт");
                reg(z, "mortar", b);
            }
            default -> { }
        }
    }

    void spawnBee(Base b) {
        Location l = spawnPoint(b, 8);
        Entity ent = b.w().spawnEntity(l, EntityType.BEE);
        if (!(ent instanceof Bee bee)) { ent.remove(); return; }
        bee.setRemoveWhenFarAway(false);
        bee.setAware(false);
        bee.setGravity(false);
        bee.setCustomName(ChatColor.YELLOW + "Пчела-смертник");
        var pdc = bee.getPersistentDataContainer();
        pdc.set(ROLE, PersistentDataType.STRING, "kamikaze");
        pdc.set(BASE, PersistentDataType.STRING, b.key());
        Entity tnt = b.w().spawnEntity(l, EntityType.TNT);
        if (tnt instanceof TNTPrimed tp) { tp.setFuseTicks(32767); bee.addPassenger(tp); }
        bees.put(bee.getUniqueId(), 0);
    }

    // ===== Быстрый цикл (каждые 2 тика): движение, застревание, техника, пчёлы =====
    void fastTick() {
        c++;
        for (Iterator<UUID> it = mobs.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity en = Bukkit.getEntity(id);
            if (en == null) { it.remove(); stuck.remove(id); continue; }
            if (!(en instanceof LivingEntity e) || e.isDead() || !e.isValid()) { it.remove(); stuck.remove(id); continue; }
            String role = role(e);
            String bk = baseOf(e);
            Base b = bk == null ? null : bases.get(bk);
            if (b == null || b.w() == null) { e.remove(); it.remove(); stuck.remove(id); continue; }
            if (!e.getWorld().equals(b.w())) continue;
            Location bl = b.loc();
            Stuck s = stuck.computeIfAbsent(id, k -> new Stuck());
            Player tp = nearest(e.getLocation(), 40);
            Location dest = (tp != null && !"negotiator".equals(role)) ? tp.getLocation() : bl;

            if (s.fly > 0) { fly(e, s, dest); continue; }
            if (c % 10 == 0) stuckCheck(e, s, dest, role);
            if (c % 8 == 0) steer(e, role, bl, tp);

            if ("gunner".equals(role) && c % 2 == 0 && tp != null
                    && tp.getLocation().distance(e.getLocation()) < 30 && e.hasLineOfSight(tp)) {
                Location eye = e.getEyeLocation();
                Vector v = tp.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
                v.add(new Vector(rnd.nextGaussian() * 0.04, rnd.nextGaussian() * 0.04, rnd.nextGaussian() * 0.04)).multiply(2.4);
                Arrow a = e.launchProjectile(Arrow.class, v);
                a.setDamage(2.0);
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                e.getWorld().playSound(eye, Sound.ENTITY_SKELETON_SHOOT, 0.6f, 1.6f);
            }
            if ("tank".equals(role) && c % 30 == 0 && tp != null
                    && tp.getLocation().distance(e.getLocation()) < 40 && e.hasLineOfSight(tp)) {
                Vector v = tp.getEyeLocation().toVector().subtract(e.getEyeLocation().toVector()).normalize();
                Fireball f = e.launchProjectile(Fireball.class, v);
                f.setYield(1.8f);
                f.setIsIncendiary(false);
                e.getWorld().playSound(e.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
            }
            if ("mortar".equals(role) && c % 50 == 0) {
                Player mp = nearest(e.getLocation(), 70);
                Location target = mp != null ? mp.getLocation() : bl;
                double d = target.distance(e.getLocation());
                if (d >= 15 && d <= 75) {
                    Location from = e.getEyeLocation().add(0, 1, 0);
                    Entity ent = e.getWorld().spawnEntity(from, EntityType.TNT);
                    if (ent instanceof TNTPrimed t) {
                        Vector h = target.toVector().subtract(from.toVector());
                        h.setY(0);
                        double dist = h.length();
                        if (dist > 0) h.normalize().multiply(Math.min(2.5, dist / 26.0));
                        h.setY(0.65);
                        t.setVelocity(h);
                        t.setFuseTicks(60);
                        e.getWorld().playSound(from, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f);
                    }
                }
            }
        }
        tickBees();
    }

    void steer(LivingEntity e, String role, Location base, Player tp) {
        if (!(e instanceof Mob m)) return;
        double d = e.getLocation().distance(base);
        if ("negotiator".equals(role)) { if (d > 6) hop(e, base, 0.25); return; }
        if ("mortar".equals(role)) { if (d > 42) hop(e, base, 0.3); else m.setAware(false); return; }
        if (tp != null) m.setTarget(tp);
        if (isFlyer(e)) {
            if (tp == null) e.setVelocity(base.toVector().subtract(e.getLocation().toVector()).normalize().multiply(0.3));
            return;
        }
        if (tp == null) hop(e, base, 0.3);
    }

    /** Spigot не умеет строить путь, поэтому мобы идут к цели прыжками. */
    void hop(LivingEntity e, Location to, double speed) {
        if (!e.isOnGround()) return;
        Vector v = to.toVector().subtract(e.getLocation().toVector());
        v.setY(0);
        if (v.lengthSquared() == 0) return;
        v.normalize().multiply(speed);
        v.setY(0.42);
        e.setVelocity(v);
    }

    void stuckCheck(LivingEntity e, Stuck s, Location dest, String role) {
        if (isFlyer(e)) return;
        if ("mortar".equals(role) && e instanceof Mob m1 && !m1.isAware()) return;
        if (e instanceof Mob m2 && m2.getTarget() != null
                && m2.getTarget().getWorld().equals(e.getWorld())
                && m2.getTarget().getLocation().distance(e.getLocation()) < 18) { s.bad = 0; return; }
        if (e.getLocation().distance(dest) < 6) { s.bad = 0; return; }
        if (s.last != null && s.last.getWorld().equals(e.getWorld()) && s.last.distance(e.getLocation()) < 0.5) s.bad++;
        else s.bad = 0;
        s.last = e.getLocation();
        if (s.bad >= 2) { s.fly = 24; s.bad = 0; }
    }

    /** Застрял: подлетаем вверх, затем вылетаем по направлению к цели. */
    void fly(LivingEntity e, Stuck s, Location dest) {
        int step = 24 - s.fly;
        e.setGravity(false);
        if (step < 10) {
            if (e.getLocation().getBlock().getType().isSolid()) e.teleport(e.getLocation().add(0, 1, 0));
            e.setVelocity(new Vector(0, 0.45, 0));
        } else {
            Vector d = dest.toVector().subtract(e.getLocation().toVector());
            d.setY(0);
            if (d.lengthSquared() > 0) d.normalize().multiply(0.3);
            d.setY(0.05);
            e.setVelocity(d);
        }
        if (--s.fly <= 0) { e.setGravity(true); e.setFallDistance(0f); }
    }

    // ===== Пчёлы-смертники =====
    void tickBees() {
        for (Iterator<Map.Entry<UUID, Integer>> it = bees.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> en = it.next();
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent == null) { it.remove(); continue; }
            if (!(ent instanceof Bee bee) || bee.isDead()) { it.remove(); continue; }
            String bk = baseOf(bee);
            Base b = bk == null ? null : bases.get(bk);
            if (b == null || b.w() == null) { boom(bee); it.remove(); continue; }
            int age = en.getValue() + 1;
            en.setValue(age);
            Location l = bee.getLocation();
            Player p = nearest(l, 90);
            Location target = p != null ? p.getEyeLocation() : b.loc().add(0, 1, 0);
            Vector vel = target.toVector().subtract(l.toVector());
            if (vel.lengthSquared() < 0.01) { boom(bee); it.remove(); continue; }
            vel.normalize().multiply(0.42);
            bee.setVelocity(vel);
            if (age > 10) {
                Location ahead = l.clone().add(vel.clone().normalize().multiply(0.8));
                boolean hit = ahead.getBlock().getType().isSolid() || l.getBlock().getType().isSolid()
                        || (p != null && p.getLocation().add(0, 1, 0).distance(l) < 1.6);
                if (hit || age > 900) { boom(bee); it.remove(); }
            }
        }
    }

    void boom(Bee bee) {
        Location l = bee.getLocation();
        for (Entity p : new ArrayList<>(bee.getPassengers())) p.remove();
        bee.remove();
        l.getWorld().createExplosion(l, 3.5f, false, true);
    }

    // ===== События =====
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!isCore(e.getItemInHand())) return;
        if (e.getBlock().getWorld().getEnvironment() != World.Environment.NORMAL) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Ядро можно ставить только в обычном мире.");
            return;
        }
        Block bl = e.getBlockPlaced();
        Base b = new Base(bl.getWorld().getName(), bl.getX(), bl.getY(), bl.getZ());
        bases.put(b.key(), b);
        save();
        e.getPlayer().sendMessage(ChatColor.DARK_PURPLE + "Ядро установлено. Скоро к вам придут гости...");
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Base b = bases.get(keyOf(e.getBlock()));
        if (b == null) return;
        bases.remove(b.key());
        removeMobs(b.key(), null);
        save();
        e.setDropItems(false);
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), core(1));
        e.getPlayer().sendMessage(ChatColor.GRAY + "Ядро убрано. Мобы отступили.");
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block bl : e.getBlocks()) if (isBase(bl)) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block bl : e.getBlocks()) if (isBase(bl)) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isBase); }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isBase); }

    @EventHandler
    public void onLoot(LootGenerateEvent e) {
        if (e.getLootTable().getKey().getKey().startsWith("chests/") && rnd.nextInt(100) < CHEST_CHANCE)
            e.getLoot().add(core(1));
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) { for (Entity en : e.getEntities()) rescan(en); }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if ("negotiator".equals(role(e.getEntity()))) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity en = e.getRightClicked();
        if (!"negotiator".equals(role(en))) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isCore(hand)) {
            p.sendMessage(ChatColor.GOLD + "Переговорщик: «Отдай нам Ядро!» (ПКМ с Ядром в руке)");
            return;
        }
        Base b = bases.get(baseOf(en));
        if (b == null) return;
        hand.setAmount(hand.getAmount() - 1);
        b.state = State.PEACE;
        removeMobs(b.key(), "negotiator");
        save();
        say(p.getName() + " отдал Ядро. Мобы ушли с миром... пока.");
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        String r = role(e.getEntity());
        if (r == null) return;
        if (r.equals("kamikaze")) {
            for (Entity p : new ArrayList<>(e.getEntity().getPassengers())) p.remove();
            bees.remove(e.getEntity().getUniqueId());
            e.getDrops().clear();
            return;
        }
        if (r.equals("negotiator")) {
            Base b = bases.get(baseOf(e.getEntity()));
            if (b != null && b.state == State.NEGOTIATE) {
                startWar(b, "Переговорщика убили! На базу " + b.x + " " + b.y + " " + b.z + " идёт война!");
                save();
            }
        }
    }

    // ===== Команды =====
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("raider.admin")) { s.sendMessage("Нет прав."); return true; }
        if (a.length == 0) {
            s.sendMessage("/raider give [игрок] [кол-во] | start | stop | wave | status | clear");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "give" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
                if (t == null) { s.sendMessage("Игрок не найден."); return true; }
                int n = 1;
                try { if (a.length > 2) n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
                t.getInventory().addItem(core(n));
                s.sendMessage("Выдано Ядро x" + n + " игроку " + t.getName());
            }
            case "start" -> {
                Base b = nearestBase(s);
                if (b == null) { s.sendMessage("Нет баз рядом."); return true; }
                startWar(b, "Война началась досрочно!"); save();
            }
            case "stop" -> {
                Base b = nearestBase(s);
                if (b == null) { s.sendMessage("Нет баз рядом."); return true; }
                b.state = State.PEACE; removeMobs(b.key(), null); save();
                s.sendMessage("Мир установлен, мобы убраны.");
            }
            case "wave" -> {
                Base b = nearestBase(s);
                if (b == null) { s.sendMessage("Нет баз рядом."); return true; }
                if (a.length > 1) { try { b.wave = Math.max(0, Integer.parseInt(a[1]) - 1); } catch (NumberFormatException ignored) { } }
                b.state = State.WAR; b.wave++; b.countdown = WAVE_SEC;
                spawnWave(b); save();
            }
            case "status" -> {
                if (bases.isEmpty()) s.sendMessage("Баз нет.");
                for (Base b : bases.values())
                    s.sendMessage(b.key() + " | " + b.state + " | волна " + b.wave + " | таймер " + b.countdown + " | мобов " + alive(b.key()));
            }
            case "clear" -> {
                for (Base b : bases.values()) removeMobs(b.key(), null);
                s.sendMessage("Все рейдеры удалены.");
            }
            default -> s.sendMessage("Неизвестная подкоманда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) return List.of("give", "start", "stop", "wave", "status", "clear");
        return Collections.emptyList();
    }
}
