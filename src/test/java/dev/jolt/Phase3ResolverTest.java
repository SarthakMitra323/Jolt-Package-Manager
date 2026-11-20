// File: src/test/java/dev/jolt/Phase3ResolverTest.java
package dev.jolt;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.pep.Pep440Version;
import dev.jolt.pep.Pep508Requirement;
import dev.jolt.pep.VersionSpec;
import dev.jolt.resolver.VersionBitSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class Phase3ResolverTest {
    public static void main(String[] args) {
        testPep440Ordering();
        testVersionSpecMatching();
        testPep508MarkerEvaluation();
        testBitSetIntersection();
        System.out.println("All Phase 3 Dependency Resolver tests passed.");
    }

    private static void testPep440Ordering() {
        // Strict PEP 440 hierarchy: 1.0.dev1 < 1.0a1 < 1.0b2 < 1.0rc1 < 1.0 == 1.0.0 < 1.0.post1
        Pep440Version vDev   = Pep440Version.parse("1.0.dev1");
        Pep440Version vAlpha = Pep440Version.parse("1.0a1");
        Pep440Version vBeta  = Pep440Version.parse("1.0b2");
        Pep440Version vRc    = Pep440Version.parse("1.0rc1");
        Pep440Version vFinal = Pep440Version.parse("1.0");
        Pep440Version vZero  = Pep440Version.parse("1.0.0");
        Pep440Version vPost  = Pep440Version.parse("1.0.post1");

        assert vDev.compareTo(vAlpha) < 0;
        assert vAlpha.compareTo(vBeta) < 0;
        assert vBeta.compareTo(vRc) < 0;
        assert vRc.compareTo(vFinal) < 0;
        assert vFinal.compareTo(vZero) == 0;
        assert vFinal.compareTo(vPost) < 0;
    }

    private static void testVersionSpecMatching() {
        VersionSpec spec = VersionSpec.parse(">=1.21.1, !=1.24.*, <2.0.0");
        assert spec.matches(Pep440Version.parse("1.21.1"));
        assert spec.matches(Pep440Version.parse("1.26.18"));
        assert !spec.matches(Pep440Version.parse("1.24.0"));
        assert !spec.matches(Pep440Version.parse("1.24.3"));
        assert !spec.matches(Pep440Version.parse("2.0.0a1")); // <2.0.0 must also reject 2.0.0a1

        VersionSpec compat = VersionSpec.parse("~=1.4.2");
        assert compat.matches(Pep440Version.parse("1.4.2"));
        assert compat.matches(Pep440Version.parse("1.4.9"));
        assert !compat.matches(Pep440Version.parse("1.5.0"));
    }

    private static void testPep508MarkerEvaluation() {
        PythonEnvironment linuxPy312 = new PythonEnvironment(
                Path.of("/tmp/.venv"), Path.of("/tmp/.venv/lib/python3.12/site-packages"),
                Path.of("/tmp/.venv/bin"), Path.of("/tmp/.venv/bin/python"),
                3, 12, 4, "cp312", "manylinux_2_28_x86_64", false
        );

        Pep508Requirement winOnly = Pep508Requirement.parse(
                "colorama (>=0.4.6) ; sys_platform == 'win32' or os_name == 'nt'"
        );
        assert !winOnly.isApplicable(linuxPy312, Set.of());

        Pep508Requirement py312Match = Pep508Requirement.parse(
                "urllib3 (<3,>=1.21.1) ; python_version >= '3.8' and extra != 'socks'"
        );
        assert py312Match.isApplicable(linuxPy312, Set.of());

        Pep508Requirement socksExtra = Pep508Requirement.parse(
                "PySocks (!=1.5.7,>=1.5.6) ; extra == 'socks'"
        );
        assert !socksExtra.isApplicable(linuxPy312, Set.of());
        assert socksExtra.isApplicable(linuxPy312, Set.of("socks"));
    }

    private static void testBitSetIntersection() {
        List<Pep440Version> candidatesDesc = List.of(
                Pep440Version.parse("2.2.0"), // idx 0
                Pep440Version.parse("2.0.7"), // idx 1
                Pep440Version.parse("1.26.18"), // idx 2
                Pep440Version.parse("1.25.11"), // idx 3
                Pep440Version.parse("1.21.0")   // idx 4
        );

        VersionBitSet maskA = VersionBitSet.fromSpec(candidatesDesc, VersionSpec.parse(">=1.25.0"));
        VersionBitSet maskB = VersionBitSet.fromSpec(candidatesDesc, VersionSpec.parse("<2.0.0"));

        maskA.intersectWith(maskB);
        assert maskA.cardinality() == 2;
        assert maskA.firstSetBit() == 2; // Index 2 -> 1.26.18 (highest version satisfying both)
    }
}