package star.tracto;

import java.util.Arrays;
import star.tracto.data.enums.GenerationTarget;

/** Shared command-line boundary for inference. The first argument selects the target. */
public final class InferenceClient {
    private InferenceClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected generation target: ASSERTION or TEST_PREFIX");
        }
        GenerationTarget target;
        try {
            target = GenerationTarget.valueOf(args[0]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown generation target: " + args[0]
                    + ". Expected ASSERTION or TEST_PREFIX", error);
        }
        run(target, Arrays.copyOfRange(args, 1, args.length));
    }

    public static void run(GenerationTarget target, String[] args) throws Exception {
        switch (target) {
            case ASSERTION -> Client.run(args);
            case TEST_PREFIX -> PrefixClient.main(args);
        }
    }
}
