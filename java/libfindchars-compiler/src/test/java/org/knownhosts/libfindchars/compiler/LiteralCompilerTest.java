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


    /**
     * SOLVE-001: both halves of the guarantee. Targets must AND to their literal,
     * and — the half this suite used to skip entirely — no non-target byte may
     * collide with any literal. Deliberately computed here rather than delegating
     * to {@link AsciiFindMask#verify}, so this stays an independent opinion of the
     * mask the compiler already verified.
     */
    private void assertLiteralGroup(AsciiLiteralGroup literalGroup, List<AsciiFindMask> masks, int i) {
        AsciiFindMask mask = masks.get(i);
        Set<Integer> targets = Sets.newHashSet();

        for (ByteLiteral literal : literalGroup.literals()) {
            for (char c : literal.chars()) {
                var nibbles = LiteralCompiler.toNibbles(c);
                byte andResult = (byte) (mask.lowNibbleMask()[nibbles[0]] & mask.highNibbleMask()[nibbles[1]]);
                Assertions.assertEquals(mask.literalOf(literal.name()), andResult);
                targets.add(c & 0xFF);
            }
        }

        Set<Integer> literalValues = Sets.newHashSet();
        for (byte lit : mask.literals().values()) {
            literalValues.add(lit & 0xFF);
        }

        for (int b = 0; b < 256; b++) {
            if (targets.contains(b)) {
                continue;
            }
            int andResult = mask.lowNibbleMask()[b & 0x0F] & mask.highNibbleMask()[(b >> 4) & 0x0F] & 0xFF;
            Assertions.assertFalse(literalValues.contains(andResult),
                    String.format("non-target byte 0x%02x collides with literal 0x%02x", b, andResult));
        }
    }

    /**
     * SOLVE-001: a verifier that always accepted would be indistinguishable from a
     * working one, so corrupt a solved mask and require it to be caught.
     */
    @Test
    public void verifierRejectsCorruptedMask() throws InterruptedException, SolverException {
        var group = new AsciiLiteralGroup("csv", new ByteLiteral("comma", ",".toCharArray()));
        var mask = literalCompiler.solve(group).get(0);
        Assertions.assertTrue(mask.verify(group, 0).isEmpty(), "freshly solved mask should verify");

        var nibbles = LiteralCompiler.toNibbles(',');
        mask.lowNibbleMask()[nibbles[0]] = 0;

        var why = mask.verify(group, 0);
        Assertions.assertTrue(why.isPresent(), "corrupted mask must be rejected");
        Assertions.assertTrue(why.get().contains("0x2c"), "unexpected diagnosis: " + why.get());
    }

    /**
     * SOLVE-001: {@code toNibbles} indexes off the low byte, so a target above
     * 0xFF would be solved for silently truncated. The verifier must refuse it
     * rather than hand back a mask for a character nobody asked about.
     */
    @Test
    public void solveRejectsTargetAboveByteRange() {
        var group = new AsciiLiteralGroup("wide", new ByteLiteral("wide", '\u0100'));
        Assertions.assertThrows(IllegalStateException.class, () -> literalCompiler.solve(group));
    }
}
