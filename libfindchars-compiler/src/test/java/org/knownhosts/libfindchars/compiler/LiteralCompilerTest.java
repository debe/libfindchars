package org.knownhosts.libfindchars.compiler;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.FindMask;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.api.SolverException;

import com.google.common.collect.Sets;

class LiteralCompilerTest {

	
	private LiteralCompiler literalCompiler;
	
	
	@BeforeEach
	public void setup() throws InvalidConfigurationException {
		this.literalCompiler = new LiteralCompiler();
	}
	
	
	@Test
	public void testToNibbles() {
		Assertions.assertArrayEquals(new byte[]{(byte)0,(byte)2}, LiteralCompiler.toNibbles(' '));
		Assertions.assertArrayEquals(new byte[]{(byte)0xd,(byte)7}, LiteralCompiler.toNibbles('}'));
		Assertions.assertArrayEquals(new byte[]{(byte)0xb,(byte)2}, LiteralCompiler.toNibbles('+'));
		Assertions.assertArrayEquals(new byte[]{(byte)9,(byte)0}, LiteralCompiler.toNibbles('\t'));
		Assertions.assertArrayEquals(new byte[]{(byte)0xc,(byte)5}, LiteralCompiler.toNibbles('\\'));
	}

	
	@Test
	public void testCompile() throws InterruptedException, SolverException {
		
		var whitespaces = new AsciiLiteral("whitespace","\r\n\t\f ".toCharArray());
		var structurals = new AsciiLiteral("structurals",":;{}[]".toCharArray());
		var star = new AsciiLiteral("star","*".toCharArray());
		var plus = new AsciiLiteral("plus","+".toCharArray());
		var group = new AsciiLiteralGroup("whitespaces",whitespaces, structurals, star, plus);
		var result = literalCompiler.solve(group);
		
		System.out.println(result);
		assertLiteralGroup(group, result, 0);	
	}

	@Test
	public void testCompileUtf8() throws InterruptedException, SolverException {

		var umlaute = new UTF8Literal("umlaute","üöä€".toCharArray());
		var sz = new UTF8Literal("sz","ß".toCharArray());
		var ws = new UTF8Literal("ws","\n\r\t ".toCharArray());

		var group = new UTF8LiteralGroup("german-specials",umlaute,sz,ws);
		var result = literalCompiler.solve(group);

		System.out.println(result);
		assertLiteralGroup(group, result, 0);
	}
	
	@Test
	public void testCompileMultiple() throws InterruptedException, SolverException {
		
		var whitespaces = new AsciiLiteral("whitespace","\r\n\t\f ".toCharArray());
		var structurals = new AsciiLiteral("structurals",":;{}[]".toCharArray());
		var star = new AsciiLiteral("star","*".toCharArray());
		var plus = new AsciiLiteral("plus","+".toCharArray());
		var group1 = new AsciiLiteralGroup("whitespaces",whitespaces, structurals, star, plus);
		
		var nums = new AsciiLiteral("nums","0123456789".toCharArray());
		var group2 = new AsciiLiteralGroup("numgroup",nums);

		var letters = new AsciiLiteral("letters","abcdefghijk".toCharArray());
		var letters2 = new AsciiLiteral("letters2","lmnopqrstuvwxyz".toCharArray());
		
		var group3 = new AsciiLiteralGroup("letters",letters, letters2);

		
		var result = literalCompiler.solve(group1,group2,group3);
		
		
		System.out.println(result);
		assertLiteralGroup(group1, result, 0);
		assertLiteralGroup(group2, result, 1);
		assertLiteralGroup(group3, result, 2);


		Set<Byte> allLiterals = Sets.newHashSet();
		for (FindMask findMask : result) {
			allLiterals.addAll(findMask.literals().values());
		}

		Assertions.assertEquals(7,allLiterals.size());
		
	}

	
	@Test
	public void testCompileOneBig() throws InterruptedException, SolverException {
		
		var whitespaces = new AsciiLiteral("whitespace","+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ".toCharArray());
		var group = new AsciiLiteralGroup("whitespaces",whitespaces);
		var result = literalCompiler.solve(group);
		
		System.out.println(result);
		assertLiteralGroup(group, result,0);	
	}
	
	
	private void assertLiteralGroup(AsciiLiteralGroup literalGroup, List<FindMask> masks, int i) {
		for (AsciiLiteral literal : literalGroup.getLiterals()) {
			for(char c : literal.getChars()) {
				var nibbles = LiteralCompiler.toNibbles(c);
				byte andResult = (byte) (masks.get(i).lowNibbleMask()[nibbles[0]] & masks.get(i).highNibbleMask()[nibbles[1]]);
				Assertions.assertEquals(masks.get(i).getLiteral(literal.getName()), andResult);
			}
		}
	}

	private void assertLiteralGroup(UTF8LiteralGroup literalGroup, List<FindMask> masks, int pos) {
		for (UTF8Literal literal : literalGroup.getLiterals()) {
			for (int i = 0; i < literal.getChars().length; i++) {
				char c = literal.getChars()[i];
				var bytes = literal.decodeChar(i);
				for (int j = 0; j < bytes.length; j++) {
					var nibbles = LiteralCompiler.toNibbles(bytes[j]);
					byte andResult = (byte) (masks.get(pos).lowNibbleMask()[nibbles[0]] & masks.get(pos).highNibbleMask()[nibbles[1]]);
					System.out.println("trying to get"+ nibbles[0]+"_"+nibbles[1]);
					Assertions.assertEquals(masks.get(pos).getLiteral("|"+nibbles[0]+"_"+nibbles[1]+"|"), andResult);
				}
			}
		}
	}
}
