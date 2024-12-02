"""
This script compares the 'tokens_kind' of two JSON elements and checks if the first token after the
"""

import json
import re

def compare_tokens_and_error(json1, json2):
    """
    Compares the 'tokens_kind' of two JSON elements and checks if the first token after the
    difference is present in the 'error' message of the second JSON element.
    """

    # Extract 'tokens_kind' and 'error' from the JSON elements
    tokens_kind1 = json1['tokens_kind']
    tokens_kind2 = json2['tokens_kind']
    error_message = json2['error']

    # Split 'tokens_kind' strings into lists of tokens
    tokens1 = tokens_kind1.strip().split()
    tokens2 = tokens_kind2.strip().split()

    # Normalize tokens for error message comparison
    tokens1 = [normalize_token(token) for token in tokens1]
    tokens2 = [normalize_token(token) for token in tokens2]

    # Find the index where the tokens differ
    min_length = min(len(tokens1), len(tokens2))
    diff_index = None
    for i in range(min_length):
        if not tokens_match(tokens1[i], tokens2[i]):
            diff_index = i
            break
    else:
        # If all tokens so far are the same, check if one is longer than the other
        if len(tokens1) > len(tokens2):
            diff_index = len(tokens2)
        else:
            #print("The tokens are the same in both JSON elements.")
            return True

    # Get the first differing token from tokens1
    if diff_index is not None and diff_index < len(tokens1):
        first_differing_token = tokens1[diff_index]
    else:
        #print("No differing token found.")
        return True

    # Check if the first differing token is in the error message
    if first_differing_token in error_message:
        return True
    else:
        print(f"The first differing token '{first_differing_token}' is NOT in the error message.")
        return False

def tokens_match(token1, token2):
    """
    Returns True if token1 and token2 represent the same token, considering literals.
    """
    # Normalize both tokens
    normalized_token1 = normalize_token(token1)
    normalized_token2 = normalize_token(token2)

    return normalized_token1 == normalized_token2

def normalize_token(token):
    """
    Normalize tokens to match the style used in error messages. For example,
    DOT -> ., EQUAL -> ==, etc. Handles actual values for literals.
    """
    token_mapping = {
        # Keywords
        'ABSTRACT': 'abstract',
        'ASSERT': 'assert',
        'BOOLEAN': 'boolean',
        'BREAK': 'break',
        'BYTE': 'byte',
        'CASE': 'case',
        'CATCH': 'catch',
        'CHAR': 'char',
        'CLASS': 'class',
        'CONST': 'const',
        'CONTINUE': 'continue',
        'DEFAULT': 'default',
        'DO': 'do',
        'DOUBLE': 'double',
        'ELSE': 'else',
        'ENUM': 'enum',
        'EXTENDS': 'extends',
        'FINAL': 'final',
        'FINALLY': 'finally',
        'FLOAT': 'float',
        'FOR': 'for',
        'IF': 'if',
        'GOTO': 'goto',
        'IMPLEMENTS': 'implements',
        'IMPORT': 'import',
        'INSTANCEOF': 'instanceof',
        'INT': 'int',
        'INTERFACE': 'interface',
        'LONG': 'long',
        'NATIVE': 'native',
        'NEW': 'new',
        'PACKAGE': 'package',
        'PRIVATE': 'private',
        'PROTECTED': 'protected',
        'PUBLIC': 'public',
        'RETURN': 'return',
        'SHORT': 'short',
        'STATIC': 'static',
        'STRICTFP': 'strictfp',
        'SUPER': 'super',
        'SWITCH': 'switch',
        'SYNCHRONIZED': 'synchronized',
        'THIS': 'this',
        'THROW': 'throw',
        'THROWS': 'throws',
        'TRANSIENT': 'transient',
        'TRY': 'try',
        'VOID': 'void',
        'VOLATILE': 'volatile',
        'WHILE': 'while',

        # Separators
        'LPAREN': '(',
        'RPAREN': ')',
        'LBRACE': '{',
        'RBRACE': '}',
        'LBRACK': '[',
        'RBRACK': ']',
        'SEMI': ';',
        'COMMA': ',',
        'DOT': '.',

        # Operators
        'ASSIGN': '=',
        'GT': '>',
        'LT': '<',
        'BANG': '!',
        'TILDE': '~',
        'QUESTION': '?',
        'COLON': ':',
        'EQUAL': '==',
        'LE': '<=',
        'GE': '>=',
        'NOTEQUAL': '!=',
        'AND': '&&',
        'OR': '||',
        'INC': '++',
        'DEC': '--',
        'ADD': '+',
        'SUB': '-',
        'MUL': '*',
        'DIV': '/',
        'BITAND': '&',
        'BITOR': '|',
        'CARET': '^',
        'MOD': '%',

        # Assignment Operators
        'ADD_ASSIGN': '+=',
        'SUB_ASSIGN': '-=',
        'MUL_ASSIGN': '*=',
        'DIV_ASSIGN': '/=',
        'AND_ASSIGN': '&=',
        'OR_ASSIGN': '|=',
        'XOR_ASSIGN': '^=',
        'MOD_ASSIGN': '%=',
        'LSHIFT_ASSIGN': '<<=',
        'RSHIFT_ASSIGN': '>>=',
        'URSHIFT_ASSIGN': '>>>='
        ,
        # Java 8 tokens
        'ARROW': '->',
        'COLONCOLON': '::',

        # Miscellaneous
        'AT': '@',
        'ELLIPSIS': '...',

        # Identifiers
        'IDENTIFIER': 'IDENTIFIER',  # Special case, keep as "IDENTIFIER"

        # Whitespace and comments are ignored in tokens_kind comparisons
        'WS': '',  # Ignore whitespace
        'COMMENT': '',  # Ignore comments
        'LINE_COMMENT': '',  # Ignore line comments
    }

    # Return the mapped value if exists, else check for literals
    if token in token_mapping:
        return token_mapping[token]
    else:
        # Check if token is a literal
        literal_type = get_literal_type(token)
        if literal_type:
            # Map the literal type to normalized representation
            return token_mapping.get(literal_type, literal_type)
        else:
            # Assume any other token is an identifier
            if re.match(r'^[A-Za-z_][A-Za-z0-9_]*$', token):
                return 'IDENTIFIER'
            else:
                # Keep the token as is (e.g., operators like '+', '-', etc.)
                return token

