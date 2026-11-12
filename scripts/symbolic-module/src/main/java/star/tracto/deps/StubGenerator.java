package star.tracto.deps;

import javassist.bytecode.AccessFlag;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Descriptor;
import javassist.bytecode.ExceptionsAttribute;
import javassist.bytecode.FieldInfo;
import javassist.bytecode.InnerClassesAttribute;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.MethodParametersAttribute;
import javassist.bytecode.SignatureAttribute;

import javax.lang.model.SourceVersion;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Stubs of the classes of a jar without sources (Step 38): one {@code .java} file per top-level class, with its member
 * classes nested, its generic signatures, its fields, its methods and constructors with the parameter names of the
 * debug information, and empty bodies. A library without sources then looks like a library with sources to every path
 * of the module (the symbol solver, the lookups of a class by name, the fields read from the syntax tree): the binary
 * jar alone is resolved by the solver, but its classes are not found by name (their names are not offered).
 * <p>
 * What is left out: the private members (never accessible from a test class of another class, Step 37), the synthetic
 * and bridge members and the members whose name has a {@code $}, the anonymous and local classes, the implicit members of the enums ({@code values},
 * {@code valueOf}), the classes of the multi-release folders, the names that are not Java identifiers (classes of other
 * JVM languages). The types are written fully qualified (no imports). Deterministic: the same jar gives the same files.
 */
public final class StubGenerator {

    /** The first line of every stub: the provenance of the file. */
    public static final String MARKER = "// tracto:stub";

    private final Map<String, ClassFile> classes = new TreeMap<>();
    private final String origin;

    private StubGenerator(String origin) {
        this.origin = origin;
    }

