# Contextual information for the next-token choice

**Status:** proposal for review (2026-11-21). To be settled **before** the large generation run (`tools/gen/generate.py`).

## Goal

At every step of the oracle generation the model chooses the next token among the candidates computed by the symbolic
module (`nextPossibleProjectTokens`, `nextPossibleJavaTokens`, `nextPossibleGenericTokens`). The prompt must give it
what it needs to choose well, and nothing that distracts it or makes the prompt long:

- **static information**: the same for every step of a test prefix (the prefix itself, the focal methods it calls, the
  test class, the focal class);
- **dynamic information**: depends on the state of the partial oracle (after `assertTrue(a.`: what the members of the
  type of `a` are and what they return).

Which information helps and which is noise is decided **by experiments** (below), not up front.

## Principle: store everything once, choose the prompt later

Generating the datasets takes weeks; assembling prompts from them takes minutes. The dataset therefore stores a
**superset** of the information, compactly, and every prompt variant is assembled from it without generating again:

- one **symbol table** per file: every class, method, field, constructor appearing in the information of its datapoints,
  stored once, with all its levels of detail (below), referenced by an id;
- per step: the **state** of the partial oracle and the **ids** of the symbols the dynamic information refers to.

Today the dynamic information (`contextualInfos`) is stored in full at every step: 99.5% of the tokens dataset (uuid:
2,818 of 2,832 MB), about 95% of it copies (steve: 9.5 MB distinct in 184 MB), and the prompt scripts do not read it.
The symbol table removes the copies without dropping anything. A converter to the current format keeps the prompt
scripts working.

## Levels of detail

Every symbol can be given at increasing levels:

| Level | Method | Field | Class |
|---|---|---|---|
| 1 name | `getValue` | `depth` | `Element` |
| 2 signature | `public String getValue()` | `int depth` | `public class Element extends Node` (+ its type parameters) |
| 3 summary | + first sentence of the javadoc | + first sentence of the javadoc | + first sentence of the javadoc |
| 4 javadoc | + full javadoc | + full javadoc | + full javadoc |
| 5 body | + body | + initializer | members (each at its own level) |

Prompt variants choose a level per kind of information. Known noise: the full javadoc of the JDK and JUnit (the `INIT`
step today carries the full javadoc of ~200 `assert*` overloads, 116,000 method copies in steve alone), the bodies of
library methods, the methods inherited from `Object` after every value.

## Static information (every step of a test prefix)

| Information | Today in the prompt | Note |
|---|---|---|
| test prefix (signature, body up to the oracle) | yes | |
| JUnit version | yes | |
| methods called in the prefix (`testPrefix.invokedMethods`) | yes (signature, javadoc, body) | |
| test class fields | yes (signature) | |
| test class auxiliary methods | yes | |
| test class setup / teardown methods (`@BeforeEach`, …) | **no**: key mismatch (`setupTeardownMethods` in `EWashQwen.py`, `setupTearDownMethods` in the data) | often initialize the fields the oracle checks |
| focal class fields, methods | yes | |
| focal class constructors | **no**: never read, although the prompt label says "Constructors and methods" | |
| test class other test cases (`testCases`) | no | **contain the targets** (369 of 369 in uuid): must never be added to the prompt |

## Dynamic information, by state of the partial oracle

The state is what the symbolic module already knows at the step (today's `infoType`: `INIT`, `CLASS_CALL`, `FIELD_CALL`,
`METHOD_CALL`, `PARAMETER_CALL`, `FIELD_RETURN`, `METHOD_RETURN`, `PARAMETER_RETURN`, `LITERAL_RETURN`, `DEFINITION`).

| State | Example | Dynamic information |
|---|---|---|
| start | `` (empty) | the assertion methods: names only (they are the candidates); no javadoc |
| after a value and `.` | `assertTrue(a.` | each candidate member of the type of `a`: method signatures (return and parameter types), field types; the type of `a` itself |
| after a class and `.` | `assertEquals(Color.` | the static members of the class, enum constants |
| at an argument | `assertEquals(a.size(), ` / `a.b(` | the method called, its overloads still compatible with the arguments written, **the type expected at this position** (see below) |
| after a complete expression | `assertEquals(a.size()` | its type (`int`): which comparison or which next argument makes sense |
| literal | `assertEquals("` | the expected type (`String`, `long`, …) |
| lambda | `assertThrows(X.class, () ->` | the functional interface expected, the type of the parameter |

Measured on three projects of dataset v4 (steve, uuid, ffmpeg): 65–72% of the steps target a Java token (`(`, `.`,
`,`, …), 21–24% a project token (method, field, variable, class), most of them chosen among more than 20 project
candidates (15–19% of all steps), 7–12% a literal. The dynamic information matters most for the project-token steps
and the literals.

## Expected type at an argument (proposal, under measurement)

Idea: at an argument position, the type expected by the parameter (`int` for the second argument of
`assertEquals(a.size(), `; `String` for the first of `a.b(` when every overload of `b` takes a `String` first) tells
which candidates can lead to a value of that type, following their members (`b` → `size()` → `int`).

**Policy: hint, never pruning.** The candidates are not removed: Java accepts more than the parameter type suggests, so
pruning would lose targets.

- JUnit overloads take `Object`: `assertEquals(a.size(), name)` compiles (`assertEquals(Object, Object)`).
- Operators change the type: `assertTrue(list.size() > 0)` starts with `list`, whose path ends in an `int`; 14% of the
  `assertTrue`/`assertFalse` arguments of v4 (328 of 2,400) contain an operator; 4.2% of all 16,046 assertion arguments.
