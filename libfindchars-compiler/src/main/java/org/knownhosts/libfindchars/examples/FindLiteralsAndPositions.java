package org.knownhosts.libfindchars.examples;

import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.knownhosts.libfindchars.MatchStorage;
import org.knownhosts.libfindchars.api.AsciiLiteral;
import org.knownhosts.libfindchars.api.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.engine.FindingEngineBuilder;
import org.knownhosts.libfindchars.engine.ShuffleMaskFindOperation;
import org.knownhosts.libfindchars.engine.ShuffleMaskFindOperationNoArray;

import jdk.incubator.vector.ByteVector;

import org.knownhosts.libfindchars.engine.FindingEngine;

class FindLiteralsAndPositions {

	public static void main(String[] args) throws Exception {
		
		var literalCompiler = new LiteralCompiler();
		
		var whitespaces = new AsciiLiteral("whitespace",0,"\r\n\t\f \013".toCharArray());
		var structurals = new AsciiLiteral("structurals",1,":;".toCharArray());
		var star = new AsciiLiteral("star",2,"*".toCharArray());
		var plus = new AsciiLiteral("plus",3,"+".toCharArray());
		var braces = new AsciiLiteral("braces",3,"[]{}()".toCharArray());
		
		var set1 = new AsciiLiteralGroup("set1", whitespaces, structurals, star, plus, braces);
		
		
		var numbers = new AsciiLiteral("nums",0,"0123456789".toCharArray());

		var set2 = new AsciiLiteralGroup("set2", numbers);

		
		var findMasks = literalCompiler.solve(set1);	
		
//
//		new FindingEngineBuilder()
//			.withFindMasks(findMasks)
//			.build();
		
		var vecFinder = new FindingEngine(new ShuffleMaskFindOperation( findMasks));
		var url = FindLiteralsAndPositions.class.getClassLoader().getResource("3mb.txt");
		var uri = url.toURI();
		
		try(Arena arena = Arena.openShared();
			var channel = FileChannel.open(Path.of(uri), StandardOpenOption.READ)){
			
			var mappedFile = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena.scope());
			var tapeStorage = new MatchStorage((int)channel.size() / 7 << 1, 32);
			System.out.println("trying to find");

			var tape = vecFinder.find(mappedFile, tapeStorage);
			System.out.println("all found");

			for(int i = 0; i< tape.size();i++) {

				switch(tape.getLiteralAt(tapeStorage, i)) {
					case 97 -> System.out.println("* at: "+ tape.getPositionAt(tapeStorage, i));
					case 32 -> System.out.println("\\w at: "+ tape.getPositionAt(tapeStorage, i));
					case 96 -> System.out.println("structurals at: "+ tape.getPositionAt(tapeStorage, i));
					case 107 -> System.out.println("+ at: "+ tape.getPositionAt(tapeStorage, i));
					case 104 -> System.out.println("{}[] at: "+ tape.getPositionAt(tapeStorage, i));
				}
			}
			System.out.println("done");
		}

	}

}
