# Survival Rebalance

Survival Rebalance is a small Windows tool for Starsector players who play Survival Mode, the game's own mode added in Starsector 0.98.5a, with other mods installed. Survival Mode changes the game's economy: ships cost more, weapons sell for more, and recovering ships is harder. Many mods were balanced for the normal game, so under Survival some of their ships, rewards and bonuses are far too cheap or too strong. The tool changes those numbers so the mods fit Survival.

It works on the mods you already have. It edits their files in place: a number in a CSV cell, a value in a JSON file, a constant in a compiled class, a row switched off. It adds no content and replaces no file.

## What it contains

Nothing from the mods it changes. Each patch file lists addresses (a file, a row id, a column, a class and method name), the old and new numbers, and short replacement tokens such as `25%`. Where the tool has to recognise a piece of a mod's text before it edits it, the patch file holds a SHA-256 hash of that text, not the text.

This project is not affiliated with the authors of the mods it covers, and they do not support it. If a patched mod misbehaves, run undo before you report a bug to its author.

This tool gets much lighter testing than my own mods and is offered on a best-effort basis. Keep a backup of your saves. Undo restores every file the tool changed.

## Covered mods

The list below is written from the patch set when the repository is published. A mod that is not listed is not changed. A mod the tool changes is supported at the version in the table, and the tool skips any other version of it. A mod that needs no change is supported at any version.

<!-- mods:start -->

32 mods with 198 changes, and 41 mods that need no change, at any version.

| Mod | Mod id | Tested on | Changes |
|---|---|---|---|
| ! PatchLib ! | `patchlib` | any version | 0 |
| Additional Console Commands | `console_additions` | any version | 0 |
| Arma Armatura | `armaa` | 3.3.9 | 9 |
| ArmaA - Anime | `zzarmaa_anime` | any version | 0 |
| Astral Ascension | `Planetace_AstralAscension` | 1.2.7 | 1 |
| Building Menu Overhaul | `bmo` | any version | 0 |
| Captain's Quarters | `captains_quarters` | any version | 0 |
| Chief Navigator | `chief_navigator` | 0.5.1 | 2 |
| Combat Chatter | `chatter` | any version | 0 |
| Console Commands | `lw_console` | any version | 0 |
| Customizable Star Systems | `customizablestarsystems` | any version | 0 |
| Demand Indicators | `demandIndicators` | any version | 0 |
| Detailed Combat Results | `DetailedCombatResults` | any version | 0 |
| Diable ArmAonics | `diablearmaaonics` | any version | 0 |
| Diable Avionics Original Flavor | `diableavionics` | any version | 0 |
| Draconis Fleet | `levianeer_draconis` | 0.7.91 | 4 |
| Emergent Threats | `EmergentThreats_Vice` | 1.4.3 | 5 |
| Emergent Threats: IX Revival | `EmergentThreats_IX_Revival` | 1.4.3 | 6 |
| Exiled Sector | `exiledSector` | 0.12.7 | 20 |
| Expanded Administrators | `zoeyea` | any version | 0 |
| Gacha Hullmods | `gacha_hullmods` | any version | 0 |
| Grand.Colonies | `GrandColonies` | any version | 0 |
| Hullmods - Renewed | `hullmods_renewed` | any version | 0 |
| Industrial.Evolution | `IndEvo` | 4.1.b | 19 |
| JaydeePiracy | `jaydeepiracy` | 6.0.3 | 4 |
| JaydeeProficiencies | `JaydeeProficiencies` | any version | 0 |
| Kadur Remnant | `kadur_remnant` | 3.2.4support3 | 1 |
| Larger Zoom Out | `largerZoomOut` | any version | 0 |
| LazyLib | `lw_lazylib` | any version | 0 |
| Leading Pip | `leadingPip` | any version | 0 |
| Liminal - Custom, Upgradeable Hullmods | `liminal` | any version | 0 |
| LunaLib | `lunalib` | any version | 0 |
| MagicLib | `MagicLib` | any version | 0 |
| Mamick Crossmod Demo | `mamick_crossmod_demo` | any version | 0 |
| More Military Missions | `MoreMilitaryMissions` | 0.5.1 | 1 |
| N84 Phase Pack | `N84_Phase_Pack` | 0.2.11 | 5 |
| Navarchy of Tania | `navarchy_of_tania` | 1.4.0 | 11 |
| Nexerelin | `nexerelin` | 0.12.2x | 19 |
| Nightcross Armory | `Nightcross` | 2.2.0 | 8 |
| Nomad Flotilla | `nomadic_fleet` | any version | 0 |
| Orky Sector | `looted_sector` | 0.71c | 1 |
| Particle Engine | `particleengine` | any version | 0 |
| Phillip Andrada: Gas Station Manager | `PAGSM` | 1.14.1aa | 2 |
| Postmodern Carriers | `su_CarrierHullmod` | any version | 0 |
| Privateering - Commission Overhaul | `privateering` | 1.0.4 | 7 |
| Project Arondight | `arondight` | any version | 0 |
| Random Assortment of Things | `assortment_of_things` | 3.4.1 | 14 |
| Refit Filters | `refitfilters` | any version | 0 |
| Salvage Magnet | `salvage_magnet` | 1.2.0 | 1 |
| Second-in-Command | `second_in_command` | 2.1.2 | 11 |
| Second-in-Command: Auxiliaries | `sic-auxiliaries` | 2.0.0 | 9 |
| Second-in-Command: Dustkeeper | `sicdustkeeper` | any version | 0 |
| Secrets of the Frontier | `secretsofthefrontier` | 0.15.2 | 11 |
| Ship/Weapon Pack | `swp` | any version | 0 |
| Solsector | `Solsector` | any version | 0 |
| Special Hullmod Upgrades | `mayu_specialupgrades` | 1.6a | 4 |
| SpeedUp | `speedUp` | any version | 0 |
| StarUILib | `staruilib` | any version | 0 |
| Stellar Networks | `stelnet` | 3.3.0 | 1 |
| Strike Craft Expansion Addon | `SCE` | 1.0.1b | 1 |
| Sunrider | `Sunrider` | 0.8.6 | 5 |
| Takeshido | `takeshido` | 1.0.0 | 3 |
| Terraforming Made Easy | `terraformingmadeeasy` | 2.10.0 | 2 |
| Terrible Pirates | `TerriblePirates` | any version | 0 |
| Tinyverse - The Wanzer Age | `TVVFW` | any version | 0 |
| Trails of Tooth and Claw | `gwyvern_trailstc` | v1.3.0 | 1 |
| True Weapon Stats | `trueweaponstats` | any version | 0 |
| United Aurora Federation | `uaf` | 0.8.4c | 9 |
| Unknown Skies | `US` | any version | 0 |
| Weapon Arcs | `WEAPONARCS` | any version | 0 |
| Wide Horizons | `WideHorizons` | 1.5.0 | 1 |
| Wide Horizons - Content | `WideHorizonsContent` | any version | 0 |
| zz GraphicsLib | `shaderLib` | any version | 0 |

