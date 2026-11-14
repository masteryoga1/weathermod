# Weather Mod

A from-scratch weather mod for Minecraft 1.21.1 on NeoForge: every kind of weather from
drizzle to EF5 tornadoes and category 5 hurricanes, built so that Iris shader packs keep
working.

- Design: [docs/DESIGN.md](docs/DESIGN.md)
- Toolchain: Java 21, NeoForge 21.1, Parchment mappings, ModDevGradle
- License: MIT

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runClient` starts a development client.

## Testing

`./gradlew build` runs the unit tests. The game tests start a headless server and play
the weather out in a real world:

```
./gradlew runGameTestServer
```

The run has passed when its log (`run/logs/latest.log`) ends with "All ... required tests passed".
