package org.knownhosts.libfindchars.compiler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.knownhosts.libfindchars.api.FindMask;
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


    public static byte[] toNibbles(char _char) {
        byte[] nibbles = new byte[2];

        nibbles[0] = (byte) ((int) _char & 15);
        nibbles[1] = (byte) ((int) _char >> 4 % 15);
        return nibbles;
    }


    public List<FindMask> solve(LiteralGroup... literalGroup) throws InterruptedException, SolverException {
        List<Byte> usedLiterals = Lists.newArrayList();
        List<FindMask> masks = Lists.newArrayList();

        for (LiteralGroup group : literalGroup) {
            var mask = solve1(group, usedLiterals);
            usedLiterals.addAll(mask.literals().values());
            masks.add(mask);
        }
        return masks;
    }

    public FindMask solve1(LiteralGroup literalGroup, List<Byte> usedLiterals) throws InterruptedException, SolverException {
        // Assume we have a SolverContext instance.
        FormulaManager formulaManager = context.getFormulaManager();
        BitvectorFormulaManager bitvectorManager = formulaManager.getBitvectorFormulaManager();
        BooleanFormulaManager booleanManager = formulaManager.getBooleanFormulaManager();

        BitvectorFormula zeroVector = bitvectorManager.makeBitvector(8, 0);

        List<BitvectorFormula> usedLiteralsVectors = usedLiterals.stream()
                .map(l -> bitvectorManager.makeBitvector(8, l))
                .toList();

        // ascii nibble matrix
        var lowNibbles = new BitvectorFormula[16];
        var highNibbles = new BitvectorFormula[8];

        // contraints
        var equations = new BooleanFormula[16][8];
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


        for (Literal literal : literalGroup.getLiterals()) {
            var literalVector = bitvectorManager.makeVariable(8, literal.getName());
            literalVectors.add(literalVector);

            // build matching constraints
            for (char c : literal.getChars()) {
                var nibbles = toNibbles(c);
                var equation = bitvectorManager.equal(
                        bitvectorManager.and(lowNibbles[nibbles[0]],
                                highNibbles[nibbles[1]]),
                        literalVector);
                logger.debug("eq{} {}", nibbles, equation);

                equations[nibbles[0]][nibbles[1]] = equation;
            }
        }

        // build not matching constraints
        for (int i = 0; i < lowNibbles.length; i++) {
            for (int j = 0; j < highNibbles.length; j++) {
                if (equations[i][j] == null) {
                    final int _i = i;
                    final int _j = j;

                    equations[i][j] = literalVectors
                            .stream()
                            .map(lit -> booleanManager.not(
                                    bitvectorManager.equal(
                                            bitvectorManager.and(lowNibbles[_i], highNibbles[_j]), lit)))
                            .reduce(booleanManager.makeTrue(), booleanManager::and);


                    logger.debug("ex / eq[{},{}] {}", i, j, equations[i][j]);

                }
            }
        }


        for (BitvectorFormula bitvectorFormula : literalVectors) {
            exclusions.add(bitvectorManager.greaterThan(bitvectorFormula, zeroVector, true));
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
                System.out.println("not satisfied");

            }

        }

        return null;

    }


    @Override
    public void close() throws Exception {
        shutdown.requestShutdown("closed");

    }

}
