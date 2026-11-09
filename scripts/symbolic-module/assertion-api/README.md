# Assertion API

The sources of the assertion libraries that the model generates, besides JUnit (whose assertions are in the grammar).
The symbol solver gets them with the sources of the JDK (`JavaUtils.initializeJavaClassPath`), for every repository,
so that their classes and methods (the matchers of Hamcrest) are candidates of the tokens stage. They are part of the
module, not dependencies of the projects: the dependencies of a project are not given to the solver (step 8 of
`docs/resume.md`).

| Folder | Library | Origin | SHA-1 of the archive | License |
|---|---|---|---|---|
| `hamcrest-2.2` | Hamcrest 2.2 (`org.hamcrest:hamcrest`: `MatcherAssert`, `Matchers`, `CoreMatchers`, ...; the API of `hamcrest-core` and `hamcrest-library` 1.3 too) | the `.java` files of `https://repo1.maven.org/maven2/org/hamcrest/hamcrest/2.2/hamcrest-2.2-sources.jar` | `a0a13cfc629420efb587d954f982c4c6a100da25` | BSD 3-Clause (`hamcrest-2.2/LICENSE.txt`) |

Not in `lib/`: the scripts of the datasets empty `lib/` when the sources of a JDK are missing.
