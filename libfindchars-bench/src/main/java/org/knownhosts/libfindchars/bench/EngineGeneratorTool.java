package org.knownhosts.libfindchars.bench;

import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.ShuffleOperation;
import org.knownhosts.libfindchars.generator.Target;

public class EngineGeneratorTool {

    public static void main(String[] args) {
        EngineConfiguration config = new EngineConfiguration()
                .withTarget(new Target()
                        .withDirectory("src/main/java")
                        .withPackageName("org.knownhosts.libfindchars.bench")
                )
                .withShuffleOperation(
                        new ShuffleOperation()
                                .withLiteralGroups(
                                        new AsciiLiteralGroup(
                                                "structurals",
                                                new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                                new AsciiLiteral("punctiations", ":;{}[]".toCharArray()),
                                                new AsciiLiteral("star", "*".toCharArray()),
                                                new AsciiLiteral("plus", "+".toCharArray())
                                        )
                                ));
        EngineBuilder.build(config);
    }

}