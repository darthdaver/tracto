package star.tracto.utils.javaParser;

import java.util.HashSet;
import java.util.Set;

/**
 * Allocates the names of the variables (or of the labels) generated within a method, so that they do not clash with
 * the names already in use. A name in use is either <strong>declared</strong> (a variable or a parameter of the method,
 * or a name allocated) or <strong>reserved</strong> (any other name a generated name must not hide, like the name of a
 * field read by the method). A name in use gets the first free numeric suffix, starting from 2: {@code count},
 * {@code count2}, {@code count3}, ...
 */
public final class NameAllocator {

    /* The names of the variables declared within the method, including the names allocated */
    private final Set<String> declaredNames = new HashSet<>();
    /* The other names in use */
    private final Set<String> reservedNames = new HashSet<>();

    /**
     * Records the name of a variable declared within the method.
     *
     * @param name the name of the variable
     */
    public void declare(String name) {
        declaredNames.add(name);
    }

    /**
     * Records a name in use that is not declared within the method (for example the name of a field).
     *
     * @param name the name in use
     */
    public void reserve(String name) {
        reservedNames.add(name);
    }

    /**
     * Checks if a name is declared within the method.
     *
     * @param name the name to check
     * @return {@code true} if a variable of the method has the given name, or the name was allocated
     */
    public boolean isDeclared(String name) {
        return declaredNames.contains(name);
    }

    /**
     * Checks if a name is in use (declared or reserved).
     *
     * @param name the name to check
     * @return {@code true} if the name is in use
     */
    public boolean isUsed(String name) {
        return declaredNames.contains(name) || reservedNames.contains(name);
    }

    /**
     * Allocates a name for a new variable declared within the method: the base name if it is not in use, otherwise
     * the base name with the first free numeric suffix, starting from 2. The allocated name is declared.
     *
     * @param baseName the preferred name
     * @return the allocated name
     */
    public String allocate(String baseName) {
        String name = baseName;
        int suffix = 2;
        while (isUsed(name)) {
            name = baseName + suffix++;
        }
        declare(name);
        return name;
    }
}