    /**
     * Writes the stubs of the classes of a jar into a folder (in the layout of the packages).
     *
     * @return the number of files written
     */
    public static int generate(Path jar, Path outputDir) throws IOException {
        StubGenerator generator = new StubGenerator(jar.getFileName().toString());
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (JarEntry entry : jarFile.stream().sorted((a, b) -> a.getName().compareTo(b.getName())).toList()) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")
                        || name.endsWith("package-info.class")) {
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    ClassFile classFile = new ClassFile(new DataInputStream(new ByteArrayInputStream(in.readAllBytes())));
                    generator.classes.put(classFile.getName(), classFile);
                } catch (IOException | RuntimeException e) {
                    // An unreadable class file: the others are still written
                }
            }
        }
        return generator.write(outputDir);
    }

    /** Writes the stubs of the classes read from class files (for tests). */
    static Map<String, String> stubs(String origin, List<byte[]> classFiles) throws IOException {
        StubGenerator generator = new StubGenerator(origin);
        for (byte[] bytes : classFiles) {
            ClassFile classFile = new ClassFile(new DataInputStream(new ByteArrayInputStream(bytes)));
            generator.classes.put(classFile.getName(), classFile);
        }
        Map<String, String> stubs = new TreeMap<>();
        for (ClassFile classFile : generator.classes.values()) {
            if (generator.isTopLevel(classFile)) {
                generator.stub(classFile).ifPresent(source -> stubs.put(classFile.getName(), source));
            }
        }
        return stubs;
    }

    private int write(Path outputDir) throws IOException {
        int written = 0;
        for (ClassFile classFile : classes.values()) {
            if (!isTopLevel(classFile)) {
                continue;
            }
            Optional<String> source = stub(classFile);
            if (source.isEmpty()) {
                continue;
            }
            Path file = outputDir.resolve(classFile.getName().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.get(), StandardCharsets.UTF_8);
            written++;
        }
        return written;
    }

    // ------------------------------------------------------------------------------------------------- Classes

    /** A class is top level when its own InnerClasses attribute does not describe it as nested, local or anonymous. */
    private boolean isTopLevel(ClassFile classFile) {
        InnerClassesAttribute inner = (InnerClassesAttribute) classFile.getAttribute(InnerClassesAttribute.tag);
        if (inner != null) {
            for (int i = 0; i < inner.tableLength(); i++) {
                if (classFile.getName().equals(inner.innerClass(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private Optional<String> stub(ClassFile classFile) {
        String simpleName = simpleName(classFile.getName());
        if (!SourceVersion.isName(simpleName) || isSynthetic(classFile.getAccessFlags())) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        out.append(MARKER).append(' ').append(origin).append('\n');
        String packageName = packageOf(classFile.getName());
        if (!packageName.isEmpty()) {
            if (!Arrays.stream(packageName.split("\\.")).allMatch(SourceVersion::isName)) {
                return Optional.empty();
            }
            out.append("package ").append(packageName).append(";\n\n");
        }
        appendClass(out, classFile, classFile.getAccessFlags(), simpleName, "");
        return Optional.of(out.toString());
    }

    private void appendClass(StringBuilder out, ClassFile classFile, int accessFlags, String simpleName, String indent) {
        boolean isInterface = (accessFlags & AccessFlag.INTERFACE) != 0;
        boolean isAnnotation = (accessFlags & AccessFlag.ANNOTATION) != 0;
        boolean isEnum = (accessFlags & AccessFlag.ENUM) != 0;
        out.append(indent).append(visibility(accessFlags));
        if ((accessFlags & AccessFlag.STATIC) != 0 && !indent.isEmpty() && !isInterface && !isEnum) {
            out.append("static ");
        }
        if ((accessFlags & AccessFlag.ABSTRACT) != 0 && !isInterface) {
            out.append("abstract ");
        }
        if ((accessFlags & AccessFlag.FINAL) != 0 && !isEnum) {
            out.append("final ");
        }
        out.append(isAnnotation ? "@interface " : isInterface ? "interface " : isEnum ? "enum " : "class ").append(simpleName);
        SignatureAttribute.ClassSignature signature = classSignature(classFile);
        if (signature != null && !isAnnotation) {
            out.append(typeParameters(signature.getParameters()));
            SignatureAttribute.ClassType superClass = signature.getSuperClass();
            List<String> interfaces = Arrays.stream(signature.getInterfaces()).map(StubGenerator::type).toList();
            if (isInterface) {
                if (!interfaces.isEmpty()) {
                    out.append(" extends ").append(String.join(", ", interfaces));
                }
            } else {
                if (!isEnum && superClass != null && !"java.lang.Object".equals(superClass.getName())) {
                    out.append(" extends ").append(type(superClass));
                }
                if (!interfaces.isEmpty()) {
                    out.append(" implements ").append(String.join(", ", interfaces));
                }
            }
        }
        out.append(" {\n");
        String memberIndent = indent + "    ";
        if (isEnum) {
            List<String> constants = new ArrayList<>();
            for (FieldInfo field : fields(classFile)) {
                if ((field.getAccessFlags() & AccessFlag.ENUM) != 0 && SourceVersion.isName(field.getName())) {
                    constants.add(field.getName());
                }
            }
            out.append(memberIndent).append(String.join(", ", constants)).append(";\n");
        }
        for (FieldInfo field : fields(classFile)) {
            appendField(out, field, isInterface, isEnum, memberIndent);
        }
        for (MethodInfo method : methods(classFile)) {
            appendMethod(out, classFile, method, simpleName, isInterface, isAnnotation, isEnum, memberIndent);
        }
        appendMemberClasses(out, classFile, memberIndent);
        out.append(indent).append("}\n");
    }

    private void appendMemberClasses(StringBuilder out, ClassFile outer, String indent) {
        InnerClassesAttribute inner = (InnerClassesAttribute) outer.getAttribute(InnerClassesAttribute.tag);
        if (inner == null) {
            return;
        }
        Map<String, Integer> members = new TreeMap<>();
        for (int i = 0; i < inner.tableLength(); i++) {
            if (outer.getName().equals(inner.outerClass(i)) && inner.innerName(i) != null) {
                members.put(inner.innerClass(i), i);
            }
        }
        for (Map.Entry<String, Integer> member : members.entrySet()) {
            ClassFile classFile = classes.get(member.getKey());
            int accessFlags = inner.accessFlags(member.getValue());
            String simpleName = inner.innerName(member.getValue());
            if (classFile == null || (accessFlags & AccessFlag.PRIVATE) != 0 || isSynthetic(accessFlags)
                    || !SourceVersion.isName(simpleName)) {
                continue;
            }
            out.append('\n');
            appendClass(out, classFile, accessFlags, simpleName, indent);
        }
    }

    // ------------------------------------------------------------------------------------------------- Members

    private void appendField(StringBuilder out, FieldInfo field, boolean inInterface, boolean inEnum, String indent) {
        int flags = field.getAccessFlags();
        if ((flags & AccessFlag.PRIVATE) != 0 || isSynthetic(flags) || (flags & AccessFlag.ENUM) != 0
                || !isSourceName(field.getName())) {
            return;
        }
        String fieldType;
        try {
            SignatureAttribute signature = (SignatureAttribute) field.getAttribute(SignatureAttribute.tag);
            fieldType = type(SignatureAttribute.toTypeSignature(signature != null ? signature.getSignature() : field.getDescriptor()));
        } catch (BadBytecode | RuntimeException e) {
            return;
        }
        out.append(indent);
        if (!inInterface) {
            out.append(visibility(flags));
            if ((flags & AccessFlag.STATIC) != 0) {
                out.append("static ");
            }
            if ((flags & AccessFlag.FINAL) != 0) {
                out.append("final ");
            }
        }
        out.append(fieldType).append(' ').append(field.getName()).append(";\n");
    }

    private void appendMethod(StringBuilder out, ClassFile owner, MethodInfo method, String className, boolean inInterface,
                              boolean inAnnotation, boolean inEnum, String indent) {
        int flags = method.getAccessFlags();
        String name = method.getName();
        boolean constructor = MethodInfo.nameInit.equals(name);
        if ((flags & AccessFlag.PRIVATE) != 0 || isSynthetic(flags) || (flags & AccessFlag.BRIDGE) != 0
                || MethodInfo.nameClinit.equals(name) || (!constructor && !isSourceName(name))) {
            return;
        }
        if (inEnum && (constructor || isImplicitEnumMethod(owner, method))) {
            return;
        }
        SignatureAttribute.MethodSignature signature;
        SignatureAttribute.MethodSignature erased;
        try {
            erased = SignatureAttribute.toMethodSignature(method.getDescriptor());
            SignatureAttribute generic = (SignatureAttribute) method.getAttribute(SignatureAttribute.tag);
            signature = generic != null ? SignatureAttribute.toMethodSignature(generic.getSignature()) : erased;
        } catch (BadBytecode | RuntimeException e) {
            return;
        }
        SignatureAttribute.Type[] parameterTypes = signature.getParameterTypes();
        // A constructor of an inner (non-static) class has the outer instance as a first, synthetic parameter in its
        // descriptor; the generic signature, when present, does not
        if (constructor && signature == erased && isInnerInstanceClass(owner) && parameterTypes.length > 0) {
            parameterTypes = Arrays.copyOfRange(parameterTypes, 1, parameterTypes.length);
        }
        List<String> names = parameterNames(method, erased.getParameterTypes(), parameterTypes.length);
        boolean isAbstract = (flags & AccessFlag.ABSTRACT) != 0;
        boolean isStatic = (flags & AccessFlag.STATIC) != 0;
        out.append(indent);
        if (!inInterface) {
            out.append(visibility(flags));
        } else if (!isAbstract && !isStatic) {
            out.append("default ");
        }
        if (isStatic) {
            out.append("static ");
        }
        if (isAbstract && !inInterface) {
            out.append("abstract ");
        }
        if ((flags & AccessFlag.FINAL) != 0 && !inInterface) {
            out.append("final ");
        }
        String typeParameters = typeParameters(signature.getTypeParameters());
        if (!typeParameters.isEmpty()) {
            out.append(typeParameters).append(' ');
        }
        out.append(constructor ? className : type(signature.getReturnType()) + " " + name).append('(');
        boolean varargs = (flags & AccessFlag.VARARGS) != 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            String parameterType = type(parameterTypes[i]);
            if (varargs && i == parameterTypes.length - 1 && parameterType.endsWith("[]")) {
                parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
            }
            out.append(parameterType).append(' ').append(names.get(i));
        }
        out.append(')');
        List<String> exceptions = exceptions(method, signature);
        if (!exceptions.isEmpty()) {
            out.append(" throws ").append(String.join(", ", exceptions));
        }
        out.append(isAbstract || inAnnotation ? ";\n" : " {}\n");
    }

    private static boolean isImplicitEnumMethod(ClassFile owner, MethodInfo method) {
        String descriptor = method.getDescriptor();
        return ("values".equals(method.getName()) && descriptor.equals("()[L" + owner.getName().replace('.', '/') + ";"))
                || ("valueOf".equals(method.getName()) && descriptor.equals("(Ljava/lang/String;)L" + owner.getName().replace('.', '/') + ";"));
    }

    private boolean isInnerInstanceClass(ClassFile classFile) {
        InnerClassesAttribute inner = (InnerClassesAttribute) classFile.getAttribute(InnerClassesAttribute.tag);
        if (inner != null) {
            for (int i = 0; i < inner.tableLength(); i++) {
                if (classFile.getName().equals(inner.innerClass(i))) {
                    return (inner.accessFlags(i) & AccessFlag.STATIC) == 0 && (inner.accessFlags(i) & AccessFlag.INTERFACE) == 0
                            && inner.outerClass(i) != null;
                }
            }
        }
        return false;
    }

    /**
     * The names of the parameters: from the MethodParameters attribute, else from the local variables of the debug
     * information (by slot), else {@code arg0}, {@code arg1}, ... The descriptor may have leading synthetic parameters
     * the signature has not: the names are aligned on the last parameters.
     */
    private static List<String> parameterNames(MethodInfo method, SignatureAttribute.Type[] descriptorTypes, int count) {
        String[] names = new String[descriptorTypes.length];
        ConstPool constPool = method.getConstPool();
        // The local variables of the debug information (by slot)
        CodeAttribute code = method.getCodeAttribute();
        LocalVariableAttribute locals = code == null ? null : (LocalVariableAttribute) code.getAttribute(LocalVariableAttribute.tag);
        if (locals != null) {
            Map<Integer, String> bySlot = new TreeMap<>();
            for (int i = 0; i < locals.tableLength(); i++) {
                if (locals.startPc(i) == 0) {
                    bySlot.putIfAbsent(locals.index(i), locals.variableName(i));
                }
            }
            int slot = (method.getAccessFlags() & AccessFlag.STATIC) != 0 ? 0 : 1;
            for (int i = 0; i < descriptorTypes.length; i++) {
                names[i] = bySlot.get(slot);
                String descriptor = descriptorTypes[i] instanceof SignatureAttribute.BaseType base
                        ? String.valueOf(base.getDescriptor()) : "L";
                slot += descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
            }
        }
        // The MethodParameters attribute, for the parameters it names (javac writes it without names for the implicit
        // parameters of the constructors of inner classes)
        MethodParametersAttribute parameters = (MethodParametersAttribute) method.getAttribute(MethodParametersAttribute.tag);
        if (parameters != null && parameters.size() == descriptorTypes.length) {
            for (int i = 0; i < parameters.size(); i++) {
                int nameIndex = parameters.name(i);
                if (nameIndex != 0) {
                    names[i] = constPool.getUtf8Info(nameIndex);
                }
            }
        }
        List<String> result = new ArrayList<>();
        int offset = descriptorTypes.length - count;
        for (int i = 0; i < count; i++) {
            String name = offset + i >= 0 && offset + i < names.length ? names[offset + i] : null;
            result.add(name != null && SourceVersion.isName(name) && !result.contains(name) ? name : "arg" + i);
        }
        return result;
    }

    private static List<String> exceptions(MethodInfo method, SignatureAttribute.MethodSignature signature) {
        List<String> exceptions = new ArrayList<>();
        for (SignatureAttribute.ObjectType exception : signature.getExceptionTypes()) {
            exceptions.add(type(exception));
        }
        if (exceptions.isEmpty()) {
            ExceptionsAttribute attribute = method.getExceptionsAttribute();
            if (attribute != null && attribute.getExceptions() != null) {
                for (String exception : attribute.getExceptions()) {
                    exceptions.add(className(exception));
                }
            }
        }
        return exceptions;
    }

    // --------------------------------------------------------------------------------------------------- Types

    private static SignatureAttribute.ClassSignature classSignature(ClassFile classFile) {
        try {
            SignatureAttribute signature = (SignatureAttribute) classFile.getAttribute(SignatureAttribute.tag);
            if (signature != null) {
                return SignatureAttribute.toClassSignature(signature.getSignature());
            }
            String superClass = classFile.getSuperclass();
            StringBuilder descriptor = new StringBuilder();
            descriptor.append(superClass == null ? "Ljava/lang/Object;" : Descriptor.of(superClass));
            for (String name : classFile.getInterfaces()) {
                descriptor.append(Descriptor.of(name));
            }
            return SignatureAttribute.toClassSignature(descriptor.toString());
        } catch (BadBytecode | RuntimeException e) {
            return null;
        }
    }

    private static String typeParameters(SignatureAttribute.TypeParameter[] parameters) {
        if (parameters == null || parameters.length == 0) {
            return "";
        }
        List<String> rendered = new ArrayList<>();
        for (SignatureAttribute.TypeParameter parameter : parameters) {
            List<String> bounds = new ArrayList<>();
            if (parameter.getClassBound() != null && !"java.lang.Object".equals(type(parameter.getClassBound()))) {
                bounds.add(type(parameter.getClassBound()));
            }
            for (SignatureAttribute.ObjectType bound : parameter.getInterfaceBound()) {
                bounds.add(type(bound));
            }
            rendered.add(parameter.getName() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds)));
        }
        return "<" + String.join(", ", rendered) + ">";
    }

    /** A type in Java syntax, fully qualified, with its type arguments. */
    static String type(SignatureAttribute.Type type) {
        if (type instanceof SignatureAttribute.BaseType base) {
            return base.toString();
        }
        if (type instanceof SignatureAttribute.ArrayType array) {
            return type(array.getComponentType()) + "[]".repeat(array.getDimension());
        }
        if (type instanceof SignatureAttribute.TypeVariable variable) {
            return variable.getName();
        }
        if (type instanceof SignatureAttribute.ClassType classType) {
            StringBuilder out = new StringBuilder();
            SignatureAttribute.ClassType declaring = classType.getDeclaringClass();
            out.append(declaring != null ? type(declaring) + "." + classType.getName() : className(classType.getName()));
            SignatureAttribute.TypeArgument[] arguments = classType.getTypeArguments();
            if (arguments != null && arguments.length > 0) {
                List<String> rendered = new ArrayList<>();
                for (SignatureAttribute.TypeArgument argument : arguments) {
                    rendered.add(switch (argument.getKind()) {
                        case '*' -> "?";
                        case '+' -> "? extends " + type(argument.getType());
                        case '-' -> "? super " + type(argument.getType());
                        default -> type(argument.getType());
                    });
                }
                out.append('<').append(String.join(", ", rendered)).append('>');
            }
            return out.toString();
        }
        return type.toString();
    }

    /** A binary class name in Java syntax: the {@code $} of the nested classes become dots ({@code Map$Entry}). */
    static String className(String binaryName) {
        String name = binaryName.replace('/', '.');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean nested = c == '$' && i > 0 && i + 1 < name.length() && Character.isJavaIdentifierStart(name.charAt(i + 1))
                    && name.charAt(i - 1) != '.';
            out.append(nested ? '.' : c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------- Helpers

    @SuppressWarnings("unchecked")
    private static List<FieldInfo> fields(ClassFile classFile) {
        return (List<FieldInfo>) classFile.getFields();
    }

    @SuppressWarnings("unchecked")
    private static List<MethodInfo> methods(ClassFile classFile) {
        return (List<MethodInfo>) classFile.getMethods();
    }

    /**
     * A member name that can come from Java source: an identifier without {@code $} (the compilers name their
     * generated members with it, and before Java 5 did not always flag them synthetic: {@code class$0}).
     */
    private static boolean isSourceName(String name) {
        return SourceVersion.isName(name) && name.indexOf('$') < 0;
    }

    private static boolean isSynthetic(int accessFlags) {
        return (accessFlags & AccessFlag.SYNTHETIC) != 0;
    }

    private static String visibility(int accessFlags) {
        if ((accessFlags & AccessFlag.PUBLIC) != 0) {
            return "public ";
        }
        if ((accessFlags & AccessFlag.PROTECTED) != 0) {
            return "protected ";
        }
        if ((accessFlags & AccessFlag.PRIVATE) != 0) {
            return "private ";
        }
        return "";
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /** {@code java -jar tracto.jar stubs <jar> <output folder>}: prints the number of files written. */
    public static void main(String[] args) throws IOException {
        System.out.println(generate(Path.of(args[0]), Path.of(args[1])));
    }
}
