package org.knownhosts.libfindchars.compiler;

import java.util.*;
import java.util.stream.Collectors;

import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.common.primitives.Chars;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.MultiByteCharacter;
import org.knownhosts.libfindchars.api.MultiByteLiteral;
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


public class LiteralCompiler implements AutoCloseable{
	
	private final SolverContext context;
	private final Configuration config;
	private final LogManager logManager;
	private final ShutdownManager shutdown;
	  
	final static Logger logger = LoggerFactory.getLogger(LiteralCompiler.class);

	public LiteralCompiler() throws InvalidConfigurationException {
		config = Configuration.defaultConfiguration();
		logManager = BasicLogManager.create(config);
		shutdown = ShutdownManager.create();
		context = SolverContextFactory.createSolverContext(config, logManager, shutdown.getNotifier(), Solvers.Z3);		
	}
    
    
	public static byte[] toNibbles(char _char) {
		byte[] nibbles = new byte[2];
		
		nibbles[0] = (byte) ((int) _char & 15);
		nibbles[1] = (byte) ((int) _char >> 4 % 15);
		return nibbles;
	}

	public static byte[] toNibbles(byte _char) {
		byte[] nibbles = new byte[2];

		nibbles[0] = (byte) (_char & 15);
		nibbles[1] = (byte) (_char >> 4 & 15);
		return nibbles;
	}
	
