# To do when preparing the prompt template for the fine-tuning

The author decided on 2026-11-26 (decision F.3 of `docs/mac-generation.md`) to fix these problems of the prompt
scripts (`scripts/prompt`, outside the symbolic module) in the step that prepares the prompt template for the
fine-tuning, not before the large run: they do not change what the symbolic module generates.

## The three problems found during the Mac generation

1. **The setup and teardown methods of the test class never reach the prompt.** `scripts/prompt/src/EWashQwen.py:152`
   (and the same line of `src/EWash.py`) checks the section key `setupTeardownMethods`; the datasets write
   `setupTearDownMethods` (capital `D`; e.g. 8 occurrences in `oracles-datapoints-uuid-0.json` of the phase E run, from
   `TestClazz.java`). The `@BeforeEach`/`@Before` methods often initialize the fields that the oracle checks.
2. **The constructors of the focal class are never read**, although the prompt label in
   `scripts/prompt/resources/ewash_intros.json:24` says "Constructors and methods defined in focal class"
   (`EWashQwen.py` has no code for them).
3. **`scripts/prompt/prompt_qwen.py:99-100` loads each dataset file whole with `json.load`.** The tokens outputs reach
   13 GB for one project (jsoup, phase E), and after decision F.1 they are zstd-compressed: the script must read them
   streaming (decompressing as it goes, one test class or one datapoint at a time) to keep the memory bounded.

## Related points already written elsewhere

- `testCases` (the other test cases of the test class) contain the targets: they must never be added to the prompt
  (`docs/contextual-information.md`, table of the static information).
- Known noise in the context: the full javadoc of the JDK and JUnit `assert*` overloads, the bodies of library methods,
  the methods inherited from `Object` (`docs/contextual-information.md`).
- `scripts/neural-module/utils/training_datasets.py` (`SupervisedDataset`) drops the examples whose `input_ids` are as
  long as `model_max_length` (8192) or longer, unless `truncate_source` is set: once the template is designed, measure
  how many examples it drops.
