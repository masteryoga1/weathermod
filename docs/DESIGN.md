# Weather Mod Design

Mirrored from the living design doc: https://claude.ai/code/artifact/d2c64254-10cb-4b55-8091-926b226eb7a9 (last synced 2026-10-17).

## Goals and scope

The mod is a from-scratch weather mod for Minecraft 1.21.1 on NeoForge, mod id `weathermod`, that recreates and extends Weather2's storm system and renders everything in a way Iris shader packs can handle. Weather2 2.8.7 (the uploaded jar) is the design reference; its code, assets and sounds are All Rights Reserved and are not reused.

Decisions already made:

- Mod id `weathermod`, display name Weather Mod, package `com.weathermod` (rename on request).
- Tornadoes rip blocks out of the ground and throw entities, Weather2 style. Full destruction is the default everywhere, single-player and dedicated servers alike. A server config switches to entities-only or visual-only.
- Every weather type that fits Minecraft is in scope, including types Weather2 never had (heat waves, derechos, waterspouts and others in the catalog below).
- No dependency on CoroUtil or any other library mod. NeoForge and vanilla only.
- Shader support means Iris on NeoForge 1.21.1. Oculus is a Forge-only project and is not a target.
- The Overworld and the Nether both get weather by default. The Nether's climate is hot and dry, so it gets ash fall, fire whirls and heat haze rather than rain. Other dimensions opt in through the datapack.
- Hurricanes ship with a limited storm surge: a temporary water rise of a few blocks near the eye that drains afterwards, never permanent flooding.
- The fantasy pack (meteor showers, blood rain, spore storms) ships as a datapack that is off by default and can be switched on in config.
- Public release on Modrinth and CurseForge under the MIT license, so every texture, model and sound is made from scratch.
- Compatibility targets: Serene Seasons (its season drives the climate field), Distant Horizons (far storms must look right against its terrain) and Create (wind turbines feed its rotation network, and the block ripper leaves its contraptions alone).

Out of scope for the first release: Fabric or Forge ports, Weather2 config or save compatibility, Lost Cities compatibility, and any world generation changes.

## How Weather2 works

Weather2 2.8.7 (the NeoForge 1.21.0 build in the project files) was decompiled into a scratch folder and read as a design reference only. Nothing from it is copied: it is All Rights Reserved and depends on CoroUtil, which we will not use. This section records what it does, where it is weak, and what breaks under shaders, so the rest of the design can borrow the good ideas and avoid the traps.

### Overall shape

- **Server-authoritative simulation.** One weather manager per dimension owns a list of storm objects, a wind manager, and any active particle storms (sand or snow). The client holds a mirror of that list and only renders.
- **One storm class does everything.** A single storm object represents a cloud, a thunderstorm, a hailstorm, a tornado and a hurricane. Behaviour is chosen by an integer stage ladder: 0 normal cloud, 1 thunder, 2 high wind, 3 hail, 4 forming funnel (F0), 5 to 9 tornado strength F1 to F5. A type flag separates land storms from cyclones, but the water type is never assigned in 2.8.7, so cyclones are effectively dead code in this build.
- **Clouds spawn upwind of players.** New clouds appear about 256 blocks upwind of each player and drift with the wind, so storms arrive rather than pop in. Only one can intensify at a time per region.
- **Fronts drive intensification.** Each storm carries a temperature sign. Two storms of opposite sign within 128 blocks collide and the survivor is allowed to climb the ladder. Each step rolls against a cumulative percentage derived from the configured maximum stage.
- **Wind is global and random.** A random-walk wind direction and speed per dimension, plus gusts and longer regimes. Wind moves clouds, drifts particles and pushes entities and items.
- **Sync is delta NBT.** Each storm serialises only changed fields every 40 ticks, dropping to every 2 ticks once it reaches high wind or above. Clients within range receive the deltas.

### Tornadoes and destruction

