package dev.jolt.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CliConfig {
    public enum Command {
        INSTALL, UNINSTALL, UPDATE, LIST, CACHE_CLEAR, SYNC, FREEZE, VENV, INIT, SELF_UPDATE, HELP
    }

    public final Command command;
    public final List<String> targets;
    public final Path venvPath;
    public final Path requirementsFile;
    public final List<String> extras;
    public final boolean allExtras;
    public final boolean quiet;
    public final boolean verbose;
    public final boolean noCache;
    public final int maxConcurrency;

    private CliConfig(
            Command command,
            List<String> targets,
            Path venvPath,
            Path requirementsFile,
            List<String> extras,
            boolean allExtras,
            boolean quiet,
            boolean verbose,
            boolean noCache,
            int maxConcurrency) {
        this.command = command;
        this.targets = Collections.unmodifiableList(targets);
        this.venvPath = venvPath;
        this.requirementsFile = requirementsFile;
        this.extras = Collections.unmodifiableList(extras);
        this.allExtras = allExtras;
        this.quiet = quiet;
        this.verbose = verbose;
        this.noCache = noCache;
        this.maxConcurrency = maxConcurrency;
    }

    public static CliConfig parse(String[] args) {
        if (args.length == 0) {
            return new CliConfig(Command.HELP, List.of(), null, null, List.of(), false, false, false, false, 16);
        }

        Command cmd = null;
        List<String> targets = new ArrayList<>(Math.max(4, args.length));
        List<String> extras = new ArrayList<>();
        boolean allExtras = false;
        Path venvPath = null;
        Path reqFile = null;
        boolean quiet = false;
        boolean verbose = false;
        boolean noCache = false;
        int concurrency = Math.min(32, Runtime.getRuntime().availableProcessors() * 4);

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                if (arg.startsWith("--extra=")) {
                    addExtras(arg.substring("--extra=".length()), extras);
                    continue;
                }
                switch (arg) {
                    case "-q", "--quiet" -> quiet = true;
                    case "-v", "--verbose" -> verbose = true;
                    case "--no-cache" -> noCache = true;
                    case "--all-extras" -> allExtras = true;
                    case "-h", "--help" -> {
                        return new CliConfig(Command.HELP, List.of(), null, null, List.of(), false, false, false, false, concurrency);
                    }
                    case "--extra" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("Missing group name after --extra");
                        addExtras(args[i], extras);
                    }
                    case "-r", "--requirement" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("Missing file path after " + arg);
                        reqFile = Path.of(args[i]);
                    }
                    case "-p", "--python", "--venv" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("Missing environment path after " + arg);
                        venvPath = Path.of(args[i]);
                    }
                    case "-j", "--jobs" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("Missing thread count after " + arg);
                        concurrency = Integer.parseInt(args[i]);
                    }
                    default -> throw new IllegalArgumentException("Unknown flag: " + arg);
                }
            } else if (cmd == null) {
                cmd = switch (arg) {
                    case "install", "i" -> Command.INSTALL;
                    case "uninstall", "remove", "rm" -> Command.UNINSTALL;
                    case "update", "upgrade" -> Command.UPDATE;
                    case "list", "ls" -> Command.LIST;
                    case "sync" -> Command.SYNC;
                    case "freeze" -> Command.FREEZE;
                    case "venv" -> Command.VENV;
                    case "init" -> Command.INIT;
                    case "self-update", "selfupdate" -> Command.SELF_UPDATE;
                    case "cache" -> {
                        if (i + 1 < args.length && "clear".equals(args[i + 1])) {
                            i++;
                            yield Command.CACHE_CLEAR;
                        }
                        throw new IllegalArgumentException("Expected 'cache clear'");
                    }
                    case "help" -> Command.HELP;
                    default -> throw new IllegalArgumentException("Unknown command: " + arg);
                };
            } else {
                if ((cmd == Command.SYNC || cmd == Command.INIT) && reqFile == null) {
                    reqFile = Path.of(arg);
                } else {
                    targets.add(arg);
                }
            }
        }

        if (cmd == null) cmd = Command.HELP;
        return new CliConfig(cmd, targets, venvPath, reqFile, extras, allExtras, quiet, verbose, noCache, concurrency);
    }

    private static void addExtras(String raw, List<String> out) {
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
    }
}