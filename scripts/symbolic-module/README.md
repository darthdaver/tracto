# Symbolic module
The symbolic module is responsible for all the symbolic tasks of the oracle generation process.

## Dataset Generation
### Oracles Dataset
At training time, the symbolic module processes the source and test code and the dependencies (if available) of a Java 
project to generate the raw dataset of oracles (a JSON list of objects). 

The program:
1. Collects the test classes found in the projects
2. Matches the test classes with the corresponding source class, using a simple heuristic (exact name match, removing 
   the *Test suffix)
3. Splits each **test cases** present in each **test classes** processed, at any occurrence of an assertion statement, 
   generating the corresponding **test prefixes** and target oracles)
4. Collects all the contextual information and build the corresponding oracles datapoints (e.g. the **target oracle**, 
   the javadoc, the signature and the body of the methods invoked within the test prefix, the javadoc, the signature 
   and the body of the test class and the focal class).

The outcome is a JSON list of objects, each containing, four main attributes:
1. `junitVersion`: The Junit Version used in the project to write and execute test classes and test cases
2. `testClass`: The information about the test class processed, such as the name, the file path, the attributes and the 
   test cases defined
3. `focalClass`: The information about the test class processed, such as the name, the file path, the attributes and the 
   contructors and the methods defined
4. `datapoints`: The list of datapoints (**test prefixes** and **target oracles**) collected from the analysis of the 
   test class

This dataset can be used to finetune a model to generate complete test oracles, in a single step, given a test prefix and 
contextual information about the test and the corresponding focal classes. This finetuning process does not involve the symbolic
module and target the finetuning of `pure neural` techniques.

A full template of the JSON list is available at this path (from the root of the project)

>/scripts/symbolic-module/resources/oracles-dataset-schema.json

### Tokens Dataset
Successively, another component handles the oracles dataset to generate the raw dataset of tokens. For each datapoint,
the target oracle is disassembled into the corresponding Java **tokens**, and the generation of the oracle becomes an iterative
process that assembles the whole final oracle, one step at a time. At each step, the symbolic module provides the partial 
oracle (i.e. the string concatenating the tokens that precede the next target token), the complete list of all the other 
possible candidate tokens that can follow the partial oracle, the target oracle, and the contextual information related to
the current step and the next eligible tokens (that can help the model with the choice). 

This dataset can be used to finetune a model to generate a complete test oracle, as an iterative process, with the contribution
of the symbolic module that, at each step, collect the information required to help the model to generate the next lexical token.
This dataset is, therefore, useful to finetune `neuro-symbolic` approaches.

A full template of the JSON list is available at this path (from the root project)

>/scripts/symbolic-module/resources/tokens-dataset-schema.json


## Inference
At inference time, given a new Java project and a test prefix of a test class, the symbolic module operates
iteratively in conjunction with the neural module to generate an oracle from scratch. Starting from an empty oracle, the
symbolic module processes the test prefix, the test and the corresponding focal classes to collect the contextual information and 
the list of all the candidate tokens required to infer the next token. The information is passed to the neural module, 
which predicts the next token, using a `client-server` model (through REST API). The predicted token is added to the 
oracle under construction, and the process is repeated until the whole oracle is generated.

## Setup
The folder contains bash scripts to install the required dependencies and to run the symbolic module as a standalone application,
both to generate the raw datasets at development time and to generate oracles at inference time.

In order to run the scripts, it is necessary to run the following commands, **from the root of the repository**:

```shell
bash scripts/utils/bash/init.sh
```

to install the dependencies required to compile and run the symbolic module. 

In particular, the script installs:
- A local version of `SDKman`, an SDK manager to effortlessly manage multiple Java versions on Unix systems.
- A local version of the JDK `8`, `11`, `17`, `21`, and `24` used within the other scripts to process the Java projects and run the symbolic module both at development and inference time.
- A local version of `Maven` to compile the symbolic module

**The script does not install anything globally, but only local versions within the project.**

## Development

### Oracles dataset
To replicate the generation of the raw dataset of oracles or generate a new oracles dataset, it is necessary to run the following command:

```shell
bash bash/oracles-dataset.sh [path-to-csv-file]
```
By default, the script downloads and processes the Java projects listed in the `csv` file stored in `tracto/resources/seart-github-repos.csv`
collected by the authors of the repository, according to the criteria described in the related paper.

However, it is possible to specify a path to a different `csv` file (as first parameter), containing the list of 
Java projects to process. The content of the `csv` file must be a list of rows, where each row is in the following format:

```csv
project-identifier,project-url,commit-sha
```

**(No header is required)**

### Tokens dataset
> **Note:** Before to run the script to generate the dataset of tokens it is necessary to generate the oracles dataset.

To replicate the generation of the raw dataset of tokens or generate a new tokens dataset, it is necessary to run the following command:

```shell
bash bash/tokens-dataset.sh [path-to-csv-file]
```
By default, the script will process the Java projects listed in the `csv` file stored in `tracto/resources/seart-github-repos.csv`.

However, it is possible to specify a path to a different `csv` file (as first parameter), containing the list of 
Java projects to process. The content of the `csv` file must be a list of rows, where each row is in the following format:

```csv
project-identifier,project-url,commit-sha
```

**(No header is required)**


### Speed up dataset generation
> **Note:** Before to run the script make sure to run the script in the `root` folder to split the original list of repositories
into smaller chunks. The script will create a folder named `github-repos-split` in the `resources` folder containing the files with the
repositories to mine. The command to run **from the root of the current repository** is:
> ```shell
> python scripts/utils/bash/split-github-repos.sh
> ```

To speed up the process, you can run multiple scripts in parallel. To do so, you can use the following command:

```shell
bash bash/dataset-parallel.sh [number_of_processes] [resolve-dependencies] [script-to-run]
```

where:

* `number_of_processes` - is the number of processes to run in parallel. The default value is 10.
* `resolve-dependencies` - if the script should download the source code of the external dependencies of the repositories 
  to acquire more data as contextual information to generate the oracles.
* `script-to-run` - `oracles-dataset.sh` or `tokens-dataset.sh` depending on the type of dataset to generate.

## Conventions
* JDK Version: `21`
* Build tool: `3.9.4`