package dev.deephide;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DeepHide extends JavaPlugin implements Listener {
    private int hideBelow, revealY, rehideY, perTick;
    private boolean hideEntities;
    private Set<String> worlds;
    private Material replace;
    private int replaceId;
    private final Set<Integer> airIds = new HashSet<>();
    private final Map<UUID, Boolean> masked = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenEnt = new HashMap<>();
    private final Map<UUID, ArrayDeque<long[]>> refreshQueue = new HashMap<>();
    private volatile boolean warned;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!load()) { getServer().getPluginManager().disablePlugin(this); return; }
        getServer().getPluginManager().registerEvents(this, this);
        registerPackets();
        getServer().getScheduler().runTaskTimer(this, this::tickEntities, 20, 10);
        getServer().getScheduler().runTaskTimer(this, this::tickRefresh, 1, 1);
        getLogger().info("DeepHide active: hiding Y < " + hideBelow + ", reveal at Y <= " + revealY);
    }

    private boolean load() {
        reloadConfig();
        var c = getConfig();
        hideBelow = c.getInt("hide-below-y", -2);
        revealY = c.getInt("reveal-at-y", 0);
        rehideY = c.getInt("rehide-above-y", 2);
        perTick = Math.max(1, c.getInt("refresh-chunks-per-tick", 6));
        hideEntities = c.getBoolean("hide-entities", true);
        worlds = new HashSet<>(c.getStringList("worlds"));
        replace = Material.matchMaterial(c.getString("replace-block", "DEEPSLATE"));
        if (replace == null || !replace.isBlock()) replace = Material.DEEPSLATE;
        try {
            replaceId = stateId(replace);
            airIds.clear();
            for (Material m : new Material[]{Material.AIR, Material.CAVE_AIR, Material.VOID_AIR}) airIds.add(stateId(m));
        } catch (Throwable t) {
            getLogger().severe("Could not resolve block state ids (unsupported server version?): " + t);
            return false;
        }
        return true;
    }

    private static int stateId(Material m) throws Exception {
        Object state = WrappedBlockData.createData(m).getHandle();
        Class<?> block = Class.forName("net.minecraft.world.level.block.Block");
        for (Method me : block.getMethods()) {
            if (me.getName().equals("getId") && Modifier.isStatic(me.getModifiers()) && me.getParameterCount() == 1
                    && me.getParameterTypes()[0].isInstance(state)) return (int) me.invoke(null, state);
        }
        throw new NoSuchMethodException("Block.getId");
    }

    private boolean enabled(World w) { return worlds.contains(w.getName()); }

    private boolean isMasked(Player p) {
        Boolean b = masked.get(p.getUniqueId());
        if (b == null) {
            b = p.getLocation().getY() > revealY;
            masked.put(p.getUniqueId(), b);
        }
        return b;
    }

    private void update(Player p, double y) {
        if (!enabled(p.getWorld())) return;
        Boolean cur = masked.get(p.getUniqueId());
        if (cur == null) { masked.put(p.getUniqueId(), y > revealY); return; }
        if (cur && y <= revealY) { masked.put(p.getUniqueId(), false); queueRefresh(p); }
        else if (!cur && y > rehideY) { masked.put(p.getUniqueId(), true); queueRefresh(p); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { masked.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        masked.remove(id); hiddenEnt.remove(id); refreshQueue.remove(id);
    }
    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && e.getTo().getBlockY() != e.getFrom().getBlockY()) update(e.getPlayer(), e.getTo().getY());
    }
    @EventHandler public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null || !enabled(e.getTo().getWorld())) return;
        double y = e.getTo().getY();
        Boolean old = masked.get(p.getUniqueId());
        boolean now = old == null ? y > revealY : (old ? y > revealY : y > rehideY);
        masked.put(p.getUniqueId(), now);
        if (old != null && old != now) getServer().getScheduler().runTaskLater(this, () -> { if (p.isOnline()) queueRefresh(p); }, 2);
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        masked.put(e.getPlayer().getUniqueId(), e.getRespawnLocation().getY() > revealY);
    }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { masked.remove(e.getPlayer().getUniqueId()); }

    private void queueRefresh(Player p) {
        World w = p.getWorld();
        int r = Math.min(Bukkit.getViewDistance(), p.getClientViewDistance()) + 1;
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        ArrayDeque<long[]> q = new ArrayDeque<>();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++)
            if (w.isChunkLoaded(cx + x, cz + z)) q.add(new long[]{cx + x, cz + z});
        refreshQueue.put(p.getUniqueId(), q);
    }

    @SuppressWarnings("deprecation")
    private void tickRefresh() {
        Iterator<Map.Entry<UUID, ArrayDeque<long[]>>> it = refreshQueue.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { it.remove(); continue; }
            for (int i = 0; i < perTick && !en.getValue().isEmpty(); i++) {
                long[] c = en.getValue().poll();
                p.getWorld().refreshChunk((int) c[0], (int) c[1]);
            }
            if (en.getValue().isEmpty()) it.remove();
        }
    }

    private void tickEntities() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Set<UUID> hs = hiddenEnt.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
                if (!hideEntities || !enabled(p.getWorld()) || !isMasked(p)) {
                    for (UUID id : hs) { Entity e = Bukkit.getEntity(id); if (e != null) p.showEntity(this, e); }
                    hs.clear();
                    continue;
                }
                for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), 192, 400, 192)) {
                    if (e == p) continue;
                    if (e.getLocation().getY() < hideBelow) { if (hs.add(e.getUniqueId())) p.hideEntity(this, e); }
                    else if (hs.remove(e.getUniqueId())) p.showEntity(this, e);
                }
                hs.removeIf(id -> Bukkit.getEntity(id) == null);
            } catch (Throwable t) { warn(t); }
        }
    }

    private void registerPackets() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this, ListenerPriority.HIGH,
                PacketType.Play.Server.MAP_CHUNK, PacketType.Play.Server.BLOCK_CHANGE,
                PacketType.Play.Server.MULTI_BLOCK_CHANGE, PacketType.Play.Server.TILE_ENTITY_DATA,
                PacketType.Play.Server.BLOCK_ACTION) {
            @Override public void onPacketSending(PacketEvent e) {
                try {
                    Player p = e.getPlayer();
                    if (p == null || !p.isOnline() || !enabled(p.getWorld()) || !isMasked(p)) return;
                    PacketType t = e.getPacketType();
                    if (t == PacketType.Play.Server.MAP_CHUNK) chunk(e, p);
                    else if (t == PacketType.Play.Server.BLOCK_CHANGE) {
                        if (e.getPacket().getBlockPositionModifier().read(0).getY() < hideBelow)
                            e.getPacket().getBlockData().write(0, WrappedBlockData.createData(replace));
                    } else if (t == PacketType.Play.Server.MULTI_BLOCK_CHANGE) multi(e);
                    else if (hideEntities && e.getPacket().getBlockPositionModifier().read(0).getY() < hideBelow)
                        e.setCancelled(true);
                } catch (Throwable ex) { warn(ex, e.getPacketType().name()); }
            }
        });
    }

    private void multi(PacketEvent e) {
        BlockPosition sec = e.getPacket().getSectionPositions().read(0);
        int base = sec.getY() * 16;
        if (base >= hideBelow) return;
        if (base + 15 < hideBelow) { e.setCancelled(true); return; }
        PacketContainer pk = e.getPacket().deepClone();
        MultiBlockChangeInfo[] arr = pk.getMultiBlockChangeInfoArrays().read(0);
        for (MultiBlockChangeInfo i : arr) if (i.getY() < hideBelow) i.setData(WrappedBlockData.createData(replace));
        pk.getMultiBlockChangeInfoArrays().write(0, arr);
        e.setPacket(pk);
    }

    private void chunk(PacketEvent e, Player p) {
        World w = p.getWorld();
        int minY = w.getMinHeight(), sections = (w.getMaxHeight() - minY) >> 4;
        PacketContainer pk = e.getPacket().deepClone();
        WrappedLevelChunkData.ChunkData cd = pk.getLevelChunkData().read(0);
        byte[] out = maskBuffer(cd.getBuffer(), minY, sections);
        if (out == null) { warn(new IllegalStateException("chunk layout mismatch, sent unmodified")); return; }
        cd.setBuffer(out);
        if (hideEntities) {
            List<WrappedLevelChunkData.BlockEntityInfo> keep = new ArrayList<>();
            for (var b : cd.getBlockEntityInfo()) if (b.getY() >= hideBelow) keep.add(b);
            cd.setBlockEntityInfo(keep);
        }
        pk.getLevelChunkData().write(0, cd);
        e.setPacket(pk);
    }

    private byte[] maskBuffer(byte[] buf, int minY, int sections) {
        ByteBuf in = Unpooled.wrappedBuffer(buf), out = Unpooled.buffer(buf.length);
        try {
            for (int s = 0; s < sections; s++) {
                int base = minY + s * 16, secStart = in.readerIndex();
                int count = in.readUnsignedShort();
                int bits = in.readUnsignedByte(), single = 0;
                int[] pal = null; long[] data = null;
                if (bits == 0) single = varInt(in);
                else if (bits <= 8) { pal = new int[varInt(in)]; for (int i = 0; i < pal.length; i++) pal[i] = varInt(in); }
                if (bits > 0) { int epl = 64 / bits; data = new long[(4096 + epl - 1) / epl]; for (int i = 0; i < data.length; i++) data[i] = in.readLong(); }
                int bioStart = in.readerIndex();
                int bb = in.readUnsignedByte();
                if (bb == 0) varInt(in);
                else { if (bb <= 3) { int n = varInt(in); for (int i = 0; i < n; i++) varInt(in); }
                       int epl = 64 / bb; in.skipBytes(((64 + epl - 1) / epl) * 8); }
                int end = in.readerIndex();
                if (base >= hideBelow) { out.writeBytes(buf, secStart, end - secStart); continue; }
                if (base + 15 < hideBelow) {
                    out.writeShort(4096); out.writeByte(0); putVarInt(out, replaceId);
                    out.writeBytes(buf, bioStart, end - bioStart); continue;
                }
                int[] ids = new int[4096];
                if (bits == 0) Arrays.fill(ids, single);
                else {
                    int epl = 64 / bits; long mask = (1L << bits) - 1;
                    for (int i = 0; i < 4096; i++) {
                        int v = (int) ((data[i / epl] >>> ((i % epl) * bits)) & mask);
                        ids[i] = pal != null ? pal[v] : v;
                    }
                }
                int nonAir = 0;
                for (int i = 0; i < 4096; i++) {
                    if (base + ((i >> 8) & 15) < hideBelow) ids[i] = replaceId;
                    if (!airIds.contains(ids[i])) nonAir++;
                }
                LinkedHashMap<Integer, Integer> map = new LinkedHashMap<>();
                for (int id : ids) map.putIfAbsent(id, map.size());
                out.writeShort(nonAir);
                if (map.size() == 1) { out.writeByte(0); putVarInt(out, ids[0]); }
                else if (map.size() <= 256) {
                    int nb = Math.max(4, 32 - Integer.numberOfLeadingZeros(map.size() - 1));
                    out.writeByte(nb); putVarInt(out, map.size());
                    for (int id : map.keySet()) putVarInt(out, id);
                    packLongs(out, ids, nb, map);
                } else if (bits > 8) { out.writeByte(bits); packLongs(out, ids, bits, null); }
                else return null;
                out.writeBytes(buf, bioStart, end - bioStart);
            }
            if (in.readableBytes() != 0) return null;
            byte[] res = new byte[out.readableBytes()]; out.readBytes(res); return res;
        } catch (RuntimeException ex) { return null; }
        finally { in.release(); out.release(); }
    }

    private static void packLongs(ByteBuf out, int[] ids, int bits, Map<Integer, Integer> map) {
        int epl = 64 / bits, n = (4096 + epl - 1) / epl;
        for (int l = 0; l < n; l++) {
            long v = 0;
            for (int k = 0; k < epl && l * epl + k < 4096; k++) {
                int id = ids[l * epl + k];
                v |= ((long) (map != null ? map.get(id) : id)) << (k * bits);
            }
            out.writeLong(v);
        }
    }

    private static int varInt(ByteBuf b) {
        int v = 0, s = 0; byte x;
        do { x = b.readByte(); v |= (x & 0x7F) << s; s += 7; if (s > 35) throw new RuntimeException("varint"); } while ((x & 0x80) != 0);
        return v;
    }
    private static void putVarInt(ByteBuf b, int v) {
        while ((v & ~0x7F) != 0) { b.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        b.writeByte(v);
    }

    private void warn(Throwable t) { warn(t, "-"); }

    private final Set<String> warnedTypes = ConcurrentHashMap.newKeySet();
    private void warn(Throwable t, String type) {
        if (warnedTypes.add(type + t.getClass().getSimpleName())) {
            StackTraceElement[] st = t.getStackTrace();
            getLogger().warning("DeepHide fail-open in " + type + ": " + t + " @ " + (st.length > 0 ? st[0] : "?")
                    + (st.length > 1 ? " <- " + st[1] : "") + (st.length > 2 ? " <- " + st[2] : ""));
        }
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) { s.sendMessage(load() ? "DeepHide reloaded" : "DeepHide reload failed"); return true; }
        long m = masked.values().stream().filter(b -> b).count();
        s.sendMessage("DeepHide: hide Y<" + hideBelow + " reveal<=" + revealY + " rehide>" + rehideY + " | masked players: " + m + "/" + masked.size());
        return true;
    }
}