<!-- mods:end -->

## Install

1. Download `SurvivalRebalance.zip` from the latest release.
2. Unzip it inside your Starsector folder, so the `SurvivalRebalance` folder sits next to the `mods` folder.
3. Close Starsector. The tool cannot change a file while the game has it open.
4. Double-click `SurvivalRebalance.exe`.

The exe is a small launcher, built from `launcher/SurvivalRebalance.cs` with the C# compiler that ships with Windows. It finds your Starsector folder and opens the tool's window with the game's own Java. If Windows or your antivirus will not start it, double-click `SurvivalRebalance.bat`, which opens the same window. On Linux or macOS, run `SurvivalRebalance.sh` from a terminal. If you keep the tool somewhere else, the window asks for your Starsector folder once and saves it in `game_dir.txt`.

## The window

The window lists every mod you have in three groups: supported mods, with the version you have, the tested version and whether each is patched; mods the tool never analysed, which it does not rebalance; and supported mods you do not have.

| Button | What it does |
|---|---|
| Check | Looks at the mods again and lists the warnings. Changes nothing. |
| Apply | Patches every supported mod you have. It says which mods first, and ends with what it patched, what it skipped, what failed, and every saved setting it changed. Running it twice is safe. |
| Undo | Puts every patched mod back the way it was. |
| Update | Shown when a newer release exists. It downloads it, checks it, and installs it over the tool folder. |

Before the tool changes a file, it copies the original into a folder named `survival_rebalance` inside that mod's folder. Undo puts every file back from there, byte for byte, and deletes the folder.

Some mods keep their settings in LunaLib's saved settings under `saves\common`. When the tool changes such a setting's default it changes the saved value too, lists the old and new value, and keeps the old one so undo can restore it.

The `app` folder holds the tool itself and a `.bat` file for each job (`apply.bat`, `undo.bat`, `status.bat`, `check.bat`, `customize.bat`, `update.bat`) for anyone who runs it from scripts.

## Customize

Select a mod in the window to see its changes. Untick a change to switch it off, or move the mod's strength slider, then press Apply. The window saves your choices in `customize.txt`, which you can also edit by hand:

