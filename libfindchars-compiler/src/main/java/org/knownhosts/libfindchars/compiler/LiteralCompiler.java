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

    private static final Logger logger = LoggerFactory.getLogger(LiteralCompiler.class);

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
    public static byte[] toNibbles(char ch) {
        byte[] nibbles = new byte[2];

        nibbles[0] = (byte) ((int) ch & 15);
        nibbles[1] = (byte) ((ch >> 4) & 0x0f);
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
        FormulaManager formulaManager = context.getFormulaManager();
        BitvectorFormulaManager bvm = formulaManager.getBitvectorFormulaManager();
        BooleanFormulaManager boolm = formulaManager.getBooleanFormulaManager();

        var lowNibbles = new BitvectorFormula[16];
        var highNibbles = new BitvectorFormula[16];
        for (int i = 0; i < 16; i++) {
            lowNibbles[i] = bvm.makeVariable(8, "l_" + i);
            highNibbles[i] = bvm.makeVariable(8, "h_" + i);
        }

        List<BitvectorFormula> literalVectors = Lists.newArrayList();
        var equations = new BooleanFormula[16][16];
        buildMatchingConstraints(literalGroup, bvm, lowNibbles, highNibbles, literalVectors, equations);
        buildExclusionConstraints(bvm, boolm, lowNibbles, highNibbles, literalVectors, equations, vectorByteSize);

        List<BooleanFormula> exclusions = buildLiteralExclusions(bvm, boolm, literalVectors, usedLiterals, vectorByteSize);

        return solveAndExtract(bvm, lowNibbles, highNibbles, literalVectors, equations, exclusions);
    }

    private void buildMatchingConstraints(AsciiLiteralGroup literalGroup, BitvectorFormulaManager bvm,
            BitvectorFormula[] lowNibbles, BitvectorFormula[] highNibbles,
            List<BitvectorFormula> literalVectors, BooleanFormula[][] equations) {
        for (ByteLiteral literal : literalGroup.literals()) {
            var literalVector = bvm.makeVariable(8, literal.name());
            literalVectors.add(literalVector);
            for (char c : literal.chars()) {
                var nibbles = toNibbles(c);
                equations[nibbles[0]][nibbles[1]] = bvm.equal(
                        bvm.and(lowNibbles[nibbles[0]], highNibbles[nibbles[1]]), literalVector);
                logger.debug("eq{} {}", nibbles, equations[nibbles[0]][nibbles[1]]);
            }
        }
    }

    private static void buildExclusionConstraints(BitvectorFormulaManager bvm, BooleanFormulaManager boolm,
            BitvectorFormula[] lowNibbles, BitvectorFormula[] highNibbles,
            List<BitvectorFormula> literalVectors, BooleanFormula[][] equations, int vectorByteSize) {
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                if (equations[i][j] == null) {
                    BitvectorFormula andResult = bvm.and(lowNibbles[i], highNibbles[j]);
                    BitvectorFormula compareValue = (vectorByteSize > 0)
                            ? bvm.and(andResult, bvm.makeBitvector(8, vectorByteSize - 1))
                            : andResult;
                    equations[i][j] = literalVectors.stream()
                            .map(lit -> boolm.not(bvm.equal(compareValue, lit)))
                            .reduce(boolm.makeTrue(), boolm::and);
                    logger.debug("ex / eq[{},{}] {}", i, j, equations[i][j]);
                }
            }
        }
    }

    private static List<BooleanFormula> buildLiteralExclusions(BitvectorFormulaManager bvm, BooleanFormulaManager boolm,
            List<BitvectorFormula> literalVectors, List<Byte> usedLiterals, int vectorByteSize) {
        BitvectorFormula zeroVector = bvm.makeBitvector(8, 0);
        List<BitvectorFormula> usedVecs = usedLiterals.stream().map(l -> bvm.makeBitvector(8, l)).toList();
        List<BooleanFormula> exclusions = Lists.newArrayList();
        for (BitvectorFormula litVec : literalVectors) {
            exclusions.add(bvm.greaterThan(litVec, zeroVector, true));
            if (vectorByteSize > 0) {
                exclusions.add(bvm.lessThan(litVec, bvm.makeBitvector(8, vectorByteSize), false));
            }
            for (BitvectorFormula used : usedVecs) {
                exclusions.add(boolm.not(bvm.equal(litVec, used)));
            }
        }
        return exclusions;
    }

    private AsciiFindMask solveAndExtract(BitvectorFormulaManager bvm,
            BitvectorFormula[] lowNibbles, BitvectorFormula[] highNibbles,
            List<BitvectorFormula> literalVectors, BooleanFormula[][] equations,
            List<BooleanFormula> exclusions) throws InterruptedException, SolverException {
        try (ProverEnvironment prover = context.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 16; j++)
                    if (equations[i][j] != null) prover.addConstraint(equations[i][j]);
            for (var ex : exclusions) prover.addConstraint(ex);
            prover.addConstraint(bvm.distinct(literalVectors));

            if (prover.isUnsat()) {
                throw new IllegalStateException("Z3 solver returned unsatisfiable - no valid mask configuration found");
            }

            Model model = prover.getModel();
            for (var vec : literalVectors) {
                logger.debug("literal identifier {} : {}", vec, model.evaluate(vec));
            }

            byte[] lowFound = new byte[16], highFound = new byte[16];
            for (int j = 0; j < 16; j++) {
                lowFound[j] = (byte) Objects.requireNonNull(model.evaluate(lowNibbles[j])).intValue();
                highFound[j] = (byte) Objects.requireNonNull(model.evaluate(highNibbles[j])).intValue();
            }

            Map<String, Byte> literalMap = literalVectors.stream()
                    .collect(Collectors.toMap(BitvectorFormula::toString,
                            v -> (byte) (Objects.requireNonNull(model.evaluate(v)).intValue())));
            return new AsciiFindMask(lowFound, highFound, literalMap);
        }
    }


    @Override
    public void close() throws Exception {
        shutdown.requestShutdown("closed");

    }

}
