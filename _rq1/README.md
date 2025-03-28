# RQ1

This folder contains the scripts to replicate the results of RQ1.

## Setup

The scripts will exploit `Python 3.9` and the dependencies installed in the `tracto/neural-module`.

Before to continue, please, follow the instructions in the section `Setup` of the corresponding [README.md](../neural-module/README.md) file
in the `tracto/neural-module` folder.

If you are in a new terminal session, remember to activate the venv or conda environment. Move to the `tracto/_rq1` folder and run the following command:

```shell
source ../neural-module/.venv/bin/activate
```

## Extract SOTA motivational example

To extract the SOTA motivational example, run the following command:

```bash
bash ./scripts/bash/sota/extract_togll_motivational_example.sh
```

The script will download the SOTA approach `TOGLL` replication package, will decode the training dataset used to train their
model, and will extract the motivational example used in the paper.

## Ablation study

To replicate the ablation study in RQ1, follow the instructions in the next paragraphs to setup Ollama and run the pre-trained models.

### Ollama

Install Ollama by following the instructions in the [Ollama](https://ollama.com/download/linux) official website.

Start the Ollama server in background by running the following command:

```shell
nohup ollama serve > log-ollama-serve.out 2>&1 & echo $! >> pid-ollama-serve.txt
```

(The script will start the Ollama server in the background and save the logs in `log-ollama-serve.out` and the process id in `pid-ollama-serve.txt`. If you want to run the process in the current terminal without saving the log, run `ollama serve &` instead).

Pull the models used in the ablation study:

```shell
bash ./scripts/bash/utils/ollama_pull_models.sh
```

Replicate the results of RQ1 by running the following command from the current directory (`tracto/_rq1`):

```shell
nohup bash ./scripts/bash/inference/inference-pretrained-ollama.sh [query-type] > log-ollama-inference.out 2>&1 & echo $! >> pid-ollama-inference.txt
```

where:
* `[query-type]` is the query type to replicate the results of RQ1. The possible values are: 
  * `assertion` - if the model have to predict only the assertion.
  * `statement` - if the model have to discern if the masked element is the next statement or an assertion (and generate it, in that case).

(The script will start the inference process in the background and save the logs in `log-ollama-client.out` and the process id in `pid-ollama-client.txt`. 
If you want to run the process in the current terminal without saving the logs, run `bash ./scripts/bash/inference/inference-pretrained-ollama.sh [query-type]` instead).

To replicate the moderation of the results with the llms involved in the inference study, run the following command:

```shell
nohup bash ./scripts/bash/analysis/moderator.sh > log-ollama-client-moderator.out 2>&1 & echo $! >> pid-ollama-client-moderator.txt
```

### HuggingFace

To replicate the results of RQ1 using huggingface, set up the parameters and hyperparameters in the file `scripts/bash/inference/infer-pretrained.sh`
(specifiying the model type, the batch size, the maximum length of the input, etc.) and run the following command from the
`neural-module` directory:

```shell
bash ./scripts/bash/inference/infer-pretrained.sh
```