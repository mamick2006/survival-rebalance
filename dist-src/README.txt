SURVIVAL REBALANCE

This tool rebalances other Starsector mods for Survival Mode, the game's own
mode added in Starsector 0.98.5a. It edits the files of those mods in place:
a number here, a switched-off row there. It contains nothing from the mods
themselves. The app\patches folder lists what it changes, one file per mod.

Before it changes a file, the tool copies the original into a folder named
survival_rebalance inside that mod's folder. Undo puts every file back from
there, byte for byte, and then deletes the folder.


START

1. Put the SurvivalRebalance folder inside your Starsector folder, next to
   the mods folder.
2. Close Starsector. The tool cannot change a mod while the game has its
   files open.
3. Double-click SurvivalRebalance.exe.

If Windows or your antivirus will not start the exe, double-click
SurvivalRebalance.bat instead. It opens the same window. On Linux or macOS,
run SurvivalRebalance.sh from a terminal.

If you keep the tool somewhere else, the window asks for your Starsector
folder the first time and saves the answer in game_dir.txt.


THE WINDOW

The list shows every mod in three groups:

   Supported mods you have    With the version you have, the version the
                              changes were tested on, and whether the mod
                              is patched.
   Not supported              Mods the tool never analysed. It does not
                              rebalance them.
   Supported, not installed   Click the line to show them.

Select a mod to see its changes: what each one was before, what it is
after, and why. Each change has a switch, and each mod has a strength
slider. See CUSTOMIZING below.

The buttons:

   Check     Look at the mods again and list the warnings. Changes nothing.
   Apply     Patch the supported mods you have. It says which mods first,
             and ends with what it patched, what it skipped, what failed,
             and every saved mod setting it changed.
   Undo      Put every patched mod back the way it was. It also says which
             mods first.
   Open log  Open the log of the last Apply or Undo.

Mods you do not have installed are skipped. Running Apply twice is safe:
the second run does nothing. Apply and Undo refuse while Starsector is
running.

The warnings at the bottom of the window are always current. Each run also
writes its text to the logs folder, with the warnings at the top.


SUPPORTED MODS

The tool changes these mods, or checked them and found they need no change
for Survival Mode. Any other mod was never analysed, so the tool does not
rebalance it, and the window and every run warn about each one you have
enabled.

A mod the tool changes is supported at the version listed here. The tool
skips any other version of it. A mod that needs no change is supported at
any version.

@SUPPORTED_MODS@

THE MONITOR MOD

The tool can only warn you when you run it. When a mod manager updates a
patched mod, the new folder has no patch in it, and you would play it
unpatched without knowing. The Survival Rebalance Monitor is a small utility
mod that checks this each time you load a game, and shows a message when a
mod is not patched.

Apply copies the monitor into your mods folder. Enable it once in the
Starsector launcher; the tool does not change which mods are enabled. Undo
removes the monitor again, and says so. A copy you put there yourself stays,
and Undo tells you to delete it. It adds nothing to your saves, so you can
turn it off at any time.


CUSTOMIZING

In the window, select a mod. Untick a change to switch it off, or move the
strength slider. Untick "Rebalance this mod" to leave the whole mod alone.
Then press Apply. Apply puts back the mods whose result changed and patches
them again. The other mods are left as they are.

The window saves your choices in customize.txt, in the SurvivalRebalance
folder, in a block at the end of the file. You can also edit customize.txt
by hand in a text editor. The window never changes the lines you write
yourself, but its own lines come last, so they win when both name the same
change.

Strength is a number from 0 to 1. At 1 a change is as designed. At 0 it is
off. At 0.5 every number goes halfway from the mod's own value to the
rebalanced one, and the tooltip that shows that number changes with it.

Some changes swap a word or switch a row off. Those have no halfway point:
any strength above 0 applies them in full. When such a change also edits a
text, apply warns you that the text may not match the number.

customize.txt has three kinds of line:

   some_mod = off                 Skip a whole mod.
   some_mod:some-change = off     Skip one change.
   some_mod:some-change = 0.5     Set how strong one change is.

You can set strength for everything (all:strength), for one mod
(some_mod:strength) or for one change. The most specific line wins.

Example 1. You want the whole rebalance at three quarters, but the price of
hiring an executive officer exactly as designed:

   all:strength = 0.75
   second_in_command:xo-hire-price = 1

Example 2. You want to pick a number yourself. app\status.bat lists each
change's ops by number (#0, #1, and so on) with the values they write. This
line makes op #0 of that change write 60000 instead of 100000:

   second_in_command:xo-hire-price#0 = 60000

The tool writes your value exactly as you typed it. If a tooltip shows the
same number, change it too: status lists the change's other ops, so you can
find it.

