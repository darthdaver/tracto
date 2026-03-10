# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tracto is a neuro-symbolic approach for generating concrete test oracles from Java projects. It generates oracles token by token using a grammar-directed approach, with a neural module guiding the search toward optimal solutions.

**Core Architecture**:
- **Symbolic module**: Processes source and test code to generate datasets (oracles/tokens) and performs inference at token-level granularity
- **Neural module**: Fine-tunes machine learning models (PyTorch/transformers) for token prediction via REST API
- **Prompt module**: Prepares datasets using E-wash approach for LLM training and inference
- **Miner**: Extracts test cases from GitHub repositories compiled with Maven

**Communication**: Symbolic module and Neural module communicate via REST API during inference. The symbolic module collects contextual information (test class, focal class, method signatures, Javadoc) and passes it to the neural model to predict the next token.

## Directory Structure

```
tracto/
├── scripts/
│   ├── miner/          # Mine test cases from GitHub repos (Python)
│   ├── neural-module/  # ML training and inference (Python)
│   ├── prompt/         # Prepare prompts for LLMs (Python)
│   ├── symbolic-module/# Symbolic processing (Java 21, Maven)
│   └── utils/          # Shared utilities
├── rqs/                # Research questions experiments (Java 21, Maven)
└── resources/          # Data files (seart-github-repos.csv, etc.)
```

## Environment Setup

**Prerequisites**:
- Python ≥ 3.12 (create virtualenv or conda environment)
- JDK versions 8, 11, 17, 21, 24 (via SDKman - see `scripts/utils/bash/init_sdkman.sh`)
- Maven 3.9.9
- GPU recommended (Nvidia A100 with 80GB vRAM for experiments)

**Installation**:
```bash
# Create venv
python3 -m venv .venv
source .venv/bin/activate

# Install Python dependencies
pip install -r requirements.txt
```

**Bash Tools**: Install required dependencies for shell scripts:
```bash
jq, zip, unzip, git
```

## Common Commands

### Setup
```bash
# Initialize SDKman and local JDKs/Maven
bash scripts/utils/bash/init.sh

# Activate venv (if used)
source .venv/bin/activate
```

### Symbolic Module (Java 21)
```bash
# Compile symbolic module
cd scripts/symbolic-module
mvn clean package -DskipTests

# Generate oracles dataset from repos
cd scripts/symbolic-module
bash bash/oracles-dataset.sh [mode] [csv_file] [resolve_deps]
# mode: "training" or "inference"
# resolve_deps: "true" to download dependencies

# Generate tokens dataset (requires oracles dataset first)
bash bash/tokens-dataset.sh [mode] [csv_file]

# Run inference (client-server mode with neural module)
java -jar target/tracto-jar-with-dependencies.jar [mode] [repo_id] [repo_path] [temp_dir] [symbolic_dir] [output_dir] [port] [config] [jdk_version]
```

### Neural Module (Python)
```bash
# Start inference server (port 5000 by default)
cd scripts/neural-module
bash bash/server.sh [config_num] [port]

# Fine-tune model (single GPU)
bash bash/finetune-single-gpu.sh [config_num]

# Test fine-tuned model
bash bash/smoke-run.sh [config_num]
```

### Prompt Module
```bash
# Generate prompts for training/inference
cd scripts/prompt
bash bash/generate_prompt.sh [dataset_type] [target_dir]
# dataset_type: "oracles" or "tokens"
# target_dir: "training" or "validation" (default: "training")
```

### Test Miner
```bash
# Mine test cases from repos
cd scripts/miner
bash bash/mine.sh

# Parallel mining
bash bash/mine-parallel.sh [num_processes]  # Default: 100
```

### Split Repositories
```bash
# Split large CSV into smaller chunks for parallel processing
python scripts/utils/bash/split-github-repos.sh
```

### Research Questions (RQs)
```bash
# RQ1: Tracto inference (neuro-symbolic approach)
bash rqs/bash/rq1_tracto_inference.sh [test_repos_csv] [config_num] [port]

# RQ1: LLM fine-tuned inference
bash rqs/bash/rq1_llm_f_inference.sh [config_num]

# RQ2: Correctness evaluation
bash rqs/bash/rq2_correctness_all.sh [repo_csv] [config_num]
```

## Key Technical Details

### Symbolic Module Data Flow

**Dataset Generation**:
1. Collect test classes from Java projects
2. Match test classes with source classes (remove "Test" suffix heuristic)
3. Split test cases at assertion statements → test prefixes + target oracles
4. Collect contextual information (javadoc, method signatures/bodies, etc.)

**Output Formats**:
- `oracles-dataset`: Full JSON list with testClass, focalClass, datapoints objects
- `tokens-dataset`: Tokenized format for iterative generation (partial oracle, candidate tokens, context)

**Token-level Inference**:
1. Start with empty oracle
2. Symbolic module collects contextual info and candidate tokens
3. Pass to neural module via REST API → predicts next token
4. Append token to oracle, repeat until complete

### Neural Module

**Models**: Fine-tuned transformers (Qwen, etc.) via LoRA
**API**: Flask server running on configurable port
**Tokenizer**: Same as base model, handles sequences up to 8192 tokens (src) / 1024 tokens (tgt)

**Important Scripts**:
- `server.py` - REST API server
- `finetune_oracles_json.py` - Fine-tuning on oracle dataset
- `inference_llm_f.py` - Inference on test repos
- `predictor.py` - Prediction utilities

### Prompt Generation

Uses **E-wash approach** to assemble contextual information into prompts:
- `src`: Input to model (test prefix + contextual information)
- `tgt`: Target token(s) to predict

Configurations stored in `scripts/prompt/resources/config/1/` and `2/`

### Research Questions

- **RQ1**: Effectiveness of Tracto (neuro-symbolic) vs. LLM fine-tuning on test oracles
- **RQ2**: Correctness of generated oracles

Output paths:
- Training: `output/raw-oracles-dataset-training`, `output/tokens-dataset-training`
- Inference: `output/raw-oracles-dataset-inference`
- RQ1 results: `OUTPUT_RQ1/inference/`
- RQ2 results: `OUTPUT_RQ2/correctness/`

## Conventions and Guidelines

- **JDK Version**: 21 is primary for symbolic module
- **Build Tool**: Maven 3.9.4
- **Python Dependencies**: PyTorch 2.6.0, transformers 4.56.2, tree-sitter 0.24.0
- **Maven Build**: `mvn clean package -DskipTests` for symbolic module
- **Parallel Processing**: Split repos into chunks using `split-github-repos.sh` before running parallel jobs
- **Environment Variables**:
  - `CUDA_VISIBLE_DEVICES` - GPU selection
  - `TOKENIZERS_PARALLELISM` - Set to "false" for training
  - `HF_DATASETS_DISABLE_PARALLELISM` - Set to "1" for training
- **Model Revisions**: Specify `model_revision` parameter when running inference to replicate exact model versions

## Important Notes

- Token datasets require pre-existing oracle datasets (run `oracles-dataset.sh` first)
- Parallel dataset generation: Use `bash bash/dataset-parallel.sh [n_procs] [resolve_deps] [script]`
- The symbolic module uses SDKman for Java version management (does not modify global system)
- Test miner ensures no data leakage by mining tests from repositories not used in model training
