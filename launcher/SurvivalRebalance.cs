// SurvivalRebalance.exe: opens the Survival Rebalance window.
//
// What it does, in order:
//   1. Finds the Starsector folder: the folder above this one when it holds "mods" and
//      "jre\bin\javaw.exe", else the path in game_dir.txt, else it asks with a folder picker and
//      saves the answer in game_dir.txt.
//   2. Installs a downloaded update if one is waiting in update\ (see the Updates class).
//   3. Starts the window with the game's own Java:
//        <game>\jre\bin\javaw.exe -cp app\SurvivalRebalance.jar survivalrebalance.gui.Window --game <game> --launcher
//   4. When the window exits with code 42 it has downloaded an update: back to step 2.
// It shows a message box when it cannot do one of these, and says what to do.
//
// Build: launcher\build_launcher.bat (uses the C# compiler that ships with Windows).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

[assembly: AssemblyTitle("Survival Rebalance")]
[assembly: AssemblyDescription("Opens the Survival Rebalance window")]
[assembly: AssemblyProduct("Survival Rebalance")]
[assembly: AssemblyCopyright("Survival Rebalance authors")]
[assembly: AssemblyVersion("1.3.5.0")]
[assembly: AssemblyFileVersion("1.3.5.0")]

static class Launcher
{
    const string Title = "Survival Rebalance";
    const string MainClass = "survivalrebalance.gui.Window";
    const int ExitRestart = 42;

