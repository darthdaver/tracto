parser grammar JavaParser;

options {
    tokenVocab = JavaLexer;
    superClass = JavaParserBase;
}

// -----------------------------------
// RULES FOR LETTERS, DIGITS, LITERALS
// -----------------------------------
literal
    : integerLiteral
    | floatLiteral
    | CHAR_LITERAL
    | STRING_LITERAL
    | BOOL_LITERAL
    | NULL_LITERAL
    | TEXT_BLOCK
    ;

integerLiteral
    : DECIMAL_LITERAL
    | HEX_LITERAL
    | OCT_LITERAL
    | BINARY_LITERAL
    ;

floatLiteral
    : FLOAT_LITERAL
    | HEX_FLOAT_LITERAL
    ;

// ---------------------
// RULES FOR IDENTIFIERS
// ---------------------
// Example: variable names, method names and class names or listed keywords
identifier
    : IDENTIFIER
    | MODULE
    | OPEN
    | REQUIRES
    | EXPORTS
    | OPENS
    | TO
    | USES
    | PROVIDES
    | WITH
    | TRANSITIVE
    | YIELD
    | SEALED
    | PERMITS
    | RECORD
    | VAR
    ;

typeIdentifier
    : IDENTIFIER
    | MODULE
    | OPEN
    | REQUIRES
    | EXPORTS
    | OPENS
    | TO
    | USES
    | PROVIDES
    | WITH
    | TRANSITIVE
    | SEALED
    | PERMITS
    | RECORD
    ;

assertionIdentifier
    : ASSERT_ALL
    | ASSERT_ARRAY_EQUALS
    | ASSERT_DOES_NOT_THROW
    | ASSERT_EQUALS
    | ASSERT_FALSE
    | ASSERT_INSTANCE_OF
    | ASSERT_ITERABLE_EQUALS
    | ASSERT_LINES_MATCH
    | ASSERT_NOT_EQUALS
    | ASSERT_NOT_NULL
    | ASSERT_NOT_SAME
    | ASSERT_NULL
    | ASSERT_SAME
    | ASSERT_THAT
    | ASSERT_THROWS
    | ASSERT_THROWS_EXACTLY
    | ASSERT_TIMEOUT
    | ASSERT_TIMEOUT_PREEMPTIVELY
    | ASSERT_TRUE
    | FAIL
    ;

projectIdentifier
    : IDENTIFIER
    ;

// -------------------
// RULES FOR MODIFIERS
// -------------------
modifier
    : classOrInterfaceModifier
    | NATIVE
    | SYNCHRONIZED
    | TRANSIENT
    | VOLATILE
    ;

classOrInterfaceModifier
    : annotation
    | PUBLIC
    | PROTECTED
    | PRIVATE
    | STATIC
    | ABSTRACT
    | FINAL         // FINAL for class only -- does not apply to interfaces
    | STRICTFP
    | SEALED
    | NON_SEALED
    ;

interfaceMethodModifier
    : annotation
    | PUBLIC
    | ABSTRACT
    | DEFAULT
    | STATIC
    | STRICTFP
    ;

variableModifier
    : FINAL
    | annotation
    ;

moduleDirective
    : REQUIRES requiresModifier* qualifiedName ';'
    | EXPORTS qualifiedName (TO qualifiedName)? ';'
    | OPENS qualifiedName (TO qualifiedName)? ';'
    | USES qualifiedName ';'
    | PROVIDES qualifiedName WITH qualifiedName ';'
    ;

requiresModifier
    : TRANSITIVE
    | STATIC
    ;

// ------------------------------------------------------------------
// RULES FOR TYPES, ARGUMENTS, PARAMETERS, QUALIFIED NAMES, CONSTANTS
// ------------------------------------------------------------------
primitiveType
    : BOOLEAN
    | CHAR
    | BYTE
    | SHORT
    | INT
    | LONG
    | FLOAT
    | DOUBLE
    ;

typeType
    : annotation* (classOrInterfaceType | primitiveType) (annotation* '[' ']')*
    ;

typeTypeOrVoid
    : typeType
    | VOID
    ;

typeList
    : typeType (',' typeType)*
    ;

