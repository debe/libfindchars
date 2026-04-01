package org.knownhosts.libfindchars.compiler;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        Assertions.assertArrayEquals(new byte[]{(byte) 0, (byte) 2}, LiteralCompiler.toNibbles(' '));
        Assertions.assertArrayEquals(new byte[]{(byte) 0xd, (byte) 7}, LiteralCompiler.toNibbles('}'));
        Assertions.assertArrayEquals(new byte[]{(byte) 0xb, (byte) 2}, LiteralCompiler.toNibbles('+'));
        Assertions.assertArrayEquals(new byte[]{(byte) 9, (byte) 0}, LiteralCompiler.toNibbles('\t'));
        Assertions.assertArrayEquals(new byte[]{(byte) 0xc, (byte) 5}, LiteralCompiler.toNibbles('\\'));
    }


    @Test
    public void testCompile() throws InterruptedException, SolverException {

        var whitespaces = new ByteLiteral("whitespace", "\r\n\t\f ".toCharArray());
        var structurals = new ByteLiteral("structurals", ":;{}[]".toCharArray());
        var star = new ByteLiteral("star", "*".toCharArray());
        var plus = new ByteLiteral("plus", "+".toCharArray());
        var group = new AsciiLiteralGroup("whitespaces", whitespaces, structurals, star, plus);
        var result = literalCompiler.solve(group);

        assertLiteralGroup(group, result, 0);
    }

    @Test
    public void testCompileMultiple() throws InterruptedException, SolverException {

        var whitespaces = new ByteLiteral("whitespace", "\r\n\t\f ".toCharArray());
        var structurals = new ByteLiteral("structurals", ":;{}[]".toCharArray());
        var star = new ByteLiteral("star", "*".toCharArray());
        var plus = new ByteLiteral("plus", "+".toCharArray());
        var group1 = new AsciiLiteralGroup("whitespaces", whitespaces, structurals, star, plus);

        var nums = new ByteLiteral("nums", "0123456789".toCharArray());
        var group2 = new AsciiLiteralGroup("numgroup", nums);

        var letters = new ByteLiteral("letters", "abcdefghijk".toCharArray());
        var letters2 = new ByteLiteral("letters2", "lmnopqrstuvwxyz".toCharArray());

        var group3 = new AsciiLiteralGroup("letters", letters, letters2);


        var result = literalCompiler.solve(group1, group2, group3);

        assertLiteralGroup(group1, result, 0);
        assertLiteralGroup(group2, result, 1);
        assertLiteralGroup(group3, result, 2);


        Set<Byte> allLiterals = Sets.newHashSet();
        for (AsciiFindMask findMask : result) {
            allLiterals.addAll(findMask.literals().values());
        }

        Assertions.assertEquals(7, allLiterals.size());

    }


    @Test
    public void testCompileOneBig() throws InterruptedException, SolverException {

        var whitespaces = new ByteLiteral("whitespace", "+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ".toCharArray());
        var group = new AsciiLiteralGroup("whitespaces", whitespaces);
        var result = literalCompiler.solve(group);

        assertLiteralGroup(group, result, 0);
    }


    private void assertLiteralGroup(AsciiLiteralGroup literalGroup, List<AsciiFindMask> masks, int i) {
        for (ByteLiteral literal : literalGroup.literals()) {
            for (char c : literal.chars()) {
                var nibbles = LiteralCompiler.toNibbles(c);
                byte andResult = (byte) (masks.get(i).lowNibbleMask()[nibbles[0]] & masks.get(i).highNibbleMask()[nibbles[1]]);
                Assertions.assertEquals(masks.get(i).literalOf(literal.name()), andResult);
            }
        }
    }
}
