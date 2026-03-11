package org.knownhosts.libfindchars.generator;

import org.knownhosts.libfindchars.api.FindCharsEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.FindOp;
import org.knownhosts.libfindchars.api.RangeOp;
import org.knownhosts.libfindchars.api.ShuffleMaskOp;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.compiler.LiteralGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;


public class EngineBuilder {

    static final Logger logger = LoggerFactory.getLogger(EngineBuilder.class);

    public record BuildResult(FindCharsEngine engine, Map<String, Byte> literals) {}

    public static BuildResult build(EngineConfiguration config) {
        List<RangeOperation> rangeOperations = config.rangeOperations();
        ShuffleOperation shuffleOperation = config.shuffleOperation();

        List<FindOp> ops = new ArrayList<>();
        Map<String, Byte> literalMap = new HashMap<>();

        if (shuffleOperation != null) {
            try (LiteralCompiler literalCompiler = new LiteralCompiler()) {
                var masks = literalCompiler.solve(shuffleOperation.literalGroups().toArray(new LiteralGroup[]{}));
                ops.add(new ShuffleMaskOp(masks));
                for (FindMask findMask : masks) {
                    literalMap.putAll(findMask.literals());
                }
            } catch (Exception e) {
                throw new IllegalStateException("compiler error", e);
            }
        }

        for (RangeOperation operation : rangeOperations) {
            var pick = ThreadLocalRandom.current().nextInt(1, 256);

            while (literalMap.containsValue((byte) pick) && literalMap.size() < 255) {
                pick = ThreadLocalRandom.current().nextInt(1, 256);
            }
            ops.add(new RangeOp(operation.from(), operation.to(), (byte) pick));
            literalMap.put(operation.name(), (byte) pick);
        }

        logger.info("built engine with {} ops and {} literals", ops.size(), literalMap.size());

        return new BuildResult(
                new FindCharsEngine(ops.toArray(new FindOp[0])),
                literalMap);
    }
}