classType
    : (classOrInterfaceType '.')? annotation* identifier typeArguments?
    ;

classOrInterfaceType
    : (identifier typeArguments? '.')* typeIdentifier typeArguments?
    ;

recordComponentList
    : recordComponent (',' recordComponent)* { this.DoLastRecordComponent() }?
    ;

recordComponent
    : annotation* typeType (annotation* ELLIPSIS)? identifier
    ;

typeParameter
    : annotation* identifier (EXTENDS annotation* typeBound)?
    ;

typeParameters
    : '<' typeParameter (',' typeParameter)* '>'
    ;

typeBound
    : typeType ('&' typeType)*
    ;

lambdaParameters
    : identifier
    | '(' formalParameterList? ')'
    | '(' identifier (',' identifier)* ')'
    | '(' lambdaLVTIList? ')'
    ;

formalParameterList
    : formalParameter (',' formalParameter)* (',' lastFormalParameter)?
    | lastFormalParameter
    ;

formalParameters
    : '(' (
        receiverParameter?
        | receiverParameter (',' formalParameterList)?
        | formalParameterList?
    ) ')'
    ;

formalParameter
    : variableModifier* typeType variableDeclaratorId
    ;

lastFormalParameter
    : variableModifier* typeType annotation* '...' variableDeclaratorId
    ;

receiverParameter
    : typeType (identifier '.')* THIS
    ;

lambdaLVTIList
    : lambdaLVTIParameter (',' lambdaLVTIParameter)*
    ;

lambdaLVTIParameter
    : variableModifier* VAR identifier
    ;

arguments
    : '(' expressionList? ')'
    ;

// Example:  java.util.List<String> (the <String> part is typeArguments)
typeArguments
    : '<' typeArgument (',' typeArgument)* '>'
    ;

typeArgument
    : typeType
    | annotation* '?' ((EXTENDS | SUPER) typeType)?
    ;

typeArgumentsOrDiamond
    : '<' '>'
    | typeArguments
    ;

nonWildcardTypeArguments
    : '<' typeList '>'
    ;

nonWildcardTypeArgumentsOrDiamond
    : '<' '>'
    | nonWildcardTypeArguments
    ;

superSuffix
    : arguments
    | '.' typeArguments? identifier arguments?
    ;

explicitGenericInvocation
    : nonWildcardTypeArguments explicitGenericInvocationSuffix
    ;

explicitGenericInvocationSuffix
    : SUPER superSuffix
    | identifier arguments
    ;

createdName
    : identifier typeArgumentsOrDiamond? ('.' identifier typeArgumentsOrDiamond?)*
    | primitiveType
    ;

qualifiedName
    : identifier ('.' identifier)*
    ;

altAnnotationQualifiedName
    : (identifier DOT)* '@' identifier
    ;

qualifiedNameList
    : qualifiedName (',' qualifiedName)*
    ;

enumConstants
    : enumConstant (',' enumConstant)*
    ;

enumConstant
    : annotation* identifier arguments? classBody?
    ;

pattern
    : variableModifier* typeType annotation* identifier
    ;

elementValuePairs
    : elementValuePair (',' elementValuePair)*
    ;

// Example: in annotations like @A(yield = "value"), yield = "value" is an element-value pair
// (noting that 'yield' is a reserved keyword in Java 14+)
elementValuePair
    : identifier '=' elementValue
    ;

elementValue
    : expression
    | annotation
    | elementValueArrayInitializer
    ;

defaultValue
    : DEFAULT elementValue
    ;

// -----------------------------------------------------
// RULES FOR DECLARATORS, DECLARATIONS, AND INITIALIZERS
// -----------------------------------------------------
compilationUnit
    : packageDeclaration? (importDeclaration | ';')* (typeDeclaration | ';')* EOF
    | moduleDeclaration EOF
    ;

// Right-hand side of a variable declaration
variableDeclaratorId
    : identifier ('[' ']')*
    ;

// Left-hand side of a variable declaration
variableInitializer
    : arrayInitializer
    | expression
    ;

// Left-hand side of an variable declaration
arrayInitializer
    : '{' (variableInitializer (',' variableInitializer)* ','?)? '}'
    ;

