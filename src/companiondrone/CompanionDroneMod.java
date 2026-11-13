package companiondrone;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.*;
import mindustry.ui.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/**
 * A cute mini Poly that follows every player, patches up damaged buildings and talks.
 * It is not a real unit: every client moves and draws the drones of all players itself; it is never saved,
 * does not count toward the unit cap and cannot be shot. Repairs are done by the side that owns the world
 * (singleplayer, host or dedicated server) for every player's drone; the game sends the new health to the clients.
 * Lines and hearts of a player's drone go to the others as mod packets.
 */
public class CompanionDroneMod extends Mod{
    public static final String KEY_ENABLED = "companion-drone-enabled", KEY_CHATTER = "companion-drone-chatter", KEY_REPAIR = "companion-drone-repair";

    /** Sprite scale relative to a real Poly. */
    public static final float SCALE = 0.55f;
    /** How far from the player the drone hovers, world units (8 per tile). */
    public static final float FOLLOW_DIST = 20f;
    /** Buildings this close to the player (or the core, when the player is dead) get repaired. */
    public static final float REPAIR_RANGE = 12f * 8f;
    /** The drone heals from this distance. */
    public static final float BEAM_RANGE = 4f * 8f;
    /** Healing per second: this fraction of max health, but at least HEAL_MIN. */
    public static final float HEAL_FRACTION = 0.03f, HEAL_MIN = 8f;
    /** Enemies within this range of the player trigger a warning. */
    public static final float ENEMY_RANGE = 22f * 8f;
    public static final float MAX_SPEED = 3.2f;
    public static final float TEXT_TIME = 60f * 3.5f;
    /** Chance that a line without it gets "nya~" added at the end (0..1). */
    public static final float NYA_CHANCE = 0.4f;
    /** Seconds between idle lines (random in this range). */
    public static final float IDLE_MIN = 15f, IDLE_MAX = 35f;
    /** After any line, idle chatter waits at least this many seconds. */
    public static final float QUIET_AFTER = 10f;
    /** Tapping or clicking this close to a drone (world units) makes it react. */
    public static final float PET_RADIUS = 10f;
    /** Network message for lines and hearts of a drone (client -> server -> everyone). */
    public static final String PACKET = "companion-drone-say";
    /** The server passes on at most one message per player in this many seconds. */
    public static final float RELAY_COOLDOWN = 0.5f;
    /** Longest line passed on by the server. */
    public static final int MAX_LINE = 120;

    public static final Color cellColor = Color.valueOf("ff9ecb"), beamColor = Pal.heal, heartColor = Color.valueOf("ff6fa8");

    /** Number of lines per message kind in the bundles. */
    static final ObjectIntMap<String> variants = new ObjectIntMap<>();
    static{
        Object[] counts = {"hello", 3, "enemies", 3, "core", 2, "wave", 2, "lowhp", 2, "repaired", 3, "repairing", 2, "died", 2, "idle", 8, "pet", 4};
        for(int i = 0; i < counts.length; i += 2) variants.put((String)counts[i], (Integer)counts[i + 1]);
    }

    /** One drone per player. */
    static class Drone{
        Player owner;
        float x, y, vx, vy, rot = 90f;
        boolean placed, wasDead;
        Building repairTarget;
        boolean repairing;
        float scanTimer, wander = Mathf.random(360f);
        String text;
        float textTime, heartTime;
    }

    final IntMap<Drone> drones = new IntMap<>();
    final IntFloatMap relayTimes = new IntFloatMap();
    final IntSeq gone = new IntSeq();

    // chatter of the local player's drone
    float idleTimer;
    int lastWaveWarned = -1;
    /** Time.time until which each message kind stays silent. */
    final ObjectFloatMap<String> cooldowns = new ObjectFloatMap<>();

