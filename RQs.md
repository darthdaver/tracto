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

The following is the list of pre-trained language models to be used in this research question:

| Model                                                                                    | Dimension  | Version |
|------------------------------------------------------------------------------------------|------------|---------|
| [Llama-3.1](https://huggingface.co/meta-llama/Llama-3.1-8B)                              | 8B         | base    |
| [CodeLlama](https://huggingface.co/meta-llama/CodeLlama-7b-hf)                           | 7B         | base    |
| [Codestral](https://huggingface.co/mistralai/Codestral-22B-v0.1)                         | 22B        | base    |
| [Starcoder2](https://huggingface.co/bigcode/starcoder2-7b)                               | 7B         | base    |
| [Mixtral-8x7B](https://huggingface.co/mistralai/Mixtral-8x7B-v0.1)                       | 56B        | base    |
| [Mistral](https://huggingface.co/mistralai/Mistral-7B-v0.3)                              | 7B         | base    |
| [DeepSeek-V3](https://huggingface.co/deepseek-ai/DeepSeek-V3)                            | 671B (fp8) | base    |
| [DeepSeek-Coder-V2-Lite-Base](https://huggingface.co/deepseek-ai/DeepSeek-Coder-V2-Base) | 7B         | base    |
| [Queen2.5-Coder](https://huggingface.co/Qwen/Qwen2.5-Coder-7B)                           | 7B         | base    |


### Questions
* Should we consider to use a GT dataset for this research question?