elementValueArrayInitializer
    : '{' (elementValue (',' elementValue)*)? ','? '}'
    ;

variableDeclarator
    : variableDeclaratorId ('=' variableInitializer)?
    ;

variableDeclarators
    : variableDeclarator (',' variableDeclarator)*
    ;

localVariableDeclaration
    : variableModifier* (VAR identifier '=' expression | typeType variableDeclarators)
    ;

localTypeDeclaration
    : classOrInterfaceModifier* (classDeclaration | interfaceDeclaration | recordDeclaration)
    ;

classBodyDeclaration
    : ';'
    | STATIC? block
    | modifier* memberDeclaration
    ;

compactConstructorDeclaration
    : modifier* identifier constructorBody = block
    ;

recordDeclaration
    : RECORD identifier typeParameters? recordHeader (IMPLEMENTS typeList)? recordBody
    ;

recordHeader
    : '(' recordComponentList? ')'
    ;

packageDeclaration
    : annotation* PACKAGE qualifiedName ';'
    ;

importDeclaration
    : IMPORT STATIC? qualifiedName ('.' '*')? ';'
    ;

typeDeclaration
    : classOrInterfaceModifier* (
        classDeclaration
        | enumDeclaration
        | interfaceDeclaration
        | annotationTypeDeclaration
        | recordDeclaration
    )
    ;

methodDeclaration
    : typeTypeOrVoid identifier formalParameters ('[' ']')* (THROWS qualifiedNameList)? methodBody
    ;

genericMethodDeclaration
    : typeParameters methodDeclaration
    ;

fieldDeclaration
    : typeType variableDeclarators ';'
    ;

interfaceBodyDeclaration
    : modifier* interfaceMemberDeclaration
    | ';'
    ;

interfaceMemberDeclaration
    : recordDeclaration // Java17
    | constDeclaration
    | interfaceMethodDeclaration
    | genericInterfaceMethodDeclaration
    | interfaceDeclaration
    | annotationTypeDeclaration
    | classDeclaration
    | enumDeclaration
    ;

interfaceDeclaration
    : INTERFACE identifier typeParameters? (EXTENDS typeList)? (PERMITS typeList)? interfaceBody
    ;

annotationTypeDeclaration
    : '@' INTERFACE identifier annotationTypeBody
    ;

classDeclaration
    : CLASS identifier typeParameters? (EXTENDS typeType)? (IMPLEMENTS typeList)? (
        PERMITS typeList
    )? // Java17
    classBody
    ;

moduleDeclaration
    : OPEN? MODULE qualifiedName moduleBody
    ;

enumDeclaration
    : ENUM identifier (IMPLEMENTS typeList)? '{' enumConstants? ','? enumBodyDeclarations? '}'
    ;

enumBodyDeclarations
    : ';' classBodyDeclaration*
    ;

genericInterfaceMethodDeclaration
    : interfaceMethodModifier* typeParameters interfaceCommonBodyDeclaration
    ;

interfaceCommonBodyDeclaration
    : annotation* typeTypeOrVoid identifier formalParameters ('[' ']')* (THROWS qualifiedNameList)? methodBody
    ;

constDeclaration
    : typeType constantDeclarator (',' constantDeclarator)* ';'
    ;

constantDeclarator
    : identifier ('[' ']')* '=' variableInitializer
    ;

interfaceMethodDeclaration
    : interfaceMethodModifier* interfaceCommonBodyDeclaration
    ;

constructorDeclaration
    : identifier formalParameters (THROWS qualifiedNameList)? constructorBody = block
    ;

genericConstructorDeclaration
    : typeParameters constructorDeclaration
    ;

annotationTypeElementDeclaration
    : modifier* annotationTypeElementRest
    | ';' // this is not allowed by the grammar, but apparently allowed by the actual compiler
    ;

memberDeclaration
    : recordDeclaration //Java17
    | methodDeclaration
    | genericMethodDeclaration
    | fieldDeclaration
    | constructorDeclaration
    | genericConstructorDeclaration
    | interfaceDeclaration
    | annotationTypeDeclaration
    | classDeclaration
    | enumDeclaration
    ;

