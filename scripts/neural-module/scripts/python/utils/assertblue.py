import json, re
from z3 import *

##############################
# Recursive-descent parser for Java boolean expressions (extended to support strings)
##############################

def tokenize(expr):
    """Tokenizes a Java-style boolean expression.
    """
    token_pattern = r'\s*(\".*?\"|\d+|[A-Za-z_][\w\.]*|&&|\|\||==|!=|>=|<=|>|<|!|\(|\))'
    tokens = re.findall(token_pattern, expr)
    return [t for t in tokens if t.strip() != '']

def parse_expr(tokens, pos, var_map):
    return parse_or(tokens, pos, var_map)

def parse_or(tokens, pos, var_map):
    left, pos = parse_and(tokens, pos, var_map)
    while pos < len(tokens) and tokens[pos] == "||":
        pos += 1
        right, pos = parse_and(tokens, pos, var_map)
        left = Or(left, right)
    return left, pos

def parse_and(tokens, pos, var_map):
    left, pos = parse_not(tokens, pos, var_map)
    while pos < len(tokens) and tokens[pos] == "&&":
        pos += 1
        right, pos = parse_not(tokens, pos, var_map)
        left = And(left, right)
    return left, pos

def parse_not(tokens, pos, var_map):
    if pos < len(tokens) and tokens[pos] == "!":
        pos += 1
        operand, pos = parse_not(tokens, pos, var_map)
        return Not(operand), pos
    else:
        return parse_atom(tokens, pos, var_map)

def parse_atom(tokens, pos, var_map):
    if tokens[pos] == "(":
        pos += 1
        expr, pos = parse_or(tokens, pos, var_map)
        if pos >= len(tokens) or tokens[pos] != ")":
            raise Exception("Expected ')'")
        pos += 1
        return expr, pos
    else:
        left, pos = parse_simple(tokens, pos, var_map)
        if pos < len(tokens) and tokens[pos] in ["==", "!=", ">", "<", ">=", "<="]:
            op = tokens[pos]
            pos += 1
            right, pos = parse_simple(tokens, pos, var_map)
            if op == "==":
                return left == right, pos
            elif op == "!=":
                return left != right, pos
            elif op == ">":
                return left > right, pos
            elif op == "<":
                return left < right, pos
            elif op == ">=":
                return left >= right, pos
            elif op == "<=":
                return left <= right, pos
        else:
            return left, pos

def parse_simple(tokens, pos, var_map):
    token = tokens[pos]
    pos += 1
    # String literal
    if token.startswith("\"") and token.endswith("\""):
        return StringVal(token[1:-1]), pos
    # Integer literal
    if token.isdigit():
        return IntVal(int(token)), pos
    # Boolean literals
    if token.lower() == "true":
        return BoolVal(True), pos
    if token.lower() == "false":
        return BoolVal(False), pos
    # Otherwise, an identifier (could include dots)
    if token in var_map:
        return var_map[token], pos
    else:
        # Create a new String constant by default.
        new_var = String(token)
        var_map[token] = new_var
        return new_var, pos

def parse_java_boolean_expression(expr_str, var_map):
    """
    Parses a Java boolean expression (e.g. "a > 0 && a < 10" or "z != null")
    into a Z3 Boolean formula.
    """
    tokens = tokenize(expr_str)
    expr, pos = parse_or(tokens, 0, var_map)
    if pos != len(tokens):
        raise Exception("Extra tokens remaining: " + str(tokens[pos:]))
    return expr

##############################
# Extended conversion functions for complete Java assertions
##############################

def parse_literal(literal_str, var_map):
    """
    Converts a literal string (or identifier) into a Z3 expression.
    Supports:
      - 'null'
      - Boolean literals
      - Quoted string literals
      - Integer literals
      - Simple arithmetic expressions like '5+5'
      - Otherwise, treat it as a string variable or reference
    """
    literal_str = literal_str.strip()
    if literal_str == "null":
        return var_map["null"]
    elif literal_str.lower() in ["true", "false"]:
        return BoolVal(literal_str.lower() == "true")
    elif literal_str.startswith("\"") and literal_str.endswith("\""):
        return StringVal(literal_str[1:-1])
    
    # --- NEW LOGIC: handle simple integer arithmetic like "5+5" ---
    # A quick way is to allow expressions of the form  <int> +|-|* <int> [+|-|* <int>]...
    # You can adjust this for your use case to avoid any security issues.
    import re
    if re.match(r'^[0-9\+\-\*]+$', literal_str):
        # Evaluate this in a *very* restricted environment.
        # If you need to parse more safely, you might do a custom parse rather than eval().
        try:
            val = eval(literal_str, {"__builtins__": None}, {})
            # Now 'val' should be an integer result (e.g., 10 for "5+5")
            if isinstance(val, int):
                return IntVal(val)
        except:
            pass
    # --- end of new logic ---

    # Try integer literal
    try:
        value = int(literal_str)
        return IntVal(value)
    except ValueError:
        pass

    # If the literal isn't recognized as an int or arithmetic expression,
    # see if it's in var_map already
    if literal_str in var_map:
        return var_map[literal_str]
    else:
        # Otherwise, assume it's a string variable or reference
        new_var = String(literal_str)
        var_map[literal_str] = new_var
        return new_var

