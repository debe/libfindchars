package org.knownhosts.libfindchars.generator;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.compiler.LiteralGroup;
import org.knownhosts.libfindchars.engine.RangeTplOp;
import org.knownhosts.libfindchars.engine.ShuffleMaskTplOp;
import org.knownhosts.libfindchars.engine.TplOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sosy_lab.common.configuration.InvalidConfigurationException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;


public class EngineBuilder {

    final static Logger logger = LoggerFactory.getLogger(EngineBuilder.class);

    private static final String engineClassName = "FindCharsEngine";
    private static final String literalsClassName = "FindCharsLiterals";
    
    public static void build(EngineConfiguration config) {

        // validate config
        Target target = config.getTarget();
        exitIfNull(target, "target is mandatory. Please configure a Target");
        exitIfNull(target.getDirectory(), "target.directory is mandatory. Set the directory please");
        exitIfNull(target.getPackageName(), "target.packageName is mandatory. Set the packagename");

        List<RangeOperation> operations = config.getRangeOperations();
        ShuffleOperation shuffleOperation = config.getShuffleOperation();

        exitIf(
                (operations == null || operations.isEmpty()) &&
                        (shuffleOperation == null),
                "at least one operation is mandatory. Please configure a least one Operation"
        );

        // Velocity setup
        VelocityEngine velocityEngine = new VelocityEngine();
        Properties p = new Properties();
        p.setProperty("resource.loader", "class");
        p.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        velocityEngine.init(p);
        
        var javaTemplates = Map.of(
        		engineClassName, velocityEngine.getTemplate("templates/FindCharsEngine.vm"),
        		literalsClassName, velocityEngine.getTemplate("templates/FindCharsLiterals.vm"));
        
        VelocityContext context = new VelocityContext();

        context.put("packagename", target.getPackageName());
        context.put("engineClassName", engineClassName);
        context.put("literalsClassName", literalsClassName);

        // Compile operations and setup data
        List<TplOp> ops = new ArrayList<>();
        Map<String, Byte> literalMap = new HashMap<>();
        Random random = new Random();

        if (shuffleOperation != null) {
            try (LiteralCompiler literalCompiler = new LiteralCompiler()) {
                var masks = literalCompiler.solve(shuffleOperation.getLiteralGroups().toArray(new LiteralGroup[]{}));
                ops.add(new ShuffleMaskTplOp(masks));
                for (FindMask findMask : masks) {
                    literalMap.putAll(findMask.literals());
                }
            } catch (InvalidConfigurationException e) {
                logger.error("compiler config error", e);
                System.exit(-1);
            } catch (Exception e) {
                logger.error("compiler error", e);
                System.exit(-1);
            }
        }

        for (RangeOperation operation : operations) {
            var pick = random.nextInt(255);

            while (literalMap.containsValue((byte) pick) && literalMap.size() < 255) {
                pick = random.nextInt(255);
            }
            ops.add(new RangeTplOp(operation.getFrom(), operation.getTo(), (byte) pick));
            literalMap.put(operation.getName(), (byte) pick);
        }

        // create literal constants
        var literalConstants = literalMap.entrySet().stream()
                .collect(Collectors.toMap(
                        k -> k.getKey().replaceAll("[^A-Za-z0-9]", "").toUpperCase(), Map.Entry::getValue));
        context.put("literals", literalConstants);
        context.put("ops", ops);

        for(var template : javaTemplates.entrySet()) {
        	String filename = Paths.get(target.getDirectory() + File.separator + String.join(File.separator, target.getPackageName().split("\\.")), template.getKey() + ".java").toString();
            logger.info("generating Engine at: {}", filename);
            File file = new File(filename);

            try (FileWriter writer = new FileWriter(file)) {
                file.mkdirs();
                file.createNewFile();
                template.getValue().merge(context, writer);
                writer.flush();
            } catch (IOException e) {
                logger.error("error in filehandling", e);
                System.exit(-1);
            }
        }
    }

    private static void exitIf(boolean condition, String message) {
        if (condition) {
            logger.error(message);
            System.exit(-1);
        }
    }

    private static void exitIfNull(Object o, String message) {
        exitIf(o == null, message);
    }

    private static void exitIfEmpty(List<?> o, String message) {
        exitIf(o == null || o.isEmpty(), message);
    }

}
