package dev.jolt.resolver;

import dev.jolt.cache.BinaryMetaCache;
import dev.jolt.env.PythonEnvironment;
import dev.jolt.installer.DownloadEngine;
import dev.jolt.net.PyPIClient;
import dev.jolt.net.PyPIClient.ArtifactFile;
import dev.jolt.net.PyPIClient.PackageIndex;
import dev.jolt.net.RangeZipReader;
import dev.jolt.pep.Pep440Version;
import dev.jolt.pep.Pep508Requirement;
import dev.jolt.pep.VersionSpec;
import dev.jolt.pep.WheelSelector;
import dev.jolt.pep.WheelSelector.ParsedWheel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PubGrubSolver {

    public record ResolvedPackage(
            String name,
            Pep440Version version,
            ArtifactFile wheelArtifact
    ) {}

    private record PackageCandidates(
            List<Pep440Version> versionsDesc,
            Map<Pep440Version, ParsedWheel> bestWheels
    ) {}

    private record IncompatibilityClause(int pkgA, int verIdxA, int pkgB, int verIdxB) {}

    private static final class DecisionFrame {
        final int decidedPkgId;
        final int chosenVerIdx;
        final VersionBitSet[] domainsSnapshot;
        final boolean[] decidedSnapshot;
        final Map<Integer, Set<String>> extrasSnapshot;

        DecisionFrame(int pkgId, int verIdx, VersionBitSet[] domains, boolean[] decided, Map<Integer, Set<String>> extras) {
            this.decidedPkgId = pkgId;
            this.chosenVerIdx = verIdx;
            this.domainsSnapshot = new VersionBitSet[domains.length];
            for (int i = 0; i < domains.length; i++) {
                this.domainsSnapshot[i] = domains[i] != null ? domains[i].copy() : null;
            }
            this.decidedSnapshot = decided.clone();
            this.extrasSnapshot = copyExtrasMap(extras);
        }
    }

    private static Map<Integer, Set<String>> copyExtrasMap(Map<Integer, Set<String>> src) {
        Map<Integer, Set<String>> copy = new java.util.HashMap<>(src.size() * 2 + 1);
        for (Map.Entry<Integer, Set<String>> e : src.entrySet()) {
            copy.put(e.getKey(), new java.util.HashSet<>(e.getValue()));
        }
        return copy;
    }

    private final PyPIClient pypiClient;
    private final PythonEnvironment env;
    private final BinaryMetaCache metaCache;
    private final DownloadEngine speculativeDownloader;

    private final Map<String, Integer> pkgToId = new HashMap<>(128);
    private final List<String> idToPkg = new ArrayList<>(128);

    private final Map<String, CompletableFuture<PackageIndex>> indexFutures = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Pep508Requirement[]>> wheelDepsFutures = new ConcurrentHashMap<>();

    private final List<PackageCandidates> pkgCandidates = new ArrayList<>(128);
    private final List<IncompatibilityClause> learnedClauses = new ArrayList<>();

    public PubGrubSolver(PyPIClient pypiClient, PythonEnvironment env) {
        this(pypiClient, env, null, null);
    }

    public PubGrubSolver(PyPIClient pypiClient, PythonEnvironment env, BinaryMetaCache metaCache) {
        this(pypiClient, env, metaCache, null);
    }

    public PubGrubSolver(
            PyPIClient pypiClient,
            PythonEnvironment env,
            BinaryMetaCache metaCache,
            DownloadEngine speculativeDownloader) {
        this.pypiClient = pypiClient;
        this.env = env;
        this.metaCache = metaCache;
        this.speculativeDownloader = speculativeDownloader;
    }

    public List<ResolvedPackage> resolve(List<String> rawRootSpecs) {
        List<Pep508Requirement> rootReqs = new ArrayList<>(rawRootSpecs.size());
        for (String raw : rawRootSpecs) {
            Pep508Requirement req = Pep508Requirement.parse(raw);
            if (req != null && req.isApplicable(env, Set.of())) {
                rootReqs.add(req);
                prefetchPackageIndex(req.name());
            }
        }

        VersionBitSet[] domains = new VersionBitSet[64];
        boolean[] decided = new boolean[64];
        int[] assignedVerIdx = new int[64];
        int[] constrainedByPkg = new int[64];
        Arrays.fill(assignedVerIdx, -1);
        Arrays.fill(constrainedByPkg, -1);

        Map<Integer, Set<String>> requestedExtras = new java.util.HashMap<>();
        for (Pep508Requirement rootReq : rootReqs) {
            int pkgId = internPackage(rootReq.name());
            if (!rootReq.extras().isEmpty()) {
                requestedExtras.computeIfAbsent(pkgId, k -> new java.util.HashSet<>()).addAll(rootReq.extras());
            }
            if (pkgId >= domains.length) {
                int newCap = pkgId * 2;
                domains = Arrays.copyOf(domains, newCap);
                decided = Arrays.copyOf(decided, newCap);
                assignedVerIdx = Arrays.copyOf(assignedVerIdx, newCap);
                constrainedByPkg = Arrays.copyOf(constrainedByPkg, newCap);
            }
            PackageCandidates cand = ensurePackageLoaded(pkgId);
            VersionBitSet rootMask = VersionBitSet.fromSpec(cand.versionsDesc(), rootReq.versionSpec());
            if (domains[pkgId] == null) {
                domains[pkgId] = rootMask;
            } else {
                domains[pkgId].intersectWith(rootMask);
            }
            if (domains[pkgId].isEmpty()) {
                throw new IllegalStateException(
                        "Unsatisfiable root constraint: " + rootReq.name() + " " + rootReq.versionSpec()
                                + " has no compatible wheels for Python " + env.major() + "." + env.minor()
                                + " (" + env.primaryPlatformTag() + ")"
                );
            }
        }

        Deque<DecisionFrame> trail = new ArrayDeque<>(64);

        while (true) {
            int nextPkg = -1;
            int minCard = Integer.MAX_VALUE;
            for (int id = 0; id < idToPkg.size(); id++) {
                if (domains[id] != null && !decided[id]) {
                    int card = domains[id].cardinality();
                    if (card < minCard) {
                        minCard = card;
                        nextPkg = id;
                    }
                }
            }

            if (nextPkg == -1) {
                break;
            }

            int candidateIdx = domains[nextPkg].firstSetBit();
            if (candidateIdx == -1) {
                if (trail.isEmpty()) {
                    throw new IllegalStateException(
                            "Resolution failed: Version conflict on package '" + idToPkg.get(nextPkg) + "'."
                    );
                }
                DecisionFrame culprit = trail.pop();
                int targetCap = Math.max(domains.length, Math.max(culprit.domainsSnapshot.length, idToPkg.size()));
                domains = Arrays.copyOf(culprit.domainsSnapshot, targetCap);
                decided = Arrays.copyOf(culprit.decidedSnapshot, targetCap);
                requestedExtras = copyExtrasMap(culprit.extrasSnapshot);
                domains[culprit.decidedPkgId].clear(culprit.chosenVerIdx);
                continue;
            }

            trail.push(new DecisionFrame(nextPkg, candidateIdx, domains, decided, requestedExtras));
            decided[nextPkg] = true;
            assignedVerIdx[nextPkg] = candidateIdx;

            PackageCandidates nextCand = pkgCandidates.get(nextPkg);
            Pep440Version chosenVersion = nextCand.versionsDesc().get(candidateIdx);
            ParsedWheel chosenWheel = nextCand.bestWheels().get(chosenVersion);

            // Phase 6 Optimization: Speculatively start streaming & unpacking this wheel
            // in the background WHILE we continue resolving deeper dependencies!
            if (speculativeDownloader != null) {
                speculativeDownloader.downloadAndUnpackAsync(
                        new ResolvedPackage(idToPkg.get(nextPkg), chosenVersion, chosenWheel.artifact())
                );
            }

            Pep508Requirement[] subDeps = fetchWheelRequirements(chosenWheel.artifact());

            Set<String> activeExtras = requestedExtras.getOrDefault(nextPkg, Set.of());
            for (Pep508Requirement sub : subDeps) {
                if (sub.isApplicable(env, activeExtras)) {
                    prefetchPackageIndex(sub.name());
                }
            }

            boolean conflictTriggered = false;
            for (Pep508Requirement sub : subDeps) {
                if (!sub.isApplicable(env, activeExtras)) continue;

                int depId = internPackage(sub.name());
                if (!sub.extras().isEmpty()) {
                    boolean addedNewExtra = requestedExtras
                            .computeIfAbsent(depId, k -> new java.util.HashSet<>())
                            .addAll(sub.extras());
                    if (addedNewExtra && depId < decided.length && decided[depId]) {
                        decided[depId] = false;
                    }
                }
                if (depId >= domains.length) {
                    int newCap = Math.max(depId + 16, domains.length * 2);
                    domains = Arrays.copyOf(domains, newCap);
                    decided = Arrays.copyOf(decided, newCap);
                    assignedVerIdx = Arrays.copyOf(assignedVerIdx, newCap);
                    constrainedByPkg = Arrays.copyOf(constrainedByPkg, newCap);
                }

                PackageCandidates depCand = ensurePackageLoaded(depId);
                VersionBitSet subMask = VersionBitSet.fromSpec(depCand.versionsDesc(), sub.versionSpec());

                if (domains[depId] == null) {
                    domains[depId] = subMask;
                    constrainedByPkg[depId] = nextPkg;
                } else {
                    domains[depId].intersectWith(subMask);
                }

                int bestSubIdx = domains[depId].firstSetBit();
                if (bestSubIdx != -1) {
                    Pep440Version topSubVer = depCand.versionsDesc().get(bestSubIdx);
                    ParsedWheel topSubWheel = depCand.bestWheels().get(topSubVer);
                    if (topSubWheel != null) {
                        prefetchWheelRequirements(topSubWheel.artifact());
                    }
                }

                if (decided[depId] && !domains[depId].get(assignedVerIdx[depId])) {
                    learnedClauses.add(new IncompatibilityClause(
                            nextPkg, candidateIdx, depId, assignedVerIdx[depId]
                    ));
                    conflictTriggered = true;
                    break;
                }

                if (domains[depId].isEmpty()) {
                    int parentCulprit = constrainedByPkg[depId];
                    if (parentCulprit != -1 && parentCulprit != nextPkg && decided[parentCulprit]) {
                        learnedClauses.add(new IncompatibilityClause(
                                nextPkg, candidateIdx, parentCulprit, assignedVerIdx[parentCulprit]
                        ));
                    }
                    conflictTriggered = true;
                    break;
                }
            }

            if (!conflictTriggered) {
                for (IncompatibilityClause clause : learnedClauses) {
                    if (clause.pkgA == nextPkg && clause.verIdxA == candidateIdx && domains[clause.pkgB] != null) {
                        domains[clause.pkgB].clear(clause.verIdxB);
                        if (domains[clause.pkgB].isEmpty()) {
                            conflictTriggered = true;
                            break;
                        }
                    } else if (clause.pkgB == nextPkg && clause.verIdxB == candidateIdx && domains[clause.pkgA] != null) {
                        domains[clause.pkgA].clear(clause.verIdxA);
                        if (domains[clause.pkgA].isEmpty()) {
                            conflictTriggered = true;
                            break;
                        }
                    }
                }
            }

            if (conflictTriggered) {
                DecisionFrame failedFrame = trail.pop();
                int targetCap = Math.max(domains.length, Math.max(failedFrame.domainsSnapshot.length, idToPkg.size()));
                domains = Arrays.copyOf(failedFrame.domainsSnapshot, targetCap);
                decided = Arrays.copyOf(failedFrame.decidedSnapshot, targetCap);
                requestedExtras = copyExtrasMap(failedFrame.extrasSnapshot);
                domains[failedFrame.decidedPkgId].clear(failedFrame.chosenVerIdx);
            }
        }

        List<ResolvedPackage> result = new ArrayList<>(idToPkg.size());
        for (int id = 0; id < idToPkg.size(); id++) {
            if (decided[id] && assignedVerIdx[id] != -1) {
                PackageCandidates cand = pkgCandidates.get(id);
                Pep440Version ver = cand.versionsDesc().get(assignedVerIdx[id]);
                ParsedWheel wheel = cand.bestWheels().get(ver);
                result.add(new ResolvedPackage(idToPkg.get(id), ver, wheel.artifact()));
            }
        }
        return result;
    }

    private int internPackage(String rawName) {
        String norm = PyPIClient.normalizePackageName(rawName);
        Integer existing = pkgToId.get(norm);
        if (existing != null) return existing;

        int id = idToPkg.size();
        pkgToId.put(norm, id);
        idToPkg.add(norm);
        pkgCandidates.add(null);
        return id;
    }

    private void prefetchPackageIndex(String pkgName) {
        String norm = PyPIClient.normalizePackageName(pkgName);
        indexFutures.computeIfAbsent(norm, key -> {
            if (metaCache != null) {
                PackageIndex cached = metaCache.getPackageIndex(key);
                if (cached != null) {
                    return CompletableFuture.completedFuture(cached);
                }
            }
            return pypiClient.fetchPackageIndexAsync(key).thenApply(idx -> {
                if (metaCache != null) {
                    metaCache.putPackageIndex(idx);
                }
                return idx;
            });
        });
    }

    private PackageCandidates ensurePackageLoaded(int pkgId) {
        PackageCandidates existing = pkgCandidates.get(pkgId);
        if (existing != null) return existing;

        String pkgName = idToPkg.get(pkgId);
        prefetchPackageIndex(pkgName);
        PackageIndex idx = indexFutures.get(pkgName).join();

        Map<Pep440Version, ParsedWheel> bestWheelPerVer = new HashMap<>(idx.versions().size());
        for (ArtifactFile file : idx.files()) {
            if (file.yanked() || !file.filename().endsWith(".whl")) continue;
            if (file.requiresPython() != null && !file.requiresPython().isBlank()) {
                VersionSpec reqPy = VersionSpec.parse(file.requiresPython());
                Pep440Version envVer = Pep440Version.parse(env.major() + "." + env.minor() + "." + env.patch());
                if (!reqPy.matches(envVer)) continue;
            }
            ParsedWheel pw = WheelSelector.parseAndScore(file, env);
            if (pw != null) {
                ParsedWheel prev = bestWheelPerVer.get(pw.version());
                if (prev == null || pw.affinityScore() > prev.affinityScore()) {
                    bestWheelPerVer.put(pw.version(), pw);
                }
            }
        }

        List<Pep440Version> sortedDesc = new ArrayList<>(bestWheelPerVer.keySet());
        sortedDesc.sort(Comparator.reverseOrder());

        PackageCandidates loaded = new PackageCandidates(sortedDesc, bestWheelPerVer);
        pkgCandidates.set(pkgId, loaded);

        if (!sortedDesc.isEmpty()) {
            ParsedWheel topWheel = bestWheelPerVer.get(sortedDesc.get(0));
            if (topWheel != null) {
                prefetchWheelRequirements(topWheel.artifact());
            }
        }
        return loaded;
    }

    private void prefetchWheelRequirements(ArtifactFile wheel) {
        String cacheKey = (wheel.sha256() != null && !wheel.sha256().isBlank()) ? wheel.sha256() : wheel.url();
        wheelDepsFutures.computeIfAbsent(cacheKey, key -> {
            if (metaCache != null) {
                Pep508Requirement[] cachedDeps = metaCache.getWheelDeps(key);
                if (cachedDeps != null) {
                    return CompletableFuture.completedFuture(cachedDeps);
                }
            }
            CompletableFuture<byte[]> rawMetaFuture = wheel.hasDetachedMetadata()
                    ? pypiClient.fetchDetachedWheelMetadataAsync(wheel)
                    : RangeZipReader.fetchMetadataViaRangeAsync(pypiClient.httpClient(), wheel.url());
            return rawMetaFuture.thenApply(bytes -> parseAndCacheRequiresDist(key, bytes));
        });
    }

    private Pep508Requirement[] fetchWheelRequirements(ArtifactFile wheel) {
        String cacheKey = (wheel.sha256() != null && !wheel.sha256().isBlank()) ? wheel.sha256() : wheel.url();
        prefetchWheelRequirements(wheel);
        return wheelDepsFutures.get(cacheKey).join();
    }

    private Pep508Requirement[] parseAndCacheRequiresDist(String cacheKey, byte[] metadataBytes) {
        List<Pep508Requirement> reqs = new ArrayList<>(8);
        List<String> rawLines = new ArrayList<>(8);
        String text = new String(metadataBytes, StandardCharsets.UTF_8);
        int pos = 0;
        int len = text.length();
        final String prefix = "Requires-Dist:";

        while (pos < len) {
            int lineEnd = text.indexOf('\n', pos);
            if (lineEnd == -1) lineEnd = len;

            int crEnd = (lineEnd > pos && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            if (crEnd == pos) {
                break;
            }

            if (text.regionMatches(true, pos, prefix, 0, prefix.length())) {
                String specRaw = text.substring(pos + prefix.length(), crEnd).trim();
                Pep508Requirement parsed = Pep508Requirement.parse(specRaw);
                if (parsed != null) {
                    reqs.add(parsed);
                    rawLines.add(specRaw);
                }
            }
            pos = lineEnd + 1;
        }
        if (metaCache != null) {
            metaCache.putWheelDeps(cacheKey, metadataBytes, rawLines);
        }
        return reqs.toArray(new Pep508Requirement[0]);
    }
}