- The funnel is a column of 150 particle layers whose centres lag behind the storm position, which gives the bending look. It is purely client-side cosmetics.
- Destruction samples random positions inside a cylinder under the storm, with a height bound that is computed incorrectly and often skips the top of tall structures. Up to 5 blocks per tick are ripped, and only while a player is within 140 blocks, so unattended tornadoes do nothing.
- Ripped blocks become thrown block entities that fly in the vortex and either land as blocks or break on impact. There is no cap on how many exist at once.

### Obscurants, fog and sky

- Sand storms and snow storms are large particle storms (radius 250, about 6000 ticks) that fire around 800 rays per cycle to deposit a sand layer block on exposed surfaces.
- Fog, dust haze and storm darkness are done by cancelling the vanilla fog event and substituting shorter fog distances, and by multiplying fog in its own particle renderer.
- Lightning is a custom entity with its own renderer, separate from the vanilla lightning bolt.

### Blocks and items

Working: weather sensor, tornado siren, weather deflector, forecast block, anemometer, wind vane, wind turbine (energy output stubbed), sand layer. Stubbed or unfinished: weather machine, radar screen, pocket sand.

### Why it fails with Iris

| Weather2 technique | What happens under a shader pack | Our replacement |
| --- | --- | --- |
| Private particle engine drawn in a level-stage event | Iris treats the mod's shader as foreign and masks colour and depth, so rain, hail, funnel and dust vanish | Register particles with the vanilla particle engine so they go through the pack's particle program |
| Mixin that removes vanilla rain and draws its own | The pack never sees precipitation, so puddles, wetness and rain effects stay off | Draw precipitation inside the vanilla rain-and-snow hook, which Iris routes to the weather program |
| Fog-only haze and darkness | Most packs ignore the fog uniforms, so storms look like clear sky | Geometric haze carriers (quads and particles) plus fog as an enhancement |
| Custom lightning entity | Packs only flash on the vanilla lightning bolt | Spawn the vanilla lightning bolt and own only the damage logic |
| Storm darkening by tinting | Packs read rain and thunder levels, not tints | Drive the client level's rain and thunder levels each tick before rendering |
| Custom core shaders | Iris replaces or masks them | No custom shaders at all |

### What we keep and what we change

- **Keep the concepts:** upwind spawning, fronts as the intensification trigger, server authority with delta sync, ripped and thrown blocks, deflector and siren blocks, wind as a first-class field.
- **Change the structure:** one data-driven weather type registry instead of a stage ladder in one class, a separate tropical cyclone model, a destruction system with budgets and tiers, and a renderer that uses only vanilla draw paths.
- **Drop:** the private particle engine, the rain-removal mixin, custom core shaders, the custom lightning entity, the player-distance gate on destruction, and any CoroUtil dependency.

## Weather type catalog

Every weather type is one entry in a data-driven registry, loaded from JSON in a datapack, so types can be added, tuned or disabled without code. Each entry names a **family**, which picks the simulation code, and carries that family's parameters (intensity range, climate requirements, duration in real seconds, visuals, sounds, gameplay flags). The table below is the target set. The milestone column says when each type lands; the build plan section explains the milestones.

**Intensity scales.** Wind uses the Beaufort scale 0 to 12 internally and is shown to players in metres per second. Tornadoes use EF0 to EF5, with EF0 as the required touchdown tier so weak, short-lived tornadoes are common and EF5 is rare. Tropical cyclones use tropical depression, tropical storm, then Saffir-Simpson categories 1 to 5.