- Casts, nested calls (2.4% of the arguments), generic types, unresolved types.

What could be given to the model: the expected type(s) at the position, and for each candidate whether it reaches one
of them within *k* member accesses. Only cases that are provably safe could ever remove a candidate (`)` when every
remaining overload needs more arguments), and only after a corpus check showing no target lost.

Cycles are not a problem: the search is over **types**, not paths (a breadth-first search that visits each type once,
bounded by *k*, with a cache per project).

Whether the hint is worth adding is decided by the measurement in `tools/measure/ExpectedTypes.java` (results below):
how often the expected type is informative (not `Object` or a type variable), how often the target has the flag, how
much shorter the flagged list is than the full list.

### Measurement results (2026-11-21, the nine projects of dataset v4)

`tools/measure/ExpectedTypes.java` (run per project with `tools/measure/run.sh`; summary `tools/measure/summarize.py`):
for every argument of every call in the target oracles (the assertion and the calls nested in it), resolved in the
split test case, the expected types (the parameter at that position of the overloads compatible with the arguments
before it) and, when the first token of the argument is a project token, the **depth** of every candidate of that step
in the v4 tokens dataset: the least number of member accesses (fields, non-private methods) after which its type is
assignable to an expected type (boxing and widening included). **Strict**: without the members of `Object` and the
generic return types; without strict, `equals`, `hashCode`, `toString` make every value reach `boolean`, `int`,
`String` in one access, and generic methods (`Class.cast`) reach everything. 21,274 arguments (8,685 of 9,148
datapoints: 463 targets not located in their split test case), 99.7% resolved, all aligned with their step.

**How often the expected type says something.** Only **38%** of the argument positions have an informative expected type
(not `Object`, not a type variable): the `Object` overloads of JUnit (`assertEquals`, `assertNotNull`, …) and Hamcrest
(`assertThat(T, Matcher<? super T>)`) cover the rest. Of the 12,249 arguments that start with a project token, 4,009
(33%) have one.

**Literals.** 3,619 of the 8,245 literal arguments have an informative expected type, and the literal written is always
compatible with it (100%): at a literal step, the expected type (`String`, `long`, `boolean`, …) is reliable.

**Flag "reaches the expected type" on the candidates** (4,009 arguments starting with a project token; candidates per
step: median 188, most of them the classes of `java.lang` and package names):

| k (strict) | candidates flagged | target flagged | target known but not flagged | target of unknown type |
|---|---|---|---|---|
| 0 | 1.3% | 26.1% | 64.9% | 9.0% |
| **1** | **33.7%** | **82.9%** | **8.1%** | 9.0% |
| 2 | 50.3% | 84.7% | 6.2% | 9.0% |
| 3 | 50.7% | 84.8% | 6.2% | 9.0% |

By expected type, k = 1:

| Expected | Arguments | Candidates flagged | Target flagged | Target known but not flagged |
|---|---|---|---|---|
| `boolean` | 2,227 | 42.3% | 93.0% | 4.5% (operators: `a <= b`, `x.length >= N`) |
| `String` | 523 | 55.3% | 96.9% | 2.9% |
| number | 162 | 42.5% | 100% | 0% |
| a JDK type | 600 | 7.4% | 68.0% | 31.0% (137 of them `assertThrows(X.class, …)`: `.class` not modelled by the measurement) |
| a project type | 497 | 1.7% | 35.4% | 4.4% (60% of the targets of unknown type: Hamcrest's generic `is(…)` in ffmpeg) |

**Reading.**

- **Pruning is excluded**: even at k = 3, 6.2% of the targets whose type is known would be removed (operators,
  `a.equals(b)`, arrays), plus the 9% of unknown type. This confirms the policy above.
- **The expected type itself** is cheap, never wrong for literals, and informative at 38% of the argument positions: a
  good candidate for the dynamic information (experiment 2 decides).
- **The flag per candidate** is a moderate signal: at k = 1 it marks a third of the candidates and the target in 83% of
  the cases, but it points away from the target in 8% (operators, `equals`). Its value for the model must be shown by
  experiment 2 (with and without the flags) **before** it is implemented in the symbolic module. Cost is not an
  obstacle: the search over types with a cache took 3–58 minutes per project for all candidates of all argument steps
  (twice, strict and not).
- Most candidates at an argument position are the ~150 classes of `java.lang` and the root package names (the median
  list has 188 entries): the flag mostly separates the values in scope from them.

## Experiments (to decide the prompt)

1. **Prompt lengths** of each variant (distribution, share over the budget). No model.
2. **Next-token ablation without fine-tuning**: a fixed sample of hard steps (project tokens among many candidates,
   literals) from the nine projects, the model choosing the next token under each variant: static only; + candidate
   names with signatures; + javadoc summaries; + full javadoc and bodies; + expected type and flags.
3. **Full oracles** with the best 2–3 variants: exact match, compiles, passes.
4. **Small fine-tuning** of the top 2 variants (decides: a model without fine-tuning may prefer other information than a
   fine-tuned one).

## Open decisions

- The symbol-table format (proposed above) instead of `contextualInfos` in full.
- The two prompt-side problems (setup/teardown key, focal constructors): fixes in `scripts/prompt`.
- Model and GPUs for experiments 2–4; repositories for experiment 4.