def parse_java_assertion_full(assertion_str, var_map):
    """
    Parses a complete Java assertion statement into a Z3 Boolean formula.
    
    Supported assertions:
      - assertEquals(expected, actual);
      - assertNotEquals(expected, actual);
      - assertNull(actual);
      - assertNotNull(actual);
      - assertTrue(expression);
      - assertFalse(expression);
      - (Additional assertion types can be added here.)
    """
    s = assertion_str.strip()
    if s.endswith(";"):
        s = s[:-1].strip()
    
    if s.startswith("assertEquals"):
        inner = s[len("assertEquals("): s.rfind(")")]
        parts = inner.split(",", 1)
        if len(parts) != 2:
            raise ValueError("assertEquals must have exactly two arguments.")
        expected_str = parts[0].strip()
        actual_str = parts[1].strip()
        expected_value = parse_literal(expected_str, var_map)
        # If the actual argument is a simple identifier (or expression) in var_map, use it;
        # otherwise, try to parse it as a literal or, if it is not a number, as a boolean expression.
        if actual_str in var_map:
            actual_value = var_map[actual_str]
        else:
            # Try parsing as a literal first.
            try:
                actual_value = parse_literal(actual_str, var_map)
            except Exception:
                # Fall back to parsing as a boolean expression.
                actual_value = parse_java_boolean_expression(actual_str, var_map)
        return actual_value == expected_value

    elif s.startswith("assertNotEquals"):
        inner = s[len("assertNotEquals("): s.rfind(")")]
        parts = inner.split(",", 1)
        if len(parts) != 2:
            raise ValueError("assertNotEquals must have exactly two arguments.")
        expected_str = parts[0].strip()
        actual_str = parts[1].strip()
        expected_value = parse_literal(expected_str, var_map)
        if actual_str in var_map:
            actual_value = var_map[actual_str]
        else:
            try:
                actual_value = parse_literal(actual_str, var_map)
            except Exception:
                actual_value = parse_java_boolean_expression(actual_str, var_map)
        return Not(actual_value == expected_value)

    elif s.startswith("assertNull"):
        inner = s[len("assertNull("): s.rfind(")")]
        actual_str = inner.strip()
        if actual_str in var_map:
            actual_value = var_map[actual_str]
        else:
            raise ValueError("Unknown variable: " + actual_str)
        return actual_value == var_map["null"]

    elif s.startswith("assertNotNull"):
        inner = s[len("assertNotNull("): s.rfind(")")]
        actual_str = inner.strip()
        if actual_str in var_map:
            actual_value = var_map[actual_str]
        else:
            raise ValueError("Unknown variable: " + actual_str)
        return Not(actual_value == var_map["null"])

    elif s.startswith("assertTrue"):
        inner = s[len("assertTrue("): s.rfind(")")]
        expr_str = inner.strip()
        return parse_java_boolean_expression(expr_str, var_map)

    elif s.startswith("assertFalse"):
        inner = s[len("assertFalse("): s.rfind(")")]
        expr_str = inner.strip()
        return Not(parse_java_boolean_expression(expr_str, var_map))
    else:
        raise ValueError("Unsupported assertion type: " + s)

def check_equivalence(formula1, formula2):
    """
    Uses Z3 to check whether two Boolean formulas are logically equivalent.
    They are equivalent if (formula1 XOR formula2) is unsatisfiable.
    """
    s = Solver()
    s.add(Xor(formula1, formula2))
    return s.check() == unsat

def check_java_assertions_equivalence_full(assertion_str1, assertion_str2, var_map):
    """
    Converts two complete Java assertion strings into Z3 formulas and checks
    if they are logically equivalent.
    """
    formula1 = parse_java_assertion_full(assertion_str1, var_map)
    formula2 = parse_java_assertion_full(assertion_str2, var_map)
    return check_equivalence(formula1, formula2)

