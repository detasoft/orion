package pro.deta.orion.git.parser.v2.lsrefs;

import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record LsRefsRequest(
        boolean peel,
        boolean symrefs,
        boolean unborn,
        List<String> refPrefixes) {

    private static final int MAX_PREFIX_COUNT = 256;
    private static final int MAX_PREFIX_CHARS = 65_536;

    public LsRefsRequest {
        Objects.requireNonNull(refPrefixes, "refPrefixes");
        refPrefixes = List.copyOf(refPrefixes);
    }

    public static LsRefsRequest parse(GitProtocolContext.Reader reader) throws IOException {
        boolean peel = false;
        boolean symrefs = false;
        boolean unborn = false;
        List<String> prefixes = new ArrayList<>();
        int prefixChars = 0;
        for (;;) {
            GitPktLine packet = reader.readGitPktLine();
            if (packet == GitPktLine.Control.FLUSH) {
                return new LsRefsRequest(peel, symrefs, unborn, prefixes);
            }
            if (!(packet instanceof GitPktLine.Data data)) {
                throw new IOException("Expected ls-refs argument or flush");
            }
            String line = data.text();
            int separator = line.indexOf(' ');
            String name = separator < 0 ? line : line.substring(0, separator);
            LsRefsArgument argument = LsRefsArgument.findByWireName(name)
                    .orElseThrow(() -> new IOException("Invalid ls-refs argument: " + name));
            if ((argument == LsRefsArgument.REF_PREFIX) != (separator >= 0)) {
                throw new IOException("Invalid value for ls-refs argument: " + name);
            }
            switch (argument) {
                case PEEL -> peel = true;
                case SYMREFS -> symrefs = true;
                case UNBORN -> unborn = true;
                case REF_PREFIX -> {
                    String prefix = line.substring(separator + 1);
                    if (prefixes.size() >= MAX_PREFIX_COUNT
                            || prefix.length() > MAX_PREFIX_CHARS - prefixChars) {
                        throw new IOException("Too many ls-refs prefixes");
                    }
                    prefixes.add(prefix);
                    prefixChars += prefix.length();
                }
            }
        }
    }

    public boolean matches(String refName) {
        Objects.requireNonNull(refName, "refName");
        if (refPrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : refPrefixes) {
            if (refName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
