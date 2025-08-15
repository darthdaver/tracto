# Symbolic module
The symbolic module is responsible for all the symbolic tasks of the oracle generation process.

At development time, it processes the source code, the test code, and the dependencies (if available) of a set of Java projects 
to generate the raw dataset of oracles. The program: (i) splits the **test cases** present in the **test classes**, at any occurrence of 
an assertion statement, generating the corresponding **test prefixes**; (ii) attempts to match the **test classes** with the 
corresponding source classes (**focal classes**); (iii) processes the **test prefixes** individually to collect all the contextual 
information required to generate the final set of oracles datapoints (e.g. the **target oracle**, the javadoc, the signature 
and the body of the methods invoked within the test prefix, the javadoc, the signature and the body of the test class and 
the focal class).
Successively, another component handles the oracles datapoints to generate the raw dataset of tokens. For each datapoint,
the target oracle is divided into the corresponding Java **tokens**. Each token is associated with the contextual information 
collected in the previous step, the partial oracle (i.e. the string concatenating the tokens that precede the current token), 
and the complete list of all the other possible candidate tokens that can follow the partial oracle. The reference tokens 
extracted from the target oracle represent the target tokens that the neural model should predict at training time. 
The new datapoints generated from the dataset of oracles constitute the raw dataset of tokens.

At inference time, given a new Java project and a test prefix of a test class, the symbolic module operates
iteratively in conjunction with the neural module to generate an oracle from scratch. Starting from an empty oracle, the
symbolic module processes the test prefix, the test class and the focal class to collect the contextual information and 
the list of all the candidate tokens required to generate the next token. The information is passed to the neural module, 
which predicts the next token. The predicted token is added to the oracle, and the process is repeated until the oracle is complete.

## Setup
The folder contains bash scripts to install the required dependencies and to run the symbolic module as a standalone application,
both to generate the raw datasets at development time and to generate oracles at inference time.

In order to run the scripts, it is necesary to run the following commands:

```shell
bash scripts/bash/utils/init.sh
```

to install the dependencies required to compile and run the symbolic module. 

In particular, the script installs:
- A local version of `SDKman`, an SDK manager to effortlessly manage multiple Java versions on Unix systems.
- A local version of the JDK `8`, `11`, `17`, and `21` used within the other scripts to process the Java projects and run the symbolic module both at development and inference time.
- A local version of `Maven` to compile the symbolic module

The script does not install anything globally, but only local versions within the project.

## Inference
[TODO]

## Development

### Oracles dataset
To replicate the generation of the raw dataset of oracles or generate a new oracles dataset, it is necessary to run the following command:

```shell
bash scripts/bash/dataset/generate-raw-oracles-dataset.sh [path-to-csv-file]
```
By default, the script downloads and processes the Java projects listed in the `csv` file stored in `scr/main/resources/dataset/m2t_repos.csv`
corresponding to the Java projects used in [Method2Test](https://github.com/microsoft/methods2test), a related work published [here](https://arxiv.org/pdf/2009.05617).

However, it is possible to specify a path to a different `csv` file (as first parameter), containing the list of 
Java projects to process. The content of the `csv` file must be a list of rows, where each row is in the following format:

```csv
project-identifier,project-url,commit-sha
```

(No header is required)

### Tokens dataset
To generate the token dataset, it is necessary to follow the following steps:

- Copy at least one datapoint of the Oracle Dataset in json format in: 
    ```symbolic-module/src/main/resources/dataset/partial-oracles/input```
- Run the file: 
    ```symbolic-module/src/main/java/star/tracto/preprocessing/PartialOracleDataset.java```
- You will find the generated token dataset in the folder: 
    ```symbolic-module/src/main/resources/dataset/partial-oracles/output/```


## Conventions
* JDK Version: `21`
* Build tool: `3.9.4`