package star.tracto.lsp.antlr4.antlr4Class;
// Generated from /Users/davidemolinelli/Downloads/tracto/scripts/symbolic-module/src/main/java/star/tracto/lsp/antlr4/grammars/JavaParser.g4 by ANTLR 4.13.2
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link JavaParser}.
 */
public interface JavaParserListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link JavaParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterLiteral(JavaParser.LiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitLiteral(JavaParser.LiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#integerLiteral}.
	 * @param ctx the parse tree
	 */
	void enterIntegerLiteral(JavaParser.IntegerLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#integerLiteral}.
	 * @param ctx the parse tree
	 */
	void exitIntegerLiteral(JavaParser.IntegerLiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#floatLiteral}.
	 * @param ctx the parse tree
	 */
	void enterFloatLiteral(JavaParser.FloatLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#floatLiteral}.
	 * @param ctx the parse tree
	 */
	void exitFloatLiteral(JavaParser.FloatLiteralContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#identifier}.
	 * @param ctx the parse tree
	 */
	void enterIdentifier(JavaParser.IdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#identifier}.
	 * @param ctx the parse tree
	 */
	void exitIdentifier(JavaParser.IdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeIdentifier}.
	 * @param ctx the parse tree
	 */
	void enterTypeIdentifier(JavaParser.TypeIdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeIdentifier}.
	 * @param ctx the parse tree
	 */
	void exitTypeIdentifier(JavaParser.TypeIdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertionIdentifier}.
	 * @param ctx the parse tree
	 */
	void enterAssertionIdentifier(JavaParser.AssertionIdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertionIdentifier}.
	 * @param ctx the parse tree
	 */
	void exitAssertionIdentifier(JavaParser.AssertionIdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classNameIdentifier}.
	 * @param ctx the parse tree
	 */
	void enterClassNameIdentifier(JavaParser.ClassNameIdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classNameIdentifier}.
	 * @param ctx the parse tree
	 */
	void exitClassNameIdentifier(JavaParser.ClassNameIdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#methodNameIdentifier}.
	 * @param ctx the parse tree
	 */
	void enterMethodNameIdentifier(JavaParser.MethodNameIdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#methodNameIdentifier}.
	 * @param ctx the parse tree
	 */
	void exitMethodNameIdentifier(JavaParser.MethodNameIdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lambdaIdentifier}.
	 * @param ctx the parse tree
	 */
	void enterLambdaIdentifier(JavaParser.LambdaIdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lambdaIdentifier}.
	 * @param ctx the parse tree
	 */
	void exitLambdaIdentifier(JavaParser.LambdaIdentifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#modifier}.
	 * @param ctx the parse tree
	 */
	void enterModifier(JavaParser.ModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#modifier}.
	 * @param ctx the parse tree
	 */
	void exitModifier(JavaParser.ModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classOrInterfaceModifier}.
	 * @param ctx the parse tree
	 */
	void enterClassOrInterfaceModifier(JavaParser.ClassOrInterfaceModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classOrInterfaceModifier}.
	 * @param ctx the parse tree
	 */
	void exitClassOrInterfaceModifier(JavaParser.ClassOrInterfaceModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceMethodModifier}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceMethodModifier(JavaParser.InterfaceMethodModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceMethodModifier}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceMethodModifier(JavaParser.InterfaceMethodModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#variableModifier}.
	 * @param ctx the parse tree
	 */
	void enterVariableModifier(JavaParser.VariableModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#variableModifier}.
	 * @param ctx the parse tree
	 */
	void exitVariableModifier(JavaParser.VariableModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#moduleDirective}.
	 * @param ctx the parse tree
	 */
	void enterModuleDirective(JavaParser.ModuleDirectiveContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#moduleDirective}.
	 * @param ctx the parse tree
	 */
	void exitModuleDirective(JavaParser.ModuleDirectiveContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#requiresModifier}.
	 * @param ctx the parse tree
	 */
	void enterRequiresModifier(JavaParser.RequiresModifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#requiresModifier}.
	 * @param ctx the parse tree
	 */
	void exitRequiresModifier(JavaParser.RequiresModifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void enterPrimitiveType(JavaParser.PrimitiveTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void exitPrimitiveType(JavaParser.PrimitiveTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeType}.
	 * @param ctx the parse tree
	 */
	void enterTypeType(JavaParser.TypeTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeType}.
	 * @param ctx the parse tree
	 */
	void exitTypeType(JavaParser.TypeTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeTypeOrVoid}.
	 * @param ctx the parse tree
	 */
	void enterTypeTypeOrVoid(JavaParser.TypeTypeOrVoidContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeTypeOrVoid}.
	 * @param ctx the parse tree
	 */
	void exitTypeTypeOrVoid(JavaParser.TypeTypeOrVoidContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeList}.
	 * @param ctx the parse tree
	 */
	void enterTypeList(JavaParser.TypeListContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeList}.
	 * @param ctx the parse tree
	 */
	void exitTypeList(JavaParser.TypeListContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classType}.
	 * @param ctx the parse tree
	 */
	void enterClassType(JavaParser.ClassTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classType}.
	 * @param ctx the parse tree
	 */
	void exitClassType(JavaParser.ClassTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 */
	void enterClassOrInterfaceType(JavaParser.ClassOrInterfaceTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 */
	void exitClassOrInterfaceType(JavaParser.ClassOrInterfaceTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#recordComponentList}.
	 * @param ctx the parse tree
	 */
	void enterRecordComponentList(JavaParser.RecordComponentListContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#recordComponentList}.
	 * @param ctx the parse tree
	 */
	void exitRecordComponentList(JavaParser.RecordComponentListContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#recordComponent}.
	 * @param ctx the parse tree
	 */
	void enterRecordComponent(JavaParser.RecordComponentContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#recordComponent}.
	 * @param ctx the parse tree
	 */
	void exitRecordComponent(JavaParser.RecordComponentContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeParameter}.
	 * @param ctx the parse tree
	 */
	void enterTypeParameter(JavaParser.TypeParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeParameter}.
	 * @param ctx the parse tree
	 */
	void exitTypeParameter(JavaParser.TypeParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeParameters}.
	 * @param ctx the parse tree
	 */
	void enterTypeParameters(JavaParser.TypeParametersContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeParameters}.
	 * @param ctx the parse tree
	 */
	void exitTypeParameters(JavaParser.TypeParametersContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeBound}.
	 * @param ctx the parse tree
	 */
	void enterTypeBound(JavaParser.TypeBoundContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeBound}.
	 * @param ctx the parse tree
	 */
	void exitTypeBound(JavaParser.TypeBoundContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lambdaParameters}.
	 * @param ctx the parse tree
	 */
	void enterLambdaParameters(JavaParser.LambdaParametersContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lambdaParameters}.
	 * @param ctx the parse tree
	 */
	void exitLambdaParameters(JavaParser.LambdaParametersContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#formalParameterList}.
	 * @param ctx the parse tree
	 */
	void enterFormalParameterList(JavaParser.FormalParameterListContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#formalParameterList}.
	 * @param ctx the parse tree
	 */
	void exitFormalParameterList(JavaParser.FormalParameterListContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#formalParameters}.
	 * @param ctx the parse tree
	 */
	void enterFormalParameters(JavaParser.FormalParametersContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#formalParameters}.
	 * @param ctx the parse tree
	 */
	void exitFormalParameters(JavaParser.FormalParametersContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#formalParameter}.
	 * @param ctx the parse tree
	 */
	void enterFormalParameter(JavaParser.FormalParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#formalParameter}.
	 * @param ctx the parse tree
	 */
	void exitFormalParameter(JavaParser.FormalParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lastFormalParameter}.
	 * @param ctx the parse tree
	 */
	void enterLastFormalParameter(JavaParser.LastFormalParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lastFormalParameter}.
	 * @param ctx the parse tree
	 */
	void exitLastFormalParameter(JavaParser.LastFormalParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#receiverParameter}.
	 * @param ctx the parse tree
	 */
	void enterReceiverParameter(JavaParser.ReceiverParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#receiverParameter}.
	 * @param ctx the parse tree
	 */
	void exitReceiverParameter(JavaParser.ReceiverParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lambdaLVTIList}.
	 * @param ctx the parse tree
	 */
	void enterLambdaLVTIList(JavaParser.LambdaLVTIListContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lambdaLVTIList}.
	 * @param ctx the parse tree
	 */
	void exitLambdaLVTIList(JavaParser.LambdaLVTIListContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lambdaLVTIParameter}.
	 * @param ctx the parse tree
	 */
	void enterLambdaLVTIParameter(JavaParser.LambdaLVTIParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lambdaLVTIParameter}.
	 * @param ctx the parse tree
	 */
	void exitLambdaLVTIParameter(JavaParser.LambdaLVTIParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#arguments}.
	 * @param ctx the parse tree
	 */
	void enterArguments(JavaParser.ArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#arguments}.
	 * @param ctx the parse tree
	 */
	void exitArguments(JavaParser.ArgumentsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void enterTypeArguments(JavaParser.TypeArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void exitTypeArguments(JavaParser.TypeArgumentsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeArgument}.
	 * @param ctx the parse tree
	 */
	void enterTypeArgument(JavaParser.TypeArgumentContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeArgument}.
	 * @param ctx the parse tree
	 */
	void exitTypeArgument(JavaParser.TypeArgumentContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeArgumentsOrDiamond}.
	 * @param ctx the parse tree
	 */
	void enterTypeArgumentsOrDiamond(JavaParser.TypeArgumentsOrDiamondContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeArgumentsOrDiamond}.
	 * @param ctx the parse tree
	 */
	void exitTypeArgumentsOrDiamond(JavaParser.TypeArgumentsOrDiamondContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#nonWildcardTypeArguments}.
	 * @param ctx the parse tree
	 */
	void enterNonWildcardTypeArguments(JavaParser.NonWildcardTypeArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#nonWildcardTypeArguments}.
	 * @param ctx the parse tree
	 */
	void exitNonWildcardTypeArguments(JavaParser.NonWildcardTypeArgumentsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#nonWildcardTypeArgumentsOrDiamond}.
	 * @param ctx the parse tree
	 */
	void enterNonWildcardTypeArgumentsOrDiamond(JavaParser.NonWildcardTypeArgumentsOrDiamondContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#nonWildcardTypeArgumentsOrDiamond}.
	 * @param ctx the parse tree
	 */
	void exitNonWildcardTypeArgumentsOrDiamond(JavaParser.NonWildcardTypeArgumentsOrDiamondContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#superSuffix}.
	 * @param ctx the parse tree
	 */
	void enterSuperSuffix(JavaParser.SuperSuffixContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#superSuffix}.
	 * @param ctx the parse tree
	 */
	void exitSuperSuffix(JavaParser.SuperSuffixContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocation(JavaParser.ExplicitGenericInvocationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocation(JavaParser.ExplicitGenericInvocationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocationSuffix(JavaParser.ExplicitGenericInvocationSuffixContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocationSuffix(JavaParser.ExplicitGenericInvocationSuffixContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#createdName}.
	 * @param ctx the parse tree
	 */
	void enterCreatedName(JavaParser.CreatedNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#createdName}.
	 * @param ctx the parse tree
	 */
	void exitCreatedName(JavaParser.CreatedNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#qualifiedName}.
	 * @param ctx the parse tree
	 */
	void enterQualifiedName(JavaParser.QualifiedNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#qualifiedName}.
	 * @param ctx the parse tree
	 */
	void exitQualifiedName(JavaParser.QualifiedNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#altAnnotationQualifiedName}.
	 * @param ctx the parse tree
	 */
	void enterAltAnnotationQualifiedName(JavaParser.AltAnnotationQualifiedNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#altAnnotationQualifiedName}.
	 * @param ctx the parse tree
	 */
	void exitAltAnnotationQualifiedName(JavaParser.AltAnnotationQualifiedNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#qualifiedNameList}.
	 * @param ctx the parse tree
	 */
	void enterQualifiedNameList(JavaParser.QualifiedNameListContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#qualifiedNameList}.
	 * @param ctx the parse tree
	 */
	void exitQualifiedNameList(JavaParser.QualifiedNameListContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#enumConstants}.
	 * @param ctx the parse tree
	 */
	void enterEnumConstants(JavaParser.EnumConstantsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#enumConstants}.
	 * @param ctx the parse tree
	 */
	void exitEnumConstants(JavaParser.EnumConstantsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#enumConstant}.
	 * @param ctx the parse tree
	 */
	void enterEnumConstant(JavaParser.EnumConstantContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#enumConstant}.
	 * @param ctx the parse tree
	 */
	void exitEnumConstant(JavaParser.EnumConstantContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#pattern}.
	 * @param ctx the parse tree
	 */
	void enterPattern(JavaParser.PatternContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#pattern}.
	 * @param ctx the parse tree
	 */
	void exitPattern(JavaParser.PatternContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#elementValuePairs}.
	 * @param ctx the parse tree
	 */
	void enterElementValuePairs(JavaParser.ElementValuePairsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#elementValuePairs}.
	 * @param ctx the parse tree
	 */
	void exitElementValuePairs(JavaParser.ElementValuePairsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#elementValuePair}.
	 * @param ctx the parse tree
	 */
	void enterElementValuePair(JavaParser.ElementValuePairContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#elementValuePair}.
	 * @param ctx the parse tree
	 */
	void exitElementValuePair(JavaParser.ElementValuePairContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#elementValue}.
	 * @param ctx the parse tree
	 */
	void enterElementValue(JavaParser.ElementValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#elementValue}.
	 * @param ctx the parse tree
	 */
	void exitElementValue(JavaParser.ElementValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#defaultValue}.
	 * @param ctx the parse tree
	 */
	void enterDefaultValue(JavaParser.DefaultValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#defaultValue}.
	 * @param ctx the parse tree
	 */
	void exitDefaultValue(JavaParser.DefaultValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#compilationUnit}.
	 * @param ctx the parse tree
	 */
	void enterCompilationUnit(JavaParser.CompilationUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#compilationUnit}.
	 * @param ctx the parse tree
	 */
	void exitCompilationUnit(JavaParser.CompilationUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#variableDeclaratorId}.
	 * @param ctx the parse tree
	 */
	void enterVariableDeclaratorId(JavaParser.VariableDeclaratorIdContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#variableDeclaratorId}.
	 * @param ctx the parse tree
	 */
	void exitVariableDeclaratorId(JavaParser.VariableDeclaratorIdContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#variableInitializer}.
	 * @param ctx the parse tree
	 */
	void enterVariableInitializer(JavaParser.VariableInitializerContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#variableInitializer}.
	 * @param ctx the parse tree
	 */
	void exitVariableInitializer(JavaParser.VariableInitializerContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#arrayInitializer}.
	 * @param ctx the parse tree
	 */
	void enterArrayInitializer(JavaParser.ArrayInitializerContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#arrayInitializer}.
	 * @param ctx the parse tree
	 */
	void exitArrayInitializer(JavaParser.ArrayInitializerContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#elementValueArrayInitializer}.
	 * @param ctx the parse tree
	 */
	void enterElementValueArrayInitializer(JavaParser.ElementValueArrayInitializerContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#elementValueArrayInitializer}.
	 * @param ctx the parse tree
	 */
	void exitElementValueArrayInitializer(JavaParser.ElementValueArrayInitializerContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#variableDeclarator}.
	 * @param ctx the parse tree
	 */
	void enterVariableDeclarator(JavaParser.VariableDeclaratorContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#variableDeclarator}.
	 * @param ctx the parse tree
	 */
	void exitVariableDeclarator(JavaParser.VariableDeclaratorContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#variableDeclarators}.
	 * @param ctx the parse tree
	 */
	void enterVariableDeclarators(JavaParser.VariableDeclaratorsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#variableDeclarators}.
	 * @param ctx the parse tree
	 */
	void exitVariableDeclarators(JavaParser.VariableDeclaratorsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#localVariableDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#localVariableDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#localTypeDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterLocalTypeDeclaration(JavaParser.LocalTypeDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#localTypeDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitLocalTypeDeclaration(JavaParser.LocalTypeDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classBodyDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classBodyDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitClassBodyDeclaration(JavaParser.ClassBodyDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#compactConstructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterCompactConstructorDeclaration(JavaParser.CompactConstructorDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#compactConstructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitCompactConstructorDeclaration(JavaParser.CompactConstructorDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#recordDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterRecordDeclaration(JavaParser.RecordDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#recordDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitRecordDeclaration(JavaParser.RecordDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#recordHeader}.
	 * @param ctx the parse tree
	 */
	void enterRecordHeader(JavaParser.RecordHeaderContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#recordHeader}.
	 * @param ctx the parse tree
	 */
	void exitRecordHeader(JavaParser.RecordHeaderContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#packageDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterPackageDeclaration(JavaParser.PackageDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#packageDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitPackageDeclaration(JavaParser.PackageDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#importDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterImportDeclaration(JavaParser.ImportDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#importDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitImportDeclaration(JavaParser.ImportDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#typeDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterTypeDeclaration(JavaParser.TypeDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#typeDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitTypeDeclaration(JavaParser.TypeDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#methodDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterMethodDeclaration(JavaParser.MethodDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#methodDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitMethodDeclaration(JavaParser.MethodDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#genericMethodDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterGenericMethodDeclaration(JavaParser.GenericMethodDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#genericMethodDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitGenericMethodDeclaration(JavaParser.GenericMethodDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#fieldDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterFieldDeclaration(JavaParser.FieldDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#fieldDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitFieldDeclaration(JavaParser.FieldDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceBodyDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceBodyDeclaration(JavaParser.InterfaceBodyDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceBodyDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceBodyDeclaration(JavaParser.InterfaceBodyDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceMemberDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceMemberDeclaration(JavaParser.InterfaceMemberDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceMemberDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceMemberDeclaration(JavaParser.InterfaceMemberDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceDeclaration(JavaParser.InterfaceDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceDeclaration(JavaParser.InterfaceDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationTypeDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationTypeDeclaration(JavaParser.AnnotationTypeDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationTypeDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationTypeDeclaration(JavaParser.AnnotationTypeDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterClassDeclaration(JavaParser.ClassDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitClassDeclaration(JavaParser.ClassDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#moduleDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterModuleDeclaration(JavaParser.ModuleDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#moduleDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitModuleDeclaration(JavaParser.ModuleDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#enumDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterEnumDeclaration(JavaParser.EnumDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#enumDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitEnumDeclaration(JavaParser.EnumDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#enumBodyDeclarations}.
	 * @param ctx the parse tree
	 */
	void enterEnumBodyDeclarations(JavaParser.EnumBodyDeclarationsContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#enumBodyDeclarations}.
	 * @param ctx the parse tree
	 */
	void exitEnumBodyDeclarations(JavaParser.EnumBodyDeclarationsContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#genericInterfaceMethodDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterGenericInterfaceMethodDeclaration(JavaParser.GenericInterfaceMethodDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#genericInterfaceMethodDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitGenericInterfaceMethodDeclaration(JavaParser.GenericInterfaceMethodDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceCommonBodyDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceCommonBodyDeclaration(JavaParser.InterfaceCommonBodyDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceCommonBodyDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceCommonBodyDeclaration(JavaParser.InterfaceCommonBodyDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#constDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterConstDeclaration(JavaParser.ConstDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#constDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitConstDeclaration(JavaParser.ConstDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#constantDeclarator}.
	 * @param ctx the parse tree
	 */
	void enterConstantDeclarator(JavaParser.ConstantDeclaratorContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#constantDeclarator}.
	 * @param ctx the parse tree
	 */
	void exitConstantDeclarator(JavaParser.ConstantDeclaratorContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceMethodDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceMethodDeclaration(JavaParser.InterfaceMethodDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceMethodDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceMethodDeclaration(JavaParser.InterfaceMethodDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#constructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#constructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#genericConstructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterGenericConstructorDeclaration(JavaParser.GenericConstructorDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#genericConstructorDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitGenericConstructorDeclaration(JavaParser.GenericConstructorDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationTypeElementDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationTypeElementDeclaration(JavaParser.AnnotationTypeElementDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationTypeElementDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationTypeElementDeclaration(JavaParser.AnnotationTypeElementDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#memberDeclaration}.
	 * @param ctx the parse tree
	 */
	void enterMemberDeclaration(JavaParser.MemberDeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#memberDeclaration}.
	 * @param ctx the parse tree
	 */
	void exitMemberDeclaration(JavaParser.MemberDeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterPrimary(JavaParser.PrimaryContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitPrimary(JavaParser.PrimaryContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#innerCreator}.
	 * @param ctx the parse tree
	 */
	void enterInnerCreator(JavaParser.InnerCreatorContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#innerCreator}.
	 * @param ctx the parse tree
	 */
	void exitInnerCreator(JavaParser.InnerCreatorContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#creator}.
	 * @param ctx the parse tree
	 */
	void enterCreator(JavaParser.CreatorContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#creator}.
	 * @param ctx the parse tree
	 */
	void exitCreator(JavaParser.CreatorContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#arrayCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterArrayCreatorRest(JavaParser.ArrayCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#arrayCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitArrayCreatorRest(JavaParser.ArrayCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterClassCreatorRest(JavaParser.ClassCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitClassCreatorRest(JavaParser.ClassCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#methodCall}.
	 * @param ctx the parse tree
	 */
	void enterMethodCall(JavaParser.MethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#methodCall}.
	 * @param ctx the parse tree
	 */
	void exitMethodCall(JavaParser.MethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#memberReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void enterMemberReferenceSuffix(JavaParser.MemberReferenceSuffixContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#memberReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void exitMemberReferenceSuffix(JavaParser.MemberReferenceSuffixContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExpressionMethodReferenceSuffix}
	 * labeled alternative in {@link JavaParser#methodReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void enterExpressionMethodReferenceSuffix(JavaParser.ExpressionMethodReferenceSuffixContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExpressionMethodReferenceSuffix}
	 * labeled alternative in {@link JavaParser#methodReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void exitExpressionMethodReferenceSuffix(JavaParser.ExpressionMethodReferenceSuffixContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TypeMethodReferenceSuffix}
	 * labeled alternative in {@link JavaParser#methodReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void enterTypeMethodReferenceSuffix(JavaParser.TypeMethodReferenceSuffixContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TypeMethodReferenceSuffix}
	 * labeled alternative in {@link JavaParser#methodReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void exitTypeMethodReferenceSuffix(JavaParser.TypeMethodReferenceSuffixContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ClassMethodReferenceSuffix}
	 * labeled alternative in {@link JavaParser#methodReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void enterClassMethodReferenceSuffix(JavaParser.ClassMethodReferenceSuffixContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ClassMethodReferenceSuffix}
	 * labeled alternative in {@link JavaParser#methodReferenceSuffix}.
	 * @param ctx the parse tree
	 */
	void exitClassMethodReferenceSuffix(JavaParser.ClassMethodReferenceSuffixContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#switchExpression}.
	 * @param ctx the parse tree
	 */
	void enterSwitchExpression(JavaParser.SwitchExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#switchExpression}.
	 * @param ctx the parse tree
	 */
	void exitSwitchExpression(JavaParser.SwitchExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#parExpression}.
	 * @param ctx the parse tree
	 */
	void enterParExpression(JavaParser.ParExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#parExpression}.
	 * @param ctx the parse tree
	 */
	void exitParExpression(JavaParser.ParExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ObjectCreationExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterObjectCreationExpr(JavaParser.ObjectCreationExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ObjectCreationExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitObjectCreationExpr(JavaParser.ObjectCreationExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExpressionLambda}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterExpressionLambda(JavaParser.ExpressionLambdaContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExpressionLambda}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitExpressionLambda(JavaParser.ExpressionLambdaContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MethodReferenceExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMethodReferenceExpr(JavaParser.MethodReferenceExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MethodReferenceExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMethodReferenceExpr(JavaParser.MethodReferenceExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BinaryOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBinaryOperatorExpr(JavaParser.BinaryOperatorExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BinaryOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBinaryOperatorExpr(JavaParser.BinaryOperatorExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExpressionSwitch}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterExpressionSwitch(JavaParser.ExpressionSwitchContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExpressionSwitch}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitExpressionSwitch(JavaParser.ExpressionSwitchContext ctx);
	/**
	 * Enter a parse tree produced by the {@code BinaryOperatorExpression}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBinaryOperatorExpression(JavaParser.BinaryOperatorExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BinaryOperatorExpression}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBinaryOperatorExpression(JavaParser.BinaryOperatorExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PostIncrementDecrementOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPostIncrementDecrementOperatorExpr(JavaParser.PostIncrementDecrementOperatorExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PostIncrementDecrementOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPostIncrementDecrementOperatorExpr(JavaParser.PostIncrementDecrementOperatorExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MethodCallExpression}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMethodCallExpression(JavaParser.MethodCallExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MethodCallExpression}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMethodCallExpression(JavaParser.MethodCallExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code InstanceOfOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterInstanceOfOperatorExpr(JavaParser.InstanceOfOperatorExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InstanceOfOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitInstanceOfOperatorExpr(JavaParser.InstanceOfOperatorExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code MemberReferenceExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMemberReferenceExpr(JavaParser.MemberReferenceExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MemberReferenceExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMemberReferenceExpr(JavaParser.MemberReferenceExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code CastExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterCastExpr(JavaParser.CastExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code CastExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitCastExpr(JavaParser.CastExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPrimaryExpr(JavaParser.PrimaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PrimaryExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPrimaryExpr(JavaParser.PrimaryExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code UnaryOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterUnaryOperatorExpr(JavaParser.UnaryOperatorExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnaryOperatorExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitUnaryOperatorExpr(JavaParser.UnaryOperatorExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code SquareBracketExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterSquareBracketExpr(JavaParser.SquareBracketExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code SquareBracketExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitSquareBracketExpr(JavaParser.SquareBracketExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TernaryExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterTernaryExpr(JavaParser.TernaryExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TernaryExpr}
	 * labeled alternative in {@link JavaParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitTernaryExpr(JavaParser.TernaryExprContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#expressionList}.
	 * @param ctx the parse tree
	 */
	void enterExpressionList(JavaParser.ExpressionListContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#expressionList}.
	 * @param ctx the parse tree
	 */
	void exitExpressionList(JavaParser.ExpressionListContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lambdaExpression}.
	 * @param ctx the parse tree
	 */
	void enterLambdaExpression(JavaParser.LambdaExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lambdaExpression}.
	 * @param ctx the parse tree
	 */
	void exitLambdaExpression(JavaParser.LambdaExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterStatement(JavaParser.StatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitStatement(JavaParser.StatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#block}.
	 * @param ctx the parse tree
	 */
	void enterBlock(JavaParser.BlockContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#block}.
	 * @param ctx the parse tree
	 */
	void exitBlock(JavaParser.BlockContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#blockStatement}.
	 * @param ctx the parse tree
	 */
	void enterBlockStatement(JavaParser.BlockStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#blockStatement}.
	 * @param ctx the parse tree
	 */
	void exitBlockStatement(JavaParser.BlockStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationTypeBody}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationTypeBody(JavaParser.AnnotationTypeBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationTypeBody}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationTypeBody(JavaParser.AnnotationTypeBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#recordBody}.
	 * @param ctx the parse tree
	 */
	void enterRecordBody(JavaParser.RecordBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#recordBody}.
	 * @param ctx the parse tree
	 */
	void exitRecordBody(JavaParser.RecordBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#interfaceBody}.
	 * @param ctx the parse tree
	 */
	void enterInterfaceBody(JavaParser.InterfaceBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#interfaceBody}.
	 * @param ctx the parse tree
	 */
	void exitInterfaceBody(JavaParser.InterfaceBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#moduleBody}.
	 * @param ctx the parse tree
	 */
	void enterModuleBody(JavaParser.ModuleBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#moduleBody}.
	 * @param ctx the parse tree
	 */
	void exitModuleBody(JavaParser.ModuleBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#classBody}.
	 * @param ctx the parse tree
	 */
	void enterClassBody(JavaParser.ClassBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#classBody}.
	 * @param ctx the parse tree
	 */
	void exitClassBody(JavaParser.ClassBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#methodBody}.
	 * @param ctx the parse tree
	 */
	void enterMethodBody(JavaParser.MethodBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#methodBody}.
	 * @param ctx the parse tree
	 */
	void exitMethodBody(JavaParser.MethodBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#lambdaBody}.
	 * @param ctx the parse tree
	 */
	void enterLambdaBody(JavaParser.LambdaBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#lambdaBody}.
	 * @param ctx the parse tree
	 */
	void exitLambdaBody(JavaParser.LambdaBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#switchLabeledRule}.
	 * @param ctx the parse tree
	 */
	void enterSwitchLabeledRule(JavaParser.SwitchLabeledRuleContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#switchLabeledRule}.
	 * @param ctx the parse tree
	 */
	void exitSwitchLabeledRule(JavaParser.SwitchLabeledRuleContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#guardedPattern}.
	 * @param ctx the parse tree
	 */
	void enterGuardedPattern(JavaParser.GuardedPatternContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#guardedPattern}.
	 * @param ctx the parse tree
	 */
	void exitGuardedPattern(JavaParser.GuardedPatternContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#switchRuleOutcome}.
	 * @param ctx the parse tree
	 */
	void enterSwitchRuleOutcome(JavaParser.SwitchRuleOutcomeContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#switchRuleOutcome}.
	 * @param ctx the parse tree
	 */
	void exitSwitchRuleOutcome(JavaParser.SwitchRuleOutcomeContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#switchBlockStatementGroup}.
	 * @param ctx the parse tree
	 */
	void enterSwitchBlockStatementGroup(JavaParser.SwitchBlockStatementGroupContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#switchBlockStatementGroup}.
	 * @param ctx the parse tree
	 */
	void exitSwitchBlockStatementGroup(JavaParser.SwitchBlockStatementGroupContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#switchLabel}.
	 * @param ctx the parse tree
	 */
	void enterSwitchLabel(JavaParser.SwitchLabelContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#switchLabel}.
	 * @param ctx the parse tree
	 */
	void exitSwitchLabel(JavaParser.SwitchLabelContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#forControl}.
	 * @param ctx the parse tree
	 */
	void enterForControl(JavaParser.ForControlContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#forControl}.
	 * @param ctx the parse tree
	 */
	void exitForControl(JavaParser.ForControlContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#forInit}.
	 * @param ctx the parse tree
	 */
	void enterForInit(JavaParser.ForInitContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#forInit}.
	 * @param ctx the parse tree
	 */
	void exitForInit(JavaParser.ForInitContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#enhancedForControl}.
	 * @param ctx the parse tree
	 */
	void enterEnhancedForControl(JavaParser.EnhancedForControlContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#enhancedForControl}.
	 * @param ctx the parse tree
	 */
	void exitEnhancedForControl(JavaParser.EnhancedForControlContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#resourceSpecification}.
	 * @param ctx the parse tree
	 */
	void enterResourceSpecification(JavaParser.ResourceSpecificationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#resourceSpecification}.
	 * @param ctx the parse tree
	 */
	void exitResourceSpecification(JavaParser.ResourceSpecificationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#resources}.
	 * @param ctx the parse tree
	 */
	void enterResources(JavaParser.ResourcesContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#resources}.
	 * @param ctx the parse tree
	 */
	void exitResources(JavaParser.ResourcesContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#resource}.
	 * @param ctx the parse tree
	 */
	void enterResource(JavaParser.ResourceContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#resource}.
	 * @param ctx the parse tree
	 */
	void exitResource(JavaParser.ResourceContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#catchClause}.
	 * @param ctx the parse tree
	 */
	void enterCatchClause(JavaParser.CatchClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#catchClause}.
	 * @param ctx the parse tree
	 */
	void exitCatchClause(JavaParser.CatchClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#catchType}.
	 * @param ctx the parse tree
	 */
	void enterCatchType(JavaParser.CatchTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#catchType}.
	 * @param ctx the parse tree
	 */
	void exitCatchType(JavaParser.CatchTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#finallyBlock}.
	 * @param ctx the parse tree
	 */
	void enterFinallyBlock(JavaParser.FinallyBlockContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#finallyBlock}.
	 * @param ctx the parse tree
	 */
	void exitFinallyBlock(JavaParser.FinallyBlockContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotation}.
	 * @param ctx the parse tree
	 */
	void enterAnnotation(JavaParser.AnnotationContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotation}.
	 * @param ctx the parse tree
	 */
	void exitAnnotation(JavaParser.AnnotationContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationTypeElementRest}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationTypeElementRest(JavaParser.AnnotationTypeElementRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationTypeElementRest}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationTypeElementRest(JavaParser.AnnotationTypeElementRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationMethodOrConstantRest}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationMethodOrConstantRest(JavaParser.AnnotationMethodOrConstantRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationMethodOrConstantRest}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationMethodOrConstantRest(JavaParser.AnnotationMethodOrConstantRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationMethodRest}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationMethodRest(JavaParser.AnnotationMethodRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationMethodRest}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationMethodRest(JavaParser.AnnotationMethodRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#annotationConstantRest}.
	 * @param ctx the parse tree
	 */
	void enterAnnotationConstantRest(JavaParser.AnnotationConstantRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#annotationConstantRest}.
	 * @param ctx the parse tree
	 */
	void exitAnnotationConstantRest(JavaParser.AnnotationConstantRestContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExecutableLambdaExpr}
	 * labeled alternative in {@link JavaParser#executable}.
	 * @param ctx the parse tree
	 */
	void enterExecutableLambdaExpr(JavaParser.ExecutableLambdaExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExecutableLambdaExpr}
	 * labeled alternative in {@link JavaParser#executable}.
	 * @param ctx the parse tree
	 */
	void exitExecutableLambdaExpr(JavaParser.ExecutableLambdaExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExecutableMethodReferenceExpr}
	 * labeled alternative in {@link JavaParser#executable}.
	 * @param ctx the parse tree
	 */
	void enterExecutableMethodReferenceExpr(JavaParser.ExecutableMethodReferenceExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExecutableMethodReferenceExpr}
	 * labeled alternative in {@link JavaParser#executable}.
	 * @param ctx the parse tree
	 */
	void exitExecutableMethodReferenceExpr(JavaParser.ExecutableMethodReferenceExprContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExecutableCreatorRestExpr}
	 * labeled alternative in {@link JavaParser#executable}.
	 * @param ctx the parse tree
	 */
	void enterExecutableCreatorRestExpr(JavaParser.ExecutableCreatorRestExprContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExecutableCreatorRestExpr}
	 * labeled alternative in {@link JavaParser#executable}.
	 * @param ctx the parse tree
	 */
	void exitExecutableCreatorRestExpr(JavaParser.ExecutableCreatorRestExprContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#supplierExpression}.
	 * @param ctx the parse tree
	 */
	void enterSupplierExpression(JavaParser.SupplierExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#supplierExpression}.
	 * @param ctx the parse tree
	 */
	void exitSupplierExpression(JavaParser.SupplierExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#booleanSupplierExpression}.
	 * @param ctx the parse tree
	 */
	void enterBooleanSupplierExpression(JavaParser.BooleanSupplierExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#booleanSupplierExpression}.
	 * @param ctx the parse tree
	 */
	void exitBooleanSupplierExpression(JavaParser.BooleanSupplierExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#throwingRunnableExpression}.
	 * @param ctx the parse tree
	 */
	void enterThrowingRunnableExpression(JavaParser.ThrowingRunnableExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#throwingRunnableExpression}.
	 * @param ctx the parse tree
	 */
	void exitThrowingRunnableExpression(JavaParser.ThrowingRunnableExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#throwingSupplierExpression}.
	 * @param ctx the parse tree
	 */
	void enterThrowingSupplierExpression(JavaParser.ThrowingSupplierExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#throwingSupplierExpression}.
	 * @param ctx the parse tree
	 */
	void exitThrowingSupplierExpression(JavaParser.ThrowingSupplierExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#booleanSupplierCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterBooleanSupplierCreatorRest(JavaParser.BooleanSupplierCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#booleanSupplierCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitBooleanSupplierCreatorRest(JavaParser.BooleanSupplierCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#executableCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterExecutableCreatorRest(JavaParser.ExecutableCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#executableCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitExecutableCreatorRest(JavaParser.ExecutableCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#supplierCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterSupplierCreatorRest(JavaParser.SupplierCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#supplierCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitSupplierCreatorRest(JavaParser.SupplierCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#throwingRunnableCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterThrowingRunnableCreatorRest(JavaParser.ThrowingRunnableCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#throwingRunnableCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitThrowingRunnableCreatorRest(JavaParser.ThrowingRunnableCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#throwingSupplierCreatorRest}.
	 * @param ctx the parse tree
	 */
	void enterThrowingSupplierCreatorRest(JavaParser.ThrowingSupplierCreatorRestContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#throwingSupplierCreatorRest}.
	 * @param ctx the parse tree
	 */
	void exitThrowingSupplierCreatorRest(JavaParser.ThrowingSupplierCreatorRestContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertAllMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertAllMethodCall(JavaParser.AssertAllMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertAllMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertAllMethodCall(JavaParser.AssertAllMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertArrayEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertArrayEqualsMethodCall(JavaParser.AssertArrayEqualsMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertArrayEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertArrayEqualsMethodCall(JavaParser.AssertArrayEqualsMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertEqualsMethodCall(JavaParser.AssertEqualsMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertEqualsMethodCall(JavaParser.AssertEqualsMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertFalseMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertFalseMethodCall(JavaParser.AssertFalseMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertFalseMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertFalseMethodCall(JavaParser.AssertFalseMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertIterableEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertIterableEqualsMethodCall(JavaParser.AssertIterableEqualsMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertIterableEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertIterableEqualsMethodCall(JavaParser.AssertIterableEqualsMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertLinesMatchMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertLinesMatchMethodCall(JavaParser.AssertLinesMatchMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertLinesMatchMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertLinesMatchMethodCall(JavaParser.AssertLinesMatchMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertNotEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertNotEqualsMethodCall(JavaParser.AssertNotEqualsMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertNotEqualsMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertNotEqualsMethodCall(JavaParser.AssertNotEqualsMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertNotNullMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertNotNullMethodCall(JavaParser.AssertNotNullMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertNotNullMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertNotNullMethodCall(JavaParser.AssertNotNullMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertNotSameMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertNotSameMethodCall(JavaParser.AssertNotSameMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertNotSameMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertNotSameMethodCall(JavaParser.AssertNotSameMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertNullMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertNullMethodCall(JavaParser.AssertNullMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertNullMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertNullMethodCall(JavaParser.AssertNullMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertSameMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertSameMethodCall(JavaParser.AssertSameMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertSameMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertSameMethodCall(JavaParser.AssertSameMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertThrowsMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertThrowsMethodCall(JavaParser.AssertThrowsMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertThrowsMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertThrowsMethodCall(JavaParser.AssertThrowsMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertTimeoutMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertTimeoutMethodCall(JavaParser.AssertTimeoutMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertTimeoutMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertTimeoutMethodCall(JavaParser.AssertTimeoutMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertTimeoutPreemptivelyMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertTimeoutPreemptivelyMethodCall(JavaParser.AssertTimeoutPreemptivelyMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertTimeoutPreemptivelyMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertTimeoutPreemptivelyMethodCall(JavaParser.AssertTimeoutPreemptivelyMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#assertTrueMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterAssertTrueMethodCall(JavaParser.AssertTrueMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#assertTrueMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitAssertTrueMethodCall(JavaParser.AssertTrueMethodCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link JavaParser#failMethodCall}.
	 * @param ctx the parse tree
	 */
	void enterFailMethodCall(JavaParser.FailMethodCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link JavaParser#failMethodCall}.
	 * @param ctx the parse tree
	 */
	void exitFailMethodCall(JavaParser.FailMethodCallContext ctx);
}