	public List<FindMask> solve(LiteralGroup... literalGroup) throws InterruptedException, SolverException {
		List<Byte> usedLiterals = Lists.newArrayList();
		List<FindMask> masks = Lists.newArrayList();

		for (int i = 0; i < literalGroup.length; i++) {
			switch (literalGroup[i]) {
				case AsciiLiteralGroup asciiLiteraGroup -> {
					var mask = solve1(asciiLiteraGroup, usedLiterals);
					usedLiterals.addAll(mask.literals().stream().map(c -> c.multibyteLiteral()).toList());
					masks.add(mask);
				}
				case UTF8LiteralGroup utf8LiteralGroup -> {
					var mask = solve1(utf8LiteralGroup, usedLiterals);
					usedLiterals.addAll(mask.literals().stream().map(c -> c.multibyteLiteral()).toList());
					masks.add(mask);
				}
			}
		}
		return masks;
	}
	public FindMask solve1(UTF8LiteralGroup literalGroup, List<Byte> usedLiterals) throws InterruptedException, SolverException {
		// Assume we have a SolverContext instance.
		FormulaManager formulaManager = context.getFormulaManager();
		BitvectorFormulaManager bitvectorManager = formulaManager.getBitvectorFormulaManager();
		BooleanFormulaManager booleanManager = formulaManager.getBooleanFormulaManager();

		List<BitvectorFormula> usedLiteralsVectors = usedLiterals.stream()
				.map(l -> bitvectorManager.makeBitvector(8, l))
				.collect(Collectors.toList());

		// ascii nibble matrix
		var lowNibbles = new BitvectorFormula[16];
		var highNibbles = new BitvectorFormula[16];

		// contraints
		var equations = new BooleanFormula[16][16];
		List<BooleanFormula> exclusions = Lists.newArrayList();

		// literal vectors
		Map<Byte,BitvectorFormula> literalVectors = new HashMap<>();

		// init vectors
		for (int i = 0; i < lowNibbles.length; i++) {
			lowNibbles[i] = bitvectorManager.makeVariable(8, "l_"+i);
		}

		for (int i = 0; i < highNibbles.length; i++) {
			highNibbles[i] = bitvectorManager.makeVariable(8, "h_"+i);
		}

//		Map<Byte, BitvectorFormula> bitvectorFormulaMap = new HashMap<>();

		for (UTF8Literal literal : literalGroup.getLiterals()) {
			// build matching constraints
			for (int i = 0; i < literal.getChars().length; i++) {
				var bytes = literal.decodeChar(i);
				for (int j = 0; j < bytes.length; j++) {
					var nibbles = toNibbles(bytes[j]);
					if(! literalVectors.containsKey(bytes[j])){
						var literalVector =  bitvectorManager.makeVariable(8, nibbles[0]+"_"+nibbles[1]);
						literalVectors.put(bytes[j], literalVector);

						var equation = bitvectorManager.equal(
								bitvectorManager.and(lowNibbles[nibbles[0]],
										highNibbles[nibbles[1]]),
								literalVector);
						logger.debug("eq{} {}",nibbles,equation);

						equations[nibbles[0]][nibbles[1]]=equation;
						literalVectors.put(bytes[j], literalVector);
					}
				}
				switch (bytes.length) {
					case 2 -> {
						var utf8lit = bitvectorManager.makeVariable(8, "utf8-"+literal.getChars()[i]);
						var l1 = literalVectors.get(bytes[0]);
						var l2 = literalVectors.get(bytes[1]);

						// finds
						bitvectorManager.equal(
								bitvectorManager.and(l1,
										bitvectorManager.not(l2)),
								utf8lit);

						// Excludes
						literalVectors
								.values().stream()
								.filter(p -> ! p.equals(l1))
								.map(lo -> booleanManager.not(
										bitvectorManager.equal(
												bitvectorManager.and(l1, bitvectorManager.not(lo)), utf8lit)))
								.reduce(booleanManager.makeTrue(), booleanManager::and);
					}
					case 3 -> {}
					case 4 -> {}
					default -> {}
				}
			}
		}

		// build not matching constraints
		for (int i = 0; i < lowNibbles.length; i++) {
			for (int j = 0; j < highNibbles.length; j++) {
				if(equations[i][j] == null) {
					final int _i = i;
					final int _j = j;

					equations[i][j] = literalVectors
							.values().stream()
							.map(lit -> booleanManager.not(
									bitvectorManager.equal(
											bitvectorManager.and(lowNibbles[_i],highNibbles[_j]), lit)))
							.reduce(booleanManager.makeTrue(), booleanManager::and);

					logger.debug("ex / eq[{},{}] {}",i,j,equations[i][j]);

				}
			}
		}


		for (BitvectorFormula bitvectorFormula : literalVectors.values()) {
			for (BitvectorFormula usedLiteral : usedLiteralsVectors) {
				exclusions.add(booleanManager.not(bitvectorManager.equal(bitvectorFormula, usedLiteral)));
			}
		}



		// solve

		try (ProverEnvironment prover = context.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
			for (int i = 0; i < lowNibbles.length; i++) {
				for (int j = 0; j < highNibbles.length; j++) {
					if(equations[i][j] != null)
						prover.addConstraint(equations[i][j]);
				}
			};

			for(var ex : exclusions) {
				prover.addConstraint(ex);
			}

			prover.addConstraint(bitvectorManager.distinct(literalVectors.values().stream().toList()));



			boolean isUnsat = prover.isUnsat();
			if (!isUnsat) {
				Model model = prover.getModel();
				for (var vec : literalVectors.values()) {
					var litIdent = model.evaluate(vec);
					logger.debug("literal identifier {} : {}", vec, litIdent);
				}

				byte[] lowNibblesFound = new byte[lowNibbles.length];
				byte[] highNibblesFound = new byte[highNibbles.length];

				for (int j = 0; j < lowNibbles.length; j++) {
					lowNibblesFound[j]= (byte)model.evaluate(lowNibbles[j]).intValue();
				}
				for (int j = 0; j < highNibbles.length; j++) {
					highNibblesFound[j]= (byte)model.evaluate(highNibbles[j]).intValue();
				}

				List<MultiByteLiteral> multiByteLiterals = literalGroup.getLiterals().stream()
						.map( lit -> {
							var mbChars = new MultiByteCharacter[lit.getChars().length];
							for (int i = 0; i < lit.getChars().length; i++) {
								var c = lit.getChars()[i];
								var bytes = lit.decodeChar(c);
								var literals = new byte[bytes.length];
								for (int j = 0; j < bytes.length; j++) {
									literals[j]=(byte)model.evaluate(literalVectors.get(bytes[j])).intValue();
								}
								mbChars[i]=new MultiByteCharacter(c, literals);
							}
							return new MultiByteLiteral(lit.getName(), (byte)
											model.evaluate(literalVectors.get(lit)).intValue(), mbChars);
								}
						)
						.collect(Collectors.toList());
				return new MultiByteFindMask(lowNibblesFound, highNibblesFound, multiByteLiterals);
			} else{
				System.out.println("not satisfied");

			}

		}

		return null;

	}