    @Override
    public void init(){
        // the server (a dedicated one too) repairs for every player and passes the lines on
        if(netServer != null) netServer.addPacketHandler(PACKET, this::relay);
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, this::update);
        if(headless){
            Log.info("[CompanionDrone] loaded (server)");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            ui.settings.game.checkPref(KEY_ENABLED, true);
            ui.settings.game.checkPref(KEY_CHATTER, true);
            ui.settings.game.checkPref(KEY_REPAIR, true);
        });
        netClient.addPacketHandler(PACKET, this::onRemoteLine);
        Events.on(BuildDamageEvent.class, e -> {
            if(player != null && e.build instanceof CoreBuild && e.build.team == player.team()) say("core", 15f, false);
        });
        Events.run(Trigger.draw, this::draw);
        Log.info("[CompanionDrone] loaded");
    }

    boolean enabled(){
        return Core.settings.getBool(KEY_ENABLED, true);
    }

    void reset(){
        drones.clear();
        relayTimes.clear();
        idleTimer = Mathf.random(IDLE_MIN, IDLE_MAX) * 60f;
        lastWaveWarned = -1;
        cooldowns.clear();
    }

    /** The drone of the player at this computer (none on a dedicated server). */
    Drone local(){
        return headless || player == null ? null : drones.get(player.id);
    }

    // region network

    /** Server: a player's drone said something; pass it on to everyone. Message: "heart|text". */
    void relay(Player from, String msg){
        if(from == null || msg == null) return;
        if(Time.time < relayTimes.get(from.id, -1f)) return;
        relayTimes.put(from.id, Time.time + RELAY_COOLDOWN * 60f);
        String clean = msg.replace('\n', ' ');
        if(clean.length() > MAX_LINE + 2) clean = clean.substring(0, MAX_LINE + 2);
        String out = from.id + "|" + clean;
        Call.clientPacketReliable(PACKET, out);
        // packets to the clients do not come back to the host, so the host shows it here
        if(!headless) onRemoteLine(out);
    }

    /** Client: a line of some player's drone. Message: "playerId|heart|text". */
    void onRemoteLine(String msg){
        String[] p = msg.split("\\|", 3);
        if(p.length < 3) return;
        int id = Strings.parseInt(p[0], -1);
        if(player != null && id == player.id) return; // our own drone already shows it
        Drone d = drones.get(id);
        if(d == null) return;
        if(p[1].equals("1")) d.heartTime = 90f;
        if(!p[2].isEmpty()){
            d.text = p[2];
            d.textTime = TEXT_TIME;
        }
    }

    /** Our drone said something or showed a heart: let the others see it. */
    void share(String text, boolean heart){
        if(!net.active()) return;
        String msg = (heart ? "1" : "0") + "|" + (text == null ? "" : text);
        if(net.client()){
            Call.serverPacketReliable(PACKET, msg);
        }else if(net.server() && player != null){
            Call.clientPacketReliable(PACKET, player.id + "|" + msg);
        }
    }

    // endregion
    // region logic

    void update(){
        if(!state.isGame()){
            drones.clear();
            return;
        }
        if(state.isPaused()) return;

        for(Player p : Groups.player){
            Drone d = drones.get(p.id);
            if(d == null){
                d = new Drone();
                drones.put(p.id, d);
            }
            d.owner = p;
            updateDrone(d);
        }
        gone.clear();
        for(var e : drones){
            if(Groups.player.getByID(e.key) == null) gone.add(e.key);
        }
        for(int i = 0; i < gone.size; i++) drones.remove(gone.get(i));

        if(!headless) updateLocal();
    }

    void updateDrone(Drone d){
        Player owner = d.owner;
        boolean mine = !headless && owner == player;
        boolean dead = owner.dead();
        Posc anchor = dead ? owner.team().core() : owner.unit();
        if(anchor == null){
            d.placed = false;
            return;
        }
        float ax = anchor.getX(), ay = anchor.getY();

        if(!d.placed || Mathf.dst(d.x, d.y, ax, ay) > 60f * 8f){
            d.x = ax - FOLLOW_DIST;
            d.y = ay + FOLLOW_DIST;
            d.vx = d.vy = 0f;
            d.placed = true;
            d.wasDead = dead;
            if(mine) say("hello", 0f, false);
        }

        if(mine && dead && !d.wasDead) say("died", 5f, false);
        d.wasDead = dead;

        boolean repairOn = Core.settings.getBool(KEY_REPAIR, true);

        // pick something to repair
        if((d.scanTimer -= Time.delta) <= 0f){
            d.scanTimer = 30f;
            if(d.repairTarget == null && repairOn){
                d.repairTarget = indexer.findTile(owner.team(), ax, ay, REPAIR_RANGE, b -> b.damaged() && !(b instanceof ConstructBuild));
                if(d.repairTarget != null && mine) say("repairing", 30f, false);
            }
            if(mine) checkDanger(owner, dead, ax, ay);
        }

        if(d.repairTarget != null && (!d.repairTarget.isValid() || d.repairTarget.team != owner.team()
            || Mathf.dst(d.repairTarget.x, d.repairTarget.y, ax, ay) > REPAIR_RANGE * 1.5f || !repairOn)){
            d.repairTarget = null;
        }

        // where to fly
        d.wander += Time.delta * 0.4f;
        float tx, ty;
        d.repairing = false;
        if(d.repairTarget != null){
            Building t = d.repairTarget;
            float side = Angles.angle(t.x, t.y, d.x, d.y);
            float hover = t.hitSize() / 2f + 10f;
            tx = t.x + Angles.trnsx(side, hover);
            ty = t.y + Angles.trnsy(side, hover);
            if(Mathf.dst(d.x, d.y, t.x, t.y) < BEAM_RANGE + t.hitSize() / 2f){
                d.repairing = true;
                // only the side that owns the world heals
                if(!net.client()){
                    t.heal(Math.max(t.maxHealth * HEAL_FRACTION, HEAL_MIN) * Time.delta / 60f);
                }
                if(!t.damaged()){
                    d.repairTarget = null;
                    if(mine){
                        d.heartTime = 90f;
                        say("repaired", 20f, true);
                    }
                }
            }
        }else{
            float angle = 135f + Mathf.sin(d.wander, 40f, 50f);
            tx = ax + Angles.trnsx(angle, FOLLOW_DIST);
            ty = ay + Angles.trnsy(angle, FOLLOW_DIST);
        }

        // smooth movement
        float dx = tx - d.x, dy = ty - d.y;
        float wantX = dx * 0.07f, wantY = dy * 0.07f;
        float len = Mathf.len(wantX, wantY), max = MAX_SPEED + (dead ? 0f : owner.unit().vel().len());
        if(len > max){
            wantX *= max / len;
            wantY *= max / len;
        }
        float a = Math.min(0.12f * Time.delta, 1f);
        d.vx = Mathf.lerp(d.vx, wantX, a);
        d.vy = Mathf.lerp(d.vy, wantY, a);
        d.x += d.vx * Time.delta;
        d.y += d.vy * Time.delta;

        float face;
        if(d.repairing && d.repairTarget != null) face = Angles.angle(d.x, d.y, d.repairTarget.x, d.repairTarget.y);
        else if(Mathf.len(d.vx, d.vy) > 0.4f) face = Angles.angle(d.vx, d.vy);
        else face = Angles.angle(d.x, d.y, ax, ay);
        d.rot = Angles.moveToward(d.rot, face, 5f * Time.delta);

        if(d.textTime > 0f) d.textTime -= Time.delta;
        if(d.heartTime > 0f) d.heartTime -= Time.delta;
    }

    /** Taps and idle lines of the local player's drone. */
    void updateLocal(){
        Drone me = local();

        // tap or click on a drone: it shows a heart; our own one also says something, and the others see it
        if(enabled() && Core.input.justTouched() && !Core.scene.hasMouse()){
            var m = Core.input.mouseWorld();
            for(var e : drones){
                Drone d = e.value;
                if(!d.placed || Mathf.dst(m.x, m.y, d.x, d.y) >= PET_RADIUS) continue;
                d.heartTime = 90f;
                if(d == me){
                    d.textTime = 0f;
                    say("pet", 1f, true);
                }
                break;
            }
        }

        if(me == null || !me.placed) return;
        if((idleTimer -= Time.delta) <= 0f){
            idleTimer = Mathf.random(IDLE_MIN, IDLE_MAX) * 60f;
            if(me.textTime <= 0f){
                boolean heart = Mathf.chance(0.5);
                if(heart) me.heartTime = 90f;
                say("idle", 0f, heart);
            }
        }
    }

    void checkDanger(Player owner, boolean dead, float ax, float ay){
        if(!dead){
            if(Units.closestEnemy(owner.team(), ax, ay, ENEMY_RANGE, u -> !u.dead()) != null) say("enemies", 25f, false);
            if(owner.unit().healthf() < 0.3f) say("lowhp", 20f, false);
        }
        if(state.rules.waves && state.rules.waveTimer && state.wavetime > 0f && state.wavetime < 10f * 60f && lastWaveWarned != state.wave){
            lastWaveWarned = state.wave;
            say("wave", 0f, false);
        }
    }

    /** Our drone shows a random line of this kind, unless the same kind was said less than cooldown seconds ago. */
    void say(String kind, float cooldown, boolean heart){
        Drone me = local();
        if(me == null) return;
        if(!Core.settings.getBool(KEY_CHATTER, true) || Time.time < cooldowns.get(kind, -1f)){
            if(heart) share(null, true);
            return;
        }
        cooldowns.put(kind, Time.time + cooldown * 60f);
        String text = Core.bundle.get("companion." + kind + "." + Mathf.random(variants.get(kind, 1) - 1));
        String nya = Core.bundle.get("companion.nya");
        if(!text.toLowerCase().contains(nya) && Mathf.chance(NYA_CHANCE)){
            text = text.replaceAll("[.!]+$", "") + ", " + nya;
        }
        me.text = text;
        me.textTime = TEXT_TIME;
        idleTimer = Math.max(idleTimer, QUIET_AFTER * 60f);
        share(text, heart);
    }

    // endregion
    // region drawing

    void draw(){
        if(!enabled() || !state.isGame()) return;
        for(var e : drones){
            if(e.value.placed) drawDrone(e.value);
        }
    }

    void drawDrone(Drone d){
        TextureRegion region = UnitTypes.poly.region, cell = UnitTypes.poly.cellRegion;
        if(region == null || !region.found()) return;
        float x = d.x, rot = d.rot;

        float bob = Mathf.sin(Time.time + d.wander * 7f, 25f, 1.5f);
        float dy = d.y + bob;
        float w = region.width * Draw.scl * SCALE, h = region.height * Draw.scl * SCALE;

        // shadow
        Draw.z(Layer.flyingUnit - 1f);
        Draw.color(Pal.shadow, 0.22f);
        Draw.rect(region, x - 5f, dy - 5f, w, h, rot - 90f);

        // engine glow
        Draw.z(Layer.flyingUnit);
        Draw.color(cellColor, 0.9f);
        Fill.circle(x + Angles.trnsx(rot + 180f, 3.4f), dy + Angles.trnsy(rot + 180f, 3.4f), 1.3f + Mathf.absin(Time.time, 2f, 0.5f));

        // body and glowing cell
        Draw.color();
        Draw.rect(region, x, dy, w, h, rot - 90f);
        if(cell != null && cell.found()){
            Draw.color(cellColor, 0.75f + Mathf.absin(Time.time, 8f, 0.25f));
            Draw.rect(cell, x, dy, w, h, rot - 90f);
        }
        Drawf.light(x, dy, 28f, cellColor, 0.5f);

        // repair beam
        Building t = d.repairTarget;
        if(d.repairing && t != null){
            Draw.z(Layer.flyingUnit + 0.1f);
            Draw.color(beamColor, 0.8f);
            Lines.stroke(1f + Mathf.absin(Time.time, 3f, 0.6f));
            Lines.line(x, dy, t.x, t.y);
            Fill.circle(t.x, t.y, 1.6f + Mathf.absin(Time.time, 4f, 1f));
            Fill.circle(x, dy, 1.2f);
            Drawf.light(t.x, t.y, 16f, beamColor, 0.6f);
        }

        // little heart
        if(d.heartTime > 0f){
            float f = d.heartTime / 90f;
            Draw.z(Layer.flyingUnit + 0.2f);
            Draw.color(heartColor, Mathf.clamp(f * 2f));
            drawHeart(x + 5f, dy + 6f + (1f - f) * 8f, 1.6f);
        }

        Draw.reset();

        if(d.text != null && d.textTime > 0f) drawBubble(d.text, x, dy + 8f, Mathf.clamp(d.textTime / 30f));
    }

    void drawHeart(float hx, float hy, float s){
        Fill.circle(hx - s * 0.55f, hy, s * 0.65f);
        Fill.circle(hx + s * 0.55f, hy, s * 0.65f);
        Fill.tri(hx - s * 1.15f, hy - s * 0.2f, hx + s * 1.15f, hy - s * 0.2f, hx, hy - s * 1.6f);
    }

    void drawBubble(String str, float bx, float by, float alpha){
        Draw.z(Layer.playerName + 1f);
        Font font = Fonts.outline;
        GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.22f / Scl.scl(1f));
        layout.setText(font, str);

        float bw = layout.width + 4f, bh = layout.height + 4f;
        float cy = by + 3f + bh / 2f;
        Draw.color(0f, 0f, 0f, 0.5f * alpha);
        Fill.rect(bx, cy, bw, bh);
        Fill.tri(bx - 1.5f, cy - bh / 2f, bx + 1.5f, cy - bh / 2f, bx, cy - bh / 2f - 2.5f);

        font.setColor(1f, 1f, 1f, alpha);
        font.draw(str, bx, cy + layout.height / 2f, 0f, Align.center, false);

        font.setColor(Color.white);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
        Draw.reset();
        Pools.free(layout);
    }

    // endregion
}
