package star.tracto.data.enums;

import java.util.ArrayList;
import java.util.Arrays;

public enum AntlrToken {
    ABSTRACT("abstract"),
    ASSERT("Assert"),
    BOOLEAN("boolean"),
    BREAK("break"),
    BYTE("byte"),
    CASE("case"),
    CATCH("catch"),
    CHAR("char"),
    CLASS("class"),
    CONST("const"),
    CONTINUE("continue"),
    DEFAULT("default"),
    DO("do"),
    DOUBLE("double"),
    ELSE("else"),
    ENUM("enum"),
    EXTENDS("extends"),
    FINAL("final"),
    FINALLY("finally"),
    FLOAT("float"),
    FOR("for"),
    IF("if"),
    GOTO("goto"),
    IMPLEMENTS("implements"),
    IMPORT("import"),
    INSTANCEOF("instanceof"),
    INT("int"),
    INTERFACE("interface"),
    LONG("long"),
    NATIVE("native"),
    NEW("new"),
    PACKAGE("package"),
    PRIVATE("private"),
    PROTECTED("protected"),
    PUBLIC("public"),
    RETURN("return"),
    SHORT("short"),
    STATIC("static"),
    STRICTFP("strictfp"),
    SUPER("super"),
    SWITCH("switch"),
    SYNCHRONIZED("synchronized"),
    THIS("this"),
    THROW("throw"),
    THROWS("throws"),
    TRANSIENT("transient"),
    TRY("try"),
    VOID("void"),
    VOLATILE("volatile"),
    WHILE("while"),
    // Module related keywords
    MODULE("module"),
    OPEN("open"),
    REQUIRES("requires"),
    EXPORTS("exports"),
    OPENS("opens"),
    TO("to"),
    USES("uses"),
    PROVIDES("provides"),
    WITH("with"),
    TRANSITIVE("transitive"),
    // Local Variable Type Inference
    VAR("var"),
    // Switch Expressions
    YIELD("yield"),
    // Records
    RECORD("record"),
    // Sealed Classes
    SEALED("sealed"),
    PERMITS("permits"),
    NON_SEALED("non-sealed"),
    // Literals
    DECIMAL_LITERAL("DECIMAL_LITERAL"),
    HEX_LITERAL("HEX_LITERAL"),
    OCT_LITERAL("OCT_LITERAL"),
    BINARY_LITERAL("BINARY_LITERAL"),
    FLOAT_LITERAL("FLOAT_LITERAL"),
    HEX_FLOAT_LITERAL("HEX_FLOAT_LITERAL"),
    BOOL_LITERAL("BOOL_LITERAL"),
    CHAR_LITERAL("CHAR_LITERAL"),
    STRING_LITERAL("STRING_LITERAL"),
    TEXT_BLOCK("TEXT_BLOCK"),
    NULL_LITERAL("null"),
    // Separators
    LPAREN("("),
    RPAREN(")"),
    LBRACE("{"),
    RBRACE("}"),
    LBRACK("["),
    RBRACK("]"),
    SEMI(";"),
    COMMA(","),
    DOT("."),
    // Operators
    ASSIGN("="),
    GT(">"),
    LT("<"),
    BANG("!"),
    TILDE("~"),
    QUESTION("?"),
    COLON(":"),
    EQUAL("=="),
    LE("<="),
    GE(">="),
    NOTEQUAL("!="),
    AND("&&"),
    OR("||"),
    INC("++"),
    DEC("--"),
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    BITAND("&"),
    BITOR("|"),
    CARET("^"),
    MOD("%"),
    QUERY_ARROW("-->"),
    ADD_ASSIGN("+="),
    SUB_ASSIGN("-="),
    MUL_ASSIGN("*="),
    DIV_ASSIGN("/="),
    AND_ASSIGN("&="),
    OR_ASSIGN("|="),
    XOR_ASSIGN("^="),
    MOD_ASSIGN("%="),
    LSHIFT_ASSIGN("<<="),
    RSHIFT_ASSIGN(">>="),
    URSHIFT_ASSIGN(">>>="),
    // Others
    ARROW("->"),
    COLONCOLON("::"),
    AT("@"),
    ELLIPSIS("..."),
    WS("WS"),
    COMMENT("COMMENT"),
    LINE_COMMENT("LINE_COMMENT"),
    IDENTIFIER("IDENTIFIER");

    private final String antlrToken;

    AntlrToken(String value) {
        this.antlrToken = value;
    }

    public String getAntlrToken() {
        return antlrToken;
    }

    public static AntlrToken fromString(String token) {
        for (AntlrToken value : AntlrToken.values()) {
            if (value.getAntlrToken().equals(token)) {
                return value;
            }
        }
        throw new IllegalArgumentException("No enum constant for symbol: " + token);
    }

    public static boolean isAtomicKeywordToken(AntlrToken antlrToken) {
        switch (antlrToken) {
            case THIS,
                 DECIMAL_LITERAL,
                 HEX_LITERAL,
                 OCT_LITERAL,
                 BINARY_LITERAL,
                 FLOAT_LITERAL,
                 HEX_FLOAT_LITERAL,
                 BOOL_LITERAL,
                 CHAR_LITERAL,
                 STRING_LITERAL,
                 TEXT_BLOCK,
                 NULL_LITERAL,
                 // Separators
                 LPAREN,
                 RPAREN,
                 LBRACE,
                 RBRACE,
                 LBRACK,
                 RBRACK,
                 SEMI,
                 COMMA,
                 DOT,
                 // Operators
                 ASSIGN,
                 GT,
                 LT,
                 BANG,
                 TILDE,
                 QUESTION,
                 COLON,
                 EQUAL,
                 LE,
                 GE,
                 NOTEQUAL,
                 AND,
                 OR,
                 INC,
                 DEC,
                 ADD,
                 SUB,
                 MUL,
                 DIV,
                 BITAND,
                 BITOR,
                 CARET,
                 MOD,
                 QUERY_ARROW,
                 ADD_ASSIGN,
                 SUB_ASSIGN,
                 MUL_ASSIGN,
                 DIV_ASSIGN,
                 AND_ASSIGN,
                 OR_ASSIGN,
                 XOR_ASSIGN,
                 MOD_ASSIGN,
                 LSHIFT_ASSIGN,
                 RSHIFT_ASSIGN,
                 URSHIFT_ASSIGN,
                 // Others
                 ARROW,
                 COLONCOLON,
                 AT,
                 ELLIPSIS,
                 WS,
                 COMMENT,
                 LINE_COMMENT,
                 IDENTIFIER:
                return false;
            default:
                return true;
        }
    }
}
