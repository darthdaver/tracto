## Prompt

The prompt submodule contains the scripts to prepare the datasets with the information to add in the queries used to feed the
LLMs (for fine-tuning and inference). The scripts use the E-wash approach to assemble the information.
The output of the scripts is a folder with `.csv` files containing two columns:
- `src`: the source of the information
- `tgt`: the target token to infer

## RAG Integration

The prompt module now supports Retrieval-Augmented Generation (RAG) for dynamic context retrieval. 
RAG retrieves semantically similar code snippets from the repository and integrates them into the 
prompt generation process, enhancing the model's understanding of code patterns.

### Prerequisites

Install the RAG dependencies:

```bash
pip install sentence-transformers>=2.2.0
```

### Setup

The scripts require `Python ≥ 3.10` and exploit the dependencies installed in the root of the project `tracto`.

Before to continue, please, follow the instructions in the section `Setup` of the corresponding [README.md](../../README.md) file
in the root `tracto` folder.

If you are in a new terminal session, remember to activate the venv or conda environment. Move to the `scripts/prompt` 
folder and run the following command:

```shell
source ../../.venv/bin/activate
```

### Run - Standard Prompt Generation

To run the prompt generation scripts, move to the `scripts/prompt` folder and execute the following command:

```shell
bash bash/generate_prompt.sh
```

The script will iterate over all the configuration files in the `resources/config` folder and generate the prompt files in the `output` folder,
for each dataset of tokens in the `scripts/dataset/output` folder.

The output files will be collected in the `output` folder, with the following structure: `output/[configuration_id]/prompt`

where `[configuration_id]` is the id of the configuration file used to generate the prompt.

### Run - RAG-Enhanced Prompt Generation

To generate prompts with RAG context integration:

```bash
bash bash/generate_prompt_qwen_rag.sh [dataset_type] [target_dir] [config_num]
```

Arguments:
- `dataset_type`: "oracles" or "tokens" (default: "oracles")
- `target_dir`: "training" or "validation" (default: "training")
- `config_num`: Configuration number (default: "1")

Or use the standard script with RAG parameters:

```bash
bash bash/generate_prompt_qwen.sh [dataset_type] [target_dir] [rag_config] [rag_enabled] [rag_max_snippets] [rag_threshold]
```

Where:
- `rag_config`: Path to RAG configuration file (default: "${PROMPT_DIR}/rag/config.json")
- `rag_enabled`: Enable/disable RAG (default: true)
- `rag_max_snippets`: Maximum number of snippets to retrieve (default: 5)
- `rag_threshold`: Minimum similarity score (0-1) (default: 0.7)

### RAG Configuration

Edit `scripts/prompt/rag/config.json` to customize RAG behavior:

```json
{
  "embedding_model": "sentence-transformers/all-MiniLM-L6-v2",
  "max_snippets": 5,
  "similarity_threshold": 0.7,
  "max_snippet_length": 500,
  "search_fields": ["test_class", "focal_class", "oracles"],
  "code_directory": "../../input/github-repos",
  "cache_embeddings": true,
  "cache_file": "../resources/embeddings_cache.pkl"
}
```

Parameters:
- `embedding_model`: Sentence-transformers model for embeddings (default: all-MiniLM-L6-v2)
- `max_snippets`: Number of similar code snippets to retrieve (default: 5)
- `similarity_threshold`: Minimum similarity score for retrieval (0-1) (default: 0.7)
- `max_snippet_length`: Maximum length of code snippets to store (default: 500 chars)
- `code_directory`: Path to repository code directory
- `cache_embeddings`: Cache embeddings for reuse (default: true)
- `cache_file`: Path to cache file

### Usage Example

Generate prompts with RAG context for training:

```bash
bash bash/generate_prompt_qwen_rag.sh oracles training 1
```

The generated prompts will include an additional contextual information section:

```
# ADDITIONAL CONTEXTUAL INFORMATION (from RAG):
// Similar code patterns from repository:
```java
[Similar code snippet 1]
```
```java
[Similar code snippet 2]
```
// Similarity: 0.856
```

### Disabling RAG

To disable RAG and use standard prompt generation:

```bash
bash bash/generate_prompt_qwen.sh oracles training "${PROMPT_DIR}/rag/config.json" false
```

### Backward Compatibility

RAG is opt-in and fully backward compatible:
- Existing workflows work without modification when RAG is disabled
- When RAG fails, the system gracefully falls back to standard context
- RAG module can be removed without affecting core functionality
