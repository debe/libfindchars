package zz.customname;


import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.knownhosts.libfindchars.api.MatchStorage;

class FindLiteralsAndPositions {

	public static void main(String[] args) throws Exception {
		
		var vecFinder = new FindCharsEngine();
		var url = FindLiteralsAndPositions.class.getClassLoader().getResource("dummy.txt");
		var uri = url.toURI();
		
		try(Arena arena = Arena.openConfined();
			var channel = FileChannel.open(Path.of(uri), StandardOpenOption.READ)){
			
			var mappedFile = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena.scope());
			var matchStorage = new MatchStorage((int)channel.size() / 7 << 1, 32);
			System.out.println("trying to find");

			var match = vecFinder.find(mappedFile, matchStorage);
//			System.exit(0);
			System.out.println("all found");

			for(int i = 0; i < match.size();i++) {

				switch(match.getLiteralAt(matchStorage, i)) {
					case FindCharsLiterals.STAR -> System.out.println("* at: "+ match.getPositionAt(matchStorage, i));
					case FindCharsLiterals.WHITESPACES -> System.out.println("\\w at: "+ match.getPositionAt(matchStorage, i));
					case FindCharsLiterals.PUNCTIATIONS -> System.out.println("punctuations at: "+ match.getPositionAt(matchStorage, i));
					case FindCharsLiterals.PLUS -> System.out.println("+ at: "+ match.getPositionAt(matchStorage, i));
					case FindCharsLiterals.NUMS -> System.out.println("numbers at: "+ match.getPositionAt(matchStorage, i));
					case FindCharsLiterals.COMPARISON -> System.out.println("<>= at: "+ match.getPositionAt(matchStorage, i));
				}
			}
			System.out.println("done");
		}

	}

}
