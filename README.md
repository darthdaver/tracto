# [Replication package] Tracto - A Neuro-Symbolic Approach to Deriving Concrete Test Oracles
This repository contains the replication package of **Tracto**, a neuro-symbolic approach to generate
concrete test oracles from Java projects.

**Tracto** stands for ***Tra**nsformer-based **C**oncrete **T**est **O**racle generation* and reminds the sound of the italian word *tratto* that means 
*line* or *way*, which illustrates the way in which **Tracto** generates oracles, as if they were a line that is continuously 
drawn, little by little (token by token). **Tracto** generates oracles token by token, in a grammar-directed way, but supported 
by a neural module to guide the search of oracles toward optimal solutions.

## Directory structure
1. `./grammar` collects the ANTLR grammar files with the rules to retrieve the list of valid tokens at each stage of the oracle generation process.
2. `./ml-model` contains the code related to the neural module.
3. `./symbolic-module` contains the code of the symbolic module.