##############################
# JSON input with multiple pairs of Java assertions
##############################

json_data = r'''
[
  {
    "assertion1": "assertEquals(null, x);",
    "assertion2": "assertNull(x);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(5, y);",
    "assertion2": "assertTrue(y == 5);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertNotEquals(5, y);",
    "assertion2": "assertFalse(y == 5);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertNotNull(z);",
    "assertion2": "assertTrue(z != null);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertFalse(x == null);",
    "assertion2": "assertNotNull(x);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertTrue(a > 0 && a < 10);",
    "assertion2": "assertTrue(a < 10 && a > 0);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(true, flag);",
    "assertion2": "assertTrue(flag);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(false, flag);",
    "assertion2": "assertFalse(flag);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertNotEquals(false, flag);",
    "assertion2": "assertTrue(flag);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(10, count);",
    "assertion2": "assertEquals(5+5, count);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(10, count);",
    "assertion2": "assertEquals(11, count);",
    "expected_equivalence": false
  },
  {
    "assertion1": "assertTrue(score >= 0 && score <= 100);",
    "assertion2": "assertTrue(score <= 100 && score >= 0);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertFalse(score < 0 || score > 100);",
    "assertion2": "assertTrue(score >= 0 && score <= 100);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertNotNull(user);",
    "assertion2": "assertEquals(null, user);",
    "expected_equivalence": false
  },
  {
    "assertion1": "assertTrue(x != null);",
    "assertion2": "assertNotNull(x);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(0, errorCount);",
    "assertion2": "assertTrue(errorCount == 0);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertNotEquals(0, errorCount);",
    "assertion2": "assertFalse(errorCount == 0);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertEquals(3, listSize);",
    "assertion2": "assertTrue(listSize > 2 && listSize < 4);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertFalse(listSize == 3);",
    "assertion2": "assertNotEquals(3, listSize);",
    "expected_equivalence": true
  },
  {
    "assertion1": "assertTrue(a > 5);",
    "assertion2": "assertTrue(a >= 7);",
    "expected_equivalence": false
  },
  {
    "generated_assertion": "assertEquals(\"465 2011-06-06 10:10:10 Network Failure\", variable);",
    "expected_assertion": "assertEquals(\"Support: Case reported by Joe Jee on 2011-06-06 10:10:10\", mailTemplate.subject(data));",
    "expected_equivalence": false
  }
]
'''

##############################
# Define the variable mapping
##############################

# For reference-type variables (used in null checks) we define a custom sort.
Ref = DeclareSort("Ref")
null_const = Const("null", Ref)

# Define known variables.
# (For non-primitive or complex identifiers that may appear in assertions, if they are not pre–declared,
#  our parser will automatically create them as String constants.)
var_map = {
    "null": null_const,
    "x": Const("x", Ref),
    "z": Const("z", Ref),
    "user": Const("user", Ref),
    "y": Int("y"),
    "a": Int("a"),
    "count": Int("count"),
    "score": Int("score"),
    "errorCount": Int("errorCount"),
    "listSize": Int("listSize"),
    "flag": Bool("flag")
}

##############################
# Read JSON data, check equivalence and report results
##############################

real_data = True
data = None

if real_data:
    with open("/Users/luca/Documents/USI/tratto-concrete-oracles_local/tratto/src/main/resources/dataset/tokens/output/20250211-113126_llama3.1_results.json") as f:
        data = json.load(f)
else:
    data = json.loads(json_data)

total = len(data)
correct = 0

for i, pair in enumerate(data, 1):
    try:
        # Use either assertion1/assertion2 or generated_assertion/expected_assertion
        assertion_str1 = pair.get("assertion1", pair.get("generated_assertion"))
        assertion_str2 = pair.get("assertion2", pair.get("expected_assertion"))

        expected = None
        if real_data:
            
            expected = pair["match"]
        else:
            expected = pair["expected_equivalence"]
        try:
            result = check_java_assertions_equivalence_full(assertion_str1, assertion_str2, var_map)
        except Exception as e:
            print(f"Error processing pair {i}: {e}")
            result = f"Error processing pair {i}: {e}"

        if result is True:
            print(f"Pair {i}:")
            print("  Generated Assertion:", assertion_str1)
            print("  Expected Assertion:", assertion_str2)
            print("  Expected equivalence:", expected)
            print("  Z3 equivalence check result:", result)
            print()
        
        if result == expected:
            correct += 1
    except Exception as e:
        print(f"Error processing pair {i}: {e}")

percentage = (correct / total) * 100
print(f"Percentage of correct predictions: {percentage:.1f}%")