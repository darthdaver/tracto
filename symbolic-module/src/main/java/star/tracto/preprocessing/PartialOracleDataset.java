package star.tracto.preprocessing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;

import star.tracto.lsp.antlr4.Antlr4Utils;

// antlr4 -Dlanguage=Java ./grammars/JavaLexer.g4 ./grammars/JavaParser.g4

public class PartialOracleDataset {

    public static void main(String[] args) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        long fileLimit = 1;
        String folderPath = "symbolic-module/src/main/resources/dataset/partial-oracles/input"; 

        File directory = new File("symbolic-module/src/main/resources/dataset/partial-oracles/output/" + timestamp);

        // mkdirs() will create parent directories if needed.
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (created) {
                System.out.println("Directory created: " + directory.getAbsolutePath());
            } else {
                System.out.println("Failed to create directory!");
            }
        } else {
            System.out.println("Directory already exists: " + directory.getAbsolutePath());
        }
        

        try {
            // Define the filename pattern to match: oracles-datapoints-*.json
            Pattern filePattern = Pattern.compile(".*oracles-datapoints-.*\\.json");
            // Walk through the directory and process files recursively
            Files.walk(Paths.get(folderPath))
                    .filter(Files::isRegularFile) // Only process files, not directories
                    .filter(path -> filePattern.matcher(path.getFileName().toString()).matches()) // Match pattern
                    .limit(fileLimit) 
                    .forEach(filePath -> {

                    List<ArrayNode> allResults = new ArrayList<>();

                    String finalJsonFilePath = "symbolic-module/src/main/resources/dataset/partial-oracles/output/"+ timestamp + "/" + timestamp + "_dataset_partialoracles_" + filePath.getFileName().toString();

                    File finalJsonFile = new File(finalJsonFilePath);
                    if(finalJsonFile.exists()){
                        System.out.println("Skipping file: " + filePath);
                        return;
                    }
                    
                    if (filePath.getFileName().toString().contains("32104835")){
                        System.out.println("Skipping file: " + filePath);
                        return;
                    }

                    System.out.println("\nChecking file: " + filePath);
                        
                    // Check if file matches the pattern
                    if (Files.isRegularFile(filePath) && filePattern.matcher(filePath.getFileName().toString()).matches()) {
                        System.out.println("Processing file: " + filePath);

                        // Define input and output paths for the function
                        String inputJsonPath = filePath.toString();
                        String outputJsonPath = filePath.getParent().resolve("output-" + filePath.getFileName()).toString();

                        // Call the function
                        ArrayNode x = saveNextTokensToJson(inputJsonPath, outputJsonPath);

                        // Collect the output paths for final aggregation
                        allResults.add(x);

                        // After the loop, save the final aggregated result
                        writeFinalJson(allResults, finalJsonFilePath);
                        System.out.println("JSON file saved to: " + finalJsonFilePath);
                        allResults.clear();
                        // Optionally: System.gc();

                    }
                });
                

            System.out.println("Processing completed.");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Function to write final aggregated JSON file.
     */
    private static void writeFinalJson(List<ArrayNode> outputJsonPaths, String finalJsonFilePath) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Create a root node with the "processedFiles" array
            ArrayNode processedFilesNode = objectMapper.createArrayNode();
    
            // Add all ArrayNodes to the "processedFiles" array
            for (ArrayNode arrayNode : outputJsonPaths) {
                processedFilesNode.addAll(arrayNode);
            }
    
            objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValue(new File(finalJsonFilePath), processedFilesNode);
    
            System.out.println("Final JSON file written to: " + finalJsonFilePath);
        } catch (IOException e) {
            System.err.println("Error writing final JSON file: " + finalJsonFilePath);
            e.printStackTrace();
        }
    }
    

    /**
     * Reads a JSON file, processes the target field to determine next possible tokens,
     * and saves the results to another JSON file.
     *
     * @param inputJsonPath  Path to the input JSON file.
     * @param outputJsonPath Path to the output JSON file.
     * @return ArrayNode containing the processed JSON objects.
          */
    private static ArrayNode saveNextTokensToJson(String inputJsonPath, String outputJsonPath) {
        ObjectMapper objectMapper_root = new ObjectMapper();
        ObjectMapper objectMapper = new ObjectMapper();
        ArrayNode outputObjectArray = objectMapper.createArrayNode();

        // Define the set of literal tokens to be replaced with "LITERAL"
        Set<String> literalsSet = new HashSet<>(Arrays.asList(
            "DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL",
            "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "BOOL_LITERAL",
            "CHAR_LITERAL", "STRING_LITERAL"
        ));


        try {
            File inputFile = new File(inputJsonPath);
            JsonNode rootNode = objectMapper_root.readTree(inputFile);
            System.out.println("Processing " + rootNode.size() + " items...");

            // Each file in the dataset is composed as a list of lists (each elements in the list have the same focalClass and the same testClass. This complexity can be relaxed simply considering a flatten list.

            

            ObjectNode outputObject = objectMapper.createObjectNode();

            // Clean and set the focalClass
            JsonNode focalClassNode = rootNode.get(0).get("focalClass");
            ObjectNode cleanedNode = focalClassCleaning(focalClassNode);
            if (cleanedNode != null) {
                outputObject.set("focalClass", cleanedNode);
            }

            // Clean and set the testClass
            JsonNode testClassNode = rootNode.get(0).get("testClass");
            ObjectNode cleanedTestClassNode = testClassCleaning(testClassNode);
            if (cleanedTestClassNode != null) {
                outputObject.set("testClass", cleanedTestClassNode);
            }

            ArrayNode datapoints = objectMapper.createArrayNode();
            outputObject.set("datapoints", datapoints);

            // Process each item in the input JSON
            for (JsonNode arrayItem_root : rootNode) {

                JsonNode DatapointsNode = arrayItem_root.get("datapoints");
                

                for (JsonNode arrayItem : DatapointsNode) {
                    JsonNode targetNode = arrayItem.get("target");
                    

                    JsonNode testPrefixNode = arrayItem.get("testPrefix");
                    JsonNode bodyNode = null;
                    if (testPrefixNode != null) {
                        bodyNode = testPrefixNode.get("body");
                        String unescapedBodyString = bodyNode.toString().replace("\\n", "\n").replace("\\t", "\t");
                        //System.out.println(unescapedBodyString);

                    }

                    // Extract focalClass and testClass nodes
                    JsonNode focalMethodNode = arrayItem.get("focalMethodTratto");

                    // Extract return type from focalMethodTratto if available
                    String focalMethodReturnType = null;
                    if (focalMethodNode != null) {
                        JsonNode focalMethodNodeReturn = focalMethodNode.get("returnType");
                        if (focalMethodNodeReturn != null) {
                            JsonNode focalMethodNodeReturnType = focalMethodNodeReturn.get("identifier");
                            if (focalMethodNodeReturnType != null) {
                                focalMethodReturnType = focalMethodNodeReturnType.asText();
                            }
                        }
                    }

                    if (targetNode != null && bodyNode != null) {
                        String snippet = targetNode.asText().replace(" ", "");
                        List<String> tokens = Antlr4Utils.tokenizeSnippet(snippet);

                        // Parse the method body to get variable names and their types
                        String methodBody = bodyNode.asText();
                        String codeToParse = "class DummyClass {" + methodBody + "}";
                        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(codeToParse);

                        // Map from identifiers to their declared types
                        Map<String, String> variableTypes = new HashMap<>();
                        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                            CompilationUnit cu = parseResult.getResult().get();
                            cu.findAll(VariableDeclarator.class).forEach(vd -> {
                                variableTypes.put(vd.getNameAsString(), vd.getType().asString());
                            });
                        } else {
                            System.err.println("Error parsing method body: " + methodBody);
                            continue;
                        }


                        // Collect identifiers from variables, methods, classes
                        Set<String> identifiers = new HashSet<>(variableTypes.keySet());

                        Map<String, String> methodReturnTypes = new HashMap<>();

                        // Extract method names from focalClass
                        if (focalClassNode != null && focalClassNode.has("methods")) {
                            JsonNode focalMethods = focalClassNode.get("methods");
                            for (JsonNode method : focalMethods) {
                                if (method.has("identifier")) {
    
                                    String mName = method.get("identifier").asText();
                                    identifiers.add(mName);
                                }
                            }
                        }

                        // Extract method names from testClass
                        if (testClassNode != null && testClassNode.has("methods")) {
                            JsonNode testMethods = testClassNode.get("methods");
                            for (JsonNode method : testMethods) {
                                if (method.has("identifier")) {
                                    String mName = method.get("identifier").asText();
                                    identifiers.add(mName);
                                }
                            }
                        }

                        // Extract class identifier from testClass
                        if (testClassNode != null && testClassNode.has("identifier")) {
                            identifiers.add(testClassNode.get("identifier").asText());
                        }

                        // Extract class identifier from focalClass if available
                        if (focalClassNode != null && focalClassNode.has("identifier")) {
                            identifiers.add(focalClassNode.get("identifier").asText());
                        }

                        
                        ArrayNode tokenSteps = objectMapper.createArrayNode();

                        StringBuilder currentSnippet = new StringBuilder();

                        // Map of method signatures (method name -> [return type, argument types...])
                        Map<String, List<String>> methodSignatures = new HashMap<>();
                        methodSignatures.put("null", List.of("null"));
                        methodSignatures.put("toString", List.of("String"));
                        methodSignatures.put("true", List.of("Boolean"));
                        methodSignatures.put("false", List.of("Boolean"));
                        methodSignatures.put("^\\d+$", List.of("Integer")); // Matches unquoted integers
                        methodSignatures.put("^\\d+\\.\\d+$", List.of("Float")); // Matches unquoted floats
                        methodSignatures.put("^\"\\d+\"$", List.of("Integer")); // Matches quoted integers
                        methodSignatures.put("^\"\\d+\\.\\d+\"$", List.of("Float")); // Matches quoted floats
                        methodSignatures.put("^\"[^\"]*\\d+[^\"]*\"$", List.of("String")); // Matches strings with embedded numbers
                        methodSignatures.put("^\".*\"$", List.of("String")); // Matches general strings
                        methodSignatures.put("toString", List.of("String")); // No arguments

                        List<String> currentSnippetTypes = new ArrayList<>();

                        for (int i = 0; i < tokens.size(); i++) {
                            //if (i > 0) {
                            //    currentSnippet.append(" ");
                            //}
                            currentSnippet.append(tokens.get(i));

                            // Find the signature of the method
                            List<String> signature = methodSignatures.get(tokens.get(i));
                            
                            if (signature != null) {
                                currentSnippetTypes.add(signature.get(0));
                            }

                            // Add all types to the list

                            List<String> nextTokens = Antlr4Utils.getNextPossibleTokens(currentSnippet.toString());

                            // Adjust nextTokens by replacing literals and IDENTIFIER tokens
                            List<String> adjustedNextAbstractTokens = new ArrayList<>();
                            List<String> adjustedNextConcreteTokens = new ArrayList<>();
                            List<String> adjustedNextGenericTokens = new ArrayList<>();
                            boolean literalAdded = false;

                            for (String token : nextTokens) {
                                if (literalsSet.contains(token)) {
                                    // It's a literal: determine its type
                                    String literalType = getLiteralType(token);
                                    // Only add if compatible
                                    if (!literalAdded) {
                                        adjustedNextGenericTokens.add("LITERAL");
                                        literalAdded = true;
                                    }
                                } else if (token.equals("IDENTIFIER")) {
                                    // Replace "IDENTIFIER" with collected identifiers but filter by type
                                    for (String id : identifiers) {
                                        String varType = variableTypes.get(id);
                                        String retType = methodReturnTypes.get(id);
                                        String actualType = null;
                                        if (varType != null) {
                                            actualType = varType;
                                        } else if (retType != null) {
                                            actualType = retType;
                                        } else {
                                            // If unknown, skip or assume Object
                                            actualType = "Object";
                                        }
                                        //if (typesAreCompatible(actualType, expectedType)) {
                                    
                                        adjustedNextConcreteTokens.add(id);
                                        //}
                                    }
                                } else {
                                    // For non-literal, non-IDENTIFIER tokens (e.g., keywords),
                                    // we can filter them based on expectedType if needed.
                                    // For now, we add them and filter afterward.
                                    adjustedNextAbstractTokens.add(token.replace("'", ""));
                                }
                            }

                            // Filter out abstract tokens that don't match expectedType
                            adjustedNextAbstractTokens = adjustedNextAbstractTokens.stream()
                                //.filter(t -> shouldKeepAbstractToken(t, expectedType))
                                .collect(Collectors.toList());

                            ObjectNode step = objectMapper.createObjectNode();
                            step.put("partialOracle", currentSnippet.toString());
                            step.put("expectedTypes", currentSnippetTypes.toString());

                            ArrayNode nextConcreteTokensArray = step.putArray("projectTokens");
                            ArrayNode nextAbstractTokensArray = step.putArray("javaTokens");
                            ArrayNode nextGenericTokensArray = step.putArray("genericTokens");
                            if (adjustedNextConcreteTokens.isEmpty() && adjustedNextAbstractTokens.isEmpty() &&  adjustedNextGenericTokens.isEmpty()) {
                                step.put("isSyntaxCorrect", "true");
                                if (i < tokens.size() - 1){ 
                                    step.put("target", tokens.get(i + 1));
                                }else{
                                     // Remove the last datapoint (equivalent to target)
                                    continue;
                                }

                            } else {
                                step.put("isSyntaxCorrect", "false");
                                if (i < tokens.size() - 1){ 
                                    step.put("target", tokens.get(i + 1));
                                }
                            }

                            for (String token : adjustedNextAbstractTokens) {
                                nextAbstractTokensArray.add(token);
                            }

                            

                            if (!adjustedNextConcreteTokens.isEmpty()) {
                                nextGenericTokensArray.add("IDENTIFIER");
                            }

                            if (literalAdded) {
                                nextGenericTokensArray.add("LITERAL");
                            }

                            for (String token : adjustedNextConcreteTokens) {
                                if (currentSnippet.toString().equals("Assert .")){
                                    nextConcreteTokensArray.add("assertEquals");
                                    nextConcreteTokensArray.add("assertNotEquals");
                                    nextConcreteTokensArray.add("assertArrayEquals");
                                    nextConcreteTokensArray.add("assertTrue");
                                    nextConcreteTokensArray.add("assertFalse");
                                    nextConcreteTokensArray.add("assertNull");
                                    nextConcreteTokensArray.add("assertNotNull");
                                    nextConcreteTokensArray.add("assertThrows");
                                    nextConcreteTokensArray.add("assertDoesNotThrow");
                                    nextConcreteTokensArray.add("assertTimeout");
                                    nextConcreteTokensArray.add("assertTimeoutPreemptively");
                                    nextGenericTokensArray.removeAll();
                                    break;
                                }else{
                                    if(currentSnippetTypes.size() == 0){
                                        nextConcreteTokensArray.add(token);
                                    }else{
                                        for (String type : currentSnippetTypes){
                                            if (token.equals(type)){
                                                nextConcreteTokensArray.add(token);
                                            }
                                        }
                                    }
                                }
                            }

                            //step.put(expectedType, "expectedType");

                            tokenSteps.add(step);
                        }

                        outputObject.put("focalClass", focalClassNode);
                        outputObject.put("testClass", testClassNode);
                        

                        // Create a datapoint object and move the fields inside it
                        ObjectNode datapoint = objectMapper.createObjectNode();

                        // Clean and set the testPrefix
                        ObjectNode cleanedTestPrefixNode = testPrefixCleaning(testPrefixNode);
                        if (cleanedTestPrefixNode != null) {
                            datapoint.set("testPrefix", cleanedTestPrefixNode);
                        }

                        if (targetNode.toString() == "<NO_ASSERTION>") {
                            datapoint.put("target", "<NO_ASSERTION>");
                            break;
                        }

                        datapoint.put("target", snippet);
                        datapoint.set("tokenSteps", tokenSteps);

                        // Add the datapoint to the shared datapoints array
                        datapoints.add(datapoint);

                    }
                       
                }
                
            }
            outputObjectArray.add(outputObject);


        } catch (IOException e) {
            System.err.println("Error processing JSON file: " + e.getMessage());
            e.printStackTrace();
        }

        return outputObjectArray;
    }


    /**
     * Given a literal token type, return its corresponding Java type.
     */
    private static String getLiteralType(String literalTokenType) {
        switch (literalTokenType) {
            case "DECIMAL_LITERAL":
            case "HEX_LITERAL":
            case "OCT_LITERAL":
            case "BINARY_LITERAL":
                return "int";
            case "FLOAT_LITERAL":
            case "HEX_FLOAT_LITERAL":
                return "float";
            case "BOOL_LITERAL":
                return "boolean";
            case "CHAR_LITERAL":
                return "char";
            case "STRING_LITERAL":
                return "String";
            default:
                return "Object";
        }
    }

    public static ObjectNode focalClassCleaning(JsonNode focalClassNode) {
        if (focalClassNode == null || !focalClassNode.isObject()) {
            return null;
        }

        ObjectNode mutableNode = (ObjectNode) focalClassNode;

        // Remove top-level fields
        List<String> topLevelFieldsToRemove = Arrays.asList("superclasses", "interfaces", "filePath");
        mutableNode.remove(topLevelFieldsToRemove);

        // Subfields to remove from "methods" and "constructors"
        List<String> subfieldsToRemove = Arrays.asList(
            "annotations", 
            "modifiers", 
            "thrownExceptions", 
            "parameters", 
            "returnType", 
            "invokedMethods"
        );

        // Helper to remove subfields from an array node
        removeSubfieldsFromArray(mutableNode, "methods", subfieldsToRemove);
        removeSubfieldsFromArray(mutableNode, "constructors", subfieldsToRemove);

        return mutableNode;
    }

    private static void removeSubfieldsFromArray(ObjectNode parentNode, String arrayName, List<String> subfieldsToRemove) {
        JsonNode arrayNode = parentNode.get(arrayName);
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode elementNode : arrayNode) {
                if (elementNode.isObject()) {
                    ((ObjectNode) elementNode).remove(subfieldsToRemove);
                }
            }
        }
    }

    /**
     * Cleans the testClassNode by removing specific fields.
     */
    public static ObjectNode testClassCleaning(JsonNode testClassNode) {
        if (testClassNode == null || !testClassNode.isObject()) {
            return null;
        }

        ObjectNode mutableNode = (ObjectNode) testClassNode;

        // Remove specified fields from the testClassNode
        List<String> fieldsToRemove = Arrays.asList("superclasses", "interfaces", "filePath", "testCases");
        mutableNode.remove(fieldsToRemove);

        return mutableNode;
    }

    /**
     * Cleans the testPrefixNode by removing specific fields.
     */
    public static ObjectNode testPrefixCleaning(JsonNode testPrefixNode) {
        if (testPrefixNode == null || !testPrefixNode.isObject()) {
            return null;
        }

        ObjectNode mutableNode = (ObjectNode) testPrefixNode;

        // Fields to remove from testPrefixNode
        List<String> fieldsToRemove = Arrays.asList(
            "annotations",
            "modifiers",
            "thrownExceptions",
            "parameters",
            "returnType"
        );

        mutableNode.remove(fieldsToRemove);

        return mutableNode;
    }


}
