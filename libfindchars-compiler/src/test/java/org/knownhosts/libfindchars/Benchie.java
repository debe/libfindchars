package org.knownhosts.libfindchars;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentScope;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.experiments.FindingEngine;
import org.knownhosts.libfindchars.experiments.GeneratedEngine;
import org.knownhosts.libfindchars.experiments.RangeFindOperation;
import org.knownhosts.libfindchars.experiments.ShuffleMaskFindOperation;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;


@BenchmarkMode(Mode.SampleTime)
@Fork(value = 1, warmups = 1)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 10)
public class Benchie {


    @State(Scope.Thread)
    public static class StateObj {
        public byte[] doc_payload;
        public byte[] facet_payload;
        public byte[] text;
        public MemorySegment segment;
//        public AutoMemorySegment tapeSegment;
        public MatchStorage tapeStorage;
        public FindingEngine findingEngine;
        public FindingEngine findingEngineRange;
        public FindingEngine findingEngineSandR;
        public GeneratedEngine generatedEngineSandR;

//		public FindingEngineScalar findingEngineScalar;
        public List<FindMask> findMasks;

        public int[] even = IntStream.range(0, 500_000).filter(x -> (x & 1) == 0).toArray();
        public int[] odd = IntStream.range(0, 500_000).filter(x -> (x & 1) != 0).toArray();
        
        public int[] continous = IntStream.range(0, 500_000).toArray();


        @Setup
        public void init() throws  Exception {
    		var literalCompiler = new LiteralCompiler();
    		
//    		
    		var whitespaces = new AsciiLiteral("whitespace","\r\n\t\f ".toCharArray());
    		var structurals = new AsciiLiteral("structurals",":;{}[]".toCharArray());
    		var star = new AsciiLiteral("star","*".toCharArray());
    		var plus = new AsciiLiteral("plus","+".toCharArray());
    		var group = new AsciiLiteralGroup("whitespaces",whitespaces, structurals, star, plus);
//    		var result = literalCompiler.solve(group);

    		
    		
//    		var whitespaces = new AsciiLiteral("whitespace",0,"+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ".toCharArray());
//    		var group = new AsciiLiteralGroup("whitespaces",whitespaces);
    		findMasks = literalCompiler.solve(group);

            findingEngine = new FindingEngine(new ShuffleMaskFindOperation(findMasks));
//            findingEngineRange = new FindingEngine(new RangeFindOperation((byte)0x61,(byte)0x7a,(byte)1));
            findingEngineRange = new FindingEngine(new RangeFindOperation((byte)0x0,(byte)0x40,(byte)1));
            findingEngineSandR = new FindingEngine(new ShuffleMaskFindOperation(findMasks),
            		new RangeFindOperation((byte)0x30,(byte)0x39,(byte)1));
            generatedEngineSandR = new GeneratedEngine();
//            findingEngineScalar = new FindingEngineScalar("+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ");
            text = Files.readAllBytes(
                    Path.of(
                    		FindingEngine.class.getClassLoader().getResource("3mb.txt").toURI()));
            
    		var channel = FileChannel.open(Path.of(FindingEngine.class.getClassLoader().getResource("3mb.txt").toURI()), StandardOpenOption.READ);
    		segment = channel.map(MapMode.READ_ONLY, 0, channel.size(), SegmentScope.global());
//    		tapeSegment = AutoMemorySegment.withProvider((t, u) -> MemorySegment.ofArray(new int[t.intValue()]), channel.size() / 7 << 2, SegmentScope.global());
			tapeStorage = new MatchStorage((int)channel.size() / 7 << 2, 32);



//            doc_payload = Files.readAllBytes(
//                    Path.of(
//                            SolrMergerTest.class.getClassLoader()
//                                    .getResource("search-distributed-document-puma.json").toURI()));
//            facet_payload = Files.readAllBytes(
//                    Path.of(
//                            SolrMergerTest.class.getClassLoader()
//                                    .getResource("search-distributed-facet-derive-puma.json").toURI()));
        }
    }

 
    
//    @Benchmark
//    public void findScalar(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
//
////        var solrMerger = new SolrMerger();
//        bh.consume(stateObj.findingEngineScalar.tokenize_bitset(stateObj.text));
//
//    }
//   
    
//    @Benchmark
//    public void findShuffleAndRange(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
//    	var tape = stateObj.findingEngineSandR.find(stateObj.segment, stateObj.tapeStorage);
//        bh.consume(tape.getPositionAt(stateObj.tapeStorage, 5));
//
//    }

    @Benchmark
    public void findShuffleAndRangeWithGeneratedCode(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
    	var tape = stateObj.generatedEngineSandR.find(stateObj.segment, stateObj.tapeStorage);
        bh.consume(tape.getPositionAt(stateObj.tapeStorage, 5));

    }    
//    
//    @Benchmark
//    public void findRange(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
//    	var tape = stateObj.findingEngineRange.find(stateObj.segment, stateObj.tapeStorage);
//        bh.consume(tape.getPositionAt(stateObj.tapeStorage, 5));
//
//    }
//    
//    @Benchmark
//    public void findShuffle(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
//    	var tape = stateObj.findingEngine.find(stateObj.segment, stateObj.tapeStorage);
//        bh.consume(tape.getPositionAt(stateObj.tapeStorage, 5));
//
//    }
//    
//    
    
    
//    @Benchmark
//    public void twoArrays(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
//    	for (int i = 0; i < stateObj.even.length; i++) {
//			bh.consume(stateObj.odd[i]+stateObj.even[i]);
//		}
//    }
//    
//    @Benchmark
//    public void oneArray(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
//    	for (int i = 0; i < stateObj.continous.length-1; i++) {
//			bh.consume(stateObj.continous[i]+stateObj.continous[i+1]);
//		}
//
//    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