To get a customize.txt that lists every mod and change as lines that start
with # (a line that starts with # does nothing), run app\customize.bat.
When a new version of the tool brings new changes, run it again. It adds
them at the end of the file.

A mistake in customize.txt (a mod id with a typo, a strength of 2) is a
warning, and the rest of the file is still used.


WHAT THE MESSAGES MEAN

Applied: <change>
   The change is now in the mod. "(strength 0.5)" after it means it runs at
   that strength.

Already applied.
   Nothing to do. The mod already has the current changes.

Skipped: Survival Rebalance was tested with version <x> of this mod and
does not change version <y>.
   You have another version of the mod than the one the changes were made
   for, so the tool changes nothing in it. See WHEN A MOD UPDATES below.

Skipped: <change> (it needs <mod>:<change>, which is not applied)
   This change belongs with a change in another mod, and that change is not
   in place: the other mod is missing, it failed, or you switched the change
   off. The rest of the mod is still patched.

FAILED: <mod> <version> (tested: <versions>)
   Something the tool expected to find in this mod is not there, so its
   files differ from the tested version. The line after it names the change
   that did not match. Nothing in that mod was changed. The tool goes on to
   the next mod, so the others are still patched.

these files changed after the tool patched them
   The mod was changed after the tool patched it, most often by updating the
   mod on top of the old folder. The tool will not touch it.

Close Starsector first.
   The game is running and holds the mod's files open.


WARNINGS

Each warning names the mod (or customize.txt) and most end with "What to
do".

Starsector is running
   A file the tool must change is in use. Close the game and run the tool
   again.

the patches were tested on Starsector <version>
   Your game version is not the one the patches were made on. Apply as
   usual. Any mod that no longer matches is reported and left alone.

<mod> <version>: skipped
   Survival Rebalance was tested with another version of this mod, so apply
   changes nothing in it. See WHEN A MOD UPDATES below.

its folder was replaced since it was patched
   An update installed a new folder, so the mod is not patched right now.
   Run apply.

updated in place since it was patched
   An update wrote over the patched files. Apply leaves the mod alone.
   Delete the mod's folder, install it again cleanly, and run apply.

patched at version <x>; the mod's version changed since
   The mod's version changed, but the patched files did not. Reinstall the
   mod cleanly and run apply, so the patch is checked against the new
   version.

patched with an older patch set, or with a different customization
   The mod was patched by another version of the tool, or before you
   changed customize.txt. Run apply.

rebalanced by another version of the tool
   That version changed this mod, and the version you run has no changes
   for it. Apply puts its original files back.

another copy of the tool is in the folder <name>
   Two copies change the same mods, each to its own version. Delete the
   copy you do not use.

<file> is in the tool folder, but this version does not use it
   No version of the tool shipped that file as it is, so the tool leaves
   it where it is. Delete it if you do not need it.

Survival Rebalance Monitor: could not be replaced
   A file of the Monitor is in use. Close Starsector, then run apply again.

this folder mixes two versions
   A .bat or .exe file in the tool folder is from another version. Copy
   customize.txt out if you changed it. Delete the SurvivalRebalance folder,
   unzip the tool again, and put customize.txt back.

two folders hold this mod
   The game loads only one of them. Delete the one you do not use.

the change "<change>" is skipped
   A change it needs is not applied. See "Skipped: <change>" above.

the saved setting <name> holds <x>, not the <y> the tool set
   You changed this mod setting in the game after apply. The tool leaves
   your value. Set it back in the mod's settings if you want the rebalance.

the patch names the setting <name>, which the saved settings file does not hold
   The mod may have renamed the setting. Check it in the mod's settings
   screen.

the backup of <file> is missing or does not match
   Undo cannot put that file back. Reinstall the mod cleanly to get its
   original files.

customize.txt: line <n>
   A line in customize.txt does nothing or was refused. The warning says
   why. Fix or delete the line.

text may not match its number
   A change runs at a strength between 0 and 1, but one of its texts cannot
   follow. Set the change to 0 or 1, or give the text your own value.

<mod> <version>: not supported
   One line for each enabled mod that is not in SUPPORTED MODS above. The
   tool never analysed it, so it is not rebalanced.

the update to version <x> did not finish installing
   Open SurvivalRebalance.exe again. It finishes the install.


WHEN A MOD UPDATES

Each patch was made for one version of its mod. When you install another
version, apply skips that mod and changes nothing in it, even when the new
version looks the same. A patch that only half fits can make a mod act
oddly, and the mod's author cannot help with that. If an earlier apply
patched the folder, apply puts its original files back.

A skipped mod is not a problem in the mod, so do not report it to its
author. You have three choices:

- Play that mod without the rebalance. The other mods are still patched.
- Install the tested version again, then run apply.
- Wait for a Survival Rebalance update that supports the new version.
  Update checks for one.

