package org.knownhosts.libfindchars.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentScope;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.AutoMemorySegment;
import org.knownhosts.libfindchars.MatchStorage;
import org.knownhosts.libfindchars.api.AsciiLiteral;
import org.knownhosts.libfindchars.api.AsciiLiteralGroup;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.api.SolverException;

import jdk.incubator.vector.ByteVector;

class FindingEngineScalarTest {

	static List<FindMask> findMasks;
	
	@BeforeAll
	static void setup() throws InvalidConfigurationException, InterruptedException, SolverException {
		var literalCompiler = new LiteralCompiler();
		var whitespaces = new AsciiLiteral("whitespace",0,"+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ".toCharArray());
		var group = new AsciiLiteralGroup("whitespaces",whitespaces);
		findMasks = literalCompiler.solve(group);
	}
	
	@Test
	void test() throws IOException, URISyntaxException {
		String chars = "+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ";
		var scalarFinder = new FindingEngineScalar(chars);
		var vecFinder = new FindingEngine(new ShuffleMaskFindOperation( findMasks));
		var url = FindingEngineScalarTest.class.getClassLoader().getResource("dummy.txt");
		var uri = url.toURI();
		
		try(Arena arena = Arena.openShared()){
			var channel = FileChannel.open(Path.of(uri), StandardOpenOption.READ);
			var srcSegment = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena.scope());
			var tapeStorage = new MatchStorage((int)channel.size() / 7 << 1, 32);
//			var tapeSegment = AutoMemorySegment.withProvider((t, u) -> MemorySegment.ofArray(new int[t.intValue()]),(int)channel.size() / 7 << 1, arena.scope());
			
			var tape = vecFinder.find(srcSegment, tapeStorage);
			var bytes = Files.readAllBytes(Paths.get(url.getFile()));
			var scalarRes = scalarFinder.tokenize_bitset(bytes).toArray();
			var vecRes = new int[tape.size()];
			
			for(int i=0, ti = 0; i < tape.size();  i++, ti+=2) {
				vecRes[i]=tape.getPositionAt(tapeStorage,i);
			}
				
			System.out.print("\n");
			for (int i = 0; i < 64; i++) {
				System.out.print(scalarRes[i]);
				System.out.print(" ");
	
			}
			System.out.print("\n");
			for (int i = 0; i < 64; i++) {
				System.out.print(vecRes[i]);
				System.out.print(" ");
	
			}
			System.out.print("\n");
	
			Assertions.assertArrayEquals(scalarRes, vecRes);
		}
	}

}
