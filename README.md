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