    [STAThread]
    static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        string tool = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        // for the tests: install a staged update into a folder and stop (0 done or nothing to do, 1 failed).
        // With no Java given, the install finishes natively instead of with "update --finish".
        if (args.Length >= 2 && args[0] == "--install-update")
        {
            string ignored;
            string err = Updates.InstallIfReady(args[1].TrimEnd('\\'), args.Length > 2 ? args[2] : null, out ignored);
            if (err != null) Console.Out.WriteLine(err);
            return err == null ? 0 : 1;
        }
        // for the tests: the message for a folder Java cannot run from (0 and nothing printed when it can)
        if (args.Length >= 2 && args[0] == "--check-path")
        {
            string why = BadPath(args[1], args.Length > 2 && args[2] == "--tool");
            if (why != null) Console.Out.WriteLine(why);
            return why == null ? 0 : 1;
        }
        try
        {
            if (FindJar(tool) == null && !Directory.Exists(Path.Combine(tool, "update")))
            {
                Tell("Survival Rebalance is missing its files. If you started it from inside the zip, unzip the whole download first "
                    + "(right-click the zip, Extract All). Then start SurvivalRebalance.exe from the new folder.", MessageBoxIcon.Error);
                return 1;
            }
            string game = FindGame(tool);
            if (game == null) return 1;
            // the Starsector folder first: when it is plain, only the tool folder's own path is at fault
            string bad = BadPath(game) ?? BadPath(tool, true);
            if (bad != null)
            {
                Tell(bad, MessageBoxIcon.Error);
                return 1;
            }
            string javaw = Path.Combine(game, @"jre\bin\javaw.exe");
            if (!File.Exists(javaw))
            {
                Tell("Cannot find Java in " + Path.Combine(game, @"jre\bin") + ".\n\n"
                    + "Starsector keeps its own Java there, and the window needs it. Check that "
                    + game + " is your Starsector folder, or open the window with SurvivalRebalance.bat, "
                    + "which can also use a Java installed on your computer.", MessageBoxIcon.Error);
                return 1;
            }
            string java = Path.Combine(game, @"jre\bin\java.exe");
            while (true)
            {
                string updated;
                string why = Updates.InstallIfReady(tool, File.Exists(java) ? java : javaw, out updated);
                if (why != null) Tell("The downloaded update could not be installed:\n\n" + why
                    + "\n\nThe version you had opens instead. Press Update in the window to try again.", MessageBoxIcon.Warning);
                string jar = FindJar(tool);
                if (jar == null)
                {
                    Tell("Cannot find SurvivalRebalance.jar in " + Path.Combine(tool, "app") + ".\n\nUnzip the tool again.", MessageBoxIcon.Error);
                    return 1;
                }
                int code = Run(javaw, jar, game, updated);
                if (code == ExitRestart) continue;
                if (code != 0)
                    Tell("Survival Rebalance stopped with an error (code " + code + ").\n\nStart SurvivalRebalance.bat instead. It shows what went wrong.", MessageBoxIcon.Warning);
                return code;
            }
        }
        catch (Exception e)
        {
            Tell("Something went wrong: " + e.Message, MessageBoxIcon.Error);
            return 1;
        }
    }

    static int Run(string javaw, string jar, string game, string updated)
    {
        string args = "-cp " + Quote(jar) + " " + MainClass + " --game " + Quote(game) + " --launcher";
        if (updated != null) args += " --updated " + Quote(updated);
        var psi = new ProcessStartInfo(javaw, args);
        psi.UseShellExecute = false;
        psi.WorkingDirectory = Path.GetDirectoryName(jar);
        using (var p = Process.Start(psi))
        {
            p.WaitForExit();
            return p.ExitCode;
        }
    }

    /// <summary>
    /// Null when Java can start the tool from this folder. A ; in the path stops the game's Java
    /// before it starts, and a letter outside this Windows' code page reaches Java as "?", so the
    /// jar cannot be found. The window's own runs start Java the same way, so neither can work.
    /// </summary>
    public static string BadPath(string dir, bool toolFolder = false)
    {
        bool semi = dir.IndexOf(';') >= 0;
        Encoding ansi = Encoding.GetEncoding(Encoding.Default.CodePage, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
        bool odd = false;
        try { ansi.GetBytes(dir); } catch (EncoderFallbackException) { odd = true; }
        if (!semi && !odd) return null;
        return "Survival Rebalance cannot run from " + dir + ", because its path has "
            + (semi ? "a ; in it" : "letters that Java cannot read on this Windows") + ".\n\n"
            + (toolFolder
                ? "Rename that folder, or move it, so that its path has only plain letters, numbers and spaces, then start the tool again."
                : "Move the Starsector folder to a folder whose path has only plain letters, numbers and spaces, such as C:\\Games\\Starsector, then start the tool again.");
    }

    public static string Quote(string s) { return "\"" + s.TrimEnd('\\') + "\""; }

    public static string FindJar(string tool)
    {
        foreach (var p in new[] { Path.Combine(tool, @"app\SurvivalRebalance.jar"), Path.Combine(tool, "SurvivalRebalance.jar") })
            if (File.Exists(p)) return p;
        return null;
    }

    static bool IsGame(string dir)
    {
        return !string.IsNullOrEmpty(dir) && Directory.Exists(Path.Combine(dir, "mods"));
    }

    /// <summary>A Starsector folder this launcher can start the window from: a mods folder and the game's own Java.</summary>
    static bool IsStarsector(string dir)
    {
        return IsGame(dir) && File.Exists(Path.Combine(dir, @"jre\bin\javaw.exe"));
    }

    static string FindGame(string tool)
    {
        string parent = Path.GetDirectoryName(tool);
        if (IsGame(parent) && File.Exists(Path.Combine(parent, @"jre\bin\javaw.exe"))) return parent;
        string file = Path.Combine(tool, "game_dir.txt");
        if (File.Exists(file))
        {
            foreach (var line in File.ReadAllLines(file))
            {
                string g = line.Replace("\"", "").Trim();
                if (g.Length == 0) continue;
                if (IsStarsector(g)) return Path.GetFullPath(g).TrimEnd('\\');
                break;
            }
        }
        using (var fb = new FolderBrowserDialog())
        {
            fb.Description = "Survival Rebalance cannot find your Starsector folder. Choose the folder that holds starsector.exe and the mods folder. It is usually C:\\Program Files (x86)\\Fractal Softworks\\Starsector.";
            fb.ShowNewFolderButton = false;
            while (fb.ShowDialog() == DialogResult.OK)
            {
                string g = fb.SelectedPath.TrimEnd('\\');
                if (!IsGame(g) && IsGame(Path.GetDirectoryName(g))) g = Path.GetDirectoryName(g);
                if (IsGame(g) && !IsStarsector(g))
                {
                    Tell("This folder has a mods folder but no Starsector in it. Choose the folder that holds starsector.exe.", MessageBoxIcon.Warning);
                    continue;
                }
                if (IsStarsector(g))
                {
                    File.WriteAllText(file, g + "\r\n", new UTF8Encoding(false));
                    return g;
                }
                Tell(g + " has no mods folder. Choose your Starsector folder, the one that holds the mods folder.", MessageBoxIcon.Warning);
            }
        }
        return null;
    }

    static void Tell(string text, MessageBoxIcon icon)
    {
        MessageBox.Show(text, Title, MessageBoxButtons.OK, icon);
    }
}

/// <summary>
/// Installs an update that the window (or update.bat) downloaded and checked, by the written
/// staged-update format: update\staged\ holds the new version's files, update\remove.txt the
/// files to delete, and update\update.json is written last, so it exists only when the staged
/// files are complete. Install: copy the staged files over the install root, delete the files in
/// remove.txt, then run the NEW jar with "update --finish", which checks every file's hash and
/// clears update\. The player's files are never written or deleted: customize.txt, game_dir.txt
/// and disabled_changes.txt in any folder, anything under a logs folder, and update\ itself.
/// </summary>
static class Updates
{
    static readonly string Self = Path.GetFullPath(Assembly.GetExecutingAssembly().Location);

