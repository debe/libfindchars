package org.knownhosts.libfindchars.compiler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BitvectorFormula;
import org.sosy_lab.java_smt.api.BitvectorFormulaManager;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.FormulaManager;
import org.sosy_lab.java_smt.api.Model;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import org.sosy_lab.java_smt.api.SolverException;

import com.google.common.collect.Lists;


/**
 * Solves for SIMD shuffle mask configurations using the Z3 theorem prover.
 *
 * <p>Given a set of {@link AsciiLiteralGroup}s, the compiler builds a constraint
 * system over 8-bit bitvectors representing low-nibble and high-nibble lookup
 * tables.  Z3 finds values such that {@code lowLUT[lo(c)] & highLUT[hi(c)]}
 * yields a unique non-zero literal byte for every target character {@code c},
 * and zero for all non-target nibble pairs.
 *
 * <p>Implements {@link AutoCloseable} &mdash; the underlying Z3 solver context
 * is released on {@link #close()}.
 */
public class LiteralCompiler implements AutoCloseable {

    private final SolverContext context;
    private final ShutdownManager shutdown;

    final static Logger logger = LoggerFactory.getLogger(LiteralCompiler.class);

    public LiteralCompiler() throws InvalidConfigurationException {
        Configuration config = Configuration.defaultConfiguration();
        LogManager logManager = BasicLogManager.create(config);
        shutdown = ShutdownManager.create();
        context = SolverContextFactory.createSolverContext(config, logManager, shutdown.getNotifier(), Solvers.Z3);
    }


    /**
     * Splits a character into its low and high nibbles.
     *
     * @param _char the character to split
     * @return a 2-element array: {@code [lowNibble, highNibble]}
     */
    public static byte[] toNibbles(char _char) {
        byte[] nibbles = new byte[2];

        nibbles[0] = (byte) ((int) _char & 15);
        nibbles[1] = (byte) ((_char >> 4) & 0x0f);
        return nibbles;
    }


    /**
     * Solves for shuffle masks for the given literal groups with no prior literal
     * reservations and default vector size.
     *
     * @param literalGroup one or more groups of characters to solve
     * @return one {@link AsciiFindMask} per group
     */
    public List<AsciiFindMask> solve(AsciiLiteralGroup... literalGroup) throws InterruptedException, SolverException {
        return solve(List.of(), literalGroup);
    }

    /**
     * Solves for shuffle masks, avoiding literal bytes already in use.
     *
     * @param initialUsedLiterals literal byte values that must not be reused
     * @param literalGroup        one or more groups of characters to solve
     * @return one {@link AsciiFindMask} per group
     */
    public List<AsciiFindMask> solve(List<Byte> initialUsedLiterals, AsciiLiteralGroup... literalGroup) throws InterruptedException, SolverException {
        return solve(initialUsedLiterals, 0, literalGroup);
    }

    /**
     * Solves for shuffle masks with reserved literals and an explicit vector byte size.
     *
     * <p>When {@code vectorByteSize > 0}, literal bytes are constrained to
     * {@code [1, vectorByteSize)} so that a single {@code vpermb} cleanup LUT
     * can map non-matching lanes to zero.
     *
     * @param initialUsedLiterals literal byte values that must not be reused
     * @param vectorByteSize      SIMD vector width in bytes (0 for unconstrained)
     * @param literalGroup        one or more groups of characters to solve
     * @return one {@link AsciiFindMask} per group
     */
    public List<AsciiFindMask> solve(List<Byte> initialUsedLiterals, int vectorByteSize, AsciiLiteralGroup... literalGroup) throws InterruptedException, SolverException {
        List<Byte> usedLiterals = Lists.newArrayList(initialUsedLiterals);
        List<AsciiFindMask> masks = Lists.newArrayList();

        for (AsciiLiteralGroup group : literalGroup) {
            var mask = solve1(group, usedLiterals, vectorByteSize);
            usedLiterals.addAll(mask.literals().values());
            masks.add(mask);
        }
        return masks;
    }

    public AsciiFindMask solve1(AsciiLiteralGroup literalGroup, List<Byte> usedLiterals) throws InterruptedException, SolverException {
        return solve1(literalGroup, usedLiterals, 0);
    }

