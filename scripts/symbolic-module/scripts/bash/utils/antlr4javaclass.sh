#!/bin/bash

# Get current directory
current_dir=$(realpath "$(dirname "$BASH_SOURCE")")
# Setup global variables
source "${current_dir}/global_variables.sh"

PACKAGE_LINE="package star.tracto.lsp.antlr4.antlr4Class;"

GRAMMAR_DIR="${SRC_DIR}/main/java/star/tracto/lsp/antlr4/grammars"
OUTPUT_DIR="${SRC_DIR}/main/java/star/tracto/lsp/antlr4/antlr4Class"

rm -rf "${OUTPUT_DIR}"/*

# Run ANTLR4 command
antlr -Dlanguage=Java "$GRAMMAR_DIR/JavaLexer.g4" "$GRAMMAR_DIR/JavaParser.g4" -o "$OUTPUT_DIR"
cp "$GRAMMAR_DIR/JavaParserBase.java" "$OUTPUT_DIR/JavaParserBase.java"

# Add package line to each of the 4 generated files
for file in "$OUTPUT_DIR"/*.java; do
    # Skip if it's not a file (just to be safe)
    [ -f "$file" ] || continue

    # Prepend package line
    tmpfile=$(mktemp)
    echo "$PACKAGE_LINE" > "$tmpfile"
    cat "$file" >> "$tmpfile"
    mv "$tmpfile" "$file"
done

echo "ANTLR generation and package insertion complete."