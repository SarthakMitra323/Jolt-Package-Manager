// File: src/main/java/dev/jolt/pep/WheelSelector.java
package dev.jolt.pep;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.net.PyPIClient.ArtifactFile;

import java.util.List;

/**
 * Parses PEP 427 wheel filenames:
 *   {distribution}-{version}(-{build tag})?-{python tag}-{abi tag}-{platform tag}.whl
 * and selects the highest-affinity wheel for the target PythonEnvironment.
 */
public final class WheelSelector {

    public record ParsedWheel(
            ArtifactFile artifact,
            Pep440Version version,
            String pyTag,
            String abiTag,
            String platTag,
            int affinityScore
    ) {}

    private WheelSelector() {}

    public static ParsedWheel selectBestWheel(
            List<ArtifactFile> files,
            Pep440Version targetVersion,
            PythonEnvironment env) {

        ParsedWheel best = null;
        for (int i = 0, n = files.size(); i < n; i++) {
            ArtifactFile f = files.get(i);
            if (f.yanked() || !f.filename().endsWith(".whl")) continue;

            // Check requires-python on the artifact fast-path
            if (f.requiresPython() != null && !f.requiresPython().isBlank()) {
                VersionSpec pySpec = VersionSpec.parse(f.requiresPython());
                Pep440Version envPyVer = Pep440Version.parse(env.major() + "." + env.minor() + "." + env.patch());
                if (!pySpec.matches(envPyVer)) continue;
            }

            ParsedWheel candidate = parseAndScore(f, env);
            if (candidate == null) continue;
            if (candidate.version.compareTo(targetVersion) != 0) continue;

            if (best == null || candidate.affinityScore > best.affinityScore) {
                best = candidate;
            }
        }
        return best;
    }

    public static ParsedWheel parseAndScore(ArtifactFile file, PythonEnvironment env) {
        String name = file.filename();
        if (!name.endsWith(".whl")) return null;
        String stem = name.substring(0, name.length() - 4);

        // Scan hyphens from right: platformTag, abiTag, pyTag
        int lastHyphen = stem.lastIndexOf('-');
        if (lastHyphen == -1) return null;
        String platTag = stem.substring(lastHyphen + 1);

        int abiHyphen = stem.lastIndexOf('-', lastHyphen - 1);
        if (abiHyphen == -1) return null;
        String abiTag = stem.substring(abiHyphen + 1, lastHyphen);

        int pyHyphen = stem.lastIndexOf('-', abiHyphen - 1);
        if (pyHyphen == -1) return null;
        String pyTag = stem.substring(pyHyphen + 1, abiHyphen);

        // Left of pyHyphen is "{dist}-{version}" or "{dist}-{version}-{build}"
        String distAndVer = stem.substring(0, pyHyphen);
        int firstHyphen = distAndVer.indexOf('-');
        if (firstHyphen == -1) return null;

        String verPart = distAndVer.substring(firstHyphen + 1);
        // Strip optional numeric build tag if present (e.g., 1.0.0-1)
        int buildHyphen = verPart.indexOf('-');
        if (buildHyphen != -1 && Character.isDigit(verPart.charAt(buildHyphen + 1))) {
            verPart = verPart.substring(0, buildHyphen);
        }

        int score = scoreCompatibility(pyTag, abiTag, platTag, env);
        if (score < 0) return null;

        Pep440Version ver = Pep440Version.parse(verPart);
        return new ParsedWheel(file, ver, pyTag, abiTag, platTag, score);
    }

    private static int scoreCompatibility(String pyTag, String abiTag, String platTag, PythonEnvironment env) {
        // 1. Score Python Tag (cp312 > py312 > py3 > py2.py3)
        String exactCp = "cp" + env.major() + env.minor();
        String exactPy = "py" + env.major() + env.minor();
        String majorPy = "py" + env.major();

        int pyScore = -1;
        for (String pt : pyTag.split("\\.")) {
            if (pt.equals(exactCp)) pyScore = Math.max(pyScore, 40);
            else if (pt.equals(exactPy)) pyScore = Math.max(pyScore, 30);
            else if (pt.equals("cp" + env.major()) || pt.startsWith("cp" + env.major())) {
                // Check abi3 minimum version e.g. cp38 with abi3 on cp312
                if (abiTag.contains("abi3") && isCompatibleAbi3(pt, env)) pyScore = Math.max(pyScore, 35);
            }
            else if (pt.equals(majorPy)) pyScore = Math.max(pyScore, 20);
        }
        if (pyScore < 0) return -1;

        // 2. Score ABI Tag (cp312 > abi3 > none)
        int abiScore = -1;
        for (String at : abiTag.split("\\.")) {
            if (at.equals(env.abiTag()) || at.equals(env.abiTag() + "m")) abiScore = Math.max(abiScore, 30);
            else if (at.equals("abi3")) abiScore = Math.max(abiScore, 20);
            else if (at.equals("none")) abiScore = Math.max(abiScore, 10);
        }
        if (abiScore < 0) return -1;

        // 3. Score Platform Tag
        int platScore = -1;
        for (String plt : platTag.split("\\.")) {
            if ("any".equals(plt)) {
                platScore = Math.max(platScore, 10);
            } else if (isPlatformCompatible(plt, env)) {
                platScore = Math.max(platScore, 50);
            }
        }
        if (platScore < 0) return -1;

        return platScore * 100 + pyScore * 10 + abiScore;
    }

    private static boolean isCompatibleAbi3(String cpTag, PythonEnvironment env) {
        if (!cpTag.startsWith("cp3")) return false;
        try {
            int minMinor = Integer.parseInt(cpTag.substring(3));
            return env.major() == 3 && env.minor() >= minMinor;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isPlatformCompatible(String wheelPlat, PythonEnvironment env) {
        String envPlat = env.primaryPlatformTag();
        if (wheelPlat.equals(envPlat)) return true;

        if (env.isWindows()) {
            return wheelPlat.equals("win_amd64") && envPlat.equals("win_amd64");
        }

        if (envPlat.startsWith("macosx_")) {
            if (!wheelPlat.startsWith("macosx_")) return false;
            boolean envArm = envPlat.endsWith("arm64");
            return wheelPlat.endsWith("universal2")
                    || (envArm && wheelPlat.endsWith("arm64"))
                    || (!envArm && wheelPlat.endsWith("x86_64"));
        }

        // Linux: match architecture suffix (x86_64 or aarch64) and allow manylinux* / linux_*
        String arch = envPlat.endsWith("aarch64") ? "aarch64" : "x86_64";
        if (!wheelPlat.endsWith(arch)) return false;
        return wheelPlat.startsWith("manylinux") || wheelPlat.startsWith("linux_");
    }
}