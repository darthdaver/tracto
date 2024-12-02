package star.tratto;

import star.tratto.preprocessing.OraclesDataset;

import java.io.IOException;
import java.nio.file.Path;

public class Tratto {

    public static void main(String[] args) {
        if (args[0].equals("oracles-dataset")) {
            // Process arguments
            Path repoRootPath = Path.of(args[1]);
            Path outputPath = Path.of(args[2]);
            String classpath = args.length == 4 ? args[3] : "";
            // Project identifier
            String projectIdentifier = args[1].substring(args[1].lastIndexOf("/") + 1);
            try {
                OraclesDataset.generate(repoRootPath, outputPath, projectIdentifier, classpath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