| Type | Family | Where it forms | What you see | What it does | Milestone |
| --- | --- | --- | --- | --- | --- |
| Overcast | Precipitation | Anywhere the climate field is humid | Grey sky, darker light, flat cloud deck | Sets the mood only | M2 |
| Drizzle | Precipitation | Temperate humid biomes | Sparse fine rain, light haze | Vanilla rain state on (wet surfaces, fire out) | M2 |
| Rain | Precipitation | Any biome with downfall | Rain through the pack's weather program, so puddles and wet shaders work | Vanilla rain effects: crops, fishing, fire | M2 |
| Downpour | Precipitation | Warm humid biomes, storm cells | Dense rain, low visibility, loud ambience | Stronger rain effects, faster fire extinguish | M2 |
| Snow | Precipitation | Cold biomes and high altitude | Snowfall, accumulating snow layers | Snow layer deposition (toggle) | M2 |
| Sleet and freezing rain | Precipitation | Temperature near freezing | Rain that glazes surfaces | Ice on water, slippery blocks, slowness | M2 |
| Fog and mist | Obscurant | Mornings, valleys, over water | Layered haze quads plus fog | Cuts visibility for players and mobs | M2 |
| Wind, calm to gale | Wind | Everywhere, always | Drifting particles, leaves, dust, wind sound | Pushes entities, items, projectiles and boats | M2 |
| Gusts and squalls | Wind | Ahead of fronts and storms | Sudden bursts of leaves and dust | Short strong pushes | M2 |
| Thunderstorm | Convective | Warm humid air, afternoons | Towering cloud, vanilla lightning flashes, rain | Lightning strikes with configurable fire | M3a |
| Severe thunderstorm | Convective | Along fronts | More lightning, downbursts, hail | Hail damages exposed entities and crops, downbursts shove | M3a |
| Hailstorm | Convective | Inside severe storms | Hail particles bouncing off blocks | Hail damage, optional glass breaking | M3a |
| Squall line and derecho | Convective | Leading edge of cold fronts | Long bowing wall of rain and wind | Sustained high wind over a wide area | M3a |
| Supercell | Convective | Fronts with strong shear | Rotating wall cloud, green tint, mesocyclone | Spawns tornadoes | M3a |
| Tornado EF0 to EF5 | Vortex | Under supercells | Funnel mesh, debris cloud, flying blocks | Rips blocks by tier, throws entities, destruction mode | M3a |
| Landspout | Vortex | Weak storms over land | Thin rope funnel | EF0 to EF1 effects | M3a |
| Waterspout | Vortex | Over oceans and large lakes | Spray funnel over water | Lifts spray, flips boats, no block damage | M3a |
| Gustnado | Vortex | Storm outflow | Short dust whirl | Brief pushes | M3a |
| Dust devil | Vortex | Clear hot deserts | Small sand whirl | Pushes items, lifts loose sand | M3a |
| Fire whirl | Vortex | Over large fires and lava, common in the Nether | Flame vortex | Spreads fire, burns | M3a |
| Tropical depression and tropical storm | Tropical cyclone | Warm oceans | Spiral rainbands, rising wind | Strong wind and rain over a wide area | M3b |
| Hurricane category 1 to 5 | Tropical cyclone | Warm oceans, decays after landfall | Eye, eyewall, rainbands, dark sky | Extreme wind, roof stripping at high category, limited surge near the eye | M3b |
| Cold front and warm front | Front | Boundaries in the climate field | Shelf cloud line, temperature and wind shift | Triggers convective storms | M3b |
| Blizzard | Winter | Cold biomes with strong wind | Whiteout, heavy snow, haze | Deep snow deposition, visibility loss | M3b |
| Snow squall | Winter | Behind cold fronts | Brief whiteout | Short, sharp | M3b |
| Ice storm | Winter | Prolonged freezing rain | Glaze on everything | Slippery, falling ice | M3b |
| Sandstorm and haboob | Obscurant | Deserts and badlands | Wall of sand, brown sky | Sand layer deposition, visibility, optional damage without cover | M3b |
| Dust storm | Obscurant | Savanna and dry plains | Dry haze | Visibility | M3b |
| Ash fall | Obscurant | The Nether, heaviest in basalt deltas and soul sand valleys | Grey ash drifting down, dim haze | Ash layer deposition, visibility | M3b |
| Heat wave | Regime | Hot dry biomes and the Nether | Heat shimmer, bright washed sky | More dust devils and fire whirls, faster fire spread | M4 |
| Cold snap | Regime | Cold biomes | Frost, ice on water | Freezes water, optional frost damage | M4 |
| Drought | Regime | Prolonged dry spells | Dusty haze | Slower crops, higher fire risk | M4 |
| Monsoon and wet season | Regime | Jungles and swamps | Daily downpours | Long rainy stretches | M4 |
| Rainbow | Optics | After rain with sun behind the player | Arc opposite the sun | Cosmetic | M4 |
| Halo and sun dog | Optics | Thin high ice cloud | Ring around sun or moon | Cosmetic | M4 |
| Crepuscular rays | Optics | Broken cloud at sunset | Light shafts | Cosmetic | M4 |
| Aurora | Optics | Cold biomes at night | Moving curtains | Cosmetic | M4 |
| Mammatus and lenticular clouds | Optics | After storms, over mountains | Distinctive cloud shapes | Cosmetic | M4 |
| Fantasy pack (datapack, off by default, config toggle) | Fantasy | Datapack-defined | Meteor shower, blood rain, spore storm | As configured | M4 |

