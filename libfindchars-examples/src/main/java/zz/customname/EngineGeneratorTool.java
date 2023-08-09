package zz.customname;

import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.RangeOperation;
import org.knownhosts.libfindchars.generator.ShuffleOperation;
import org.knownhosts.libfindchars.generator.Target;

public class EngineGeneratorTool {
	
	public static void main(String[] args) {
		EngineConfiguration config = new EngineConfiguration()
			.withTarget(new Target()
					.withDirectory("src/main/java")
					.withPackageName("zz.customname")
					)
			.withRangeOperations(new RangeOperation("comparison").withRange(0x3c, 0x3e))
			.withShuffleOperation(
					new ShuffleOperation()
						.withLiteralGroups(
								new AsciiLiteralGroup(
										"structurals", 
										new AsciiLiteral("whitespaces","\r\n\t\f ".toCharArray()),
										new AsciiLiteral("punctiations",":;{}[],.".toCharArray()),
										new AsciiLiteral("star","*".toCharArray()),
										new AsciiLiteral("plus","+".toCharArray())
								),
								new AsciiLiteralGroup(
										"numbers", 
										new AsciiLiteral("nums","0123456789".toCharArray())
								)
							));
		EngineBuilder.build(config);
	}

}