	public FindMask solve1(AsciiLiteralGroup literalGroup, List<Byte> usedLiterals) throws InterruptedException, SolverException {
	    // Assume we have a SolverContext instance.
	    FormulaManager formulaManager = context.getFormulaManager();
	    BitvectorFormulaManager bitvectorManager = formulaManager.getBitvectorFormulaManager();
	    BooleanFormulaManager booleanManager = formulaManager.getBooleanFormulaManager();

	    BitvectorFormula zeroVector = bitvectorManager.makeBitvector(8, 0);
	    
	    List<BitvectorFormula> usedLiteralsVectors = usedLiterals.stream()
	    	.map(l -> bitvectorManager.makeBitvector(8, l))
	    	.collect(Collectors.toList());
	    
	    // ascii nibble matrix
	    var lowNibbles = new BitvectorFormula[16];
	    var highNibbles = new BitvectorFormula[8];
	    
	    // contraints
	    var equations = new BooleanFormula[16][8];
	    List<BooleanFormula> exclusions = Lists.newArrayList();

	    // literal vectors
	    
	    Map<Literal, BitvectorFormula> literalVectors = new HashMap<>();

		// init vectors
	    for (int i = 0; i < lowNibbles.length; i++) {
			lowNibbles[i] = bitvectorManager.makeVariable(8, "l_"+i);
		}
	    
	    for (int i = 0; i < highNibbles.length; i++) {
	    	highNibbles[i] = bitvectorManager.makeVariable(8, "h_"+i);
		}
	    
	    
	    
	    for (Literal literal : literalGroup.getLiterals()) {
		    var literalVector = bitvectorManager.makeVariable(8, literal.getName());
		    literalVectors.put(literal, literalVector);
		    
		    // build matching constraints
	    	for(char c : literal.getChars()) {
	    		var nibbles = toNibbles(c);
	    		var equation = bitvectorManager.equal(
	    							bitvectorManager.and(lowNibbles[nibbles[0]], 
	    									highNibbles[nibbles[1]]),
	    							literalVector);
			    logger.debug("eq{} {}",nibbles,equation);

	    		equations[nibbles[0]][nibbles[1]]=equation;
	    	}
		}
	    
	    // build not matching constraints
	    for (int i = 0; i < lowNibbles.length; i++) {
			for (int j = 0; j < highNibbles.length; j++) {
				if(equations[i][j] == null) {
					final int _i = i;
					final int _j = j;
					
					equations[i][j] = literalVectors.values()
						.stream()
						.map(lit -> booleanManager.not(
										bitvectorManager.equal(
												bitvectorManager.and(lowNibbles[_i],highNibbles[_j]), lit)))
						.reduce(booleanManager.makeTrue(), booleanManager::and);

				    logger.debug("ex / eq[{},{}] {}",i,j,equations[i][j]);

				}
			}
		}
	    
	    
	    for (BitvectorFormula bitvectorFormula : literalVectors.values()) {
	    	exclusions.add(bitvectorManager.greaterThan(bitvectorFormula, zeroVector,true));
	    	for (BitvectorFormula usedLiteral : usedLiteralsVectors) {
	    		exclusions.add(booleanManager.not(bitvectorManager.equal(bitvectorFormula, usedLiteral)));
			}
	    }
	
	  
	    
	    // solve
	    
	    try (ProverEnvironment prover = context.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
		    for (int i = 0; i < lowNibbles.length; i++) {
				for (int j = 0; j < highNibbles.length; j++) {
					if(equations[i][j] != null)
						prover.addConstraint(equations[i][j]);
				}
			};
			
			for(var ex : exclusions) {				
				prover.addConstraint(ex);
			}
			
			prover.addConstraint(bitvectorManager.distinct(literalVectors.values().stream().toList()));
			
		   
			
	        boolean isUnsat = prover.isUnsat();
	        if (!isUnsat) {
	          Model model = prover.getModel();
	          for (var vec : literalVectors.values()) {
	  		    	var litIdent = model.evaluate(vec);
	  		    	logger.debug("literal identifier {} : {}", vec, litIdent);
	  	      }
	          
	          byte[] lowNibblesFound = new byte[lowNibbles.length];
	          byte[] highNibblesFound = new byte[highNibbles.length];
	          
	          for (int j = 0; j < lowNibbles.length; j++) {
				lowNibblesFound[j]= (byte)model.evaluate(lowNibbles[j]).intValue();
	          }
	          for (int j = 0; j < highNibbles.length; j++) {
					highNibblesFound[j]= (byte)model.evaluate(highNibbles[j]).intValue();
	          }

			  List<MultiByteLiteral> multiByteLiterals = literalGroup.getLiterals().stream()
					  .map( lit -> {
						  var mbChars = Chars.asList(lit.getChars()).stream()
								  .map(c -> new MultiByteCharacter(c.charValue(),
										  new byte[]{}
										  ))
								  .toArray(MultiByteCharacter[]::new);
						  return new MultiByteLiteral(lit.getName(),(byte) 0, mbChars);
					  }
					  )
					  .collect(Collectors.toList());

	          var result = new MultiByteFindMask(lowNibblesFound, highNibblesFound, multiByteLiterals);
	         
	          return result;
	         
	        } else{
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