**Weather2 parity.** Everything Weather2 2.8.7 ships working is covered: clouds, rain, thunder, hail, high wind, tornado F0 to F5, sandstorm, snowstorm, fog and its wind. Its cyclone, which is dead code in that build, becomes a real tropical cyclone here.

## Shader compatibility strategy

Iris on 1.21.1 picks a shader program from two things only: which vanilla shader a draw call asks for, and which rendering phase the game is in when it asks. There is no API for a mod to request a program. Anything drawn with a mod's own shader is treated as foreign and rendered with colour and depth writes disabled, which is why Weather2's rain and funnels disappear under packs. So the whole strategy is one rule: **draw everything through a path vanilla already uses, in the phase vanilla uses it.**

### The five draw paths

1. **Precipitation goes through the vanilla rain hook.** We register a dimension special effects override that replaces the vanilla rain-and-snow renderer. Iris routes that call to the pack's weather program, so our rain, snow, sleet and hail get puddles, wetness and the pack's own rain look. Returning true from the override suppresses the vanilla drawing. Only one mod can own that hook per dimension, so we log a warning if another mod has already registered one.
2. **Particles go through the vanilla particle engine.** Funnel dust, spray, hail, leaves, sand and haze puffs are normal registered particle types. The engine draws them in the pack's particle program with correct depth and lighting. The terrain sheet counts as translucent on NeoForge, which is what we want; custom render types must report translucency honestly or they draw in the wrong pass.
3. **Large volumes are entities with vanilla render types.** The tornado funnel mesh, cloud walls, wall clouds and the cyclone eyewall are client-side visual entities drawn with the vanilla entity translucent render type. Entity draws go through the pack's entity program and cast shadows. These entities need a reserved id range and culling overrides so a storm a few hundred blocks away still renders.
4. **Lightning is the vanilla lightning bolt.** Packs flash the sky only for the vanilla bolt entity. We spawn it flagged visual-only and apply damage, fire and sound ourselves, which also lets us delay thunder for distance.
5. **Fog is an enhancement, never the carrier.** Most packs ignore the fog events, so haze, dust and storm darkness are carried by geometry: layered translucent quads and particles that exist with or without a pack. The fog events are still used for extra depth when the pack honours them.

### Rules the renderer must follow