```
some_mod = off                 skip a whole mod
some_mod:some-change = off     skip one change
some_mod:some-change = 0.5     run one change at half strength
all:strength = 0.75            run everything at three quarters
```

At strength 0.5 every number goes halfway from the mod's own value to the rebalanced one, and the tooltip that shows the number follows it. A change that swaps a word or switches a row off has no halfway point: any strength above 0 applies it in full. `README.txt` in the zip covers the rest, including setting one value by hand.

## Updating the tool

When a newer release exists, the window says so and shows an Update button. Update downloads the release, checks its SHA-256, copies it over the tool folder and opens the window again. Then press Apply, so your mods get the new patch set. `customize.txt` and `game_dir.txt` are kept.

To look for a release, the tool reads `latest.json` from this repository's release downloads on GitHub. It contacts nothing else. With no network it carries on without the check. To switch the check off, add this line to `customize.txt`:

```
all:check_updates = off
```

## Changing versions

To update by hand, unzip the new version's `SurvivalRebalance.zip` into your Starsector folder, over the old `SurvivalRebalance` folder. When Windows asks, choose to replace the files. Or delete the old `SurvivalRebalance` folder first, then unzip. Copy `customize.txt` out before you delete the folder if you changed it, and put it back after. Then open the tool and press Apply.

To go back to an older version, delete the `SurvivalRebalance` folder first, then unzip the older version's zip. Copy `customize.txt` out before you delete the folder, and put it back after. Then open the tool and press Apply.

Choosing to keep the existing files does not work, for any version: the old program stays and runs beside the new version's files. From 1.3.4 on the tool notices this and says so first. It cannot tell that case from an older version unzipped over a newer one, so in both cases Apply changes no mod until the folder holds one version. This is on purpose. Undo still works.

When you press Apply or Undo, the tool removes the files another version left in its folder, and says which. Status and a dry run only say what Apply would remove. A file of your own in the folder stays where it is. If you changed one of the tool's own files, Apply puts the shipped one back and keeps yours beside it, named `<file>.edited`. A changed `.bat`, `.exe` or `.sh` file is left as it is, with a warning: unzip the tool again to get the shipped one. Apply brings every mod to the version you run: a mod whose changes differ is put back and patched again, and a mod this version does not change gets its original files back. `customize.txt`, `game_dir.txt` and the logs are kept. A line in `customize.txt` for a change this version does not have does nothing, and the tool says so in a note.

Unzip the zip as it is, so it makes a `SurvivalRebalance` folder. If its files are put loose into another folder, the tool writes nothing in that folder and says how to install it.

The tool cannot run from a folder whose path has a `;` in it, or letters outside your Windows language (Japanese letters on an English Windows, for example). Java cannot start it there, from the exe or from the `.bat` files. Both say so in one line and name the folder at fault: the Starsector folder, or the tool's own folder when only its name has such letters. Move or rename that folder so its path has plain letters, such as `C:\Games\Starsector`.

## When a mod updates

A mod manager installs an update as a new folder, and the new folder is not patched. The zip includes the Survival Rebalance Monitor, a small utility mod that checks this each time you load a game and shows a message when a covered mod is not patched. Apply copies it into your `mods` folder; enable it once in the launcher. It adds nothing to your saves. Undo removes it, unless you copied it there yourself; then Undo says so and leaves it for you to delete.

After an update, delete the mod's old folder, install the new version cleanly, and run apply. Each patch was made for one version of its mod. When you have another version, apply skips that mod and changes nothing in it, even when the new version looks the same, and the window says so. A patch that only half fits can make a mod act oddly, and the mod's author cannot help with that, so a skipped mod is not a bug to report to them. You can play that mod without the rebalance, install the tested version again, or wait for a Survival Rebalance update that supports the new version.

If a mod was updated on top of its patched folder, the tool refuses to touch it and says so. Reinstall that mod cleanly and run apply.

## Removing the tool

Press Undo in the window, then delete the `SurvivalRebalance` folder. The tool keeps nothing outside the mod folders and `saves\common`.

## Building from source

The repository holds the tool's Java source, its tests, the companion mod's source, and the sealed patch files in `patches/`. You need JDK 17 or later and a Starsector install, because the monitor compiles against the game's jars.

```
set STARSECTOR=C:\Games\Starsector
build.bat
```

`build.bat` compiles the tool, the monitor and the launcher, runs their tests, and writes `dist\SurvivalRebalance.zip`. The tool bundles the ASM library; its licence is in `lib/ASM-LICENSE.txt`.

## Licence

GPL-3.0. See `LICENSE`.