When you update a patched mod, delete its old folder and install the new
version cleanly. Then run apply. The new folder has no survival_rebalance
folder, so the tool treats it as a fresh mod.

If the tool says files changed after it patched them, do the same: delete
that mod's folder, install it again, and run apply. To put back the
originals anyway, run undo from a command window with --force:

   app\undo.bat --force


SAVED SETTINGS

Some mods keep their settings (LunaLib settings) in saves\common. A saved
setting overrides the mod's default, so when the tool changes a default it
also changes the saved value. Every saved setting it changes is listed with
the old and new value:

   Saved setting changed: some_mod some_setting: 1 -> 0.5

The original value is kept in saves\common\survival_rebalance.json.data,
with the list of mods the tool patched. Undo puts the value back. If you
changed that setting yourself after apply, undo leaves your value alone and
says so.


UPDATING

When a newer version exists, the window says so at the top and shows an
Update button. Update downloads the new version, checks it, closes the
window, copies the new version over the SurvivalRebalance folder and opens
the window again. Then press Apply, so your mods get the new patch set.

An update keeps customize.txt, game_dir.txt and the logs folder. It removes
the files the old version shipped and the new one does not, such as the
patch file of a mod that no longer needs changes. Apply then puts that
mod's original files back.

The tool contacts only GitHub, at the release downloads of this tool:

   https://github.com/mamick2006/survival-rebalance/releases

Having no network is not an error. To switch the check off, add this line
to customize.txt:

   all:check_updates = off

If an update fails, the message says why, and your installed version keeps
working. If the copy stops part way, the next start of
SurvivalRebalance.exe finishes it.


CHANGING VERSIONS

To update by hand, download the new version's SurvivalRebalance.zip from
the releases page. Unzip it into your Starsector folder, over the old
SurvivalRebalance folder. When Windows asks, choose to replace the files.
Or delete the old SurvivalRebalance folder first, then unzip. Copy
customize.txt out before you delete the folder if you changed it, and put
it back after. Then open SurvivalRebalance.exe and press Apply.

To go back to an older version, delete the SurvivalRebalance folder first,
then unzip the older version's zip. Copy customize.txt out before you
delete the folder, and put it back after. Then open SurvivalRebalance.exe
and press Apply.

Choosing to keep the existing files does not work, for any version: the
old program stays and runs beside the new version's files. From 1.3.4 on
the tool notices this and says so first. It cannot tell that case from an
older version unzipped over a newer one, so in both cases Apply changes no
mod until the folder holds one version. This is on purpose. Undo still
works.

When you press Apply or Undo, the tool removes the files another version
left in its folder, and says which. Status and a dry run only say what
Apply would remove. A file of your own in the folder stays where it is. If
you changed one of the tool's own files, Apply puts the shipped one back
and keeps yours beside it, named <file>.edited. A changed .bat, .exe or
.sh file is left as it is, with a warning: unzip the tool again to get the
shipped one. Apply brings every mod to the version you run: a mod whose
changes differ is put back and patched again, and a mod this version does
not change gets its original files back. customize.txt, game_dir.txt and
the logs folder are kept. A line in customize.txt for a change this
version does not have does nothing, and the tool says so in a note.

Unzip the zip as it is, so it makes a SurvivalRebalance folder. If its
files are put loose into another folder, the tool writes nothing in that
folder and says how to install it.

The tool cannot run from a folder whose path has a ; in it, or letters
outside your Windows language (Japanese letters on an English Windows, for
example). Java cannot start it there, from the exe or from the .bat files.
Both say so in one line and name the folder at fault: the Starsector
folder, or the tool's own folder when only its name has such letters. Move
or rename that folder so its path has plain letters, such as
C:\Games\Starsector.


FOR SCRIPTS: THE .BAT FILES IN app\

Each one runs one job in a command window, prints what it did, and waits for
a key. Extra arguments are passed on (for example --dry-run, --only <id>,
--offline).

   apply.bat      Patch every installed mod that has a patch file.
   undo.bat       Put every patched mod back the way it was.
   status.bat     Show which mods are patched, and for each change the
                  values apply writes. Status changes nothing.
   check.bat      Print only the warnings. It exits with code 0 when there
                  are no warnings, 2 when there are warnings, and 1 when
                  apply would fail on a mod.
   customize.bat  Write customize.txt, or add the changes that are new
                  since it was written.
   update.bat     Install the newest version, then run apply.

Run from a command window, apply, status and check also ask GitHub once
whether a newer version exists. When one does, the warnings end with a note
that says so. --offline skips that.


REMOVING THE TOOL

Press Undo in the window first. Then delete the SurvivalRebalance folder.
The tool keeps nothing else outside the mod folders and saves\common. Your
customize.txt is in the SurvivalRebalance folder, so keep a copy if you
want it later.