- **Drive the vanilla rain and thunder levels.** Every client tick, before rendering, set the client level's rain level and thunder level from the storms near the camera. Packs read those two values for darkening, wet surfaces and puddles. Thunder level is multiplied by rain level inside the game, so a thunderstorm must raise both.
- **Keep vanilla weather state truthful on the server.** When our weather covers a dimension, set the vanilla raining and thundering state too, so crops, fire, fishing, mob spawns and villager behaviour keep working. Vanilla's own random weather is disabled so the two never fight.
- **No custom core shaders, no level-stage drawing as a primary path.** Draws in the after-weather and after-sky stage events run in no phase, get a generic program, and cast no shadows. They are allowed only as a fallback for very distant storm silhouettes.
- **Respect the alpha test.** The particle, weather, cloud and textured programs discard pixels below one tenth alpha. Soft edges come from layering and texture gradients, not from low alpha values.
- **Clouds are ours, not the cloud hook.** Sodium replaces the vanilla cloud renderer and Iris has open cloud bugs, so storm clouds are particles and entities, and the vanilla cloud layer is left alone.
- **Sky optics render inside the sky hook.** Rainbows, halos and auroras are drawn from the dimension special effects sky override, which keeps them in the sky phase.
- **Iris is detected, never required.** We compile against the Iris API only to ask whether a pack is active, and use that to scale the fog enhancement. The mod runs identically without Iris or Sodium.
- **Delayed thunder.** The vanilla thunder sound is cancelled in the play-sound event and replaced with our own, played after a distance-based delay.

### Test matrix

Each milestone's shader pass checks these on the user's PC with Iris and Sodium installed:

| Check | No pack | Complementary Reimagined | BSL | Photon |
| --- | --- | --- | --- | --- |
| Rain and snow visible, puddles and wetness react |  |  |  |  |
| Hail and funnel particles visible with depth |  |  |  |  |
| Funnel mesh visible and casts a shadow |  |  |  |  |
| Lightning flashes the sky |  |  |  |  |
| Storm darkening matches the pack's rain look |  |  |  |  |
| Fog and haze readable |  |  |  |  |
| Sky optics visible and not clipped |  |  |  |  |

## Mod architecture

The mod is one NeoForge project with mod id `weathermod`, Java 21, NeoForge 21.1 and Parchment mappings. It is split into six modules that only talk to each other through the registry and the network layer, so the simulation can be tested without a client and the renderer can be swapped without touching physics.

_(Architecture diagram lives in the Claude Doc linked above.)_

Everything on the left runs on the server and is authoritative. The client receives snapshots and deltas for storms in range and draws only through the five vanilla paths on the right, which is what keeps every shader pack working.

### Modules

- **Core and registry.** The weather type registry, the family enum, biome and block tags, and the datapack loader. Types live in `data/<pack>/weathermod/weather_type/*.json`. Block tags decide what each EF tier can rip; biome tags decide where each type may form. The Overworld and the Nether are enabled by default; other dimensions opt in through a dimension tag.
- **Server simulation.** One dimension weather object per level, stored as saved data so storms survive restarts. It owns the climate field (noise over biome temperature and downfall, with seasonal and daily drift), the wind field (random walk plus gusts and regimes), the front system, and the storm director. When Serene Seasons is installed, its current season replaces the built-in seasonal drift, so snow lines and winter storms follow it. The director spawns clouds upwind of players, biases storm headings toward populated areas so storms are seen, and applies a frequency controller with cooldowns so severe weather stays rare and configurable. Each storm is an instance of a family system: precipitation, convective, vortex, tropical cyclone, front, winter, obscurant, regime, optics.
- **Impact.** Everything that changes the world. The block ripper samples candidates from the heightmap under the vortex, filters by the tier's block tag, fires a block break event through a fake player so protection mods can veto it, and spends a per-tick budget of about 16 blocks. Blocks that belong to a Create contraption are never ripped. Ripped blocks become flying block entities, capped at about 48 per storm, that orbit and land or shatter. The destruction mode is a server config that defaults to full; entities only and visual only are the alternatives. The storm surge raises water a few blocks near a hurricane's eye and drains it afterwards, never flooding permanently. Impact also covers entity forces from wind, snow, sand and ash deposition, lightning damage and fire, and the bridge that keeps vanilla rain state truthful.
- **Network.** Typed payloads with stream codecs: full storm snapshot on first sight, small deltas every few ticks, funnel keyframes every 10 ticks, wind and climate updates once a second. Each storm has an interest radius so a player only receives storms that could affect what they see.
- **Client.** A client weather state that mirrors the storms in range. From it: the precipitation renderer inside the vanilla rain hook, particle emitters feeding the vanilla particle engine, visual entities for funnels and cloud walls, sky optics inside the sky hook, the fog enhancer, the sound director, and the forecast and radar screens. Every client tick it also writes the vanilla rain and thunder levels. Storms beyond vanilla render distance are drawn as simple silhouettes so they sit correctly against Distant Horizons terrain.
- **Content and config.** Blocks and items: weather sensor, tornado siren, weather deflector, forecast station, anemometer, wind vane, wind turbine with real energy output and rotational output for Create when it is installed, rain gauge, barometer. Commands to spawn, steer, intensify and clear storms for testing. Three config files: server (frequencies, destruction mode, budgets), client (particle density, haze strength, sound), common (dimension list, fantasy pack toggle, datapack overrides), all editable from the in-game configuration screen.

