package zz.customname;

import org.knownhosts.libfindchars.FindCharsEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zz.customname.tokenizer.TokenStorage;
import zz.customname.tokenizer.Tokenizer;

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


@BenchmarkMode(Mode.SampleTime)
@Fork(value = 1, warmups = 1)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 1)
public class TokenizeBench {


    @State(Scope.Thread)
    public static class StateObj {
        public byte[] text;
        public MemorySegment segment;
//        public AutoMemorySegment tapeSegment;
        public MatchStorage tapeStorage;

        public FindCharsEngine findCharsEngineSandR;

        public TokenStorage tokenStorage;

        public Tokenizer tokenizer;
        public MatchView matchView;

//		public FindingEngineScalar findingEngineScalar;
        public List<FindMask> findMasks;



        @Setup
        public void init() throws  Exception {
            findCharsEngineSandR = new FindCharsEngine();
//            findingEngineScalar = new FindingEngineScalar("+;:\r\n\t\f&()!\\#$%&()*<=>?@[]^_{}~ ");
            text = Files.readAllBytes(
                    Path.of(
                    		TokenizeBench.class.getClassLoader().getResource("3mb.txt").toURI()));
            
    		var channel = FileChannel.open(Path.of(TokenizeBench.class.getClassLoader().getResource("3mb.txt").toURI()), StandardOpenOption.READ);
    		segment = channel.map(MapMode.READ_ONLY, 0, channel.size(), SegmentScope.global());
			tapeStorage = new MatchStorage((int)channel.size() / 7 << 2, 32);
            tokenStorage = new TokenStorage((int)channel.size() / 7 << 2, 32);
            tokenizer = new Tokenizer();
            matchView = findCharsEngineSandR.find(segment, tapeStorage);



        }
    }

    @Benchmark
    public void branchytokenize(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
        var tokenView = stateObj.tokenizer.tokenizeBranchy(stateObj.matchView, stateObj.tapeStorage, stateObj.tokenStorage, stateObj.segment);
        bh.consume(tokenView.getSize());
        bh.consume(stateObj.tokenStorage.getPositionsBuffer()[5]);
        bh.consume(stateObj.tokenStorage.getSizeBuffer()[5]);

    }
    @Benchmark
    public void tokenize(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
        var tokenView = stateObj.tokenizer.tokenize(stateObj.matchView, stateObj.tapeStorage, stateObj.tokenStorage, stateObj.segment);
        bh.consume(tokenView.getSize());
        bh.consume(stateObj.tokenStorage.getPositionsBuffer()[5]);
        bh.consume(stateObj.tokenStorage.getSizeBuffer()[5]);

    }
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
