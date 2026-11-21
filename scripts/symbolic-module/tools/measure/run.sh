#!/bin/bash
# Runs the measurement on one project of dataset v4 (tmp/ab/v4/<id>): tools/measure/run.sh <id>
# Compile first: javac -proc:none -cp tmp/ab/jar-step50.jar -d tmp/measure/classes tools/measure/ExpectedTypes.java
cd /home/coder/project/tracto/scripts/symbolic-module
export PATH=$PWD/../utils/resources/sdkman/candidates/java/21.0.6-amzn/bin:$PATH
id=$1
f=$(ls tmp/ab/v4/$id/out/raw-tokens-dataset-training/$id/*.json | head -1)
start=$(date +%s); java -Xmx12g -cp tmp/ab/jar-step50.jar:tmp/measure/classes ExpectedTypes $id tmp/ab/v4/$id/repo $f $PWD tmp/measure/$id.jsonl 2>tmp/measure/$id.err | tail -1; echo "$id $(( $(date +%s) - start )) s"
tail -1 tmp/measure/$id.err