    /// <summary>Null when there was nothing to do or the update is installed; else why it failed. updated: the new version, when one was installed.</summary>
    public static string InstallIfReady(string tool, string java, out string updated)
    {
        updated = null;
        try { if (File.Exists(Self + ".old")) File.Delete(Self + ".old"); } catch (Exception) { }
        string ud = Path.Combine(tool, "update");
        string staged = Path.Combine(ud, "staged");
        string marker = Path.Combine(ud, "update.json");
        if (!File.Exists(marker))
        {
            // no marker: a download was cut short, or there is nothing to install
            TryDeleteDir(staged);
            TryDelete(Path.Combine(ud, "remove.txt"));
            TryDelete(Path.Combine(ud, "SurvivalRebalance.zip"));
            return null;
        }
        string version = "?";
        try
        {
            var m = Regex.Match(File.ReadAllText(marker), "\"version\"\\s*:\\s*\"([^\"]+)\"");
            if (m.Success) version = m.Groups[1].Value;
            if (!Directory.Exists(staged)) throw new IOException("the downloaded files are missing (update\\staged)");
            Copy(tool, staged);
            Remove(tool, Path.Combine(ud, "remove.txt"));
        }
        catch (Exception e)
        {
            return e.Message;
        }
        if (java == null)
        {
            TryDelete(marker);
            TryDeleteDir(staged);
            TryDelete(Path.Combine(ud, "remove.txt"));
        }
        else
        {
            string jar = Launcher.FindJar(tool);
            if (jar == null) return "the new version has no SurvivalRebalance.jar";
            var psi = new ProcessStartInfo(java, "-jar " + Launcher.Quote(jar) + " update --finish");
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            psi.WorkingDirectory = tool;
            using (var p = Process.Start(psi))
            {
                string output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
                p.WaitForExit();
                if (p.ExitCode != 0)
                {
                    foreach (var line in output.Split('\n'))
                        if (line.StartsWith("UPDATE FAILED:")) return line.Substring("UPDATE FAILED:".Length).Trim();
                    return "the check of the installed files failed (code " + p.ExitCode + "): " + output.Trim();
                }
            }
        }
        // the install is done: update\ only holds the batch installer now, which update.bat writes again when it needs one
        TryDeleteDir(ud);
        updated = version;
        return null;
    }

    /// <summary>Copy every staged file over the install root, skipping the player's files.</summary>
    static void Copy(string tool, string staged)
    {
        foreach (var f in Directory.GetFiles(staged, "*", SearchOption.AllDirectories))
        {
            string rel = f.Substring(staged.TrimEnd('\\').Length + 1);
            if (Protected(rel)) continue;
            string dst = Path.Combine(tool, rel);
            Directory.CreateDirectory(Path.GetDirectoryName(dst));
            if (string.Equals(Path.GetFullPath(dst), Self, StringComparison.OrdinalIgnoreCase))
            {
                // a running program cannot be overwritten, but it can be renamed out of the way
                if (File.Exists(dst + ".old")) File.Delete(dst + ".old");
                File.Move(dst, dst + ".old");
            }
            File.Copy(f, dst, true);
        }
    }

    /// <summary>Delete each file remove.txt lists, then the folders that left empty.</summary>
    static void Remove(string tool, string list)
    {
        if (!File.Exists(list)) return;
        string top = Path.GetFullPath(tool).TrimEnd('\\');
        foreach (var line in File.ReadAllLines(list))
        {
            string rel = line.Trim().Replace('/', '\\');
            if (rel.Length == 0 || Protected(rel) || rel.Contains("..") || rel.Contains(":") || rel.StartsWith("\\")) continue;
            string p = Path.Combine(tool, rel);
            if (File.Exists(p)) File.Delete(p);
            string dir = Path.GetDirectoryName(p);
            while (dir != null && Path.GetFullPath(dir).TrimEnd('\\').Length > top.Length && Directory.Exists(dir)
                   && Directory.GetFileSystemEntries(dir).Length == 0)
            {
                Directory.Delete(dir);
                dir = Path.GetDirectoryName(dir);
            }
        }
    }

    /// <summary>The player's files: never written, never deleted.</summary>
    public static bool Protected(string rel)
    {
        string r = rel.Replace('/', '\\').TrimStart('\\').ToLowerInvariant();
        string name = r.Substring(r.LastIndexOf('\\') + 1);
        if (name == "customize.txt" || name == "game_dir.txt" || name == "disabled_changes.txt") return true;
        if (r == "update" || r.StartsWith("update\\")) return true;
        foreach (var seg in r.Split('\\')) if (seg == "logs") return true;
        return false;
    }

    static void TryDelete(string f)
    {
        try { if (File.Exists(f)) File.Delete(f); } catch (Exception) { }
    }

    static void TryDeleteDir(string d)
    {
        try { if (Directory.Exists(d)) Directory.Delete(d, true); } catch (Exception) { }
    }
}