// -------------------------------------------------
// RULES FOR EXPRESSIONS, METHOD CALLS, AND CREATION
// -------------------------------------------------
primary
    : '(' expression ')'
    | THIS
    | SUPER
    | literal
    | identifier
    | typeTypeOrVoid '.' CLASS
    | nonWildcardTypeArguments (explicitGenericInvocationSuffix | THIS arguments)
    ;

innerCreator
    : identifier nonWildcardTypeArgumentsOrDiamond? classCreatorRest
    ;

creator
    : nonWildcardTypeArguments? createdName classCreatorRest
    | createdName arrayCreatorRest
    ;

arrayCreatorRest
    : ('[' ']')+ arrayInitializer
    | ('[' expression ']')+ ('[' ']')*
    ;

classCreatorRest
    : arguments classBody?
    ;

methodCall
    : (identifier | THIS | SUPER) arguments
    ;

memberReferenceSuffix
    : bop = '.' (
        identifier
        | methodCall
        | THIS
        | NEW nonWildcardTypeArguments? innerCreator
        | SUPER superSuffix
        | explicitGenericInvocation
    )
    ;

methodReferenceSuffix
    : bop = '::' typeArguments? identifier                          #ExpressionMethodReferenceSuffix
    | typeType '::' (typeArguments? identifier | NEW)               #TypeMethodReferenceSuffix
    | classType '::' typeArguments? NEW                             #ClassMethodReferenceSuffix
    ;



switchExpression
    : SWITCH parExpression '{' switchLabeledRule* '}'
    ;

parExpression
    : '(' expression ')'
    ;

expression
    : primary                                                       #PrimaryExpr
    | expression '[' expression ']'                                 #SquareBracketExpr
    | expression memberReferenceSuffix                              #MemberReferenceExpr
    | methodCall                                                    #MethodCallExpression
    | expression methodReferenceSuffix                              #MethodReferenceExpr
    | switchExpression                                              #ExpressionSwitch
    | expression postfix = ('++' | '--')                            #PostIncrementDecrementOperatorExpr
    | prefix = ('+' | '-' | '++' | '--' | '~' | '!') expression     #UnaryOperatorExpr
    | '(' annotation* typeType ('&' typeType)* ')' expression       #CastExpr
    | NEW creator                                                   #ObjectCreationExpr
    | expression bop = ('*' | '/' | '%') expression                 #BinaryOperatorExpr
    | expression bop = ('+' | '-') expression                       #BinaryOperatorExpr
    | expression ('<' '<' | '>' '>' '>' | '>' '>') expression       #BinaryOperatorExpr
    | expression bop = ('<=' | '>=' | '>' | '<') expression         #BinaryOperatorExpr
    | expression bop = INSTANCEOF (typeType | pattern)              #InstanceOfOperatorExpr
    | expression bop = ('==' | '!=') expression                     #BinaryOperatorExpr
    | expression bop = '&' expression                               #BinaryOperatorExpr
    | expression bop = '^' expression                               #BinaryOperatorExpr
    | expression bop = '|' expression                               #BinaryOperatorExpr
    | expression bop = '&&' expression                              #BinaryOperatorExpr
    | expression bop = '||' expression                              #BinaryOperatorExpr
    | <assoc = right> expression bop = '?' expression ':' expression #TernaryExpr
    | <assoc = right> expression bop = (
        '='
        | '+='
        | '-='
        | '*='
        | '/='
        | '&='
        | '|='
        | '^='
        | '>>='
        | '>>>='
        | '<<='
        | '%='
    ) expression                                              #BinaryOperatorExpression
    | lambdaExpression                                        #ExpressionLambda
    ;

expressionList
    : expression (',' expression)*
    ;

lambdaExpression
    : lambdaParameters '->' lambdaBody
    ;

// --------------------------------------------
// RULES FOR STATEMENTS AND BLOCKS
// --------------------------------------------
statement
    : blockLabel = block
    | ASSERT expression (':' expression)? ';'
    | IF parExpression statement (ELSE statement)?
    | FOR '(' forControl ')' statement
    | WHILE parExpression statement
    | DO statement WHILE parExpression ';'
    | TRY block (catchClause+ finallyBlock? | finallyBlock)
    | TRY resourceSpecification block catchClause* finallyBlock?
    | SWITCH parExpression '{' switchBlockStatementGroup* switchLabel* '}'
    | SYNCHRONIZED parExpression block
    | RETURN expression? ';'
    | THROW expression ';'
    | BREAK identifier? ';'
    | CONTINUE identifier? ';'
    | YIELD expression ';' // Java17
    | SEMI
    | statementExpression = expression ';'
    | switchExpression ';'? // Java17
    | identifierLabel = identifier ':' statement
    ;

