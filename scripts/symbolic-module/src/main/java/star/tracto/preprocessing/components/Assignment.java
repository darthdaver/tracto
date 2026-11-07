package star.tracto.preprocessing.components;

import java.util.List;

/**
 * The declaration of the variable assigned with the result of a target assertion, as written in the test
 * ({@code final Exception e = assertThrows(...)}): the model generates the assertion only, the symbolic module adds the
 * declaration back at the end of the generation (Step 27).
 *
 * @param modifiers the annotations and the modifiers of the declaration, in their order ({@code final})
 * @param type the declared type, copied from the test (it can differ from the type of the assertion result)
 * @param name the name of the variable
 */
public record Assignment(List<String> modifiers, String type, String name) {

    /**
     * The statement that assigns the variable with an assertion: the declaration joined with the text of the
     * assertion as it is (the assertion is not reprinted).
     *
     * @param assertion the assertion statement, ending with {@code ;}
     * @return the declaration statement {@code <modifiers> <type> <name> = <assertion>}
     */
    public String declare(String assertion) {
        StringBuilder statement = new StringBuilder();
        for (String modifier : modifiers) {
            statement.append(modifier).append(' ');
        }
        return statement.append(type).append(' ').append(name).append(" = ").append(assertion).toString();
    }
}
