package mindustry.entities.comp;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.Queue;
import arc.util.*;
import mindustry.*;
import mindustry.annotations.Annotations.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.util.*;

import static mindustry.Vars.*;

@Component
abstract class BuilderComp implements Posc, Statusc, Teamc, Rotc{
    @Import float x, y, rotation, buildSpeedMultiplier;
    @Import UnitType type;
    @Import Team team;

    @SyncLocal Queue<BuildPlan> plans = new Queue<>(1);
    @SyncLocal boolean updateBuilding = true;

    private transient float buildCounter;
    private transient BuildPlan lastActive;
    private transient int lastSize;
    transient float buildAlpha = 0f;

    public boolean canBuild(){
        return type.buildSpeed > 0 && buildSpeedMultiplier > 0;
    }

    @Override
    public void update(){
        updateBuildLogic();
    }

    public void updateBuildLogic(){
        if(!headless){
            //visual activity update
            if(lastActive != null && buildAlpha <= 0.01f){
                lastActive = null;
            }

            buildAlpha = Mathf.lerpDelta(buildAlpha, activelyBuilding() ? 1f : 0f, 0.15f);
        }

        if(!updateBuilding || !canBuild()) return;

        float finalPlaceDst = state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange;
        boolean infinite = state.rules.infiniteResources || team().rules().infiniteResources;

        buildCounter += Time.delta;

        while(buildCounter >= 1){
            buildCounter -= 1f;

            Iterator<BuildPlan> it = plans.iterator();
            while(it.hasNext()){
                BuildPlan req = it.next();
                Tile tile = world.tile(req.x, req.y);
                if(tile == null || (req.breaking && tile.block() == Blocks.air) || (!req.breaking && ((tile.build != null && tile.build.rotation == req.rotation) || !req.block.rotate) && tile.block() == req.block)){
                    it.remove();
                }
            }

            var core = core();

            //nothing to build.
            if(buildPlan() == null) continue;

            //find the next build request
            if(plans.size > 1){
                int total = 0;
                BuildPlan req;
                while((!within((req = buildPlan()).tile(), finalPlaceDst) || shouldSkip(req, core)) && total < plans.size){
                    plans.removeFirst();
                    plans.addLast(req);
                    total++;
                }
            }

            BuildPlan current = buildPlan();
            Tile tile = current.tile();

            lastActive = current;
            buildAlpha = 1f;
            if(current.breaking) lastSize = tile.block().size;

            if(!within(tile, finalPlaceDst)) continue;

            if(!headless){
                Vars.control.sound.loop(Sounds.build, tile, 0.51f);
            }

            if(!(tile.build instanceof ConstructBuild cb)){
                if(!current.initialized && !current.breaking && Build.validPlace(current.block, team, current.x, current.y, current.rotation)){
                    boolean hasAll = infinite || current.isRotation(team) || !Structs.contains(current.block.requirements, i -> core != null && !core.items.has(i.item, Math.min(Mathf.round(i.amount * state.rules.buildCostMultiplier), 1)));

                    if(hasAll){
                        Call.beginPlace(self(), current.block, team, current.x, current.y, current.rotation);
                    }else{
                        current.stuck = true;
                    }
                }else if(!current.initialized && current.breaking && Build.validBreak(team, current.x, current.y)){
                    Call.beginBreak(self(), team, current.x, current.y);
                }else{
                    plans.removeFirst();
                    continue;
                }
            }else if((tile.team() != team && tile.team() != Team.derelict) || (!current.breaking && (cb.current != current.block || cb.tile != current.tile()))){
                plans.removeFirst();
                continue;
            }

            if(tile.build instanceof ConstructBuild && !current.initialized){
                Core.app.post(() -> Events.fire(new BuildSelectEvent(tile, team, self(), current.breaking)));
                current.initialized = true;
            }

            //if there is no core to build with or no build entity, stop building!
            if((core == null && !infinite) || !(tile.build instanceof ConstructBuild entity)){
                continue;
            }

            float bs = 1f / entity.buildCost * Time.delta * type.buildSpeed * buildSpeedMultiplier * state.rules.buildSpeed(team);

            //otherwise, update it.
            if(current.breaking){
                entity.deconstruct(self(), core, bs);
            }else{
                entity.construct(self(), core, bs, current.config);
            }

            current.stuck = Mathf.equal(current.progress, entity.progress);
            current.progress = entity.progress;
        }
    }

    /** Draw all current build plans. Does not draw the beam effect, only the positions. */
    void drawBuildPlans(){
        Boolf<BuildPlan> skip = plan -> plan.progress > 0.01f || (buildPlan() == plan && plan.initialized && (within(plan.x * tilesize, plan.y * tilesize, type.buildRange) || state.isEditor()));

        for(int i = 0; i < 2; i++){
            for(BuildPlan plan : plans){
                if(skip.get(plan)) continue;
                if(i == 0){
                    drawPlan(plan, 1f);
                }else{
                    drawPlanTop(plan, 1f);
                }
            }
        }

        Draw.reset();
    }