### The tornado funnel

The server keeps a funnel spine of 24 control points from the ground to the cloud base. Each point lags the one above it, which bends the funnel naturally. The spine is sent as a keyframe every 10 ticks and the client interpolates between keyframes and tessellates a tube mesh around it, textured with scrolling dust. A shared vortex physics model, a Rankine vortex with a solid core and decaying outer field, gives tangential and inward wind at any point and is reused by every vortex type. The tropical cyclone uses a separate model with an eye radius, a Holland pressure profile, rainbands and landfall decay, because it is hundreds of blocks across and behaves nothing like a tornado.

### Tick flow

Server, every tick: climate and wind update, director decides spawns and dissipations, each storm advances its state, impact applies forces and destruction within budget, the vanilla rain state is reconciled, then deltas and keyframes are sent to interested players.

Client, every tick: apply incoming deltas, interpolate funnel spines, update the vanilla rain and thunder levels for the camera position, emit particles, position visual entities, update sounds.

Client, every frame: the vanilla rain hook draws precipitation, the particle engine draws particles, the entity renderer draws funnels and cloud walls, the sky hook draws optics, and the fog events add depth where the pack allows it.

## Build plan and milestones

The four steps agreed in the thread map onto five milestones. Each milestone ends with a jar the user can drop into their instance and a short checklist to run in game. Code is compiled and unit-tested in the cloud; anything visual is checked on the user's PC. No dates are attached: the first two milestones are well understood, the tornado and cyclone work is where estimates usually slip, so M3 is split in two.

_(Roadmap diagram lives in the Claude Doc linked above.)_

Steps 2 to 4 of the plan agreed in the thread map onto five milestones. Each one ends with a jar in your hands and the in-game check written under its diamond.

### M1: Skeleton (start of step 2)

- Gradle project on NeoForge 21.1 with Parchment, Iris as a compile-only dependency, a GitHub Actions build.
- Registry, datapack loader, config files and configuration screen, saved data per dimension, payload registration, debug overlay and test commands.
- **Done when:** the mod loads with Iris and Sodium installed, a command spawns a placeholder storm that appears on the debug overlay for a second player, and the build runs green in Actions.

### M2: Core weather (rest of step 2)

- Climate field, wind field, storm director, precipitation and obscurant families.
- Overcast, drizzle, rain, downpour, snow, sleet, fog, wind and gusts. Vanilla rain state bridge. Snow and ice deposition under toggles.
- Overworld and Nether climates from the start: the Nether's hot, dry field produces heat haze and wind here, with its ash fall and fire whirls following in M3.
- Serene Seasons integration: when it is installed, its season drives the climate field's temperature and the snow line.
- Precipitation renderer in the vanilla rain hook, haze carriers, wind particles, vanilla rain and thunder level driver, basic ambience sounds.
- First shader pass against the test matrix, with Distant Horizons installed for the far-terrain check.
- **Done when:** a survival world has believable weather that arrives from upwind, puddles appear under Complementary and BSL, and vanilla crops and fishing still respond to rain.

