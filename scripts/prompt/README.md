## Prompt
The prompt submodule contains the scripts to prepare the datasets with the information to add in the queries used to feed the
LLMs (for fine-tuning and inference).
The scripts uses the E-wash approach to assemble the information.
The output of the scripts is a folder with `.csv` files containing two columns:
- `src`: the source of the information
- `tgt`: the target token to infer

## Setup

The scripts require `Python ≥ 3.10` and exploit the dependencies installed in the root of the project `llm-prompts-empirical-study`.

Before to continue, please, follow the instructions in the section `Setup` of the corresponding [README.md](../../README.md) file
in the root `tracto` folder.

If you are in a new terminal session, remember to activate the venv or conda environment. Move to the `scripts/prompt` 
folder and run the following command:

```shell
source ../../.venv/bin/activate
```

## Run
To run the prompt generation scripts, move to the `scripts/prompt` folder and execute the following command:

```shell
bash bash/generate_prompt.sh
```

The script will iterate over all the configuration files in the `resources/config` folder and generate the prompt files in the `output` folder,
for each dataset of tokens in the `scripts/dataset/output` folder.

The output files will be collected in the `output` folder, with the following structure: `output/[configuration_id]/prompt`

where `[configuration_id]` is the id of the configuration file used to generate the prompt.