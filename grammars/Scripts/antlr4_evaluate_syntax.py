"""
This script evaluates the syntax of the Oracle dataset using the ANTLR4 grammar. To run it:
    python3 -m venv venv
    source venv/bin/activate
    pip3 install -r requirements.txt
    antlr4 -Dlanguage=Python3 ./grammars/ANTLR4/JavaLexer.g4 ./grammars/ANTLR4/JavaParser.g4
"""

import json
import time

from antlr4 import *
from ANTLR4.JavaLexer import JavaLexer
from ANTLR4.JavaParser import JavaParser
from antlr4.error.ErrorListener import ErrorListener
from antlr4.Token import Token
from antlr4 import InputStream, CommonTokenStream
from antlr4.error.Errors import NoViableAltException


def check_token_kind(input_string):
    """
    Transforms the input string into token representations similar to the style
    used in the 'error' messages for consistent comparison.
    """
    # Create an InputStream from the input string
    input_stream = InputStream(input_string)

    # Create a lexer for the input
    lexer = JavaLexer(input_stream)

    # Tokenize the input
    token_stream = CommonTokenStream(lexer)
    token_stream.fill()  # Load all tokens
    tokens = token_stream.tokens

    # Get token representations
    token_reprs = []
    for token in tokens:
        if token.type == Token.EOF:
            continue  # Skip EOF token

        token_type = token.type
        literal_names = lexer.literalNames
        symbolic_names = lexer.symbolicNames

        # Initialize token_repr as None
        token_repr = None

        # Check if token_type is within bounds for symbolicNames
        if 0 <= token_type < len(symbolic_names):
            token_repr = symbolic_names[token_type]

        # If not found or if it's INVALID, check literalNames
        if (not token_repr or token_repr == '<INVALID>') and 0 <= token_type < len(literal_names):
            token_repr = literal_names[token_type]
            if token_repr and token_repr != '<INVALID>':
                token_repr = token_repr.strip("'")  # Remove quotes around literal

        # If still not found, use the token text as the last resort
        if not token_repr or token_repr == '<INVALID>':
            token_repr = token.text

        # Exclude whitespace tokens ('WS') from the final output
        if symbolic_names[token_type] != 'WS':
            token_reprs.append(token_repr)

    # Return the token kinds as a space-separated string
    return ' '.join(token_reprs)



class SyntaxErrorListener(ErrorListener):
    """ ANTLR4 error listener that captures syntax errors """
    def __init__(self):
        super(SyntaxErrorListener, self).__init__()
        self.errors = []

    def get_token_display_name(self, recognizer, token_type):
        """ Get the display name of a token based on the parser """
        # Get the token names from the parser
        if token_type == Token.EOF:
            return "EOF"

        literal_names = recognizer.literalNames
        symbolic_names = recognizer.symbolicNames

        # Try to get the literal name
        if token_type >= 0 and token_type < len(literal_names):
            literal_name = literal_names[token_type]
            if literal_name and literal_name != "<INVALID>":
                return literal_name.strip("'")  # Remove quotes around literals

        # Try to get the symbolic name
        if token_type >= 0 and token_type < len(symbolic_names):
            symbolic_name = symbolic_names[token_type]
            if symbolic_name and symbolic_name != "<INVALID>":
                return symbolic_name

        # Fallback to token type number if name not found
        return str(token_type)

    def syntaxError(self, recognizer, offendingSymbol, line, column, msg, e):
        """ Capture syntax errors and store them in a list """
        # Get the token name
        expected_tokens = ""
        if e is not None:
            if isinstance(e, NoViableAltException):
                # Handle NoViableAltException to get specific expected tokens
                dead_end_configs = e.deadEndConfigs
                token_types = set()
                if dead_end_configs is not None:
                    for config in dead_end_configs:
                        state = config.state
                        for i in range(len(state.transitions)):
                            trans = state.transitions[i]
                            if not trans.isEpsilon:
                                labels = trans.label
                                if labels is not None:
                                    token_types.update(list(labels))
                # Now get the token names from token_types
                token_names = []
                for token_type in token_types:
                    token_name = self.get_token_display_name(recognizer, token_type)
                    token_names.append(token_name)
                expected_tokens = ", ".join(token_names)
                msg += f" Expected one of the following tokens: {expected_tokens}"
            else:
                # Get the set of expected tokens
                expected = e.getExpectedTokens()
                expected_tokens_list = list(expected)
                token_names = []

                for token_type in expected_tokens_list:
                    token_name = self.get_token_display_name(recognizer, token_type)
                    token_names.append(token_name)

                expected_tokens = ", ".join(token_names)
                msg += f" Expected one of the following tokens: {expected_tokens}"

        self.errors.append(f"{msg}")




def check_syntax(tokens_list):
    """ Check the syntax of the input tokens """

    # Convert list of tokens to a string (assuming space-separated tokens for simplicity)
    input_stream = InputStream(tokens_list)

    # Create a lexer and parser for the input
    lexer = JavaLexer(input_stream)
    token_stream = CommonTokenStream(lexer)
    parser = JavaParser(token_stream)

    # Add custom error listener to capture syntax errors
    error_listener = SyntaxErrorListener()
    parser.removeErrorListeners()  # Remove the default error listeners
    parser.addErrorListener(error_listener)

    #print("Tokens so far: ", ' '.join(tokens_list))
    # Start parsing 
    parser.reset()
    # prinnt parse tree
    #print(parser.querySnippet().toStringTree(recog=parser))
    parser.querySnippet()


    # Return errors if any
    return error_listener.errors

def process_tokens(tokens):
    """ Process the tokens and check for syntax errors """

    tokens_kind = check_token_kind(tokens)
    print(f"Tokens kind: {tokens_kind}")

    errors = check_syntax(tokens)

    if errors:
        #print(f"Syntax errors:")
        for error in errors:
            print(f"  {error}")
        pass
    else:
        #print(f"Syntax is correct")
        return True, tokens_kind, ' '.join(errors)

    print("\n")

    return False, tokens_kind, ' '.join(errors)

count = 0
extracted_data = []
count_time = 0

method = "separatorPattern.matcher(\"\").matches();"
print(f"Method: {method}\n")
check = process_tokens(method)
print(f"{method} check is {check}\n")



with open('./grammars/Input/new_oracle_dataset.json', 'r', encoding='utf-8') as file:
    data_list = json.load(file)
    sub_list = data_list#[9:1008]
    extracted_data = []
    # Iterate over each JSON element in the list
    for data in sub_list:
        method = data.get('oracle', None)
        #method = "Arrays.stream(predicates).anyMatch(jdVar -> jdVar == null) "

        #print(f"Method: {method}")
        time_init = time.time()
        check, tokens, errors = process_tokens(method)
        end_time  = time.time() - time_init
        count_time += end_time


        if str(check).lower() == str(data.get('correct')):
            print(f"{method} check is Correct")
            count += 1
        else:
            print(f"{method} check is Incorrect")


        extracted_data.append({
                'input': method,
                'oracle_response': str(check).lower(), 
                'error': errors,
                'tokens_kind': tokens,
                'ground_truth': data.get('correct'),
                'time_taken': round(end_time, 2)
                })

with open('./grammars/Output/antlr4_eval.json', 'w', encoding='utf-8') as outfile:
    json.dump(extracted_data, outfile, indent=4)

print(f"Accuracy: {round(count/len(sub_list), 2)}")
print(f"Average time taken: {round(count_time/len(sub_list), 3)} seconds")