    public AsciiFindMask solve1(AsciiLiteralGroup literalGroup, List<Byte> usedLiterals, int vectorByteSize) throws InterruptedException, SolverException {
        // Assume we have a SolverContext instance.
        FormulaManager formulaManager = context.getFormulaManager();
        BitvectorFormulaManager bitvectorManager = formulaManager.getBitvectorFormulaManager();
        BooleanFormulaManager booleanManager = formulaManager.getBooleanFormulaManager();

        BitvectorFormula zeroVector = bitvectorManager.makeBitvector(8, 0);

        List<BitvectorFormula> usedLiteralsVectors = usedLiterals.stream()
                .map(l -> bitvectorManager.makeBitvector(8, l))
                .toList();

        // nibble matrix (16×16 covers full 0x00-0xFF byte range)
        var lowNibbles = new BitvectorFormula[16];
        var highNibbles = new BitvectorFormula[16];

        // constraints
        var equations = new BooleanFormula[16][16];
        List<BooleanFormula> exclusions = Lists.newArrayList();


        // literal vectors
        List<BitvectorFormula> literalVectors = Lists.newArrayList();

        // init vectors
        for (int i = 0; i < lowNibbles.length; i++) {
            lowNibbles[i] = bitvectorManager.makeVariable(8, "l_" + i);
        }

        for (int i = 0; i < highNibbles.length; i++) {
            highNibbles[i] = bitvectorManager.makeVariable(8, "h_" + i);
        }

        for (ByteLiteral literal : literalGroup.literals()) {
            var literalVector = bitvectorManager.makeVariable(8, literal.name());
            literalVectors.add(literalVector);

            // build matching constraints
            for (char c : literal.chars()) {
                var nibbles = toNibbles(c);
                var equation = bitvectorManager.equal(
                        bitvectorManager.and(lowNibbles[nibbles[0]],
                                highNibbles[nibbles[1]]),
                        literalVector);
                logger.debug("eq{} {}", nibbles, equation);

                equations[nibbles[0]][nibbles[1]] = equation;
            }
        }

        // build not matching constraints: for non-target nibble pairs, ensure that
        // the AND result does not equal any literal byte value.
        // When vectorByteSize > 0 (vpermb cleanup mode), use modular comparison
        // (lower log2(vectorByteSize) bits) to enable single vpermb cleanup LUT.
        for (int i = 0; i < lowNibbles.length; i++) {
            for (int j = 0; j < highNibbles.length; j++) {
                if (equations[i][j] == null) {
                    final int _i = i;
                    final int _j = j;

                    BitvectorFormula andResult = bitvectorManager.and(lowNibbles[_i], highNibbles[_j]);
                    BitvectorFormula compareValue = (vectorByteSize > 0)
                            ? bitvectorManager.and(andResult, bitvectorManager.makeBitvector(8, vectorByteSize - 1))
                            : andResult;

                    equations[i][j] = literalVectors
                            .stream()
                            .map(lit -> booleanManager.not(
                                    bitvectorManager.equal(compareValue, lit)))
                            .reduce(booleanManager.makeTrue(), booleanManager::and);


                    logger.debug("ex / eq[{},{}] {}", i, j, equations[i][j]);

                }
            }
        }

        for (BitvectorFormula bitvectorFormula : literalVectors) {
            exclusions.add(bitvectorManager.greaterThan(bitvectorFormula, zeroVector, true));
            if (vectorByteSize > 0) {
                // Constrain literal bytes to [1, vectorByteSize-1] so vpermb cleanup LUT works
                BitvectorFormula maxLiteral = bitvectorManager.makeBitvector(8, vectorByteSize);
                exclusions.add(bitvectorManager.lessThan(bitvectorFormula, maxLiteral, false));
            }
            for (BitvectorFormula usedLiteral : usedLiteralsVectors) {
                exclusions.add(booleanManager.not(bitvectorManager.equal(bitvectorFormula, usedLiteral)));
            }
        }

        // solve

        try (ProverEnvironment prover = context.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
            for (int i = 0; i < lowNibbles.length; i++) {
                for (int j = 0; j < highNibbles.length; j++) {
                    if (equations[i][j] != null)
                        prover.addConstraint(equations[i][j]);
                }
            }

            for (var ex : exclusions) {
                prover.addConstraint(ex);
            }

            prover.addConstraint(bitvectorManager.distinct(literalVectors));


            boolean isUnsat = prover.isUnsat();
            if (!isUnsat) {
                Model model = prover.getModel();
                for (var vec : literalVectors) {
                    var litIdent = model.evaluate(vec);
                    logger.debug("literal identifier {} : {}", vec, litIdent);
                }

                byte[] lowNibblesFound = new byte[lowNibbles.length];
                byte[] highNibblesFound = new byte[highNibbles.length];

                for (int j = 0; j < lowNibbles.length; j++) {
                    lowNibblesFound[j] = (byte) Objects.requireNonNull(model.evaluate(lowNibbles[j])).intValue();
                }
                for (int j = 0; j < highNibbles.length; j++) {
                    highNibblesFound[j] = (byte) Objects.requireNonNull(model.evaluate(highNibbles[j])).intValue();
                }

                Map<String, Byte> literalMap = literalVectors.stream()
                        .collect(Collectors.toMap(BitvectorFormula::toString,
                                v -> (byte) (Objects.requireNonNull(model.evaluate(v)).intValue())));

                return new AsciiFindMask(lowNibblesFound, highNibblesFound, literalMap);

            } else {
                throw new IllegalStateException("Z3 solver returned unsatisfiable - no valid mask configuration found");
            }

        }

    }


    @Override
    public void close() throws Exception {
        shutdown.requestShutdown("closed");

    }

}
