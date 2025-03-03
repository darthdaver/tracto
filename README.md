# [Replication package] [technique-id] A Neuro-Symbolic Approach to Deriving Concrete Test Oracles
This repository contains the replication package of [technique-id], a neuro-symbolic approach to generate
concrete test oracles from Java projects.

## Directory structure
1. `./grammar` collects the ANTLR grammar files with the rules to retrieve the list of valid tokens at each stage of the oracle generation process.
2. `./ml-model` contains the code related to the neural module.
3. `./tratto` contains the code of the symbolic module.