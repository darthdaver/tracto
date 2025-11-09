package star.tracto.rqs.mutation;

import star.tracto.rqs.mutation.data.records.InferenceRecord;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import star.tracto.rqs.mutation.data.records.TestType;
import star.tracto.rqs.mutation.utils.FilesUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) {
        // Process arguments
        Path repoRootPath = Path.of(args[0]);
        Path inferenceFilePath = Path.of(args[1]);
        Path tempTestsClassesPath = Path.of(args[2]);
        Path javaHome = Path.of(args[3]);
        Path mvnHome = Path.of(args[4]);
        TestType testType = TestType.valueOf(args[5].toUpperCase());
        String classPath = args[6];
        String tog = args[7];

        FilesUtils.createDirectories(tempTestsClassesPath);
        // Parse inference JSON file
        List<InferenceRecord> inferenceRecords = Arrays.asList(FilesUtils.readJSON(inferenceFilePath, InferenceRecord[].class));

        List<List<String>> successCompiled = new ArrayList<>();
        List<List<String>> failCompiled = new ArrayList<>();
        List<List<String>> successTested = new ArrayList<>();
        List<List<String>> failTested = new ArrayList<>();
        List<String> testClassesProcessed = new ArrayList<>();
        List<List<String>> testClassesPathsMap = new ArrayList<>();
        HashMap<String, CompilationUnit> testClassesTempPathsMap = new HashMap<>();

        CompilationUnit cu;
        TypeDeclaration<?> testClass = null;

        for (InferenceRecord record : inferenceRecords) {
            // Remove first `/` from relative path
            String cleanedTestClassPath = record.testClassPath().startsWith("/") ? record.testClassPath().substring(1) : record.testClassPath();

            Path absoluteTestClassPath = repoRootPath.resolve(cleanedTestClassPath);
            JavaParser javaParser = new JavaParser();

            try {
                if (testClassesTempPathsMap.containsKey(absoluteTestClassPath.toString())) {
                    cu = testClassesTempPathsMap.get(absoluteTestClassPath.toString());
                } else {
                    cu = javaParser.parse(absoluteTestClassPath).getResult().orElseThrow();
                    testClass = cu.getPrimaryType().get();
                }

                // Get the list of all the methods defined within the test class
                List<MethodDeclaration> testClassMethods = cu.findAll(MethodDeclaration.class);
                MethodDeclaration newMethodDeclaration = javaParser.parseMethodDeclaration(record.tpBody()).getResult().get();

                for (MethodDeclaration method : testClassMethods) {
                    // Check if the method name matches the record
                    //if (method.getNameAsString().equals(record.testName())) {

                    if (method.getNameAsString().equals(newMethodDeclaration.getNameAsString())) {
                        try {
                            String newBodyStr = "";
                            List<String> outputRecord = new ArrayList<>();
                            outputRecord.add(absoluteTestClassPath.toString());
                            outputRecord.add(cu.getPackageDeclaration().get().getNameAsString() + "." + testClass.getNameAsString());
                            outputRecord.add(method.getSignature().toString());

                            if (testType == TestType.INFERENCE || testType == TestType.BOTH) {
                                String extracted = record.generated();

                                String replacer = testType == TestType.INFERENCE ? extracted : extracted + "\n    " + record.tgt();
                                newBodyStr = record.tpBody().replace(record.signature(), "").replace("/*<MASK_PLACEHOLDER>*/", replacer);
                                outputRecord.add(extracted);
                            } else if (testType == TestType.NO_ORACLE) {
                                newBodyStr = record.tpBody().replace(record.signature(), "").replace("/*<MASK_PLACEHOLDER>*/", "");
                                outputRecord.add("");
                            }

                            BlockStmt newBody = javaParser.parseBlock(newBodyStr).getResult().get();
                            method.setBody(newBody);

                            Path tempTestClassPath = null;
                            Path modifiedTestClassPath = null;

                            if (!testClass.getNameAsString().endsWith(testType.getTestTypeTestLabel())) {
                                tempTestClassPath = tempTestsClassesPath.resolve(testClass.getNameAsString() + testType.getTestTypeTestLabel() + ".java");
                                modifiedTestClassPath = Path.of(absoluteTestClassPath.toString().replace(".java", testType.getTestTypeTestLabel() + ".java"));
                                testClass.setName(testClass.getNameAsString() + testType.getTestTypeTestLabel());
                            } else {
                                tempTestClassPath = tempTestsClassesPath.resolve(testClass.getNameAsString() + ".java");
                                modifiedTestClassPath = Path.of(absoluteTestClassPath.toString().replace(".java", testType.getTestTypeTestLabel() + ".java"));
                            }

                            if (!testClassesProcessed.contains(cu.getPackageDeclaration().get().getNameAsString() + "." + testClass.getNameAsString())) {
                                testClassesProcessed.add(cu.getPackageDeclaration().get().getNameAsString() + "." + testClass.getNameAsString());
                            }

                            FilesUtils.writeJavaFile(tempTestClassPath, cu);
                            FilesUtils.writeJavaFile(modifiedTestClassPath, cu);

                            boolean compilationResult = bashCall(new String[]{
                                    javaHome + "/bin/javac",
                                    "-cp",
                                    classPath,
                                    "-d",
                                    repoRootPath.toString() + "/target/test-classes",
                                    tempTestClassPath.toString()
                            }, repoRootPath, mvnHome, javaHome);

                            boolean runTestResult = false;
                            if (compilationResult) {
                                System.out.println("mvn test -Dtest=" + cu.getPackageDeclaration().get().getNameAsString() + "." + testClass.getNameAsString() + "#" + method.getNameAsString());
                                successCompiled.add(outputRecord);
                                runTestResult = bashCall(new String[]{
                                        mvnHome + "/bin/mvn",
                                        "test",
                                        "-DargLine=",  // ADD THIS LINE
                                        "-Dtest=" + cu.getPackageDeclaration().get().getNameAsString() + "." + testClass.getNameAsString() + "#" + method.getNameAsString(),
                                }, repoRootPath, mvnHome, javaHome);


                                if (runTestResult) {
                                    successTested.add(outputRecord);
                                } else {
                                    failTested.add(outputRecord);
                                }
                            } else {
                                failCompiled.add(outputRecord);
                            }

                            if (!compilationResult || !runTestResult) {
                                System.err.println("Compilation or test execution failed for: " + outputRecord);
                                String noOracleBodyStr = record.tpBody().replace(record.signature(), "").replace("/*<MASK_PLACEHOLDER>*/", "");
                                BlockStmt noOracleBody = javaParser.parseBlock(noOracleBodyStr).getResult().get();
                                method.setBody(noOracleBody);
                                FilesUtils.writeJavaFile(tempTestClassPath, cu);
                                FilesUtils.writeJavaFile(modifiedTestClassPath, cu);
                                bashCall(new String[]{
                                        javaHome + "/bin/javac",
                                        "-cp",
                                        classPath,
                                        "-d",
                                        repoRootPath.toString() + "/target/test-classes",
                                        tempTestClassPath.toString()
                                }, repoRootPath, mvnHome, javaHome);
                            }

                            FilesUtils.writeJavaFile(tempTestClassPath, cu);
                            FilesUtils.writeJavaFile(modifiedTestClassPath, cu);
                            testClassesTempPathsMap.put(absoluteTestClassPath.toString(), cu);

                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("Error parsing the new body: " + e.getMessage());
                        }

                        Path modifiedTestClassPath = Path.of(absoluteTestClassPath.toString().replace(".java", testType.getTestTypeTestLabel() + ".java"));
                        Path tempTestClassPath = tempTestsClassesPath.resolve(testClass.getNameAsString() + testType.getTestTypeTestLabel() + ".java");

                        List<String> mappingPaths = new ArrayList<>();
                        mappingPaths.add(tempTestClassPath.toString());
                        mappingPaths.add(modifiedTestClassPath.toString());
                        mappingPaths.add(cu.getPackageDeclaration().get().getNameAsString() + "." + testClass.getNameAsString());
                        mappingPaths.add(record.testId());

                        boolean exists = false;
                        for (List<String> el : testClassesPathsMap) {
                            if (el.get(0).equals(mappingPaths.get(0)) &&
                                    el.get(1).equals(mappingPaths.get(1)) &&
                                    el.get(2).equals(mappingPaths.get(2)) &&
                                    el.get(3).equals(mappingPaths.get(3))) {
                                exists = true;
                            }
                        }
                        if (!exists) {
                            testClassesPathsMap.add(mappingPaths);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        FilesUtils.writeCSV(repoRootPath.resolve(tog + "_star_classes_mapping.csv"), testClassesPathsMap);
        FilesUtils.writeCSV(repoRootPath.resolve(tog + "_" + testType + "_success_compiled.csv"), successCompiled);
        FilesUtils.writeCSV(repoRootPath.resolve(tog + "_" + testType + "_success_tested.csv"), successTested);
        FilesUtils.writeCSV(repoRootPath.resolve(tog + "_" + testType + "_fail_compiled.csv"), failCompiled);
        FilesUtils.writeCSV(repoRootPath.resolve(tog + "_" + testType + "_fail_tested.csv"), failTested);
        FilesUtils.writeCSV(repoRootPath.resolve(tog + "_" + testType + "_classes_processed.csv"), List.of(testClassesProcessed));
    }

    private static boolean bashCall(String[] args, Path repoRootPath, Path mvnHome, Path javaHome) {
        try {
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.directory(new java.io.File(repoRootPath.toString()));
            Map<String, String> env = builder.environment();
            String currentPath = System.getenv("PATH");
            String javaBin = javaHome.resolve("bin").toString();
            String mvnBin = mvnHome.resolve("bin").toString();
            String newPath = javaBin + ":" + mvnBin + ":" + currentPath;
            env.put("PATH", newPath);
            env.put("JAVA_HOME", javaHome.toString());
            env.put("MAVEN_HOME", mvnHome.toString());

            Process process = builder.start();
            BufferedReader stdOutput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = stdError.readLine()) != null) {
                System.err.println(line);
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }
}