### M3a: Thunderstorms and the tornado family (step 3, part one)

- Convective and vortex families, fronts as the trigger, vanilla lightning with our damage logic and delayed thunder.
- Thunderstorm, severe thunderstorm, hail, squall line and derecho, supercell, tornado EF0 to EF5, landspout, waterspout, gustnado, dust devil, fire whirl (including the Nether).
- Funnel spine sync and mesh, block ripper with tiers, budgets and destruction mode (default full), flying blocks, entity forces, tornado siren and weather deflector blocks. The ripper skips Create contraptions.
- **Done when:** an EF3 crosses a test village, rips what its tag allows at a steady frame rate, protection via the break event is respected, and the funnel is visible with a shadow under the three test packs.

### M3b: Cyclones, fronts, winter and dust (step 3, part two)

- Tropical cyclone model with eye, rainbands, landfall decay and the limited surge; winter family; sandstorm, dust storm and Nether ash fall with deposition.
- Hurricane categories, blizzard, snow squall, ice storm, sandstorm, haboob, dust storm, ash fall, cold and warm fronts as visible weather.
- Forecast station, weather sensor, anemometer, wind vane, wind turbine (with Create rotation when Create is present), rain gauge, barometer, radar screen.
- Second shader pass including the eyewall and whiteout.
- **Done when:** a category 3 hurricane makes landfall on a coastal base, the surge rises and drains without leaving water behind, the forecast station predicted it, and the radar screen shows rainbands moving.

### M4: Regimes, optics, extras and polish (step 4)

- Regime and optics families: heat wave, cold snap, drought, monsoon, rainbow, halo, crepuscular rays, aurora, mammatus and lenticular clouds.
- Fantasy pack (meteor showers, blood rain, spore storms) as a separate datapack, off by default, with a config toggle that turns it on.
- Final shader pass, compatibility checks with Serene Seasons, Distant Horizons and Create, performance pass on particle counts and sync bandwidth, sound polish, translations, documentation.
- Modrinth and CurseForge listings under the MIT license, with all original textures, models and sounds.
- **Done when:** the full catalog is in, the test matrix is green on all four columns, and a fresh install needs no configuration to feel right.

### Working agreement

- Design doc first, then code. This document is mirrored into the repository as `docs/DESIGN.md` once approved and updated as decisions change.
- One pull request per milestone slice, kept small enough to review, each with the compile and unit tests passing in Actions.
- In-game checks run on connorspc. Nothing is installed or run there until the user says go; until then the cloud builds the jar and the user tests it by hand.
- Every shader pass also runs with Distant Horizons installed, since far storms meet its terrain at the edge of render distance.

## Decisions from the review

Answered by masteryoga in the thread on 17 October 2026. Each one is folded into the sections above.

1. **Destruction default.** Full destruction everywhere, dedicated servers included. Entities-only and visual-only stay available in the server config.
2. **Storm surge.** Ship the limited surge in M3b: a temporary rise of a few blocks near the eye that drains afterwards.
3. **Dimensions.** The Nether gets weather by default from the start: ash fall, fire whirls and heat haze. Other dimensions opt in through the datapack.
4. **Fantasy pack.** A datapack that is off by default, with a config toggle to turn it on.
5. **Testing machine.** connorspc runs the in-game checks once the user says go.
6. **Release.** Public, on Modrinth and CurseForge, under the MIT license with all original assets.
7. **Compatibility targets.** Serene Seasons, Distant Horizons and Create.

Still open: whether step 3 stays split into M3a (tornadoes first) and M3b (cyclones second), asked in the comment on the build plan. The plan assumes yes.
