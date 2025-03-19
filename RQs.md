# Research Questions
The document contains the research questions that will be answered in this research project.

## RQ1
What is the effectiveness of pre-trained language models in generating test oracles, providing different contextual information?
### Scope
**Preliminary Evaluation**
### Motivation
Provide a preliminary evaluation of the effectiveness of pre-trained language models in generating whole test oracles, without
any further fine-tuning. The models receive different contextual information to generate the oracles.

### Implementation
- [ ] Implement a pipeline that generates test oracles using pre-trained language models.
- [ ] Generate different inputs to evaluate the effectiveness of the models when fed with different piece of information
  - [ ] Test prefix
  - [ ] Test prefix + i.m.* javadocs
  - [ ] Test prefix + i.m. signatures
  - [ ] Test prefix + i.m. javadocs + i.m. signatures
  - [ ] Test prefix + i.m. signatures + i.m. bodies
  - [ ] Test prefix + i.m. javadocs + i.m. signatures + i.m. bodies
- [ ] Evaluate the effectiveness of the models in generating test oracles in terms of correct (equivalent) and incorrect oracles.

\* i.m. stands for invoked methods (within the test prefix)

The following is the list of inputs generated to evaluate the effectiveness of the models (the test prefix is always included):

| ID   | Attr       | Javadoc   | Signature  | Body      |
|------|------------|-----------|------------|-----------|
| `1`  |            |           |            |           |
| `2`  |            | `im`      | `im`       |           |
| `3`  |            |           | `im`       | `im`      |
| `4`  |            | `im`      | `im`       | `im`      |
| `5`  |            | `fc`      | `fc`       |           |
| `6`  |            |           | `fc`       | `fc`      |
| `7`  |            | `fc`      | `fc`       | `fc`      |
| `8`  | `fc`       | `fc`      | `fc`       | `fc`      |
| `9`  |            | `fc` `tc` | `fc` `tc`  |           |
| `10` |            |           | `fc` `tc`  | `fc` `tc` |
| `11` |            | `fc` `tc` | `fc` `tc`  | `fc` `tc` |
| `12` | `fc` `tc`  | `fc` `tc` | `fc` `tc`  | `fc` `tc` |



where:
- `fc` stands for focal class
- `tc` stands for test class
- `im` stands for invoked methods in test prefix

The following is the list of pre-trained language models to be used in this research question:

| Model                                                                              | Model Dim | Context Length   | Version  |
|------------------------------------------------------------------------------------|-----------|------------------|----------|
| [Llama-3.1](https://huggingface.co/meta-llama/Llama-3.1-8B)                        | 8B        | 128k             | base     |
| [CodeLlama](https://huggingface.co/meta-llama/CodeLlama-7b-hf)                     | 7B        | 16k - 100k       | base     |
| [Codestral](https://huggingface.co/mistralai/Codestral-22B-v0.1)                   | 22B       | 32k              | base     |
| [Starcoder2](https://huggingface.co/bigcode/starcoder2-7b)                         | 7B        | 16k              | base     |
| [Mixtral-8x7B](https://huggingface.co/mistralai/Mixtral-8x7B-v0.1)                 | 56B       | 32k - 128k       | base     |
| [Mistral](https://huggingface.co/mistralai/Mistral-7B-v0.3)                        | 7B        | 8k - 32k         | base     |
| [DeepSeek-R1](https://huggingface.co/deepseek-ai/DeepSeek-V3)                      | 7B        | 128k             | base     |
| [DeepSeek-Coder-V2-Lite-Base](https://huggingface.co/deepseek-ai/DeepSeek-R1)      | 7B        | 128k             | base     |
| [Qwen2.5-Coder](https://huggingface.co/Qwen/Qwen2.5-Coder-7B)                      | 7B        | 128k             | base     |


### Questions & Observations
* Should we consider to use a GT dataset for this research question?
* In [[1]]((https://arxiv.org/abs/2405.03786)) and [[2]](https://arxiv.org/abs/2009.05617) the method provided in the input is what they consider as focal method. However, the automaticy retrieval of the focal method from a test prefix is a complex an undeterministic task. We consider in our paper the list of invoked methods as a more relaxed way to provide the context to the model. 
* We should report in how many cases we overcome the maximum token limit of the models and some statistics about the average/max/min length ot the input generated with our approach.