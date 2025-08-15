package star.tracto.data.enums;

import star.tracto.lsp.antlr4.Antlr4Utils;

import java.util.ArrayList;
import java.util.Arrays;

public enum AntlrTokenMapper {
    // Java keywords
    ABSTRACT(new ArrayList<>(Arrays.asList("abstract"))),
    ASSERT(new ArrayList<>(Arrays.asList("Assert"))),
    BOOLEAN(new ArrayList<>(Arrays.asList("boolean"))),
    BREAK(new ArrayList<>(Arrays.asList("break"))),
    BYTE(new ArrayList<>(Arrays.asList("byte"))),
    CASE(new ArrayList<>(Arrays.asList("case"))),
    CATCH(new ArrayList<>(Arrays.asList("catch"))),
    CHAR(new ArrayList<>(Arrays.asList("char"))),
    CLASS(new ArrayList<>(Arrays.asList("class"))),
    CONST(new ArrayList<>(Arrays.asList("const"))),
    CONTINUE(new ArrayList<>(Arrays.asList("continue"))),
    DEFAULT(new ArrayList<>(Arrays.asList("default"))),
    DO(new ArrayList<>(Arrays.asList("do"))),
    DOUBLE(new ArrayList<>(Arrays.asList("double"))),
    ELSE(new ArrayList<>(Arrays.asList("else"))),
    ENUM(new ArrayList<>(Arrays.asList("enum"))),
    EXTENDS(new ArrayList<>(Arrays.asList("extends"))),
    FINAL(new ArrayList<>(Arrays.asList("final"))),
    FINALLY(new ArrayList<>(Arrays.asList("finally"))),
    FLOAT(new ArrayList<>(Arrays.asList("float"))),
    FOR(new ArrayList<>(Arrays.asList("for"))),
    IF(new ArrayList<>(Arrays.asList("if"))),
    GOTO(new ArrayList<>(Arrays.asList("goto"))),
    IMPLEMENTS(new ArrayList<>(Arrays.asList("implements"))),
    IMPORT(new ArrayList<>(Arrays.asList("import"))),
    INSTANCEOF(new ArrayList<>(Arrays.asList("instanceof"))),
    INT(new ArrayList<>(Arrays.asList("int"))),
    INTERFACE(new ArrayList<>(Arrays.asList("interface"))),
    LONG(new ArrayList<>(Arrays.asList("long"))),
    NATIVE(new ArrayList<>(Arrays.asList("native"))),
    NEW(new ArrayList<>(Arrays.asList("new"))),
    PACKAGE(new ArrayList<>(Arrays.asList("package"))),
    PRIVATE(new ArrayList<>(Arrays.asList("private"))),
    PROTECTED(new ArrayList<>(Arrays.asList("protected"))),
    PUBLIC(new ArrayList<>(Arrays.asList("public"))),
    RETURN(new ArrayList<>(Arrays.asList("return"))),
    SHORT(new ArrayList<>(Arrays.asList("short"))),
    STATIC(new ArrayList<>(Arrays.asList("static"))),
    STRICTFP(new ArrayList<>(Arrays.asList("strictfp"))),
    SUPER(new ArrayList<>(Arrays.asList("super"))),
    SWITCH(new ArrayList<>(Arrays.asList("switch"))),
    SYNCHRONIZED(new ArrayList<>(Arrays.asList("synchronized"))),
    THIS(new ArrayList<>(Arrays.asList("this"))),
    THROW(new ArrayList<>(Arrays.asList("throw"))),
    THROWS(new ArrayList<>(Arrays.asList("throws"))),
    TRANSIENT(new ArrayList<>(Arrays.asList("transient"))),
    TRY(new ArrayList<>(Arrays.asList("try"))),
    VOID(new ArrayList<>(Arrays.asList("void"))),
    VOLATILE(new ArrayList<>(Arrays.asList("volatile"))),
    WHILE(new ArrayList<>(Arrays.asList("while"))),
    // Module related keywords
    MODULE(new ArrayList<>(Arrays.asList("module"))),
    OPEN(new ArrayList<>(Arrays.asList("open"))),
    REQUIRES(new ArrayList<>(Arrays.asList("requires"))),
    EXPORTS(new ArrayList<>(Arrays.asList("exports"))),
    OPENS(new ArrayList<>(Arrays.asList("opens"))),
    TO(new ArrayList<>(Arrays.asList("to"))),
    USES(new ArrayList<>(Arrays.asList("uses"))),
    PROVIDES(new ArrayList<>(Arrays.asList("provides"))),
    WITH(new ArrayList<>(Arrays.asList("with"))),
    TRANSITIVE(new ArrayList<>(Arrays.asList("transitive"))),
    // Local Variable Type Inference
    VAR(new ArrayList<>(Arrays.asList("var"))),
    // Switch Expressions
    YIELD(new ArrayList<>(Arrays.asList("yield"))),
    // Records
    RECORD(new ArrayList<>(Arrays.asList("record"))),
    // Sealed Classes
    SEALED(new ArrayList<>(Arrays.asList("sealed"))),
    PERMITS(new ArrayList<>(Arrays.asList("permits"))),
    NON_SEALED(new ArrayList<>(Arrays.asList("non-sealed"))),
    // Literals
    DECIMAL_LITERAL(new ArrayList<>(Arrays.asList("DECIMAL_LITERAL"))),
    HEX_LITERAL(new ArrayList<>(Arrays.asList("HEX_LITERAL"))),
    OCT_LITERAL(new ArrayList<>(Arrays.asList("OCT_LITERAL"))),
    BINARY_LITERAL(new ArrayList<>(Arrays.asList("BINARY_LITERAL"))),
    FLOAT_LITERAL(new ArrayList<>(Arrays.asList("FLOAT_LITERAL"))),
    HEX_FLOAT_LITERAL(new ArrayList<>(Arrays.asList("HEX_FLOAT_LITERAL"))),
    BOOL_LITERAL(new ArrayList<>(Arrays.asList("true", "false"))),
    CHAR_LITERAL(new ArrayList<>(Arrays.asList("CHAR_LITERAL"))),
    STRING_LITERAL(new ArrayList<>(Arrays.asList("STRING_LITERAL"))),
    TEXT_BLOCK(new ArrayList<>(Arrays.asList("TEXT_BLOCK"))),
    NULL_LITERAL(new ArrayList<>(Arrays.asList("null"))),
    NEWLINE(new ArrayList<>(Arrays.asList("\\r", "\\n", "\\f"))),
    // Separators
    LPAREN(new ArrayList<>(Arrays.asList("("))),
    RPAREN(new ArrayList<>(Arrays.asList(")"))),
    LBRACE(new ArrayList<>(Arrays.asList("{"))),
    RBRACE(new ArrayList<>(Arrays.asList("}"))),
    LBRACKET(new ArrayList<>(Arrays.asList("["))),
    RBRACKET(new ArrayList<>(Arrays.asList("]"))),
    SEMI(new ArrayList<>(Arrays.asList(";"))),
    COMMA(new ArrayList<>(Arrays.asList(","))),
    DOT(new ArrayList<>(Arrays.asList("."))),
    // Operators
    ASSIGN(new ArrayList<>(Arrays.asList("="))),
    GT(new ArrayList<>(Arrays.asList(">"))),
    LT(new ArrayList<>(Arrays.asList("<"))),
    BANG(new ArrayList<>(Arrays.asList("!"))),
    TILDE(new ArrayList<>(Arrays.asList("~"))),
    QUESTION(new ArrayList<>(Arrays.asList("?"))),
    COLON(new ArrayList<>(Arrays.asList(":"))),
    EQUAL(new ArrayList<>(Arrays.asList("=="))),
    LE(new ArrayList<>(Arrays.asList("<="))),
    GE(new ArrayList<>(Arrays.asList(">="))),
    NOTEQUAL(new ArrayList<>(Arrays.asList("!="))),
    AND(new ArrayList<>(Arrays.asList("&&"))),
    OR(new ArrayList<>(Arrays.asList("||"))),
    INC(new ArrayList<>(Arrays.asList("++"))),
    DEC(new ArrayList<>(Arrays.asList("--"))),
    ADD(new ArrayList<>(Arrays.asList("+"))),
    SUB(new ArrayList<>(Arrays.asList("-"))),
    MUL(new ArrayList<>(Arrays.asList("*"))),
    DIV(new ArrayList<>(Arrays.asList("/"))),
    BITAND(new ArrayList<>(Arrays.asList("&"))),
    BITOR(new ArrayList<>(Arrays.asList("|"))),
    CARET(new ArrayList<>(Arrays.asList("^"))),
    MOD(new ArrayList<>(Arrays.asList("%"))),
    QUERY_ARROW(new ArrayList<>(Arrays.asList("-->"))),
    ADD_ASSIGN(new ArrayList<>(Arrays.asList("+="))),
    SUB_ASSIGN(new ArrayList<>(Arrays.asList("-="))),
    MUL_ASSIGN(new ArrayList<>(Arrays.asList("*="))),
    DIV_ASSIGN(new ArrayList<>(Arrays.asList("/="))),
    AND_ASSIGN(new ArrayList<>(Arrays.asList("&="))),
    OR_ASSIGN(new ArrayList<>(Arrays.asList("|="))),
    XOR_ASSIGN(new ArrayList<>(Arrays.asList("^="))),
    MOD_ASSIGN(new ArrayList<>(Arrays.asList("%="))),
    LSHIFT_ASSIGN(new ArrayList<>(Arrays.asList("<<="))),
    RSHIFT_ASSIGN(new ArrayList<>(Arrays.asList(">>="))),
    URSHIFT_ASSIGN(new ArrayList<>(Arrays.asList(">>>="))),
    // Others
    ARROW(new ArrayList<>(Arrays.asList("->"))),
    COLONCOLON(new ArrayList<>(Arrays.asList("::"))),
    AT(new ArrayList<>(Arrays.asList("@"))),
    ELLIPSIS(new ArrayList<>(Arrays.asList("...")));