def get_literal_type(token):
    """
    Determines the type of the literal token.
    Returns the literal type, or None if not a literal.
    """
    if re.fullmatch(r'[0-9]+', token):
        return 'DECIMAL_LITERAL'
    elif re.fullmatch(r'0[xX][0-9a-fA-F]+', token):
        return 'HEX_LITERAL'
    elif re.fullmatch(r'0[bB][01]+', token):
        return 'BINARY_LITERAL'
    elif re.fullmatch(r'0[0-7]+', token):
        return 'OCT_LITERAL'
    elif re.fullmatch(r'[0-9]*\.[0-9]+([eE][+-]?[0-9]+)?', token) or re.fullmatch(r'[0-9]+[eE][+-]?[0-9]+', token):
        return 'FLOAT_LITERAL'
    elif re.fullmatch(r'0[xX][0-9a-fA-F]+\.[0-9a-fA-F]*([pP][+-]?[0-9]+)?', token) or re.fullmatch(r'0[xX][0-9a-fA-F]+[pP][+-]?[0-9]+', token):
        return 'HEX_FLOAT_LITERAL'
    elif token.lower() in ['true', 'false']:
        return 'BOOL_LITERAL'
    elif re.fullmatch(r"'.'", token):
        return 'CHAR_LITERAL'
    elif re.fullmatch(r'".*"', token):
        return 'STRING_LITERAL'
    elif token == 'null':
        return 'NULL_LITERAL'
    else:
        return None

# Main code to process the JSON file and compare tokens
with open('./grammars/Output/antlr4_eval.json', 'r', encoding='utf-8') as file:
    data_list = json.load(file)
    sub_list = data_list
    extracted_data = []
    count_total = 0
    count_true = 0
    # Iterate over each JSON element in the list

    for i in range(0, len(sub_list), 2):
        if compare_tokens_and_error(sub_list[i], sub_list[i+1]):
            count_true += 1
        else:
            print(f"\nComplete Java Oracle: {sub_list[i]['input']}")
            print(f"Incomplete Java Oracle: {sub_list[i+1]['input']}\n")
        count_total += 1

    print(f"""\n\n{count_true} out of {count_total} 
          comparisons are correct. Percentage: {round(count_true/count_total*100,1)}%""")
