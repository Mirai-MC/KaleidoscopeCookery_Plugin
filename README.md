# KaleidoscopeCookery Plugin

KaleidoscopeCookery Plugin brings the cooking, kitchen appliances, crops, food, and furniture of Kaleidoscope Cookery to modern Minecraft servers through CraftEngine.

This repository is a maintained fork focused on the current server stack.

## Compatibility

| Component | Supported version |
| --- | --- |
| Minecraft | 26.3 |
| Java | 25 |
| Paper API | 26.3 |
| Folia / Lophine | Supported |
| CraftEngine | 26.9.2-SNAPSHOT |

CraftEngine is required. Dominion and PlaceholderAPI integrations are optional.

These versions were tested together on Lophine 26.3 with its Folia region scheduler enabled. Older Minecraft versions are not supported by this maintained fork.

## Features

- Cooking appliances including pots, stockpots, steamers, millstones, chopping boards, stoves, and shawarma spits
- Configurable ingredients, dishes, recipes, food quality, and cooking behavior
- Crops, kitchen tools, tables, chairs, decorations, and interactive furniture
- CraftEngine-backed custom blocks, items, models, sounds, and resource-pack content
- Recipe browsing and editing with `/kcrecipe`
- Folia-compatible scheduling

## Installation

1. Install Java 25, a Minecraft 26.3 Paper/Folia-compatible server, and CraftEngine 26.9.2-SNAPSHOT.
2. Put the plugin JAR in `plugins/`.
3. Extract the KaleidoscopeCookery content pack into `plugins/CraftEngine/resources/kaleidoscopecookery/`.
4. Start the server and confirm that all configured blocks pass the startup registry check.

The content-pack directory must contain `pack.yml`, `configuration/`, and `resourcepack/`.

## Building

Install JDK 25, then run:

```shell
./gradlew --no-configuration-cache clean build
```

On Windows:

```powershell
.\gradlew.bat --no-configuration-cache clean build
```

The plugin JAR is generated in `build/libs/`.

Pushing a tag beginning with `v`, such as `v1.1.9`, automatically builds the project and attaches the plugin JAR to a GitHub Release.

## Credits

This is a maintained fork of [Nicoppara/KaleidoscopeCookery_Plugin](https://github.com/Nicoppara/KaleidoscopeCookery_Plugin). See the [original plugin README](https://github.com/Nicoppara/KaleidoscopeCookery_Plugin#readme) for the original documentation and credits.

The original gameplay and artwork are based on [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) by the Kaleidoscope Mods team. CraftEngine is developed by [Xiao-MoMi](https://github.com/Xiao-MoMi/craft-engine).