    private final ArrayList<String> tokens;

    AntlrTokenMapper(ArrayList<String> values) {
        this.tokens = values;
    }

    public ArrayList<String> getValues() {
        return tokens;
    }

    public static ArrayList<String> getValuesByName(String name) {
        try {
            AntlrTokenMapper option = AntlrTokenMapper.valueOf(name);
            return option.getValues();
        } catch (IllegalArgumentException e) {
            return new ArrayList<>(Arrays.asList(name)); // Return an empty list for invalid names
        }
    }

    public static AntlrTokenAction getTokenAction(String token) {
        AntlrToken antlrToken = Antlr4Utils.getAntlrToken(token);

        switch (antlrToken) {
            case ASSERT:
            case CASE:
            case SEMI:
            case COMMA:
            case ASSIGN:
            case GT:
            case LT:
            case BANG:
            case TILDE:
            case QUESTION:
            case COLON:
            case EQUAL:
            case LE:
            case GE:
            case NOTEQUAL:
            case AND:
            case OR:
            case INC:
            case DEC:
            case ADD:
            case SUB:
            case MUL:
            case DIV:
            case BITAND:
            case BITOR:
            case CARET:
            case MOD:
            case QUERY_ARROW:
            case ADD_ASSIGN:
            case SUB_ASSIGN:
            case MUL_ASSIGN:
            case DIV_ASSIGN:
            case AND_ASSIGN:
            case OR_ASSIGN:
            case XOR_ASSIGN:
            case MOD_ASSIGN:
            case LSHIFT_ASSIGN:
            case RSHIFT_ASSIGN:
            case URSHIFT_ASSIGN:
            case ARROW:
                return AntlrTokenAction.SET_TO_ROOT;
            case LPAREN:
            case LBRACE:
            case LBRACK:
                return AntlrTokenAction.ADD_ROOT;
            case RPAREN:
            case RBRACE:
            case RBRACK:
                return AntlrTokenAction.REMOVE_CURRENT;
            case ABSTRACT:
            case BOOLEAN:
            case BREAK:
            case BYTE:
            case CATCH:
            case CHAR:
            case CLASS:
            case CONST:
            case CONTINUE:
            case DEFAULT:
            case DO:
            case DOUBLE:
            case ELSE:
            case ENUM:
            case EXTENDS:
            case FINAL:
            case FINALLY:
            case FLOAT:
            case FOR:
            case IF:
            case GOTO:
            case IMPLEMENTS:
            case IMPORT:
            case INSTANCEOF:
            case INT:
            case INTERFACE:
            case LONG:
            case NATIVE:
            case PACKAGE:
            case PRIVATE:
            case PROTECTED:
            case PUBLIC:
            case RETURN:
            case SHORT:
            case STATIC:
            case STRICTFP:
            case SUPER:
            case SWITCH:
            case SYNCHRONIZED:
            case THIS:
            case THROW:
            case THROWS:
            case TRANSIENT:
            case TRY:
            case VOID:
            case VOLATILE:
            case WHILE:
            case MODULE:
            case OPEN:
            case REQUIRES:
            case EXPORTS:
            case OPENS:
            case TO:
            case USES:
            case PROVIDES:
            case WITH:
            case TRANSITIVE:
            case VAR:
            case RECORD:
            case SEALED:
            case PERMITS:
            case NON_SEALED:
            case DECIMAL_LITERAL:
            case HEX_LITERAL:
            case OCT_LITERAL:
            case BINARY_LITERAL:
            case FLOAT_LITERAL:
            case HEX_FLOAT_LITERAL:
            case BOOL_LITERAL:
            case CHAR_LITERAL:
            case STRING_LITERAL:
            case TEXT_BLOCK:
            case NULL_LITERAL:
            case ELLIPSIS:
            case NEW:
            case COLONCOLON:
            case IDENTIFIER:
                return AntlrTokenAction.UPDATE_CURRENT;
            case YIELD:
            case AT:
            case DOT:
                return AntlrTokenAction.KEEP_CURRENT;
        }
        throw new IllegalArgumentException("No action defined for token: " + token);
    }
}
