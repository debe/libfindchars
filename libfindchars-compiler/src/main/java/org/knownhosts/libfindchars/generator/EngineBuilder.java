package org.knownhosts.libfindchars.generator;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.ShuffleMaskOp;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.compiler.LiteralGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.incubator.vector.ByteVector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;


public class EngineBuilder {

    static final Logger logger = LoggerFactory.getLogger(EngineBuilder.class);

    public record BuildResult(FindEngine engine, Map<String, Byte> literals) {}

    public static BuildResult build(EngineConfiguration config) {
        var species = config.species();
        List<RangeOperation> rangeOperations = config.rangeOperations();
        ShuffleOperation shuffleOperation = config.shuffleOperation();

        Map<String, Byte> literalMap = new HashMap<>();

        // Collect shuffle group vectors
        ShuffleMaskOp shuffleOp = null;
        if (shuffleOperation != null) {
            try (LiteralCompiler literalCompiler = new LiteralCompiler()) {
                var masks = literalCompiler.solve(shuffleOperation.literalGroups().toArray(new LiteralGroup[]{}));
                shuffleOp = new ShuffleMaskOp(species, masks);
                for (FindMask findMask : masks) {
                    literalMap.putAll(findMask.literals());
                }
            } catch (Exception e) {
                throw new IllegalStateException("compiler error", e);
            }
        }

        // Collect range vectors
        List<ByteVector> rangeLowerList = new ArrayList<>();
        List<ByteVector> rangeUpperList = new ArrayList<>();
        List<ByteVector> rangeLitList = new ArrayList<>();
        for (RangeOperation operation : rangeOperations) {
            var pick = ThreadLocalRandom.current().nextInt(1, 256);
            while (literalMap.containsValue((byte) pick) && literalMap.size() < 255) {
                pick = ThreadLocalRandom.current().nextInt(1, 256);
            }
            rangeLowerList.add(ByteVector.broadcast(species, operation.from()));
            rangeUpperList.add(ByteVector.broadcast(species, operation.to()));
            rangeLitList.add(ByteVector.broadcast(species, (byte) pick));
            literalMap.put(operation.name(), (byte) pick);
        }

        // Extract vectors for code generation
        var zero = ByteVector.broadcast(species, (byte) 0);
        var lowMask = ByteVector.broadcast(species, 0x0f);
        var highMask = ByteVector.broadcast(species, 0x7f);

        int groupCount = shuffleOp != null ? shuffleOp.groupCount() : 0;
        var lowLUTs = new ByteVector[groupCount];
        var highLUTs = new ByteVector[groupCount];
        var literals = new ByteVector[groupCount][];
        for (int g = 0; g < groupCount; g++) {
            lowLUTs[g] = shuffleOp.lowLUT(g);
            highLUTs[g] = shuffleOp.highLUT(g);
            literals[g] = new ByteVector[shuffleOp.literalCount(g)];
            for (int l = 0; l < shuffleOp.literalCount(g); l++) {
                literals[g][l] = shuffleOp.literalVec(g, l);
            }
        }

        var rangeLower = rangeLowerList.toArray(new ByteVector[0]);
        var rangeUpper = rangeUpperList.toArray(new ByteVector[0]);
        var rangeLit = rangeLitList.toArray(new ByteVector[0]);
        var intSpecies = species.withLanes(int.class);
        var intBatchSize = intSpecies.length();

        logger.info("built engine with {} shuffle groups, {} range ops, {} literals",
                groupCount, rangeLower.length, literalMap.size());

        var engine = EngineCodeGen.compile(species, zero, lowMask, highMask,
                lowLUTs, highLUTs, literals,
                rangeLower, rangeUpper, rangeLit,
                intSpecies, intBatchSize);

        return new BuildResult(engine, literalMap);
    }
}