    void drawPlan(BuildPlan request, float alpha){
        request.animScale = 1f;
        if(request.breaking){
            control.input.drawBreaking(request);
        }else{
            request.block.drawPlan(request, control.input.allRequests(),
            Build.validPlace(request.block, team, request.x, request.y, request.rotation) || control.input.requestMatches(request),
            alpha);
        }
    }

    void drawPlanTop(BuildPlan request, float alpha){
        if(!request.breaking){
            Draw.reset();
            Draw.mixcol(Color.white, 0.24f + Mathf.absin(Time.globalTime, 6f, 0.28f));
            Draw.alpha(alpha);
            request.block.drawPlanConfigTop(request, plans);
        }
    }

    /** @return whether this request should be skipped, in favor of the next one. */
    boolean shouldSkip(BuildPlan request, @Nullable Building core){
        //requests that you have at least *started* are considered
        if(state.rules.infiniteResources || team.rules().infiniteResources || request.breaking || core == null || request.isRotation(team) || (isBuilding() && !within(plans.last(), type.buildRange))) return false;

        return (request.stuck && !core.items.has(request.block.requirements)) || (Structs.contains(request.block.requirements, i -> !core.items.has(i.item, Math.min(i.amount, 15)) && Mathf.round(i.amount * state.rules.buildCostMultiplier) > 0) && !request.initialized);
    }

    void removeBuild(int x, int y, boolean breaking){
        //remove matching request
        int idx = plans.indexOf(req -> req.breaking == breaking && req.x == x && req.y == y);
        if(idx != -1){
            plans.removeIndex(idx);
        }
    }

    /** Return whether this builder's place queue contains items. */
    boolean isBuilding(){
        return plans.size != 0;
    }

    /** Clears the placement queue. */
    void clearBuilding(){
        plans.clear();
    }

    /** Add another build requests to the tail of the queue, if it doesn't exist there yet. */
    void addBuild(BuildPlan place){
        addBuild(place, true);
    }

    /** Add another build requests to the queue, if it doesn't exist there yet. */
    void addBuild(BuildPlan place, boolean tail){
        if(!canBuild()) return;

        BuildPlan replace = null;
        for(BuildPlan request : plans){
            if(request.x == place.x && request.y == place.y){
                replace = request;
                break;
            }
        }
        if(replace != null){
            plans.remove(replace);
        }
        Tile tile = world.tile(place.x, place.y);
        if(tile != null && tile.build instanceof ConstructBuild cons){
            place.progress = cons.progress;
        }
        if(tail){
            plans.addLast(place);
        }else{
            plans.addFirst(place);
        }
    }

    boolean activelyBuilding(){
        //not actively building when not near the build plan
        if(isBuilding()){
            if(!state.isEditor() && !within(buildPlan(), state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
                return false;
            }
        }
        return isBuilding() && updateBuilding;
    }

    /** Return the build request currently active, or the one at the top of the queue.*/
    @Nullable
    BuildPlan buildPlan(){
        return plans.size == 0 ? null : plans.first();
    }

    public void draw(){
        drawBuilding();
    }

    public void drawBuilding(){
        //TODO make this more generic so it works with builder "weapons"
        boolean active = activelyBuilding();
        if(!active && lastActive == null) return;

        Draw.z(Layer.flyingUnit);

        BuildPlan plan = active ? buildPlan() : lastActive;
        Tile tile = plan.tile();
        var core = team.core();

        if(tile == null || !within(plan, state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
            return;
        }

        //draw remote plans.
        if(core != null && active && !isLocal() && !(tile.block() instanceof ConstructBlock)){
            Draw.z(Layer.plans - 1f);
            drawPlan(plan, 0.5f);
            drawPlanTop(plan, 0.5f);
            Draw.z(Layer.flyingUnit);
        }

        if(type.drawBuildBeam){
            float focusLen = type.buildBeamOffset + Mathf.absin(Time.time, 3f, 0.6f);
            float px = x + Angles.trnsx(rotation, focusLen);
            float py = y + Angles.trnsy(rotation, focusLen);

            drawBuildingBeam(px, py);
        }
    }

    public void drawBuildingBeam(float px, float py){
        boolean active = activelyBuilding();
        if(!active && lastActive == null) return;

        Draw.z(Layer.flyingUnit);

        BuildPlan plan = active ? buildPlan() : lastActive;
        Tile tile = world.tile(plan.x, plan.y);

        if(tile == null || !within(plan, state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
            return;
        }

        int size = plan.breaking ? active ? tile.block().size : lastSize : plan.block.size;
        float tx = plan.drawx(), ty = plan.drawy();

        Lines.stroke(1f, plan.breaking ? Pal.remove : Pal.accent);
        Draw.z(Layer.buildBeam);

        Draw.alpha(buildAlpha);

        if(!active && !(tile.build instanceof ConstructBuild)){
            Fill.square(plan.drawx(), plan.drawy(), size * tilesize/2f);
        }

        Drawf.buildBeam(px, py, tx, ty, Vars.tilesize * size / 2f);

        Fill.square(px, py, 1.8f + Mathf.absin(Time.time, 2.2f, 1.1f), rotation + 45);

        Draw.reset();
        Draw.z(Layer.flyingUnit);
    }
}