block
    : '{' blockStatement* '}'
    ;

blockStatement
    : localVariableDeclaration ';'
    | localTypeDeclaration
    | statement
    ;

annotationTypeBody
    : '{' annotationTypeElementDeclaration* '}'
    ;

recordBody
    : '{' (classBodyDeclaration | compactConstructorDeclaration)* '}'
    ;

interfaceBody
    : '{' interfaceBodyDeclaration* '}'
    ;

moduleBody
    : '{' moduleDirective* '}'
    ;

classBody
    : '{' classBodyDeclaration* '}'
    ;

methodBody
    : block
    | ';'
    ;

lambdaBody
    : expression
    | block
    ;

// ---------------------------
// RULES FOR SWITCH STATEMENTS
// ---------------------------
switchLabeledRule
    : CASE (expressionList | NULL_LITERAL | guardedPattern) (ARROW | COLON) switchRuleOutcome
    | DEFAULT (ARROW | COLON) switchRuleOutcome
    ;

guardedPattern
    : '(' guardedPattern ')'
    | variableModifier* typeType annotation* identifier ('&&' expression)*
    | guardedPattern '&&' expression
    ;

switchRuleOutcome
    : block
    | blockStatement*
    ;

switchBlockStatementGroup
    : switchLabel+ blockStatement+
    ;

switchLabel
    : CASE (
        constantExpression = expression
        | enumConstantName = IDENTIFIER
        | typeType varName = identifier
    ) ':'
    | DEFAULT ':'
    ;

// ------------------------
// RULES FOR FOR STATEMENTS
// ------------------------
forControl
    : enhancedForControl
    | forInit? ';' expression? ';' forUpdate = expressionList?
    ;

forInit
    : localVariableDeclaration
    | expressionList
    ;

enhancedForControl
    : variableModifier* (typeType | VAR) variableDeclaratorId ':' expression
    ;

// ------------------------
// RULES FOR TRY STATEMENTS
// ------------------------
resourceSpecification
    : '(' resources ';'? ')'
    ;

resources
    : resource (';' resource)*
    ;

resource
    : variableModifier* (classOrInterfaceType variableDeclaratorId | VAR identifier) '=' expression
    | qualifiedName
    ;

catchClause
    : CATCH '(' variableModifier* catchType identifier ')' block
    ;

catchType
    : qualifiedName ('|' qualifiedName)*
    ;

finallyBlock
    : FINALLY block
    ;

// ---------------------
// RULES FOR ANNOTATIONS
// ---------------------
annotation
    : ('@' qualifiedName | altAnnotationQualifiedName) (
        '(' ( elementValuePairs | elementValue)? ')'
    )?
    ;

annotationTypeElementRest
    : typeType annotationMethodOrConstantRest ';'
    | classDeclaration ';'?
    | interfaceDeclaration ';'?
    | enumDeclaration ';'?
    | annotationTypeDeclaration ';'?
    | recordDeclaration ';'? // Java17
    ;

annotationMethodOrConstantRest
    : annotationMethodRest
    | annotationConstantRest
    ;

annotationMethodRest
    : identifier '(' ')' defaultValue?
    ;

annotationConstantRest
    : variableDeclarators
    ;

// --------------------
// RULES FOR ASSERTIONS
// --------------------

assertAllMethodCall
    : ASSERT_ALL '(' executable (',' executable)* ')' SEMI
    ;

executable
    : lambdaExpression                      #ExecutableLambdaExpr
    | expression methodReferenceSuffix      #ExecutableMethodReferenceExpr
    | executableCreatorRest                 #ExecutableCreatorRestExpr
    ;

executableCreatorRest
    : NEW EXECUTABLE nonWildcardTypeArgumentsOrDiamond? classCreatorRest
    ;
